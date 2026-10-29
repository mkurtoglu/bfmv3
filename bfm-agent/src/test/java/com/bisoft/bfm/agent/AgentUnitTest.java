package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.exec.CommandTemplate;
import com.bisoft.bfm.agent.pg.ControlData;
import com.bisoft.bfm.agent.state.EpochStore;
import com.bisoft.bfm.agent.state.LeaseGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentUnitTest {

    @TempDir
    Path dir;

    @Test
    void templateValuesNeverSplitIntoExtraArguments() {
        List<String> argv = CommandTemplate.expand("sudo -n /usr/sbin/ip addr add {vip}/{prefix} dev {iface}",
                Map.of("vip", "10.0.0.5", "prefix", "24", "iface", "eth0; rm -rf /"));
        assertEquals(List.of("sudo", "-n", "/usr/sbin/ip", "addr", "add", "10.0.0.5/24", "dev", "eth0; rm -rf /"), argv);
    }

    @Test
    void templateRejectsUnknownPlaceholder() {
        assertThrows(IllegalArgumentException.class, () -> CommandTemplate.expand("ip {nope}", Map.of()));
    }

    @Test
    void leaseExpiresOnMonotonicClock() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        LeaseGuard lease = new LeaseGuard(now::get);
        assertFalse(lease.mustFence());

        lease.grant(7, 30_000, true);
        now.addAndGet(29_000L * 1_000_000L);
        assertFalse(lease.mustFence());
        assertEquals(1000, lease.info().remainingMs());

        now.addAndGet(1_000L * 1_000_000L);
        assertTrue(lease.mustFence());

        lease.revoke();
        assertFalse(lease.mustFence());
    }

    @Test
    void leaseWithoutFencingNeverFences() {
        AtomicLong now = new AtomicLong();
        LeaseGuard lease = new LeaseGuard(now::get);
        lease.grant(1, 1000, false);
        now.addAndGet(5_000_000_000L);
        assertTrue(lease.expired());
        assertFalse(lease.mustFence());
    }

    @Test
    void epochRefusesOlderCommands() throws Exception {
        EpochStore store = new EpochStore(dir);
        store.accept(5);
        store.accept(5);
        AgentException e = assertThrows(AgentException.class, () -> store.accept(4));
        assertEquals(409, e.httpStatus());
        assertEquals(5, new EpochStore(dir).current());
    }

    @Test
    void parsesControlData() {
        ControlData cd = ControlData.parse("""
                pg_control version number:            1300
                Database cluster state:               shut down
                Latest checkpoint location:           0/3000028
                Latest checkpoint's REDO location:    0/3000028
                Latest checkpoint's TimeLineID:       2
                """);
        assertEquals("shut down", cd.clusterState());
        assertEquals("0/3000028", cd.checkpointLsn());
        assertEquals(Integer.valueOf(2), cd.timeline());
        assertTrue(cd.cleanShutdown());
    }

    @Test
    void configRequiresTokenWithoutTls() {
        var p = TestConfigs.base(dir);
        p.remove("api.token");
        assertThrows(IllegalArgumentException.class, () -> AgentConfig.of(p));
    }

    @Test
    void leaseSurvivesAnAgentRestartAndNeverGrowsBeyondItsTtl(@TempDir Path dir) {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicLong wall = new AtomicLong(1_800_000_000_000L);
        Path file = dir.resolve("lease");
        LeaseGuard first = new LeaseGuard(nanos::get, wall::get, file);
        first.grant(7, 30_000, true);

        // The agent restarts 10 s later (a fresh monotonic clock): 20 s are left.
        wall.addAndGet(10_000);
        AtomicLong nanos2 = new AtomicLong(5L);
        LeaseGuard again = new LeaseGuard(nanos2::get, wall::get, file);
        assertEquals(7, again.info().epoch());
        assertTrue(again.info().fenceOnExpiry());
        assertEquals(20_000, again.info().remainingMs());
        nanos2.addAndGet(20_000_000_000L);
        assertTrue(again.mustFence());

        // A wall clock that jumped back cannot stretch the lease beyond its TTL.
        wall.addAndGet(-3_600_000);
        LeaseGuard jumped = new LeaseGuard(nanos2::get, wall::get, file);
        assertEquals(30_000, jumped.info().remainingMs());

        jumped.revoke();
        assertFalse(Files.exists(file));
        assertFalse(new LeaseGuard(nanos2::get, wall::get, file).info().granted());
    }
}
