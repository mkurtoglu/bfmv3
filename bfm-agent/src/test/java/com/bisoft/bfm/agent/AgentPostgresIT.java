package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;
import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.OperationStatus;
import com.bisoft.bfm.common.model.PgRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives two real PostgreSQL instances through the agent: build a replica, switch over cleanly, fail over hard
 * and rejoin with pg_rewind. Opt-in: set BFM_IT_PGBIN to a PostgreSQL bin directory and run as a non-root
 * OS user, for example {@code BFM_IT_PGBIN=/usr/lib/postgresql/16/bin mvn -pl bfm-agent -Dtest=AgentPostgresIT test}.
 * The VIP is disabled here; VipManagerTest covers it.
 */
class AgentPostgresIT {

    private static final int PORT_A = 55432;
    private static final int PORT_B = 55433;

    private final ProcessRunner runner = new ProcessRunner();
    private Path base;
    private AgentService a;
    private AgentService b;
    private long epoch = 1;

    @AfterEach
    void cleanup() {
        for (AgentService s : new AgentService[]{a, b}) {
            if (s != null) {
                try {
                    s.stopPg(body().put("mode", "immediate"));
                } catch (RuntimeException ignored) {
                    // already down
                }
                s.stop();
            }
        }
    }

    @Test
    void replicaSwitchoverFailoverAndRewind() throws Exception {
        String bin = System.getenv("BFM_IT_PGBIN");
        if (bin == null || bin.isBlank()) {
            System.out.println("SKIP AgentPostgresIT: BFM_IT_PGBIN not set");
            return;
        }
        base = Files.createTempDirectory("bfm-it");
        Path sock = Files.createDirectories(base.resolve("sock"));
        initPrimary(bin, base.resolve("data-a"), sock);

        a = new AgentService(AgentConfig.of(props("pg-a", bin, "a", PORT_A, sock)));
        b = new AgentService(AgentConfig.of(props("pg-b", bin, "b", PORT_B, sock)));
        a.start();
        b.start();

        // A starts as primary only when explicitly allowed (no standby.signal).
        assertThrows(AgentException.class, () -> a.startPg(body()));
        a.startPg(body().put("allowPrimary", true));
        a.refresh();
        assertEquals(PgRole.PRIMARY, a.status().role());
        a.ensureSlots(body().put("names", List.of("pg_b", "pg_a")));

        // B is built from A with pg_basebackup and streams.
        await(b.reinit(body().put("host", "127.0.0.1").put("port", PORT_A).put("slot", "pg_b")), b);
        waitFor("B streaming", () -> b.status().isReplica() && b.status().streaming());
        sql(bin, PORT_A, "CREATE TABLE t(x int); INSERT INTO t SELECT generate_series(1, 1000)");
        String aLsn = a.status().currentLsn();
        waitFor("B caught up", () -> Lsn.compare(b.status().replayLsn(), aLsn) >= 0);
        waitFor("slot pg_b active", () -> a.status().slots().stream().anyMatch(s -> s.name().equals("pg_b") && s.active()));
        waitFor("pg-b in pg_stat_replication",
                () -> a.status().replicas().stream().anyMatch(r -> "pg-b".equals(r.applicationName())));

        // Clean switchover A -> B: stop A, wait for B to replay the shutdown checkpoint, promote B.
        epoch++;
        ApiResult stopped = a.stopPg(body().put("mode", "fast"));
        String shutdownLsn = stopped.data().str("checkpointLsn");
        waitFor("B replayed shutdown checkpoint", () -> Lsn.compare(b.status().replayLsn(), shutdownLsn) > 0);
        b.promote(body().put("leaseTtlMs", 30000));
        b.refresh();
        assertEquals(PgRole.PRIMARY, b.status().role());
        assertEquals(Integer.valueOf(2), b.status().timeline());
        b.ensureSlots(body().put("names", List.of("pg_a")));

        // A rejoins without rewind (clean shutdown) and follows B on timeline 2.
        await(a.demote(body().put("host", "127.0.0.1").put("port", PORT_B).put("slot", "pg_a").put("rewind", false)), a);
        waitFor("A streaming from B", () -> a.status().isReplica() && a.status().streaming());
        sql(bin, PORT_B, "INSERT INTO t VALUES (1001)");
        waitFor("A on timeline 2", () -> Integer.valueOf(2).equals(a.status().timeline()));

        // Hard failover: B crashes, A is promoted, B comes back and needs pg_rewind.
        epoch++;
        sql(bin, PORT_B, "INSERT INTO t VALUES (1002)");
        b.stopPg(body().put("mode", "immediate"));
        a.promote(body());
        sql(bin, PORT_A, "INSERT INTO t VALUES (2000)");
        await(b.demote(body().put("host", "127.0.0.1").put("port", PORT_A).put("slot", "pg_b").put("rewind", true)), b);
        waitFor("B streaming from A after rewind", () -> b.status().isReplica() && b.status().streaming());
        assertEquals(Integer.valueOf(3), a.status().timeline());

        // Old epochs are refused.
        long old = epoch - 1;
        AgentException stale = assertThrows(AgentException.class,
                () -> a.checkpoint(JsonMap.create().put("epoch", old)));
        assertEquals(409, stale.httpStatus());

        // Peer check from the replica sees the primary.
        ApiResult peer = b.peerCheck(JsonMap.create().put("host", "127.0.0.1").put("port", PORT_A));
        assertTrue(peer.data().bool("reachable", false));
        NodeStatus bs = b.status();
        assertFalse(bs.fenced());
    }

    private JsonMap body() {
        return JsonMap.create().put("epoch", epoch);
    }

    private Properties props(String node, String bin, String suffix, int port, Path sock) throws Exception {
        Properties p = new Properties();
        p.setProperty("node.name", node);
        p.setProperty("tls.enabled", "false");
        p.setProperty("api.token", "it");
        p.setProperty("state.dir", Files.createDirectories(base.resolve("state-" + suffix)).toString());
        p.setProperty("pg.bin", bin);
        p.setProperty("pg.data", base.resolve("data-" + suffix).toString());
        p.setProperty("pg.port", Integer.toString(port));
        p.setProperty("pg.socket.dir", sock.toString());
        p.setProperty("pg.start.command", "{bin}/pg_ctl -D {data} -l {state}/postgres.log -o -p{port} -w -t {timeout} start");
        p.setProperty("replication.user", "postgres");
        p.setProperty("rewind.user", "postgres");
        p.setProperty("replication.conninfo.extra", "");
        p.setProperty("vip.enabled", "false");
        p.setProperty("probe.interval.ms", "500");
        return p;
    }

    private void initPrimary(String bin, Path data, Path sock) throws Exception {
        ProcessResult r = runner.run(List.of(bin + "/initdb", "-D", data.toString(), "-U", "postgres",
                "--auth=trust", "--no-sync"), Duration.ofMinutes(2));
        assertTrue(r.ok(), r.summary());
        Files.writeString(data.resolve("postgresql.conf"), String.join("\n",
                "",
                "listen_addresses = '127.0.0.1'",
                "unix_socket_directories = '" + sock + "'",
                "wal_level = replica",
                "wal_log_hints = on",
                "max_wal_senders = 10",
                "max_replication_slots = 10",
                "hot_standby = on",
                "wal_keep_size = '128MB'",
                "fsync = off",
                ""), java.nio.file.StandardOpenOption.APPEND);
    }

    private void sql(String bin, int port, String sql) {
        ProcessResult r = runner.run(List.of(bin + "/psql", "-X", "-q", "-h", base.resolve("sock").toString(),
                "-p", Integer.toString(port), "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql),
                Duration.ofSeconds(30));
        assertTrue(r.ok(), r.summary());
    }

    private static void await(ApiResult started, AgentService svc) throws InterruptedException {
        assertTrue(started.ok(), started.message());
        long deadline = System.currentTimeMillis() + 120_000;
        OperationStatus op;
        do {
            Thread.sleep(300);
            op = svc.operation(started.operationId());
        } while (!op.finished() && System.currentTimeMillis() < deadline);
        assertEquals(OperationStatus.State.SUCCEEDED, op.state(), op.name() + ": " + op.message() + " " + op.log());
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // not ready yet
            }
            Thread.sleep(300);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }
}
