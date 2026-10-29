package com.bisoft.bfm.agent.pg;

import com.bisoft.bfm.agent.AgentConfig;
import com.bisoft.bfm.agent.AgentException;
import com.bisoft.bfm.agent.exec.CommandTemplate;
import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;
import com.bisoft.bfm.agent.state.InterruptedOperation;
import com.bisoft.bfm.agent.ops.OpLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Starts, stops, promotes and reconfigures the local PostgreSQL. */
public class PgController {

    private static final Pattern SLOT = Pattern.compile("[a-z0-9_]{1,63}");
    private static final Set<String> STANDBY_KEYS = Set.of("primary_conninfo", "primary_slot_name",
            "recovery_target_timeline");

    private final AgentConfig cfg;
    private final ProcessRunner runner;
    private final Psql psql;
    private final PgProbe probe;
    private final InterruptedOperation interrupted;

    public PgController(AgentConfig cfg, ProcessRunner runner, Psql psql, PgProbe probe,
                        InterruptedOperation interrupted) {
        this.cfg = cfg;
        this.runner = runner;
        this.psql = psql;
        this.probe = probe;
        this.interrupted = interrupted;
    }

    /** The rewind or rebuild that did not finish rewriting the data directory, if any. */
    public InterruptedOperation interrupted() {
        return interrupted;
    }

    public Path standbySignal() {
        return cfg.pgData.resolve("standby.signal");
    }

    /**
     * Starts PostgreSQL. Without {@code allowPrimary} the agent refuses to start a data directory that has no
     * standby.signal, so a node can never come back as a second primary on its own.
     */
    public String start(boolean allowPrimary) {
        if (probe.isRunning()) {
            return "already running";
        }
        if (!allowPrimary && !Files.exists(standbySignal())) {
            throw AgentException.conflict("refusing to start without standby.signal: this would start a primary");
        }
        if (interrupted.present()) {
            throw AgentException.conflict(interrupted.refusal());
        }
        Map<String, String> v = placeholders();
        v.put("timeout", Integer.toString(cfg.pgStartTimeoutSec));
        ProcessResult r = runner.run(CommandTemplate.expand(cfg.pgStartCommand, v),
                Duration.ofSeconds(cfg.pgStartTimeoutSec + 15L));
        if (!r.ok()) {
            throw new AgentException("start failed: " + r.summary());
        }
        return "started";
    }

    public String stop(String mode) {
        if (!Set.of("smart", "fast", "immediate").contains(mode)) {
            throw AgentException.badRequest("stop mode must be smart, fast or immediate");
        }
        if (!probe.isRunning()) {
            return "already stopped";
        }
        Map<String, String> v = placeholders();
        v.put("mode", mode);
        v.put("timeout", Integer.toString(cfg.pgStartTimeoutSec));
        ProcessResult r = runner.run(CommandTemplate.expand(cfg.pgStopCommand, v),
                Duration.ofSeconds(cfg.pgStartTimeoutSec + 15L));
        if (!r.ok()) {
            throw new AgentException("stop failed: " + r.summary());
        }
        return "stopped (" + mode + ")";
    }

    /** pg_promote() and wait until the server leaves recovery. */
    public String promote() {
        try {
            String inRecovery = psql.scalar("SELECT pg_is_in_recovery()", 5);
            if ("f".equals(inRecovery)) {
                return "already primary";
            }
            String done = psql.scalar("SELECT pg_promote(true, " + cfg.promoteTimeoutSec + ")",
                    cfg.promoteTimeoutSec + 5);
            if (!"t".equals(done)) {
                throw new AgentException("pg_promote() did not finish within " + cfg.promoteTimeoutSec + " s");
            }
            if (!"f".equals(psql.scalar("SELECT pg_is_in_recovery()", 5))) {
                throw new AgentException("server still in recovery after pg_promote()");
            }
            return "promoted";
        } catch (PgException e) {
            throw new AgentException("promote failed: " + e.getMessage());
        }
    }

    public String checkpoint() {
        try {
            psql.exec("CHECKPOINT", 600);
            return "checkpoint done";
        } catch (PgException e) {
            throw new AgentException("checkpoint failed: " + e.getMessage());
        }
    }

    /** libpq connection string the replica uses to stream from {@code host:port}. */
    public String conninfo(String host, int port, String user, boolean forReplication) {
        List<String> parts = new ArrayList<>();
        parts.add(kv("host", host));
        parts.add(kv("port", Integer.toString(port)));
        parts.add(kv("user", user));
        if (forReplication) {
            parts.add(kv("application_name", cfg.nodeName));
        } else {
            parts.add(kv("dbname", "postgres"));
        }
        if (cfg.replicationPassfile != null) {
            parts.add(kv("passfile", cfg.replicationPassfile));
        }
        if (cfg.conninfoExtra != null && !cfg.conninfoExtra.isBlank()) {
            parts.add(cfg.conninfoExtra.trim());
        }
        return String.join(" ", parts);
    }

    /** Points a running replica at a new upstream (primary_conninfo is reloadable since PostgreSQL 13). */
    public void followRunning(String host, int port, String slot) {
        checkSlot(slot);
        String conninfo = conninfo(host, port, cfg.replicationUser, true);
        try {
            psql.exec("ALTER SYSTEM SET primary_conninfo = " + Psql.literal(conninfo), 10);
            if (slot == null) {
                psql.exec("ALTER SYSTEM RESET primary_slot_name", 10);
            } else {
                psql.exec("ALTER SYSTEM SET primary_slot_name = " + Psql.literal(slot), 10);
            }
            psql.exec("ALTER SYSTEM SET recovery_target_timeline = 'latest'", 10);
            psql.exec("SELECT pg_reload_conf()", 10);
        } catch (PgException e) {
            throw new AgentException("cannot reconfigure replica: " + e.getMessage());
        }
    }

    /** Writes standby.signal and the upstream settings into postgresql.auto.conf while the server is down. */
    public void configureStandbyStopped(String host, int port, String slot) {
        checkSlot(slot);
        if (probe.isRunning()) {
            throw AgentException.conflict("server is running; stop it before rewriting its recovery settings");
        }
        Path auto = cfg.pgData.resolve("postgresql.auto.conf");
        try {
            List<String> lines = Files.exists(auto)
                    ? new ArrayList<>(Files.readAllLines(auto, StandardCharsets.UTF_8))
                    : new ArrayList<>(List.of("# Do not edit this file manually!",
                    "# It will be overwritten by the ALTER SYSTEM command."));
            lines.removeIf(line -> {
                int eq = line.indexOf('=');
                return eq > 0 && STANDBY_KEYS.contains(line.substring(0, eq).trim());
            });
            lines.add("primary_conninfo = " + confLiteral(conninfo(host, port, cfg.replicationUser, true)));
            if (slot != null) {
                lines.add("primary_slot_name = " + confLiteral(slot));
            }
            lines.add("recovery_target_timeline = 'latest'");
            Path tmp = auto.resolveSibling("postgresql.auto.conf.bfm-tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, auto, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (!Files.exists(standbySignal())) {
                Files.createFile(standbySignal());
            }
        } catch (IOException e) {
            throw new AgentException("cannot write recovery settings: " + e.getMessage());
        }
    }

    /** pg_rewind against the new primary. The local server must be stopped. */
    public void rewind(String host, int port, OpLog log) {
        if (probe.isRunning()) {
            throw AgentException.conflict("stop the server before pg_rewind");
        }
        if (interrupted.present()) {
            throw AgentException.conflict(interrupted.refusal());
        }
        List<String> argv = List.of(cfg.pgBin.resolve("pg_rewind").toString(),
                "-D", cfg.pgData.toString(),
                "--source-server=" + conninfo(host, port, cfg.rewindUser, false),
                "--progress");
        log.line("pg_rewind from " + host + ":" + port);
        // Until pg_rewind reports success the directory is a mix of old and new blocks.
        interrupted.begin("demote (pg_rewind)");
        ProcessResult r = runner.run(argv, Duration.ofHours(6), Map.of("LC_ALL", "C"));
        tail(r, log);
        if (!r.ok()) {
            throw new AgentException("pg_rewind failed: " + r.summary());
        }
        interrupted.end();
    }

    /** Rebuilds the data directory with pg_basebackup. The old directory is kept or removed per config. */
    public void reinit(String host, int port, String slot, OpLog log) {
        checkSlot(slot);
        if (probe.isRunning()) {
            log.line(stop("immediate"));
        }
        Path data = cfg.pgData;
        interrupted.begin("reinit (pg_basebackup)");
        try {
            if (Files.exists(data)) {
                if (cfg.reinitKeepOld) {
                    String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
                    Path old = data.resolveSibling(data.getFileName() + ".bfm-old-" + stamp);
                    Files.move(data, old);
                    log.line("old data directory kept as " + old);
                } else {
                    deleteTree(data);
                    log.line("old data directory removed");
                }
            }
            Files.createDirectories(data, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException e) {
            throw new AgentException("cannot prepare data directory: " + e.getMessage());
        }
        List<String> argv = new ArrayList<>(List.of(cfg.pgBin.resolve("pg_basebackup").toString(),
                "-D", data.toString(),
                "-d", conninfo(host, port, cfg.replicationUser, true),
                "-X", "stream", "-c", "fast", "-R", "-P"));
        if (slot != null) {
            argv.add("-S");
            argv.add(slot);
        }
        log.line("pg_basebackup from " + host + ":" + port + (slot == null ? "" : " using slot " + slot));
        ProcessResult r = runner.run(argv, Duration.ofHours(24), Map.of("LC_ALL", "C"));
        tail(r, log);
        if (!r.ok()) {
            throw new AgentException("pg_basebackup failed: " + r.summary());
        }
        interrupted.end();
        configureStandbyStopped(host, port, slot);
        log.line(start(false));
    }

    public void ensureSlots(List<String> names) {
        for (String n : names) {
            checkSlot(n);
            try {
                psql.exec("SELECT pg_create_physical_replication_slot(" + Psql.literal(n) + ", true)"
                        + " WHERE NOT EXISTS (SELECT 1 FROM pg_replication_slots WHERE slot_name = "
                        + Psql.literal(n) + ")", 10);
            } catch (PgException e) {
                throw new AgentException("cannot create slot " + n + ": " + e.getMessage());
            }
        }
    }

    public void dropSlot(String name) {
        checkSlot(name);
        try {
            String active = psql.scalar("SELECT active FROM pg_replication_slots WHERE slot_name = "
                    + Psql.literal(name), 5);
            if (active == null) {
                return;
            }
            if ("t".equals(active)) {
                throw AgentException.conflict("slot " + name + " is in use");
            }
            psql.exec("SELECT pg_drop_replication_slot(" + Psql.literal(name) + ")", 10);
        } catch (PgException e) {
            throw new AgentException("cannot drop slot " + name + ": " + e.getMessage());
        }
    }

    Map<String, String> placeholders() {
        return new HashMap<>(probe.placeholders());
    }

    private static void checkSlot(String slot) {
        if (slot != null && !SLOT.matcher(slot).matches()) {
            throw AgentException.badRequest("invalid slot name: " + slot);
        }
    }

    /** A libpq keyword=value pair, quoted when the value needs it. */
    static String kv(String key, String value) {
        if (value.isEmpty() || value.matches(".*[\\s'\\\\].*")) {
            return key + "='" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        return key + "=" + value;
    }

    /** A string value in postgresql.conf syntax. */
    static String confLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void tail(ProcessResult r, OpLog log) {
        String text = (r.stdout() + "\n" + r.stderr()).strip();
        String[] lines = text.split("[\r\n]+");
        int from = Math.max(0, lines.length - 15);
        for (int i = from; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                log.line(lines[i]);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
