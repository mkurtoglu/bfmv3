package com.bisoft.bfm.core;

import com.bisoft.bfm.agent.AgentConfig;
import com.bisoft.bfm.agent.AgentService;
import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;
import com.bisoft.bfm.agent.http.AgentHttpServer;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.agent.AgentClient;
import com.bisoft.bfm.core.agent.HttpAgentClient;
import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.config.Timing;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.monitor.NodeView;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.SwitchoverOperation;
import com.bisoft.bfm.core.state.ClusterIdentity;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.core.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole chain against real PostgreSQL: three in-process agents over HTTP, a fake VIP (scripts/lab-fake-ip.sh)
 * and a watcher with short timings. Bootstrap, reinit, switchover, crash failover, automatic rejoin with the old
 * primary, and "no VIP, no promote". Opt-in: set BFM_IT_PGBIN and run as a non-root user, for example
 * {@code BFM_IT_PGBIN=/usr/lib/postgresql/16/bin mvn -pl bfm-core -am -Dtest=WatcherPostgresIT
 * -Dsurefire.failIfNoSpecifiedTests=false test}.
 */
class WatcherPostgresIT {

    private static final String TOKEN = "it-token";
    private final ProcessRunner runner = new ProcessRunner();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private Path base;
    private String bin;
    private Watcher watcher;

    private final class Node {
        final String name;
        final int pgPort;
        final int agentPort;
        AgentConfig cfg;
        AgentService svc;
        AgentHttpServer http;

        Node(String name, int pgPort, int agentPort) {
            this.name = name;
            this.pgPort = pgPort;
            this.agentPort = agentPort;
        }

        void startAgent() throws Exception {
            cfg = AgentConfig.of(props(this));
            svc = new AgentService(cfg);
            http = new AgentHttpServer(cfg, svc);
            svc.start();
            http.start();
        }

        void stopAgent() {
            if (http != null) {
                http.stop();
            }
            if (svc != null) {
                svc.stop();
            }
        }

        Path data() {
            return base.resolve("data-" + name);
        }
    }

    @AfterEach
    void cleanup() {
        if (watcher != null) {
            watcher.close();
        }
        for (Node n : nodes.values()) {
            n.stopAgent();
            runner.run(List.of(bin + "/pg_ctl", "-D", n.data().toString(), "-m", "immediate", "stop"),
                    Duration.ofSeconds(30));
        }
    }

    @Test
    void bootstrapSwitchoverFailoverRejoinAndNoVipNoPromote() throws Exception {
        bin = System.getenv("BFM_IT_PGBIN");
        if (bin == null || bin.isBlank()) {
            System.out.println("SKIP WatcherPostgresIT: BFM_IT_PGBIN not set");
            return;
        }
        base = Files.createTempDirectory("bfm-wit");
        Files.createDirectories(base.resolve("sock"));
        Path script = base.resolve("lab-fake-ip.sh");
        Files.copy(Path.of("..", "scripts", "lab-fake-ip.sh"), script);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        for (String n : List.of("a", "b", "c")) {
            nodes.put(n, new Node(n, freePort(), freePort()));
        }
        initPrimary(nodes.get("a"));
        for (Node n : nodes.values()) {
            n.startAgent();
        }

        List<NodeConfig> configs = new ArrayList<>();
        for (Node n : nodes.values()) {
            configs.add(NodeConfig.of(n.name, "http://127.0.0.1:" + n.agentPort, "127.0.0.1", n.pgPort, "DC1"));
        }
        Timing timing = new Timing(500, 3, 2_000, 6_000, 2_000, 2_000, 16L * 1024 * 1024, 30_000, 120_000,
                3_000, 3_000, 3_000);
        ClusterConfig cfg = ClusterConfig.of("it", configs, timing);
        StateStore store = new LocalStateStore("w1", base.resolve("watcher"));
        watcher = new Watcher(cfg, store,
                HttpAgentClient.factory(null, TOKEN, Duration.ofSeconds(2), Duration.ofSeconds(180)), TimeSource.SYSTEM);
        watcher.start();

        // Bootstrap: nothing runs yet; start a as primary, build b and c from it.
        waitFor("cluster state", () -> watcher.state() != null);
        assertEquals(null, watcher.state().primary());
        succeeds(watcher.startPrimary("a", "it"));
        assertTrue(vipOn("a"));
        succeeds(watcher.reinit("b", "it"));
        succeeds(watcher.reinit("c", "it"));
        sql("a", "CREATE TABLE t(x int); INSERT INTO t SELECT generate_series(1, 1000)");
        waitFor("b and c streaming", () -> streaming("b") && streaming("c"));

        // Identity: the primary's system identifier is the cluster's, b and c (copies of a) share it, and every
        // agent is bound to this cluster.
        String sysid = scalar("a", "SELECT system_identifier FROM pg_control_system()");
        waitFor("system identifier recorded", () -> sysid.equals(ClusterIdentity.systemIdentifier(store)));
        String clusterId = ClusterIdentity.existingClusterId(store);
        waitFor("every agent bound", () -> nodes.keySet().stream().allMatch(n -> status(n) != null
                && clusterId.equals(status(n).identity().clusterId()) && sysid.equals(status(n).systemIdentifier())));
        assertTrue(watcher.identity().problems().isEmpty(), watcher.identity().toString());

        // A watcher of another cluster that reaches a's agent through a mistyped URL can do nothing to it.
        AgentClient wrong = HttpAgentClient.factory(null, TOKEN, Duration.ofSeconds(2), Duration.ofSeconds(30))
                .create(NodeConfig.of("crm-1", "http://127.0.0.1:" + nodes.get("a").agentPort, "127.0.0.1",
                        nodes.get("a").pgPort, "DC1"));
        wrong.useCluster(UUID.randomUUID().toString(), "crm");
        long aEpoch = status("a").epoch();
        AgentCallException refused = assertThrows(AgentCallException.class,
                () -> wrong.fence(aEpoch + 100, "crm takes this for its rogue primary"));
        assertEquals(409, refused.httpStatus());
        assertTrue(refused.detail().contains("belongs to cluster it (" + clusterId + ") as a"), refused.detail());
        assertEquals(aEpoch, status("a").epoch(), "the refused command did not move the epoch");
        assertFalse(status("a").fenced());
        assertTrue(vipOn("a"));

        // Planned switchover a -> b.
        waitFor("pre-checks pass", () -> com.bisoft.bfm.core.ops.PreCheck.allowed(watcher.switchoverChecks("b")));
        succeeds(watcher.switchover("b", SwitchoverOperation.Options.defaults(), "it", "it"));
        assertEquals("b", watcher.state().primary());
        assertTrue(vipOn("b"));
        assertFalse(vipOn("a"));
        waitFor("a and c stream from b", () -> streamingFrom("a", "b") && streamingFrom("c", "b"));
        sql("b", "INSERT INTO t VALUES (1001)");

        // Hard failure of b: host off, VIP gone with it. The watcher promotes a or c.
        caughtUp("b", "a", "c");
        long epoch = watcher.state().epoch();
        crash("b");
        waitFor("failover away from b", () -> {
            ClusterState s = watcher.state();
            return s != null && !"b".equals(s.primary()) && s.operation() == null && s.epoch() > epoch;
        });
        String p2 = watcher.state().primary();
        assertTrue(vipOn(p2));
        waitFor(p2 + " seen as primary", () -> status(p2) != null && status(p2).role() == PgRole.PRIMARY);
        assertEquals("1001", scalar(p2, "SELECT count(*) FROM t"));
        String other = p2.equals("a") ? "c" : "a";
        waitFor(other + " streams from " + p2, () -> streamingFrom(other, p2));

        // b comes back: the watcher rejoins it as a replica of the new primary.
        nodes.get("b").startAgent();
        Files.deleteIfExists(base.resolve("net").resolve("down@b"));
        waitFor("b rejoined", () -> streamingFrom("b", p2), 90_000);
        sql(p2, "INSERT INTO t VALUES (1002)");

        // No VIP, no promote: the next primary's VIP cannot come up anywhere; nobody is promoted.
        Files.createFile(base.resolve("net").resolve("fail-add@" + other));
        Files.createFile(base.resolve("net").resolve("fail-add@b"));
        caughtUp(p2, other, "b");
        long epoch2 = watcher.state().epoch();
        crash(p2);
        waitFor("failed failover", () -> watcher.events(50).stream()
                .anyMatch(e -> e.type() == EventType.FAILOVER_FAILED && e.timeMillis() > 0
                        && e.message().contains("no VIP, no promote")));
        Thread.sleep(1_000);
        assertEquals(p2, watcher.state().primary());
        assertEquals("t", scalar(other, "SELECT pg_is_in_recovery()"), other + " stays a replica");
        assertEquals("t", scalar("b", "SELECT pg_is_in_recovery()"), "b stays a replica");
        assertTrue(watcher.state().epoch() > epoch2);

        // The VIP works again: the next attempt promotes.
        Files.delete(base.resolve("net").resolve("fail-add@" + other));
        Files.delete(base.resolve("net").resolve("fail-add@b"));
        waitFor("failover after the VIP works again", () -> {
            ClusterState s = watcher.state();
            return s != null && !p2.equals(s.primary()) && s.operation() == null;
        }, 60_000);
        String p3 = watcher.state().primary();
        assertNotEquals(p2, p3);
        assertTrue(vipOn(p3));
        assertEquals("1002", scalar(p3, "SELECT count(*) FROM t"));
    }

    // ---------------------------------------------------------------- helpers

    private void succeeds(OperationProgress.Snapshot started) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 180_000;
        OperationProgress.Snapshot s = started;
        while (s.running() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            s = watcher.operation(started.id()).orElseThrow();
        }
        assertEquals(OperationProgress.State.SUCCEEDED, s.state(), s.type() + " " + s.result() + "\n"
                + String.join("\n", s.log().stream().map(l -> l.source() + " " + l.text()).toList()));
    }

    private NodeStatus status(String n) {
        return watcher.view().node(n).map(NodeView::status).orElse(null);
    }

    private boolean streaming(String n) {
        NodeStatus s = status(n);
        return s != null && s.isReplica() && s.streaming();
    }

    private boolean streamingFrom(String n, String primary) {
        NodeStatus s = status(n);
        return s != null && s.isReplica() && s.streaming()
                && Integer.valueOf(nodes.get(primary).pgPort).equals(s.upstreamPort());
    }

    /** Waits until every replica has replayed everything the primary has written. */
    private void caughtUp(String primary, String... replicas) throws InterruptedException {
        String target = scalar(primary, "SELECT pg_current_wal_lsn()");
        for (String r : replicas) {
            waitFor(r + " replayed " + target, () -> {
                NodeStatus s = status(r);
                return s != null && s.replayLsn() != null && com.bisoft.bfm.common.Lsn.compare(s.replayLsn(), target) >= 0;
            });
        }
    }

    private boolean vipOn(String n) {
        return Files.exists(base.resolve("net").resolve("10.99.0.50@" + n));
    }

    private void crash(String n) throws IOException {
        Node node = nodes.get(n);
        Files.createDirectories(base.resolve("net"));
        Files.writeString(base.resolve("net").resolve("down@" + n), "", StandardOpenOption.CREATE);
        node.stopAgent();
        ProcessResult r = runner.run(List.of(bin + "/pg_ctl", "-D", node.data().toString(), "-m", "immediate",
                "stop"), Duration.ofSeconds(30));
        assertTrue(r.ok(), r.summary());
        Files.deleteIfExists(base.resolve("net").resolve("10.99.0.50@" + n));
    }

    private Properties props(Node n) throws IOException {
        Properties p = new Properties();
        p.setProperty("node.name", n.name);
        p.setProperty("listen.address", "127.0.0.1");
        p.setProperty("listen.port", Integer.toString(n.agentPort));
        p.setProperty("tls.enabled", "false");
        p.setProperty("api.token", TOKEN);
        p.setProperty("state.dir", Files.createDirectories(base.resolve("state-" + n.name)).toString());
        p.setProperty("pg.bin", bin);
        p.setProperty("pg.data", n.data().toString());
        p.setProperty("pg.port", Integer.toString(n.pgPort));
        p.setProperty("pg.socket.dir", base.resolve("sock").toString());
        p.setProperty("pg.start.command",
                "{bin}/pg_ctl -D {data} -l {state}/postgres.log -o -p{port} -w -t {timeout} start");
        p.setProperty("replication.user", "postgres");
        p.setProperty("rewind.user", "postgres");
        p.setProperty("replication.conninfo.extra", "");
        String fake = base.resolve("lab-fake-ip.sh").toString();
        p.setProperty("vip.enabled", "true");
        p.setProperty("vip.address", "10.99.0.50");
        p.setProperty("vip.interface", n.name);
        p.setProperty("vip.show.command", fake + " show {iface} {vip} {prefix}");
        p.setProperty("vip.add.command", fake + " add {iface} {vip} {prefix}");
        p.setProperty("vip.del.command", fake + " del {iface} {vip} {prefix}");
        p.setProperty("vip.dad.command", fake + " dad {iface} {vip}");
        p.setProperty("vip.garp.command", fake + " garp {iface} {vip}");
        p.setProperty("probe.interval.ms", "500");
        return p;
    }

    private void initPrimary(Node n) throws Exception {
        ProcessResult r = runner.run(List.of(bin + "/initdb", "-D", n.data().toString(), "-U", "postgres",
                "--auth=trust", "--no-sync"), Duration.ofMinutes(2));
        assertTrue(r.ok(), r.summary());
        Files.writeString(n.data().resolve("postgresql.conf"), String.join("\n", "",
                "listen_addresses = '127.0.0.1'",
                "unix_socket_directories = '" + base.resolve("sock") + "'",
                "wal_level = replica",
                "wal_log_hints = on",
                "max_wal_senders = 10",
                "max_replication_slots = 10",
                "hot_standby = on",
                "wal_keep_size = '128MB'",
                "fsync = off",
                ""), StandardOpenOption.APPEND);
    }

    private void sql(String n, String sql) {
        ProcessResult r = runner.run(List.of(bin + "/psql", "-X", "-q", "-h", base.resolve("sock").toString(),
                "-p", Integer.toString(nodes.get(n).pgPort), "-U", "postgres", "-d", "postgres", "-v",
                "ON_ERROR_STOP=1", "-c", sql), Duration.ofSeconds(30));
        assertTrue(r.ok(), r.summary());
    }

    private String scalar(String n, String sql) {
        ProcessResult r = runner.run(List.of(bin + "/psql", "-X", "-At", "-h", base.resolve("sock").toString(),
                "-p", Integer.toString(nodes.get(n).pgPort), "-U", "postgres", "-d", "postgres", "-c", sql),
                Duration.ofSeconds(30));
        assertTrue(r.ok(), r.summary());
        return r.stdout().trim();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitFor(String what, BooleanSupplier c) throws InterruptedException {
        waitFor(what, c, 60_000);
    }

    private static void waitFor(String what, BooleanSupplier c, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (c.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // not ready yet
            }
            Thread.sleep(250);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
