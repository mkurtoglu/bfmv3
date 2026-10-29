package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.state.LeaseGuard;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.OperationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the agent does with a data directory after a rewind or rebuild that did not finish, and at its own start. */
class AgentRecoveryTest {

    @TempDir
    Path dir;

    /** A stopped server: pg_ctl status says so, rewind fails or succeeds as told, basebackup and start succeed. */
    private FakeRunner stoppedHost(AtomicBoolean rewindFails) {
        return new FakeRunner(argv -> {
            String cmd = String.join(" ", argv);
            if (cmd.contains("pg_ctl") && cmd.contains("status")) {
                return FakeRunner.exit(argv, 3, "pg_ctl: no server running");
            }
            if (cmd.contains("pg_rewind")) {
                return rewindFails.get() ? FakeRunner.exit(argv, 1, "pg_rewind: error: could not fetch remote file")
                        : FakeRunner.ok(argv, "pg_rewind: Done!");
            }
            if (cmd.contains("addr show")) {
                return FakeRunner.ok(argv, "2: eth0    inet 192.168.0.27/24 scope global eth0\\");
            }
            return FakeRunner.ok(argv, "");
        });
    }

    private static JsonMap demote() {
        return JsonMap.create().put("epoch", 5L).put("host", "10.0.0.11").put("port", 5432).put("slot", "pg_a2");
    }

    private OperationStatus await(AgentService svc, ApiResult started) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            OperationStatus s = svc.operation(started.operationId());
            if (s.state() != OperationStatus.State.RUNNING) {
                return s;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("operation did not finish");
    }

    @Test
    void aFailedRewindLeavesAMarkThatBlocksStartAndRewindUntilARebuild(@TempDir Path other) throws Exception {
        AtomicBoolean rewindFails = new AtomicBoolean(true);
        FakeRunner runner = stoppedHost(rewindFails);
        AgentConfig cfg = TestConfigs.config(dir);
        Files.createDirectories(cfg.pgData);
        Files.createFile(cfg.pgData.resolve("standby.signal"));
        AgentService svc = new AgentService(cfg, runner, new LeaseGuard());

        assertEquals(OperationStatus.State.FAILED, await(svc, svc.demote(demote())).state());
        assertTrue(Files.exists(dir.resolve("operation")), "the interrupted rewind is remembered on disk");

        // The same node after an agent restart: it still knows, and refuses to start the half-written directory.
        AgentService again = new AgentService(cfg, runner, new LeaseGuard());
        AgentException e = assertThrows(AgentException.class,
                () -> again.startPg(JsonMap.create().put("epoch", 5L)));
        assertTrue(e.getMessage().contains("demote (pg_rewind) was interrupted"), e.getMessage());
        assertTrue(again.status().error().contains("re-initialize this node"));

        // A second rewind is refused too, even one that would succeed: the directory is not trusted any more.
        rewindFails.set(false);
        OperationStatus second = await(again, again.demote(demote()));
        assertEquals(OperationStatus.State.FAILED, second.state());
        assertTrue(second.message().contains("was interrupted"), second.message());
        assertEquals(1, runner.count("pg_rewind"), "pg_rewind ran only the first time");

        // Only a rebuild from the primary clears it.
        OperationStatus rebuilt = await(again, again.reinit(demote()));
        assertEquals(OperationStatus.State.SUCCEEDED, rebuilt.state(), rebuilt.message());
        assertFalse(Files.exists(dir.resolve("operation")));
        assertNull(again.status().error());
        assertEquals("started", again.startPg(JsonMap.create().put("epoch", 5L)).message());
    }

    @Test
    void autostartBringsAStoppedReplicaBackButNeverAPrimary() throws Exception {
        FakeRunner runner = stoppedHost(new AtomicBoolean(false));
        Properties p = TestConfigs.base(dir);
        p.setProperty("pg.autostart.replica", "true");
        AgentConfig cfg = AgentConfig.of(p);
        Files.createDirectories(cfg.pgData);

        // No standby.signal: this would be a primary, so nothing is started.
        AgentService svc = new AgentService(cfg, runner, new LeaseGuard());
        svc.autostartReplica();
        assertEquals(0, runner.count(" start"));

        // With standby.signal the replica is started.
        Files.createFile(cfg.pgData.resolve("standby.signal"));
        svc.autostartReplica();
        assertEquals(1, runner.count(" start"), "exactly one start");

        // A half-written directory is not started either.
        Files.writeString(dir.resolve("operation"), "reinit (pg_basebackup)");
        AgentService marked = new AgentService(cfg, runner, new LeaseGuard());
        long before = runner.count(" start");
        marked.autostartReplica();
        assertEquals(before, runner.count(" start"));
    }

    @Test
    void autostartIsOffByDefault() {
        assertFalse(TestConfigs.config(dir).pgAutostartReplica);
    }
}
