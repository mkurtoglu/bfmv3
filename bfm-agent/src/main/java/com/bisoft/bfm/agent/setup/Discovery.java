package com.bisoft.bfm.agent.setup;

import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the PostgreSQL instances on this host: running postmasters (their working directory is the data
 * directory), {@code postmaster.pid} files and data directories in the PGDG and Debian default places, and what
 * {@code pg_lsclusters} lists where it exists. For each: version, port, running or not, role, binaries, the agent
 * instance that already manages it and the PostgreSQL service that starts it today.
 *
 * <p>Every path is resolved under {@code root} ("/" on a host), so tests run against a directory tree.
 */
public class Discovery {

    private static final Pattern PORT = Pattern.compile("(?m)^\\s*port\\s*=\\s*'?(\\d+)'?");

    private final Path root;
    private final ProcessRunner runner;

    public Discovery(Path root, ProcessRunner runner) {
        this.root = root;
        this.runner = runner;
    }

    private Path at(String absolute) {
        return root.resolve(absolute.startsWith("/") ? absolute.substring(1) : absolute);
    }

    /** Fields of a {@code postmaster.pid}: pid, data directory, port, socket directory (each may be null). */
    public record PidFile(Long pid, String dataDir, Integer port, String socketDir) {
    }

    public static PidFile parsePidFile(String text) {
        String[] l = text.split("\n", -1);
        Long pid = l.length > 0 ? parseLong(l[0]) : null;
        String data = l.length > 1 && !l[1].isBlank() ? l[1].trim() : null;
        Integer port = l.length > 3 ? parseInt(l[3]) : null;
        String sock = l.length > 4 && !l[4].isBlank() ? l[4].trim() : null;
        return new PidFile(pid, data, port, sock);
    }

    /** The last {@code port = N} of a configuration file, or null. */
    public static Integer portFromConf(String text) {
        Matcher m = PORT.matcher(text);
        Integer port = null;
        while (m.find()) {
            port = Integer.parseInt(m.group(1));
        }
        return port;
    }

    /** One line of {@code pg_lsclusters}: version, cluster, port, status, owner, data directory. */
    public record LsCluster(String version, String name, int port, String status, String dataDir) {
    }

    public static List<LsCluster> parseLsClusters(String text) {
        List<LsCluster> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 6 || !f[0].matches("\\d+(\\.\\d+)?") || !f[2].matches("\\d+")) {
                continue;
            }
            out.add(new LsCluster(f[0], f[1], Integer.parseInt(f[2]), f[3], f[5]));
        }
        return out;
    }

    public List<PgInstance> find(List<Path> extra) {
        Set<Path> dirs = new LinkedHashSet<>();
        dirs.addAll(runningDataDirs());
        for (String pattern : List.of("/var/lib/pgsql/data", "/var/lib/pgsql/*/data", "/var/lib/pgsql/*/*",
                "/var/lib/postgresql/*/*")) {
            dirs.addAll(glob(pattern));
        }
        ProcessResult ls = runner.run(List.of("pg_lsclusters", "--no-header"), Duration.ofSeconds(10));
        if (ls.ok()) {
            for (LsCluster c : parseLsClusters(ls.stdout())) {
                dirs.add(at(c.dataDir()));
            }
        }
        for (Path p : extra) {
            // A data directory given by hand may live outside --root (a lab); on a host both are the same.
            dirs.add(p.isAbsolute() && Files.exists(at(p.toString())) ? at(p.toString()) : p.toAbsolutePath());
        }
        Map<Path, String> managed = managedDataDirs();
        List<PgInstance> out = new ArrayList<>();
        for (Path d : dirs) {
            if (Files.isRegularFile(d.resolve("PG_VERSION"))) {
                out.add(describe(d, managed));
            }
        }
        return out;
    }

    PgInstance describe(Path dir, Map<Path, String> managed) {
        String version = read(dir.resolve("PG_VERSION")).trim();
        PidFile pid = Files.exists(dir.resolve("postmaster.pid")) ? parsePidFile(read(dir.resolve("postmaster.pid")))
                : null;
        boolean running = pid != null && pid.pid() != null && (Files.isDirectory(at("/proc/" + pid.pid()))
                || !dir.startsWith(root) && Files.isDirectory(Path.of("/proc/" + pid.pid())));
        Integer port = running && pid.port() != null ? pid.port() : null;
        if (port == null) {
            port = portFromConf(read(dir.resolve("postgresql.auto.conf")));
        }
        if (port == null) {
            port = portFromConf(read(dir.resolve("postgresql.conf")));
        }
        String role = Files.exists(dir.resolve("standby.signal")) || Files.exists(dir.resolve("recovery.signal"))
                ? "replica" : running ? "primary" : "?";
        String major = version.contains(".") ? version.substring(0, version.indexOf('.')) : version;
        Path bin = null;
        for (String c : List.of("/usr/pgsql-" + major + "/bin", "/usr/lib/postgresql/" + major + "/bin")) {
            if (Files.isRegularFile(at(c).resolve("pg_ctl"))) {
                bin = Path.of(c);
                break;
            }
        }
        Path real = dir.startsWith(root) ? Path.of("/").resolve(root.relativize(dir).toString()) : dir;
        return new PgInstance(real, version, port == null ? 5432 : port, running, role, bin,
                pid != null && pid.socketDir() != null ? pid.socketDir() : "/var/run/postgresql",
                managed.get(real), unitFor(real));
    }

    /** Working directories of running postmasters (a postmaster is a postgres process whose parent is not one). */
    private List<Path> runningDataDirs() {
        List<Path> out = new ArrayList<>();
        Path proc = at("/proc");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(proc, "[0-9]*")) {
            for (Path p : ds) {
                String comm = read(p.resolve("comm")).trim();
                if (!comm.equals("postgres") && !comm.equals("postmaster")) {
                    continue;
                }
                String parent = parentComm(p);
                if ("postgres".equals(parent) || "postmaster".equals(parent)) {
                    continue;
                }
                try {
                    out.add(at(Files.readSymbolicLink(p.resolve("cwd")).toString()));
                } catch (IOException | UnsupportedOperationException ignored) {
                    // not ours to read; its postmaster.pid is found below anyway
                }
            }
        } catch (IOException ignored) {
            // no /proc: only the default places are searched
        }
        return out;
    }

    private String parentComm(Path proc) {
        String stat = read(proc.resolve("stat"));
        int close = stat.lastIndexOf(')');
        if (close < 0) {
            return null;
        }
        String[] f = stat.substring(close + 2).split(" ");
        return f.length > 1 ? read(at("/proc/" + f[1]).resolve("comm")).trim() : null;
    }

    /** Data directories already run by an agent instance: {@code /etc/bfm/minipgagent/[<instance>/]agent.properties}. */
    Map<Path, String> managedDataDirs() {
        Map<Path, String> out = new LinkedHashMap<>();
        Path base = at("/etc/bfm/minipgagent");
        addManaged(out, base.resolve("agent.properties"), "minipgagent");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(base)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    addManaged(out, p.resolve("agent.properties"), "minipgagent@" + p.getFileName());
                }
            }
        } catch (IOException ignored) {
            // no agent installed yet
        }
        return out;
    }

    private static void addManaged(Map<Path, String> out, Path file, String unit) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return;
        }
        String data = p.getProperty("pg.data");
        if (data != null && !data.isBlank()) {
            out.put(Path.of(data.trim()), unit);
        }
    }

    /** The PostgreSQL systemd service whose PGDATA (or -D) is this directory, or null. */
    private String unitFor(Path dataDir) {
        ProcessResult r = runner.run(List.of("systemctl", "list-unit-files", "--type=service", "--no-legend",
                "postgresql*"), Duration.ofSeconds(10));
        if (!r.ok()) {
            return null;
        }
        for (String line : r.stdout().split("\n")) {
            String unit = line.trim().split("\\s+")[0];
            if (unit.isEmpty() || unit.endsWith("@.service")) {
                continue;
            }
            ProcessResult show = runner.run(List.of("systemctl", "show", "-p", "Environment", "-p", "ExecStart",
                    unit), Duration.ofSeconds(10));
            if (Pattern.compile("(PGDATA=|-D\\s*)" + Pattern.quote(dataDir.toString()) + "/?(\\s|;|$)",
                    Pattern.MULTILINE).matcher(show.stdout()).find()) {
                return unit;
            }
        }
        return null;
    }

    private List<Path> glob(String pattern) {
        List<Path> out = new ArrayList<>();
        int star = pattern.indexOf('*');
        if (star < 0) {
            Path p = at(pattern);
            if (Files.isDirectory(p)) {
                out.add(p);
            }
            return out;
        }
        String head = pattern.substring(0, pattern.lastIndexOf('/', star));
        String rest = pattern.substring(pattern.indexOf('/', star) < 0 ? pattern.length()
                : pattern.indexOf('/', star));
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(at(head))) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) {
                    continue;
                }
                String next = Path.of("/").resolve(root.relativize(p).toString()) + rest;
                out.addAll(rest.isEmpty() ? List.of(p) : glob(next));
            }
        } catch (IOException ignored) {
            // that place does not exist here
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
