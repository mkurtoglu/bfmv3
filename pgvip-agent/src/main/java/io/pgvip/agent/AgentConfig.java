package io.pgvip.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Agent settings, read from a Java properties file (see {@code packaging/agent.properties.example}).
 *
 * <p>Every external command (start, stop, VIP) is a template. Templates are split on whitespace first and
 * placeholders are filled per token afterwards, so values never get re-split and no shell is involved.
 */
public final class AgentConfig {

    public final String nodeName;
    public final String listenAddress;
    public final int listenPort;

    public final boolean tlsEnabled;
    public final Path keyStore;
    public final char[] keyStorePassword;
    public final Path trustStore;
    public final char[] trustStorePassword;
    public final String apiToken;

    public final Path stateDir;

    public final Path pgBin;
    public final Path pgData;
    public final int pgPort;
    public final String pgSocketDir;
    public final String pgUser;
    public final String pgDatabase;
    public final String pgStartCommand;
    public final String pgStopCommand;
    public final String pgStatusCommand;
    public final int pgStartTimeoutSec;
    public final int promoteTimeoutSec;

    public final String replicationUser;
    public final String replicationPassfile;
    public final String rewindUser;
    public final String conninfoExtra;
    public final boolean reinitKeepOld;

    public final boolean vipEnabled;
    public final String vipAddress;
    public final int vipPrefix;
    public final String vipInterface;
    public final String vipAddCommand;
    public final String vipDelCommand;
    public final String vipShowCommand;
    public final boolean vipDadEnabled;
    public final String vipDadCommand;
    public final String vipGarpCommand;

    public final String fenceAction;
    public final String fenceStopMode;

    public final boolean watchdogEnabled;
    public final Path watchdogDevice;
    public final long watchdogIntervalMs;

    public final int commandTimeoutSec;
    public final long probeIntervalMs;

    private AgentConfig(Properties p) {
        nodeName = req(p, "node.name");
        listenAddress = p.getProperty("listen.address", "0.0.0.0");
        listenPort = Integer.parseInt(p.getProperty("listen.port", "8008"));

        tlsEnabled = Boolean.parseBoolean(p.getProperty("tls.enabled", "true"));
        keyStore = path(p, "tls.keystore");
        keyStorePassword = secret(p, "tls.keystore.password", "PGVIP_KEYSTORE_PASSWORD");
        trustStore = path(p, "tls.truststore");
        trustStorePassword = secret(p, "tls.truststore.password", "PGVIP_TRUSTSTORE_PASSWORD");
        char[] token = secret(p, "api.token", "PGVIP_API_TOKEN");
        apiToken = token.length == 0 ? null : new String(token);
        if (tlsEnabled && (keyStore == null || trustStore == null)) {
            throw new IllegalArgumentException("tls.enabled=true needs tls.keystore and tls.truststore");
        }
        if (!tlsEnabled && apiToken == null) {
            throw new IllegalArgumentException("Without TLS an api.token is required");
        }

        stateDir = Path.of(p.getProperty("state.dir", "/var/lib/pgvip"));

        pgBin = Path.of(req(p, "pg.bin"));
        pgData = Path.of(req(p, "pg.data"));
        pgPort = Integer.parseInt(p.getProperty("pg.port", "5432"));
        pgSocketDir = p.getProperty("pg.socket.dir", "/var/run/postgresql");
        pgUser = p.getProperty("pg.user", "postgres");
        pgDatabase = p.getProperty("pg.database", "postgres");
        pgStartCommand = p.getProperty("pg.start.command",
                "{bin}/pg_ctl -D {data} -l {state}/postgres-start.log -w -t {timeout} start");
        pgStopCommand = p.getProperty("pg.stop.command",
                "{bin}/pg_ctl -D {data} -m {mode} -w -t {timeout} stop");
        pgStatusCommand = p.getProperty("pg.status.command", "{bin}/pg_ctl -D {data} status");
        pgStartTimeoutSec = Integer.parseInt(p.getProperty("pg.start.timeout.seconds", "120"));
        promoteTimeoutSec = Integer.parseInt(p.getProperty("pg.promote.timeout.seconds", "60"));

        replicationUser = p.getProperty("replication.user", "replicator");
        replicationPassfile = blankToNull(p.getProperty("replication.passfile"));
        rewindUser = p.getProperty("rewind.user", pgUser);
        conninfoExtra = p.getProperty("replication.conninfo.extra", "sslmode=prefer");
        reinitKeepOld = Boolean.parseBoolean(p.getProperty("reinit.keep.old", "true"));

        vipEnabled = Boolean.parseBoolean(p.getProperty("vip.enabled", "true"));
        vipAddress = vipEnabled ? req(p, "vip.address") : p.getProperty("vip.address");
        vipPrefix = Integer.parseInt(p.getProperty("vip.prefix", "24"));
        vipInterface = vipEnabled ? req(p, "vip.interface") : p.getProperty("vip.interface", "");
        vipAddCommand = p.getProperty("vip.add.command", "sudo -n /usr/sbin/ip addr add {vip}/{prefix} dev {iface}");
        vipDelCommand = p.getProperty("vip.del.command", "sudo -n /usr/sbin/ip addr del {vip}/{prefix} dev {iface}");
        vipShowCommand = p.getProperty("vip.show.command", "/usr/sbin/ip -o -4 addr show dev {iface}");
        vipDadEnabled = Boolean.parseBoolean(p.getProperty("vip.dad.enabled", "true"));
        vipDadCommand = p.getProperty("vip.dad.command", "sudo -n /usr/sbin/arping -D -q -c 2 -w 3 -I {iface} {vip}");
        vipGarpCommand = p.getProperty("vip.garp.command", "sudo -n /usr/sbin/arping -U -q -c 3 -I {iface} {vip}");

        fenceAction = p.getProperty("fence.action", "stop");
        if (!fenceAction.equals("stop") && !fenceAction.equals("vip-only")) {
            throw new IllegalArgumentException("fence.action must be 'stop' or 'vip-only'");
        }
        fenceStopMode = p.getProperty("fence.stop.mode", "immediate");

        watchdogEnabled = Boolean.parseBoolean(p.getProperty("watchdog.enabled", "false"));
        watchdogDevice = Path.of(p.getProperty("watchdog.device", "/dev/watchdog"));
        watchdogIntervalMs = Long.parseLong(p.getProperty("watchdog.interval.ms", "5000"));

        commandTimeoutSec = Integer.parseInt(p.getProperty("command.timeout.seconds", "30"));
        probeIntervalMs = Long.parseLong(p.getProperty("probe.interval.ms", "1000"));
    }

    public static AgentConfig load(Path file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new AgentConfig(p);
    }

    public static AgentConfig of(Properties p) {
        return new AgentConfig(p);
    }

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required setting " + key);
        }
        return v.trim();
    }

    private static Path path(Properties p, String key) {
        String v = blankToNull(p.getProperty(key));
        return v == null ? null : Path.of(v);
    }

    private static char[] secret(Properties p, String key, String env) {
        String v = System.getenv(env);
        if (v == null || v.isEmpty()) {
            v = p.getProperty(key, "");
        }
        String fileKey = key + ".file";
        if ((v == null || v.isEmpty()) && p.getProperty(fileKey) != null) {
            try {
                v = Files.readString(Path.of(p.getProperty(fileKey))).trim();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + fileKey + ": " + e.getMessage());
            }
        }
        return v == null ? new char[0] : v.toCharArray();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
