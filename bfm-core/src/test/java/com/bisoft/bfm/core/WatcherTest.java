package com.bisoft.bfm.core;

import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.config.FailoverScope;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.config.Timing;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.PreCheck;
import com.bisoft.bfm.core.ops.SwitchoverOperation;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.core.state.StateConflictException;
import com.bisoft.bfm.core.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatcherTest {

    private final ManualTime time = new ManualTime();
    private final FakeCluster cluster = new FakeCluster(time);
    private Watcher watcher;
    private StateStore store;

    @AfterEach
    void close() {
        if (watcher != null) {
            watcher.close();
        }
    }

    private void threeNodes() {
        cluster.primary("pg-a1", "10.0.0.1", "DC1");
        cluster.replica("pg-a2", "10.0.0.2", "DC1", "10.0.0.1");
        cluster.replica("pg-a3", "10.0.0.3", "DC1", "10.0.0.1");
    }

    private Watcher start(ClusterConfig cfg) {
        return start(cfg, LocalStateStore.inMemory("w1"));
    }

    private Watcher start(ClusterConfig cfg, StateStore s) {
        store = s;
        watcher = new Watcher(cfg, store, cluster, time);
        tick();
        return watcher;
    }

    private ClusterConfig standalone() {
        return ClusterConfig.of("prod-main", cluster.configs(), Timing.defaults());
    }

    private void tick() {
        time.advance(Timing.defaults().pollIntervalMs());
        watcher.tick();
        awaitIdle();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            tick();
        }
    }

    private void awaitIdle() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (watcher.operations().stream().anyMatch(OperationProgress.Snapshot::running)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("operation did not finish: " + watcher.operations().get(0));
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private boolean hasEvent(EventType type) {
        return watcher.events(500).stream().anyMatch(e -> e.type() == type);
    }

    private ClusterEvent event(EventType type) {
        return watcher.events(500).stream().filter(e -> e.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " in " + watcher.events(50)));
    }

    // ---------------------------------------------------------------- bootstrap and lease

    @Test
    void bootstrapRecordsTheRunningPrimaryAndRenewsItsLease() {
        threeNodes();
        cluster.node("pg-a2").epoch = 6;
        start(standalone());
        ClusterState st = watcher.state();
        assertNotNull(st);
        assertEquals("pg-a1", st.primary());
        assertEquals(7, st.epoch(), "epoch starts above every agent's epoch");
        tick();
        assertTrue(cluster.node("pg-a1").leaseGranted);
        assertFalse(cluster.node("pg-a1").fenceOnExpiry, "standalone default: no self-fencing");
        assertTrue(hasEvent(EventType.CLUSTER_INITIALISED));
    }

    @Test
    void bootstrapRefusesWithTwoPrimaries() {
        cluster.primary("pg-a1", "10.0.0.1", "DC1");
        FakeCluster.Node b = cluster.replica("pg-a2", "10.0.0.2", "DC1", null);
        b.role = PgRole.PRIMARY;
        start(standalone());
        assertNull(watcher.state());
        assertTrue(hasEvent(EventType.EPOCH_CONFLICT));
    }

    // ---------------------------------------------------------------- failover

    @Test
    void crashedPrimaryFailsOverToTheMostAdvancedReplicaWithVipFirst() {
        threeNodes();
        cluster.node("pg-a3").lag = 4096;
        start(standalone());
        cluster.write(10_000);
        ticks(2);
        long epochBefore = watcher.state().epoch();

        cluster.crash("pg-a1");
        ticks(3);

        ClusterState st = watcher.state();
        assertEquals("pg-a2", st.primary());
        assertEquals(epochBefore + 1, st.epoch());
        assertNull(st.operation());
        assertEquals(PgRole.PRIMARY, cluster.node("pg-a2").role);
        assertEquals("pg-a2", cluster.vipHolder());
        int vip = cluster.log.indexOf("pg-a2: vip acquire");
        int promote = cluster.log.indexOf("pg-a2: promote (timeline 2)");
        assertTrue(vip >= 0 && promote > vip, "VIP before promote: " + cluster.log);
        tick();
        assertEquals("10.0.0.2", cluster.node("pg-a3").upstreamHost, "the other replica follows the new primary");
        assertTrue(cluster.node("pg-a3").isStreaming());
        assertTrue(event(EventType.FAILOVER_FINISHED).detail().contains("New primary: pg-a2"));
        assertEquals(epochBefore + 1, cluster.node("pg-a3").epoch, "epoch announced to every reachable agent");
    }

    @Test
    void noVipNoPromote() {
        threeNodes();
        start(standalone());
        cluster.node("pg-a2").vipAddFails = true;
        cluster.node("pg-a3").vipAddFails = true;
        cluster.crash("pg-a1");
        ticks(3);

        assertEquals("pg-a1", watcher.state().primary(), "no new primary recorded");
        assertEquals(PgRole.REPLICA, cluster.node("pg-a2").role);
        assertEquals(PgRole.REPLICA, cluster.node("pg-a3").role);
        assertNull(cluster.vipHolder());
        assertTrue(event(EventType.FAILOVER_FAILED).message().contains("no VIP, no promote"));
        assertNull(watcher.state().operation(), "the cluster is released after the failed attempt");
        assertFalse(cluster.log.stream().anyMatch(l -> l.contains("promote")));

        // Once the VIP can be brought up again, the next attempt (after the retry wait) promotes.
        cluster.node("pg-a2").vipAddFails = false;
        cluster.node("pg-a3").vipAddFails = false;
        tick();
        assertEquals("pg-a1", watcher.state().primary(), "waits for the retry interval");
        time.advance(Timing.defaults().failoverRetryMs());
        ticks(1);
        assertEquals("pg-a3", watcher.state().primary(), "pg-a2 failed the VIP last time; pg-a3 is chosen");
    }

    @Test
    void onlyCandidateIsRetriedAfterAVipFailure() {
        cluster.primary("pg-a1", "10.0.0.1", "DC1");
        cluster.replica("pg-a2", "10.0.0.2", "DC1", "10.0.0.1");
        start(standalone());
        cluster.node("pg-a2").vipAddFails = true;
        cluster.crash("pg-a1");
        ticks(3);
        assertEquals("pg-a1", watcher.state().primary());
        cluster.node("pg-a2").vipAddFails = false;
        time.advance(Timing.defaults().failoverRetryMs());
        ticks(1);
        assertEquals("pg-a2", watcher.state().primary(), "not skipped when it is the only choice");
    }

    @Test
    void vipStillAnsweringBlocksPromotion() {
        threeNodes();
        start(standalone());
        // pg-a1's agent and PostgreSQL vanish from the network, but the host still answers ARP for the VIP.
        FakeCluster.Node a = cluster.node("pg-a1");
        a.agentUp = false;
        a.pgRunning = false;
        ticks(3);
        assertEquals("pg-a1", watcher.state().primary());
        assertEquals(PgRole.REPLICA, cluster.node("pg-a2").role);
        assertTrue(event(EventType.FAILOVER_FAILED).message().contains("answers from another host"));
    }

    @Test
    void replicaThatStillSeesThePrimaryBlocksFailover() {
        threeNodes();
        start(standalone());
        // Only the watcher lost the primary's agent; PostgreSQL still runs and the replicas reach it.
        cluster.node("pg-a1").agentUp = false;
        ticks(4);
        assertEquals("pg-a1", watcher.state().primary());
        long epoch = watcher.state().epoch();
        assertTrue(event(EventType.FAILOVER_BLOCKED).message().contains("still reach"));
        assertTrue(hasEvent(EventType.PRIMARY_UNREACHABLE));
        ticks(3);
        assertEquals(epoch, watcher.state().epoch(), "no epoch change while blocked");
    }

    @Test
    void primaryWhosePostgresDiedIsRestartedInPlaceFirst() {
        threeNodes();
        start(standalone());
        FakeCluster.Node a = cluster.node("pg-a1");
        a.pgRunning = false;
        ticks(3);
        assertTrue(a.pgRunning);
        assertEquals(PgRole.PRIMARY, a.role);
        assertEquals("pg-a1", watcher.state().primary());
        assertEquals("pg-a1", cluster.vipHolder());
        assertTrue(hasEvent(EventType.PRIMARY_RESTARTED));
        assertEquals(PgRole.REPLICA, cluster.node("pg-a2").role);
    }

    @Test
    void failedRestartFallsBackToFailover() {
        threeNodes();
        ClusterConfig cfg = standalone();
        start(cfg);
        FakeCluster.Node a = cluster.node("pg-a1");
        a.pgRunning = false;
        a.standbySignal = false;
        a.fenced = true;          // fenced earlier: the agent refuses to start it as primary...
        a.fencedReason = "test";
        a.vipAddFails = true;     // ...and the restart cannot even bring the VIP back
        cluster.crash("pg-a1");
        cluster.node("pg-a1").agentUp = true;
        cluster.node("pg-a1").hostUp = true;
        ticks(3);
        ticks(1);
        assertEquals("pg-a2", watcher.state().primary());
        assertEquals(PgRole.PRIMARY, cluster.node("pg-a2").role);
    }

    @Test
    void dcLocalScopeKeepsFailoverInTheDataCenter() {
        cluster.primary("pg-a1", "10.0.0.1", "DC1");
        cluster.replica("pg-dr1", "10.20.0.11", "DR", "10.0.0.1");
        start(standalone());
        cluster.crash("pg-a1");
        ticks(4);
        assertEquals("pg-a1", watcher.state().primary());
        assertTrue(event(EventType.FAILOVER_BLOCKED).message().contains("automatic failover stays in DC1"));
        assertEquals(PgRole.REPLICA, cluster.node("pg-dr1").role);
    }

    @Test
    void anyScopeMayPromoteAcrossDataCenters() {
        cluster.primary("pg-a1", "10.0.0.1", "DC1");
        cluster.replica("pg-dr1", "10.20.0.11", "DR", "10.0.0.1");
        ClusterConfig c = standalone();
        start(new ClusterConfig(c.name(), c.nodes(), c.timing(), FailoverScope.ANY, false, true, true, true, true));
        cluster.crash("pg-a1");
        ticks(4);
        assertEquals("pg-dr1", watcher.state().primary());
    }

    @Test
    void replicaTooFarBehindIsNotPromoted() {
        threeNodes();
        cluster.node("pg-a2").lag = 64L * 1024 * 1024;
        cluster.node("pg-a3").lag = 64L * 1024 * 1024;
        start(standalone());
        cluster.write(128L * 1024 * 1024);
        ticks(2);
        cluster.crash("pg-a1");
        ticks(4);
        assertEquals("pg-a1", watcher.state().primary());
        assertTrue(event(EventType.FAILOVER_BLOCKED).message().contains("behind the primary"));
    }

    @Test
    void maintenanceModePausesFailover() {
        threeNodes();
        start(standalone());
        watcher.setControls(null, true, null, null, "admin");
        cluster.crash("pg-a1");
        ticks(5);
        assertEquals("pg-a1", watcher.state().primary());
        assertTrue(event(EventType.FAILOVER_BLOCKED).message().contains("maintenance"));
        watcher.setControls(null, false, null, null, "admin");
        ticks(1);
        assertEquals("pg-a2", watcher.state().primary());
        assertTrue(event(EventType.CONTROL_CHANGED).actor().equals("admin"));
    }

    @Test
    void selfFencingFailoverWaitsForTheOldLease() {
        threeNodes();
        ClusterConfig c = standalone();
        start(new ClusterConfig(c.name(), c.nodes(), c.timing(), FailoverScope.DC_LOCAL, true, true, true, true, true));
        tick();
        assertTrue(cluster.node("pg-a1").fenceOnExpiry);
        // Network partition: the old primary keeps running but nobody reaches it, and it no longer answers ARP.
        FakeCluster.Node a = cluster.node("pg-a1");
        a.agentUp = false;
        a.hostUp = false;
        long before = time.nanos();
        ticks(3);
        assertEquals("pg-a2", watcher.state().primary());
        long waitedMs = (time.nanos() - before) / 1_000_000L;
        assertTrue(waitedMs >= 30_000 + 5_000, "waited only " + waitedMs + " ms");
        cluster.sync();
        assertTrue(a.fenced, "the old primary fenced itself when its lease ran out");
        assertFalse(a.pgRunning);
    }

    @Test
    void returningOldPrimaryIsFencedAndRejoinsWithRewind() {
        threeNodes();
        start(standalone());
        FakeCluster.Node a = cluster.node("pg-a1");
        // pg-a1 is cut off, writes a little more WAL nobody received, then the failover happens.
        a.agentUp = false;
        a.hostUp = false;
        a.lsn += 5000;
        vipLost(a);
        ticks(3);
        assertEquals("pg-a2", watcher.state().primary());

        // The partition heals: pg-a1 still runs as primary on the old timeline.
        a.agentUp = true;
        a.hostUp = true;
        tick();
        assertTrue(hasEvent(EventType.ROGUE_PRIMARY_FENCED));
        assertTrue(a.fenced);
        assertFalse(a.pgRunning);
        ticks(17);
        assertEquals(PgRole.REPLICA, a.role);
        assertTrue(a.pgRunning);
        assertTrue(a.isStreaming(), "streams from pg-a2 after pg_rewind: " + cluster.log);
        assertFalse(a.fenced);
        assertTrue(cluster.log.stream().anyMatch(l -> l.equals("pg-a1: demote rewind=true -> follow 10.0.0.2")));
        assertTrue(hasEvent(EventType.REJOIN_FINISHED));
    }

    private void vipLost(FakeCluster.Node a) {
        // The partitioned host can no longer be seen holding the VIP on the client network.
        a.vip = "192.168.99.99";
    }

    @Test
    void stoppedReplicaIsStartedAgain() {
        threeNodes();
        start(standalone());
        FakeCluster.Node c = cluster.node("pg-a3");
        c.pgRunning = false;
        ticks(18);
        assertTrue(c.pgRunning);
        assertTrue(c.isStreaming());
        assertTrue(hasEvent(EventType.REJOIN_FINISHED));
        assertTrue(hasEvent(EventType.NODE_DOWN));
        assertTrue(hasEvent(EventType.NODE_BACK));
    }

    @Test
    void emptyNodeIsRebuiltOnlyWithAutoReinit() {
        threeNodes();
        start(standalone());
        FakeCluster.Node c = cluster.node("pg-a3");
        c.pgRunning = false;
        c.clusterState = null;        // no data directory
        ticks(18);
        assertFalse(c.pgRunning, "not touched without auto reinit");
        assertFalse(cluster.log.stream().anyMatch(l -> l.startsWith("pg-a3:")), cluster.log.toString());
        assertTrue(watcher.events(50).stream().anyMatch(e -> e.message().contains("no readable data directory")));
        watcher.setControls(null, null, null, true, "admin");
        ticks(18);
        assertTrue(c.isStreaming());
        assertTrue(cluster.log.contains("pg-a3: reinit from 10.0.0.1"));
    }

    @Test
    void strayVipOnReplicaIsRemoved() {
        threeNodes();
        start(standalone());
        // Someone moved the VIP to a replica by hand.
        cluster.giveVip("pg-a2");
        tick();
        assertFalse(cluster.node("pg-a2").vipHeld());
        assertEquals("pg-a1", cluster.vipHolder(), "the primary gets its VIP back");
        assertTrue(hasEvent(EventType.VIP));
    }

    // ---------------------------------------------------------------- switchover

    @Test
    void switchoverMovesTheVipBeforePromotingAndRestartsTheOldPrimaryAsReplica() {
        threeNodes();
        start(standalone());
        cluster.write(50_000);
        ticks(2);
        List<PreCheck> checks = watcher.switchoverChecks("pg-a2");
        assertTrue(PreCheck.allowed(checks), checks.toString());
        long epoch = watcher.state().epoch();

        assertThrows(IllegalArgumentException.class, () -> watcher.switchover("pg-a2",
                SwitchoverOperation.Options.defaults(), "admin", "wrong-name"));
        OperationProgress.Snapshot s = watcher.switchover("pg-a2", SwitchoverOperation.Options.defaults(), "admin",
                "prod-main");
        awaitIdle();
        OperationProgress.Snapshot done = watcher.operation(s.id()).orElseThrow();
        assertEquals(OperationProgress.State.SUCCEEDED, done.state(), done.toString());
        assertEquals("pg-a2", watcher.state().primary());
        assertEquals(epoch + 1, watcher.state().epoch());
        assertEquals("pg-a2", cluster.vipHolder());
        FakeCluster.Node a = cluster.node("pg-a1");
        assertEquals(PgRole.REPLICA, a.role);
        assertTrue(a.isStreaming());
        int stop = cluster.log.indexOf("pg-a1: stop fast");
        int vip = cluster.log.indexOf("pg-a2: vip acquire");
        int promote = cluster.log.indexOf("pg-a2: promote (timeline 2)");
        assertTrue(stop >= 0 && vip > stop && promote > vip, cluster.log.toString());
        tick();
        assertTrue(cluster.node("pg-a3").isStreaming());
        assertEquals("10.0.0.2", cluster.node("pg-a3").upstreamHost);
        assertTrue(hasEvent(EventType.SWITCHOVER_FINISHED));
    }

    @Test
    void switchoverRollsBackWhenTheVipCannotBeBroughtUp() {
        threeNodes();
        start(standalone());
        cluster.node("pg-a2").vipAddFails = true;
        OperationProgress.Snapshot s = watcher.switchover("pg-a2", SwitchoverOperation.Options.defaults(), "admin",
                "prod-main");
        awaitIdle();
        OperationProgress.Snapshot done = watcher.operation(s.id()).orElseThrow();
        assertEquals(OperationProgress.State.FAILED, done.state());
        assertTrue(done.result().contains("no VIP, no promote"), done.result());
        assertTrue(done.result().contains("pg-a1 started again as primary"), done.result());
        assertEquals("pg-a1", watcher.state().primary());
        assertNull(watcher.state().operation());
        assertEquals(PgRole.PRIMARY, cluster.node("pg-a1").role);
        assertTrue(cluster.node("pg-a1").pgRunning);
        assertEquals("pg-a1", cluster.vipHolder());
        assertEquals(PgRole.REPLICA, cluster.node("pg-a2").role);
        assertTrue(event(EventType.SWITCHOVER_FAILED).message().contains("failed"));
        tick();
        assertTrue(cluster.node("pg-a2").isStreaming());
    }

    @Test
    void switchoverRefusedWhilePreChecksFail() {
        threeNodes();
        start(standalone());
        cluster.node("pg-a2").pgRunning = false;
        tick();
        assertThrows(StateConflictException.class, () -> watcher.switchover("pg-a2",
                SwitchoverOperation.Options.defaults(), "admin", "prod-main"));
        assertEquals("pg-a1", watcher.state().primary());
    }

    @Test
    void reinitRebuildsAReplica() {
        threeNodes();
        start(standalone());
        OperationProgress.Snapshot s = watcher.reinit("pg-a3", "admin");
        awaitIdle();
        assertEquals(OperationProgress.State.SUCCEEDED, watcher.operation(s.id()).orElseThrow().state());
        assertTrue(cluster.log.contains("pg-a3: reinit from 10.0.0.1"));
        assertTrue(hasEvent(EventType.REINIT_FINISHED));
    }

    @Test
    void startPrimaryBootstrapsAnEmptyCluster() {
        FakeCluster.Node a = cluster.replica("pg-a1", "10.0.0.1", "DC1", null);
        a.pgRunning = false;
        a.standbySignal = false;
        FakeCluster.Node b = cluster.replica("pg-a2", "10.0.0.2", "DC1", "10.0.0.1");
        b.pgRunning = false;
        synchronized (cluster) {
            // nobody holds the VIP yet
        }
        start(standalone());
        assertNotNull(watcher.state());
        assertNull(watcher.state().primary());
        OperationProgress.Snapshot s = watcher.startPrimary("pg-a1", "admin");
        awaitIdle();
        assertEquals(OperationProgress.State.SUCCEEDED, watcher.operation(s.id()).orElseThrow().state(),
                watcher.operation(s.id()).toString());
        assertEquals("pg-a1", watcher.state().primary());
        assertEquals("pg-a1", cluster.vipHolder());
        ticks(18);
        assertTrue(b.isStreaming(), cluster.log.toString());
    }

    @Test
    void commandsAreRefusedOnAFollower() {
        threeNodes();
        TestStore follower = new TestStore("w2", StateStore.Mode.RAFT);
        follower.leader = false;
        follower.leaderId = "w1";
        start(standalone(), follower);
        assertThrows(NotLeaderException.class, () -> watcher.switchover("pg-a2",
                SwitchoverOperation.Options.defaults(), "admin", "prod-main"));
        assertNull(watcher.state(), "a follower does not bootstrap");
    }

    // ---------------------------------------------------------------- Raft quorum

    @Test
    void raftModeNeedsAMajorityOfWatchersToSeeThePrimaryGone() {
        threeNodes();
        TestStore s = new TestStore("w1", StateStore.Mode.RAFT);
        start(standalone(), s);
        long epoch = watcher.state().epoch();
        cluster.crash("pg-a1");
        // w2 and w3 still see the primary.
        s.observe("w2", time.millis(), epoch, "pg-a1", true);
        s.observe("w3", time.millis(), epoch, "pg-a1", true);
        ticks(4);
        assertEquals("pg-a1", watcher.state().primary());
        assertTrue(event(EventType.FAILOVER_BLOCKED).message().contains("only 1 of 3 watchers"));
        // w2 agrees now.
        s.observe("w2", time.millis(), epoch, "pg-a1", false);
        tick();
        assertEquals("pg-a2", watcher.state().primary());
    }

    @Test
    void raftLeaderThatCannotConfirmLeadershipStopsRenewingTheLease() {
        threeNodes();
        TestStore s = new TestStore("w1", StateStore.Mode.RAFT);
        start(standalone(), s);
        tick();
        FakeCluster.Node a = cluster.node("pg-a1");
        long deadline = a.leaseDeadline;
        s.confirm = false;
        ticks(3);
        assertEquals(deadline, a.leaseDeadline, "no renewal without a confirmed majority");
    }

    // ---------------------------------------------------------------- uncertain outcomes (review findings)

    @Test
    void promoteThatTimesOutButCompletesIsKept() {
        threeNodes();
        start(standalone());
        cluster.node("pg-a2").promoteTimesOut = true;
        cluster.crash("pg-a1");
        ticks(3);
        assertEquals("pg-a2", watcher.state().primary());
        assertEquals("pg-a2", cluster.vipHolder(), "the VIP is not taken away from a node that became primary");
        assertTrue(hasEvent(EventType.FAILOVER_FINISHED));
        ticks(3);
        assertFalse(cluster.node("pg-a2").fenced, "not fenced as a rogue primary");
    }

    @Test
    void promotedPrimaryIsRecordedThroughStoreFailures() {
        threeNodes();
        TestStore s = new TestStore("w1", StateStore.Mode.STANDALONE);
        start(standalone(), s);
        cluster.node("pg-a2").onPromote = () -> s.failWrites.set(3);
        cluster.crash("pg-a1");
        ticks(3);
        assertEquals("pg-a2", watcher.state().primary());
        ticks(3);
        assertEquals(PgRole.PRIMARY, cluster.node("pg-a2").role);
        assertFalse(cluster.node("pg-a2").fenced);
    }

    @Test
    void switchoverDoesNotRollBackAfterAnUncertainPromote() {
        threeNodes();
        start(standalone());
        cluster.node("pg-a2").promoteFails = true;     // 500 half way: the outcome is unknown
        OperationProgress.Snapshot sw = watcher.switchover("pg-a2", SwitchoverOperation.Options.defaults(), "admin",
                "prod-main");
        awaitIdle();
        OperationProgress.Snapshot done = watcher.operation(sw.id()).orElseThrow();
        assertEquals(OperationProgress.State.FAILED, done.state());
        assertTrue(done.result().contains("state is unknown"), done.result());
        assertFalse(cluster.node("pg-a1").pgRunning, "the old primary is not started next to an unknown target");
        // The watcher then removes the VIP left on the replica and restarts the old primary in place.
        ticks(4);
        assertEquals("pg-a1", watcher.state().primary());
        assertTrue(cluster.node("pg-a1").pgRunning);
        assertEquals(PgRole.PRIMARY, cluster.node("pg-a1").role);
        assertEquals("pg-a1", cluster.vipHolder());
        assertEquals(PgRole.REPLICA, cluster.node("pg-a2").role);
    }

    @Test
    void newLeaderAdoptsAPrimaryPromotedByAnUnfinishedOperation() {
        threeNodes();
        TestStore s = new TestStore("w1", StateStore.Mode.RAFT);
        start(standalone(), s);
        long epoch = watcher.state().epoch();
        // Another watcher (w0) raised the epoch, promoted pg-a2 and lost the lead before recording it.
        new com.bisoft.bfm.core.state.ClusterStateRepo(s).update(c -> true, "", c -> c.withEpoch(epoch + 1).withOperation(
                new ClusterState.OperationRef("op-w0", "FAILOVER", "pg-a2", "w0", "watcher w0", time.millis())));
        cluster.crash("pg-a1");
        FakeCluster.Node b = cluster.node("pg-a2");
        b.epoch = epoch + 1;
        cluster.giveVip("pg-a2");
        b.promote(epoch + 1, 30_000, false);
        tick();
        assertEquals("pg-a1", watcher.state().primary(), "waits before taking over another watcher's operation");
        time.advance(40_000);
        tick();
        assertEquals("pg-a2", watcher.state().primary());
        assertNull(watcher.state().operation());
        ticks(3);
        assertFalse(b.fenced, "adopted, not fenced");
        assertEquals("pg-a2", cluster.vipHolder());
    }

    @Test
    void fencedButRunningPrimaryIsRestoredInPlace() {
        threeNodes();
        start(standalone());
        FakeCluster.Node a = cluster.node("pg-a1");
        a.fenceKeepsRunning = true;
        a.fence("primary lease expired (vip-only fence)");
        assertTrue(a.pgRunning);
        ticks(4);
        assertFalse(a.fenced);
        assertEquals("pg-a1", watcher.state().primary());
        assertEquals("pg-a1", cluster.vipHolder());
        assertEquals(PgRole.REPLICA, cluster.node("pg-a2").role, "no failover next to a running primary");
        assertTrue(hasEvent(EventType.PRIMARY_RESTARTED));
    }

    @Test
    void lagLimitHoldsAfterAWatcherRestart() {
        threeNodes();
        cluster.node("pg-a2").lag = 64L * 1024 * 1024;
        cluster.node("pg-a3").lag = 64L * 1024 * 1024;
        LocalStateStore shared = LocalStateStore.inMemory("w1");
        start(standalone(), shared);
        cluster.write(128L * 1024 * 1024);
        ticks(2);
        watcher.close();
        cluster.crash("pg-a1");
        watcher = new Watcher(standalone(), shared, cluster, time);
        ticks(5);
        assertEquals("pg-a1", watcher.state().primary());
        assertTrue(event(EventType.FAILOVER_BLOCKED).message().contains("behind the primary"));
    }
}
