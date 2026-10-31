package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.state.ClusterBinding;
import com.bisoft.bfm.agent.state.ExclusiveLock;
import com.bisoft.bfm.agent.state.LeaseGuard;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.model.NodeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which cluster an agent belongs to, and the locks that keep two agents from sharing what only one may own. */
class AgentIdentityTest {

    private static final String ERP = "5f2c0d4e-0000-4000-8000-0000000000e1";
    private static final String CRM = "0b7e5a2c-0000-4000-8000-00000000c4a1";

    @TempDir
    Path dir;

    private static JsonMap command(long epoch, String clusterId, String name) {
        JsonMap m = JsonMap.create().put("epoch", epoch).put("names", List.of());
        if (clusterId != null) {
            m.put("clusterId", clusterId).put("clusterName", name);
        }
        return m;
    }

    @Test
    void bindsOnFirstContactAndThenRefusesAnyOtherCluster() throws Exception {
        ClusterBinding b = new ClusterBinding(dir, null, "pg-a2");
        b.check(command(1, null, null));
        assertNull(b.clusterId(), "a watcher that names no cluster does not bind");
        b.check(command(1, ERP, "erp"));
        assertEquals(ERP, b.clusterId());

        AgentException other = assertThrows(AgentException.class, () -> b.check(command(2, CRM, "crm")));
        assertEquals(409, other.httpStatus());
        assertEquals("this agent belongs to cluster erp (" + ERP + ") as pg-a2; the command is for cluster crm ("
                + CRM + ") (check the agent URL and port)", other.getMessage());
        AgentException none = assertThrows(AgentException.class, () -> b.check(command(2, null, null)));
        assertTrue(none.getMessage().contains("names no cluster"), none.getMessage());

        ClusterBinding again = new ClusterBinding(dir, null, "pg-a2");
        assertEquals(ERP, again.clusterId(), "kept across a restart");
        assertEquals("erp", again.clusterName());
        again.clear();
        assertNull(new ClusterBinding(dir, null, "pg-a2").clusterId());
    }

    @Test
    void theConfiguredClusterWinsOverTheStoredOne() throws Exception {
        new ClusterBinding(dir, null, "pg-a2").check(command(1, CRM, "crm"));
        ClusterBinding b = new ClusterBinding(dir, ERP, "pg-a2");
        assertEquals(ERP, b.clusterId());
        assertNull(b.clusterName(), "the old cluster's name does not carry over");
        b.check(command(1, ERP, "erp"));
        assertEquals("erp", b.clusterName());
        assertThrows(AgentException.class, () -> b.check(command(1, CRM, "crm")));
    }

    @Test
    void aCommandForAnotherClusterDoesNotEvenMoveTheEpoch() throws Exception {
        FakeRunner runner = new FakeRunner(argv -> {
            String cmd = String.join(" ", argv);
            if (cmd.contains("pg_ctl") && cmd.contains("status")) {
                return FakeRunner.exit(argv, 3, "pg_ctl: no server running");
            }
            if (cmd.contains("pg_controldata")) {
                return FakeRunner.ok(argv, """
                        pg_control version number:            1300
                        Database system identifier:           7000000000000000001
                        Database cluster state:               shut down
                        """);
            }
            return FakeRunner.ok(argv, "");
        });
        Properties p = TestConfigs.base(dir);
        p.setProperty("instance.name", "erp");
        AgentService svc = new AgentService(AgentConfig.of(p), runner, new LeaseGuard());
        svc.ensureSlots(command(3, ERP, "erp"));

        NodeStatus s = svc.status();
        assertEquals(ERP, s.identity().clusterId());
        assertEquals("erp", s.identity().clusterName());
        assertEquals("erp", s.identity().instance());
        assertEquals("7000000000000000001", s.systemIdentifier(), "read from pg_controldata while stopped");

        AgentException e = assertThrows(AgentException.class, () -> svc.ensureSlots(command(9, CRM, "crm")));
        assertEquals(409, e.httpStatus());
        assertEquals(3, svc.status().epoch());
        AgentException promote = assertThrows(AgentException.class,
                () -> svc.promote(command(9, CRM, "crm").put("leaseTtlMs", 30000L)));
        assertEquals(409, promote.httpStatus());
        assertEquals(3, svc.status().epoch());
    }

    @Test
    void aLockHeldByAnotherProcessIsRefusedWithItsPid() throws Exception {
        Path file = dir.resolve("lock");
        Process holder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LockHolder.class.getName(), file.toString())
                .redirectErrorStream(true).start();
        try {
            BufferedReader out = new BufferedReader(new InputStreamReader(holder.getInputStream(),
                    StandardCharsets.UTF_8));
            String line;
            do {
                line = out.readLine();
            } while (line != null && !line.equals("locked"));
            assertEquals("locked", line);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ExclusiveLock.acquire(file, "state directory " + dir));
            assertTrue(e.getMessage().contains("in use by PID " + holder.pid()), e.getMessage());
            assertTrue(e.getMessage().contains("another agent instance is already running"), e.getMessage());
        } finally {
            holder.getOutputStream().close();
            holder.waitFor(30, TimeUnit.SECONDS);
            holder.destroy();
        }
        try (ExclusiveLock l = ExclusiveLock.acquire(file, "state directory " + dir)) {
            assertEquals(Long.toString(ProcessHandle.current().pid()), Files.readString(file).trim());
        }
    }

    @Test
    void theSameProcessCannotTakeALockTwice() throws Exception {
        Path file = dir.resolve("watchdog.lock");
        try (ExclusiveLock first = ExclusiveLock.acquire(file, "the hardware watchdog")) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ExclusiveLock.acquire(file, "the hardware watchdog"));
            assertTrue(e.getMessage().startsWith("the hardware watchdog is in use"), e.getMessage());
        }
    }
}
