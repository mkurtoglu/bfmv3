package com.bisoft.bfm.agent.setup;

import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything setup does to the host goes through here: files with their owner and mode, commands, and psql as the
 * postgres user. {@code --root} puts every path under a directory (a lab or a test); {@code --dry-run} prints what
 * would change and changes nothing.
 */
public class Host {

    final Path root;
    final boolean dryRun;
    final boolean systemd;
    final boolean asCurrentUser;
    final ProcessRunner runner;
    final PrintStream out;

    public Host(Path root, boolean dryRun, boolean systemd, boolean asCurrentUser, ProcessRunner runner,
                PrintStream out) {
        this.root = root;
        this.dryRun = dryRun;
        this.systemd = systemd;
        this.asCurrentUser = asCurrentUser;
        this.runner = runner;
        this.out = out;
    }

    /** An absolute host path, under {@code --root} when one is given. */
    public Path path(String absolute) {
        return root.resolve(absolute.startsWith("/") ? absolute.substring(1) : absolute);
    }

    public boolean isRoot() {
        ProcessResult r = runner.run(List.of("id", "-u"), Duration.ofSeconds(5));
        return r.ok() && r.stdout().trim().equals("0");
    }

    /** The postgres user's home directory (for .pgpass). */
    public String postgresHome() {
        if (asCurrentUser) {
            return System.getProperty("user.home");
        }
        ProcessResult r = runner.run(List.of("getent", "passwd", "postgres"), Duration.ofSeconds(5));
        String[] f = r.stdout().trim().split(":");
        return r.ok() && f.length >= 6 ? f[5] : "/var/lib/pgsql";
    }

    /**
     * Writes a file; an existing one is kept as {@code .bak}.
     *
     * @param owner "postgres" or "root" (ignored with {@code --as-current-user})
     */
    public void write(Path file, byte[] content, String mode, String owner) {
        if (dryRun) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, content);
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(rwx(mode)));
            own(tmp, owner);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    public void write(Path file, String content, String mode, String owner) {
        write(file, content.getBytes(StandardCharsets.UTF_8), mode, owner);
    }

    public void append(Path file, String text, String mode, String owner) {
        if (dryRun) {
            return;
        }
        try {
            boolean existed = Files.exists(file);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (!existed) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(rwx(mode)));
                own(file, owner);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    /** A directory owned by {@code owner}, mode {@code mode}. */
    public void mkdir(Path dir, String mode, String owner) {
        if (dryRun) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString(rwx(mode)));
            own(dir, owner);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + dir + ": " + e.getMessage(), e);
        }
    }

    private void own(Path p, String owner) throws IOException {
        if (asCurrentUser || owner == null) {
            return;
        }
        UserPrincipalLookupService lookup = p.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal user = lookup.lookupPrincipalByName(owner);
        GroupPrincipal group = lookup.lookupPrincipalByGroupName(owner);
        PosixFileAttributeView v = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        v.setOwner(user);
        v.setGroup(group);
    }

    /** "600" to "rw-------". */
    static String rwx(String octal) {
        StringBuilder b = new StringBuilder();
        for (char c : octal.toCharArray()) {
            int d = c - '0';
            b.append((d & 4) != 0 ? 'r' : '-').append((d & 2) != 0 ? 'w' : '-').append((d & 1) != 0 ? 'x' : '-');
        }
        return b.toString();
    }

    /** Runs a command (printed first); with {@code --dry-run} only printed. */
    public ProcessResult run(List<String> argv) {
        if (dryRun) {
            return new ProcessResult(argv, 0, "", "", false);
        }
        return runner.run(argv, Duration.ofSeconds(120));
    }

    /** systemctl, unless {@code --no-systemd} (a lab without systemd). */
    public ProcessResult systemctl(String... args) {
        List<String> argv = new ArrayList<>(List.of("systemctl"));
        argv.addAll(List.of(args));
        if (!systemd) {
            out.println("   (skipped without systemd: " + String.join(" ", argv) + ")");
            return new ProcessResult(argv, 0, "", "", false);
        }
        return run(argv);
    }

    /** Runs SQL through the instance's psql as the postgres user, over its Unix socket. */
    public List<List<String>> psql(PgInstance pg, String sql) throws Exception {
        List<String> argv = new ArrayList<>();
        if (!asCurrentUser) {
            argv.addAll(List.of("runuser", "-u", "postgres", "--"));
        }
        Path bin = pg.binDir() == null ? Path.of("psql") : pg.binDir().resolve("psql");
        argv.addAll(List.of(bin.toString(), "-X", "-q", "-A", "-t", "-F", "\u001f", "-R", "\u001e", "-v",
                "ON_ERROR_STOP=1", "-h", pg.socketDir(), "-p", Integer.toString(pg.port()), "-U", "postgres",
                "-d", "postgres", "-c", sql));
        ProcessResult r = runner.run(argv, Duration.ofSeconds(30), Map.of("PGCONNECT_TIMEOUT", "5", "LC_ALL", "C",
                "PGAPPNAME", "minipgagent-setup"));
        if (!r.ok()) {
            throw new IllegalStateException(r.stderr().isBlank() ? r.summary() : r.stderr().trim());
        }
        List<List<String>> rows = new ArrayList<>();
        String text = r.stdout().endsWith("\n") ? r.stdout().substring(0, r.stdout().length() - 1) : r.stdout();
        for (String rec : text.split("\u001e", -1)) {
            if (!rec.isEmpty()) {
                rows.add(new ArrayList<>(List.of(rec.split("\u001f", -1))));
            }
        }
        return rows;
    }
}
