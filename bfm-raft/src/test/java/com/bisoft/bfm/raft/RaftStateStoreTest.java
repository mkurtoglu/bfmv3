package com.bisoft.bfm.raft;

import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.core.FakeCluster;
import com.bisoft.bfm.core.ManualTime;
import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.config.Timing;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.ClusterStateRepo;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.core.state.StateUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaftStateStoreTest {

    @TempDir
    Path dir;

    private final List<RaftStateStore> stores = new ArrayList<>();
    private final List<Watcher> watchers = new ArrayList<>();

    @AfterEach
    void close() {
        watchers.forEach(Watcher::close);
        stores.forEach(RaftStateStore::close);
    }

    private List<RaftStateStore> group(int n) throws IOException {
        List<RaftStateStore.Peer> peers = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            peers.add(new RaftStateStore.Peer("w" + i, "127.0.0.1", freePort()));
        }
        for (RaftStateStore.Peer p : peers) {
            RaftStateStore s = new RaftStateStore(new RaftStateStore.Settings(p.id(), peers, dir.resolve(p.id()),
                    "test-cluster", Duration.ofSeconds(3), null, null));
            stores.add(s);
        }
        for (RaftStateStore s : stores) {
            s.start();
        }
        await("a leader", () -> leader() != null);
        return stores;
    }

    private RaftStateStore leader() {
        for (RaftStateStore s : stores) {
            try {
                if (s.leadership().leader()) {
                    return s;
                }
            } catch (RuntimeException ignored) {
                // closed
            }
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void await(String what, BooleanSupplier c) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    void compareAndSetIsDecidedOnceForTheWholeGroup() throws Exception {
        group(3);
        RaftStateStore a = stores.get(0);
        RaftStateStore b = stores.get(1);
        assertTrue(a.compareAndSet("cluster", 0, "{\"epoch\":1}"));
        assertFalse(b.compareAndSet("cluster", 0, "{\"epoch\":1}"), "second create loses");
        StateStore.Versioned v = b.get("cluster");
        assertEquals(1, v.version());
        assertTrue(b.compareAndSet("cluster", 1, "{\"epoch\":2}"));
        assertFalse(a.compareAndSet("cluster", 1, "{\"epoch\":3}"), "stale version loses");
        assertEquals("{\"epoch\":2}", stores.get(2).get("cluster").value());

        a.appendEvent("{\"id\":\"e1\"}");
        b.appendEvent("{\"id\":\"e2\"}");
        await("events replicated", () -> stores.get(2).events(10).size() == 2);
        assertEquals("{\"id\":\"e2\"}", stores.get(2).events(10).get(0));

        a.put("observation/w1", "{\"x\":1}");
        assertEquals(1, b.getPrefix("observation/").size());
        assertEquals(1, stores.stream().filter(s -> s.leadership().leader()).count());
        assertEquals(3, a.peers().size());
    }

    @Test
    void survivesTheLossOfTheLeaderAndRefusesWritesWithoutAMajority() throws Exception {
        group(3);
        RaftStateStore first = leader();
        assertNotNull(first);
        assertTrue(first.confirmLeadership());
        first.compareAndSet("cluster", 0, "{\"epoch\":7}");
        first.close();
        stores.remove(first);

        await("a new leader", () -> leader() != null);
        RaftStateStore second = leader();
        assertEquals("{\"epoch\":7}", second.get("cluster").value());
        assertTrue(second.compareAndSet("cluster", 1, "{\"epoch\":8}"));

        // Down to one member: no majority, no writes, no confirmed leadership.
        RaftStateStore other = stores.stream().filter(s -> s != second).findFirst().orElseThrow();
        other.close();
        stores.remove(other);
        assertThrows(StateUnavailableException.class, () -> second.compareAndSet("cluster", 2, "{\"epoch\":9}"));
        await("leadership lost or unconfirmed", () -> !second.confirmLeadership());
        assertEquals("{\"epoch\":8}", second.get("cluster").value(), "local copy still readable");
    }

    @Test
    void stateSurvivesARestartOfTheWholeGroup() throws Exception {
        List<RaftStateStore.Peer> peers = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            peers.add(new RaftStateStore.Peer("w" + i, "127.0.0.1", freePort()));
        }
        for (int round = 0; round < 2; round++) {
            stores.clear();
            for (RaftStateStore.Peer p : peers) {
                stores.add(new RaftStateStore(new RaftStateStore.Settings(p.id(), peers, dir.resolve(p.id()),
                        "restart", Duration.ofSeconds(3), null, null)));
            }
            for (RaftStateStore s : stores) {
                s.start();
            }
            await("a leader", () -> leader() != null);
            if (round == 0) {
                assertTrue(leader().compareAndSet("cluster", 0, "{\"epoch\":5}"));
                leader().appendEvent("{\"n\":0}");
                leader().appendEvent("id-erp", "{\"n\":1}");
                leader().appendEvent("id-crm", "{\"n\":2}");
                stores.forEach(RaftStateStore::close);
            } else {
                assertEquals("{\"epoch\":5}", leader().get("cluster").value());
                await("events replayed", () -> !leader().events("id-erp", 5).isEmpty());
                assertEquals(List.of("{\"n\":1}"), leader().events("id-erp", 5), "each cluster's own log");
                assertEquals(List.of("{\"n\":2}"), leader().events("id-crm", 5));
                assertEquals(List.of("{\"n\":0}"), leader().events(5));
            }
        }
    }

    @Test
    void threeWatchersFailOverExactlyOnce() throws Exception {
        group(3);
        ManualTime time = new ManualTime();
        FakeCluster cluster = new FakeCluster(time);
        cluster.primary("pg-a1", "10.0.0.1", "DC1");
        cluster.replica("pg-a2", "10.0.0.2", "DC1", "10.0.0.1");
        cluster.replica("pg-a3", "10.0.0.3", "DC1", "10.0.0.1");
        ClusterConfig cfg = ClusterConfig.of("prod-main", cluster.configs(), Timing.defaults());
        for (RaftStateStore s : stores) {
            watchers.add(new Watcher(cfg, s, cluster, time));
        }
        tickAll(time, 2);
        ClusterState st = new ClusterStateRepo(stores.get(0)).current();
        assertNotNull(st);
        assertEquals("pg-a1", st.primary());
        long epoch = st.epoch();

        cluster.crash("pg-a1");
        tickAll(time, 5);
        ClusterState after = new ClusterStateRepo(stores.get(1)).current();
        assertEquals("pg-a2", after.primary());
        assertEquals(epoch + 1, after.epoch(), "exactly one failover");
        assertEquals(PgRole.PRIMARY, cluster.node("pg-a2").role);
        assertEquals(PgRole.REPLICA, cluster.node("pg-a3").role);
        long finished = watchers.get(0).events(200).stream()
                .filter(e -> e.type() == EventType.FAILOVER_FINISHED).count();
        assertEquals(1, finished);
    }

    private void tickAll(ManualTime time, int rounds) {
        for (int i = 0; i < rounds; i++) {
            time.advance(Timing.defaults().pollIntervalMs());
            for (Watcher w : watchers) {
                w.tick();
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (watchers.stream().anyMatch(w -> w.operations().stream()
                    .anyMatch(OperationProgress.Snapshot::running))) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("operation did not finish");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }
}
