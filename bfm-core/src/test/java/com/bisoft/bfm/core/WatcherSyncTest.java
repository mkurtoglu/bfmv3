package com.bisoft.bfm.core;

import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.config.Timing;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.PreCheck;
import com.bisoft.bfm.core.ops.SwitchoverOperation;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.common.model.PgRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synchronous replication chosen per node, and which nodes automatic failover may promote. */
class WatcherSyncTest {

    private final ManualTime time = new ManualTime();
    private final FakeCluster cluster = new FakeCluster(time);
    private Watcher watcher;

    @AfterEach
    void close() {
        if (watcher != null) {
            watcher.close();
        }
    }

    private void start() {
        cluster.primary("pg-a1", "10.0.0.1", "DC1");
        cluster.replica("pg-a2", "10.0.0.2", "DC1", "10.0.0.1");
        cluster.replica("pg-a3", "10.0.0.3", "DC1", "10.0.0.1");
        watcher = new Watcher(ClusterConfig.of("prod-main", cluster.configs(), Timing.defaults()),
                LocalStateStore.inMemory("w1"), cluster, time);
        tick();
    }

    private void tick() {
        time.advance(Timing.defaults().pollIntervalMs());
        watcher.tick();
        long deadline = System.currentTimeMillis() + 10_000;
        while (watcher.operations().stream().anyMatch(OperationProgress.Snapshot::running) || !watcher.roleIdle()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("operation did not finish: " + watcher.operations().get(0));
            }
            Thread.onSpinWait();
        }
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    private long events(EventType type) {
        return watcher.events(500).stream().filter(e -> e.type() == type).count();
    }

    private void down(String node) {
        cluster.crash(node);
    }

    private void up(String node) {
        FakeCluster.Node n = cluster.node(node);
        n.agentUp = true;
        n.hostUp = true;
        n.pgRunning = true;
    }

    @Test
    void oneCheckedReplicaIsWrittenAsFirstOneOnEveryNode() {
        start();
        watcher.setReplication("quorum", 1, List.of("pg-a2"), "admin");
        tick();
        for (String n : List.of("pg-a1", "pg-a2", "pg-a3")) {
            assertEquals("FIRST 1 (\"pg-a2\")", cluster.node(n).syncStandbys, n);
        }
        assertTrue(watcher.events(50).stream().anyMatch(e -> e.type() == EventType.CONTROL_CHANGED
                && e.message().equals("replication mode quorum, ANY 1 of pg-a2 (was asynchronous) by admin")));

        watcher.setReplication("quorum", 2, List.of("pg-a3", "pg-a2"), "admin");
        tick();
        assertEquals("ANY 2 (\"pg-a2\", \"pg-a3\")", cluster.node("pg-a1").syncStandbys);

        watcher.setReplication("quorum", 1, List.of(), "admin");
        tick();
        assertEquals("", cluster.node("pg-a1").syncStandbys, "no node checked: asynchronous");
    }

    @Test
    void choicesThatCannotWorkAreRefused() {
        start();
        assertThrows(IllegalArgumentException.class,
                () -> watcher.setReplication("quorum", 1, List.of("pg-a1"), "admin"), "only the primary");
        assertThrows(IllegalArgumentException.class,
                () -> watcher.setReplication("quorum", 2, List.of("pg-a2"), "admin"), "more than checked");
        assertThrows(IllegalArgumentException.class,
                () -> watcher.setReplication("quorum", 1, List.of("pg-x9"), "admin"), "unknown node");
        assertThrows(IllegalArgumentException.class,
                () -> watcher.setReplication("sometimes", 1, List.of("pg-a2"), "admin"), "unknown mode");
    }

    @Test
    void quorumDropsTheWaitWhenTheSynchronousReplicaIsGoneAndTakesItUpAgain() {
        start();
        watcher.setReplication("quorum", 1, List.of("pg-a2"), "admin");
        tick();
        down("pg-a2");
        tick();
        assertFalse(watcher.state().replication().degraded(), "not before 10 s");
        ticks(6);
        assertTrue(watcher.state().replication().degraded());
        assertEquals(1, events(EventType.SYNC_DEGRADED));
        assertEquals("", cluster.node("pg-a1").syncStandbys, "commits stop waiting");

        up("pg-a2");
        ticks(3);
        assertFalse(watcher.state().replication().degraded());
        assertEquals(1, events(EventType.SYNC_RESTORED));
        assertEquals("FIRST 1 (\"pg-a2\")", cluster.node("pg-a1").syncStandbys, "commits wait again");
    }

    @Test
    void strictKeepsWaitingAndSaysSoOnce() {
        start();
        watcher.setReplication("strict", 1, List.of("pg-a2"), "admin");
        tick();
        down("pg-a2");
        ticks(10);
        assertFalse(watcher.state().replication().degraded());
        assertEquals("FIRST 1 (\"pg-a2\")", cluster.node("pg-a1").syncStandbys, "strict never drops the wait");
        assertEquals(1, events(EventType.SYNC_WAITING));
        up("pg-a2");
        ticks(2);
        assertEquals(1, events(EventType.SYNC_RESTORED));
    }

    @Test
    void strictFailoverWaitsWhenTheSynchronousReplicaIsGoneToo() {
        start();
        watcher.setReplication("strict", 1, List.of("pg-a2"), "admin");
        tick();
        down("pg-a2");
        tick();
        down("pg-a1");
        ticks(5);
        assertEquals("pg-a1", watcher.state().primary(), "pg-a3 may miss commits: no promotion");
        assertTrue(watcher.events(100).stream().anyMatch(e -> e.type() == EventType.FAILOVER_BLOCKED
                && e.message().contains("strict replication")), watcher.events(20).toString());
    }

    @Test
    void strictFailoverStartsAStoppedSynchronousReplicaAndThenDecides() {
        start();
        watcher.setReplication("strict", 1, List.of("pg-a2"), "admin");
        watcher.setNodeFailover("pg-a2", false, "admin"); // only pg-a3 may be promoted
        tick();
        down("pg-a2");
        tick();
        down("pg-a1");
        ticks(4);
        assertEquals("pg-a1", watcher.state().primary(), "pg-a2's position is not known: no promotion");
        FakeCluster.Node a2 = cluster.node("pg-a2");
        a2.agentUp = true; // its host is back, PostgreSQL still stopped
        a2.hostUp = true;
        ticks(5);
        assertTrue(cluster.log.stream().anyMatch(l -> l.startsWith("pg-a2: start as")), cluster.log.toString());
        assertEquals("pg-a3", watcher.state().primary(), "pg-a3 has every commit pg-a2 has: promoted");
        assertEquals(PgRole.REPLICA, a2.role, "the synchronous replica itself was not allowed");
    }

    @Test
    void quorumFailoverStillPromotesWhenTheSynchronousReplicaIsGone() {
        start();
        watcher.setReplication("quorum", 1, List.of("pg-a2"), "admin");
        tick();
        down("pg-a2");
        tick();
        down("pg-a1");
        ticks(5);
        assertEquals("pg-a3", watcher.state().primary());
    }

    @Test
    void automaticFailoverPromotesOnlyTheCheckedNodes() {
        start();
        cluster.node("pg-a3").lag = 4096; // pg-a2 is the most advanced
        watcher.setNodeFailover("pg-a2", false, "admin");
        assertEquals(Set.of("pg-a2"), watcher.notPromotable(watcher.state()));
        assertTrue(watcher.state().autoFailover());
        cluster.write(10_000);
        ticks(2);
        down("pg-a1");
        ticks(4);
        assertEquals("pg-a3", watcher.state().primary());
        assertEquals(PgRole.PRIMARY, cluster.node("pg-a3").role);
    }

    @Test
    void noCheckedNodeMeansNoAutomaticFailover() {
        start();
        watcher.setNodeFailover("pg-a2", false, "admin");
        watcher.setNodeFailover("pg-a3", false, "admin");
        assertTrue(watcher.state().autoFailover(), "pg-a1 is still checked");
        watcher.setNodeFailover("pg-a1", false, "admin");
        assertFalse(watcher.state().autoFailover());
        down("pg-a1");
        ticks(5);
        assertEquals("pg-a1", watcher.state().primary());

        watcher.setNodeFailover("pg-a3", true, "admin");
        assertTrue(watcher.state().autoFailover());
        ticks(4);
        assertEquals("pg-a3", watcher.state().primary(), "allowed again: fails over to it");
    }

    @Test
    void theOldSwitchSetsEveryNode() {
        start();
        watcher.setControls(false, null, null, null, "admin");
        assertEquals(Set.of("pg-a1", "pg-a2", "pg-a3"), watcher.notPromotable(watcher.state()));
        watcher.setControls(true, null, null, null, "admin");
        assertEquals(Set.of(), watcher.notPromotable(watcher.state()));
    }

    @Test
    void switchoverToTheOnlySynchronousNodeWarns() {
        ClusterState.Replication strict = new ClusterState.Replication("strict", 1, false, List.of("pg-a2"));
        List<PreCheck> checks = SwitchoverOperation.syncCheck(strict, "pg-a2");
        assertEquals(PreCheck.Level.WARN, checks.get(0).level());
        assertTrue(checks.get(0).detail().contains("writes on pg-a2 stop"));
        ClusterState.Replication two = new ClusterState.Replication("quorum", 1, false, List.of("pg-a1", "pg-a2"));
        assertEquals(PreCheck.Level.PASS, SwitchoverOperation.syncCheck(two, "pg-a2").get(0).level());
        assertTrue(SwitchoverOperation.syncCheck(ClusterState.Replication.ASYNC, "pg-a2").isEmpty());
    }

    @Test
    void stateKeepsBothChoicesThroughJson() {
        ClusterState st = ClusterState.initial(3, "pg-a1", 0, "w")
                .withReplication(new ClusterState.Replication("strict", 2, false, List.of("pg-a3", "pg-a2")))
                .withNoFailover(List.of("pg-a3"), List.of("pg-a1", "pg-a2", "pg-a3"));
        ClusterState back = ClusterState.fromJson(com.bisoft.bfm.common.json.Json.write(st));
        assertEquals(st, back);
        assertEquals(List.of("pg-a2", "pg-a3"), back.replication().standbys());
        ClusterState old = ClusterState.fromJson("{\"epoch\":1,\"primary\":\"pg-a1\",\"autoFailover\":false}");
        assertEquals(Set.of("pg-a1", "pg-a2"), old.notPromotable(List.of("pg-a1", "pg-a2"), n -> false),
                "before a per-node choice, automatic failover off means no node");
        assertFalse(old.replication().synchronous());
    }
}
