package com.bisoft.bfm.core;

import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.SlotInfo;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.agent.AgentClient;
import com.bisoft.bfm.core.agent.AgentClientFactory;
import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.decision.PrimaryHealth;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.EventLog;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.monitor.ClusterView;
import com.bisoft.bfm.core.monitor.LagHistory;
import com.bisoft.bfm.core.monitor.NodeView;
import com.bisoft.bfm.core.monitor.Observation;
import com.bisoft.bfm.core.monitor.Poller;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.core.notify.NotificationSettingsRepo;
import com.bisoft.bfm.core.ops.FailoverOperation;
import com.bisoft.bfm.core.ops.NodeRepairOperation;
import com.bisoft.bfm.core.ops.OpContext;
import com.bisoft.bfm.core.ops.Operation;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.ops.PreCheck;
import com.bisoft.bfm.core.ops.StartPrimaryOperation;
import com.bisoft.bfm.core.ops.SwitchoverOperation;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.ClusterStateRepo;
import com.bisoft.bfm.core.state.StateConflictException;
import com.bisoft.bfm.core.state.StateStore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The watcher: polls every agent, keeps the primary's lease, raises alerts and, on the leading watcher, decides and
 * runs failovers, restarts and repairs. Operator commands (switchover, rejoin, reinit, start, controls) come in through
 * the public methods and are refused on a watcher that does not lead.
 *
 * <p>Role changes (failover, switchover, start as primary) run one at a time on their own thread and hold the shared
 * cluster state while they run. Replica repairs (rejoin, reinit) run beside them and do not pause failover.
 */
public class Watcher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(Watcher.class.getName());
    private static final int KEEP_HISTORY = 50;
    private static final int BOOTSTRAP_PATIENCE_POLLS = 30;

    private final ClusterConfig cfg;
    private final StateStore store;
    private final ClusterStateRepo repo;
    private final NotificationSettingsRepo notifications;
    private final EventLog events;
    private final TimeSource time;
    private final Map<String, AgentClient> agents;
    private final Poller poller;
    private final LagHistory lag = new LagHistory();
    private final OpContext ctx;
    private final String actor;

    private final ExecutorService roleExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "bfm-role-op"));
    private final ExecutorService nodeExecutor = Executors.newCachedThreadPool(r -> daemon(r, "bfm-node-op"));
    private ScheduledExecutorService loop;

    private volatile ClusterView view = ClusterView.EMPTY;
    private volatile OperationProgress roleOp;
    private final Map<String, OperationProgress> nodeOps = new ConcurrentHashMap<>();
    private final Deque<OperationProgress> history = new ArrayDeque<>();

    // primary failure tracking (tick thread only)
    private String trackedPrimary;
    private int primaryFailures;
    private boolean unreachableReported;
    private boolean restartAttempted;
    private volatile String blockedReason;
    private volatile long nextFailoverNanos;
    private final Map<String, Long> vipCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> nextRepairNanos = new ConcurrentHashMap<>();
    private final Map<String, Long> badSinceNanos = new HashMap<>();
    private int bootstrapPolls;
    private String bootstrapReported;

    // newest WAL position seen on the current primary
    private volatile String lastPrimaryName;
    private volatile String lastPrimaryLsn;
    private volatile Integer lastPrimaryTimeline;
    private String persistedLsn;
    private long persistedAtNanos;
    static final String POSITION_KEY = "primary-position";

    // lease
    private volatile long lastRenewalNanos;
    private volatile boolean renewedOnce;

    // leadership and alerts
    private String lastLeaderId;
    private boolean wasLeader;
    private long leaderTerm = -1;
    /** When this watcher saw itself become leader; a lease granted by an earlier leader may run until then + TTL. */
    private volatile long leaderSinceNanos;
    private final Set<String> activeAlerts = new HashSet<>();

    public Watcher(ClusterConfig cfg, StateStore store, AgentClientFactory factory, TimeSource time) {
        this.cfg = cfg;
        this.store = store;
        this.time = time;
        this.repo = new ClusterStateRepo(store);
        this.notifications = new NotificationSettingsRepo(store);
        this.events = new EventLog(store, time);
        this.actor = "watcher " + store.selfId();
        Map<String, AgentClient> a = new LinkedHashMap<>();
        for (NodeConfig n : cfg.nodes()) {
            a.put(n.name(), factory.create(n));
        }
        this.agents = Collections.unmodifiableMap(a);
        this.poller = new Poller(cfg, agents, time);
        this.ctx = new OpContext(cfg, agents, store, repo, events, time, this::pollNow, this::primaryInfo,
                new OpContext.LeaseClock() {
                    @Override
                    public long safeAfterNanos() {
                        long since = leaderSinceNanos;
                        long last = renewedOnce && lastRenewalNanos - since > 0 ? lastRenewalNanos : since;
                        return last + TimeUnit.MILLISECONDS.toNanos(cfg.timing().leaseTtlMs()
                                + cfg.timing().leaseMarginMs());
                    }

                    @Override
                    public void granted() {
                        lastRenewalNanos = time.nanos();
                        renewedOnce = true;
                    }
                });
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ---------------------------------------------------------------- lifecycle

    public synchronized void start() {
        if (loop != null) {
            return;
        }
        loop = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "bfm-watcher"));
        loop.scheduleWithFixedDelay(this::safeTick, 0, cfg.timing().pollIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        if (loop != null) {
            loop.shutdownNow();
        }
        roleExecutor.shutdownNow();
        nodeExecutor.shutdownNow();
        poller.close();
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "watcher tick failed", e);
        }
    }

    // ---------------------------------------------------------------- the loop

    /** One round: poll, observe, and, when leading, act. Public for tests. */
    public synchronized void tick() {
        ClusterView v = poller.poll();
        view = v;
        ClusterState st = readState();
        trackPrimary(v, st);
        recordLag(v, st);
        publishObservation(v, st);

        StateStore.Leadership lead = store.leadership();
        trackLeadership(lead);
        if (!lead.leader()) {
            return;
        }
        persistPrimaryPosition();
        if (st == null) {
            bootstrap(v);
            return;
        }
        alerts(v, st);
        if (roleOperationRunning()) {
            return;
        }
        st = clearStaleOperation(st);
        if (st == null) {
            return;
        }
        if (epochConflict(v, st)) {
            return;
        }
        renewLease(v, st);
        if (st.primary() != null) {
            releaseStrayVips(v, st);
        }
        if (handlePrimary(v, st)) {
            return;
        }
        if (st.primary() == null) {
            return;
        }
        restorePrimaryVip(v, st);
        fenceRogues(v, st);
        repairReplicas(v, st);
    }

    private ClusterState readState() {
        try {
            return repo.current();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cannot read cluster state: " + e.getMessage());
            return null;
        }
    }

    private void trackPrimary(ClusterView v, ClusterState st) {
        if (st == null || st.primary() == null) {
            return;
        }
        if (!st.primary().equals(lastPrimaryName)) {
            lastPrimaryName = st.primary();
            lastPrimaryLsn = null;
            lastPrimaryTimeline = null;
        }
        v.node(st.primary()).filter(NodeView::runningPrimary).ifPresent(p -> {
            String lsn = p.status().currentLsn();
            if (lsn != null && (lastPrimaryLsn == null || Lsn.compare(lsn, lastPrimaryLsn) > 0)) {
                lastPrimaryLsn = lsn;
            }
            if (p.status().timeline() != null) {
                lastPrimaryTimeline = p.status().timeline();
            }
        });
    }

    /**
     * Keeps the newest primary position in the shared state (at most every 10 s, or at once after 1 MB), so a
     * watcher that restarts while the primary is down still enforces the lag limit and the timeline check.
     */
    private void persistPrimaryPosition() {
        String lsn = lastPrimaryLsn;
        String primary = lastPrimaryName;
        if (lsn == null || primary == null || lsn.equals(persistedLsn)) {
            return;
        }
        Long moved = persistedLsn == null ? null : Lsn.diff(lsn, persistedLsn);
        if (moved != null && moved < 1024 * 1024 && time.nanos() - persistedAtNanos < TimeUnit.SECONDS.toNanos(10)) {
            return;
        }
        try {
            store.put(POSITION_KEY, JsonMap.create().put("primary", primary).put("lsn", lsn)
                    .put("timeline", lastPrimaryTimeline).put("timeMillis", time.millis()).toJson());
            persistedLsn = lsn;
            persistedAtNanos = time.nanos();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "cannot store the primary position", e);
        }
    }

    /**
     * Own last primary position, raised by fresh observations from other watchers and by the stored position, all
     * about the same primary.
     */
    private OpContext.PrimaryInfo primaryInfo() {
        String lsn = lastPrimaryLsn;
        Integer tl = lastPrimaryTimeline;
        String primary = lastPrimaryName;
        try {
            StateStore.Versioned saved = store.get(POSITION_KEY);
            if (saved != null) {
                JsonMap m = Json.parseObject(saved.value());
                if (Objects.equals(primary, m.str("primary")) && m.str("lsn") != null
                        && (lsn == null || Lsn.compare(m.str("lsn"), lsn) > 0)) {
                    lsn = m.str("lsn");
                    tl = m.intOrNull("timeline") != null ? m.intOrNull("timeline") : tl;
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "cannot read the stored primary position", e);
        }
        for (Observation o : observations()) {
            if (o.watcherId().equals(store.selfId()) || !Objects.equals(primary, o.primary())
                    || o.lastPrimaryLsn() == null) {
                continue;
            }
            if (lsn == null || Lsn.compare(o.lastPrimaryLsn(), lsn) > 0) {
                lsn = o.lastPrimaryLsn();
                if (o.lastPrimaryTimeline() != null) {
                    tl = o.lastPrimaryTimeline();
                }
            }
        }
        return new OpContext.PrimaryInfo(lsn, tl);
    }

    private void recordLag(ClusterView v, ClusterState st) {
        if (st == null || st.primary() == null) {
            return;
        }
        NodeView p = v.node(st.primary()).orElse(null);
        for (NodeView n : v.nodes()) {
            Long l = ClusterView.lagBytes(p, n);
            if (l != null) {
                lag.record(n.name(), v.observedAtMillis(), l);
            }
        }
    }

    private void publishObservation(ClusterView v, ClusterState st) {
        String primary = st == null ? null : st.primary();
        PrimaryHealth h = primary == null ? PrimaryHealth.AGENT_UNREACHABLE : PrimaryHealth.of(v.node(primary).orElse(null));
        Observation o = new Observation(store.selfId(), time.millis(), st == null ? 0 : st.epoch(), primary,
                h == PrimaryHealth.OK, h == PrimaryHealth.OK ? null : primary == null ? "no primary" : h.describe(primary),
                lastPrimaryLsn, lastPrimaryTimeline);
        try {
            store.put(Observation.KEY_PREFIX + store.selfId(), o.toJson());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "cannot publish observation", e);
        }
    }

    private void trackLeadership(StateStore.Leadership lead) {
        if (lead.leader() && (!wasLeader || lead.term() != leaderTerm)) {
            // A new term means another watcher may have led (and renewed leases) in between.
            leaderSinceNanos = time.nanos();
        }
        wasLeader = lead.leader();
        leaderTerm = lead.term();
        if (lead.leader() && !Objects.equals(lastLeaderId, lead.leaderId()) && store.mode() == StateStore.Mode.RAFT) {
            events.publish(EventType.LEADER_CHANGED, null, "Watcher " + store.selfId() + " leads the cluster (term "
                    + lead.term() + ")", actor);
        }
        lastLeaderId = lead.leaderId();
    }

    // ---------------------------------------------------------------- bootstrap

    private void bootstrap(ClusterView v) {
        bootstrapPolls++;
        List<NodeView> primaries = v.runningPrimaries();
        boolean allAnswered = v.nodes().stream().allMatch(NodeView::reachable);
        long epoch = v.maxAgentEpoch() + 1;
        String note;
        if (primaries.size() > 1) {
            note = "Several nodes run as primary (" + String.join(", ", primaries.stream().map(NodeView::name).toList())
                    + "); the watcher will not act until only one does";
            reportBootstrap(note, EventType.EPOCH_CONFLICT);
            return;
        }
        if (!allAnswered && bootstrapPolls < BOOTSTRAP_PATIENCE_POLLS) {
            return;
        }
        String primary = primaries.isEmpty() ? null : primaries.get(0).name();
        ClusterState created = repo.initialise(ClusterState.initial(epoch, primary, time.millis(), actor));
        if (created != null && Objects.equals(created.primary(), primary) && created.epoch() == epoch) {
            note = primary == null ? "Cluster state created; no node runs as primary yet. Start one from the console."
                    : "Cluster state created with " + primary + " as primary (epoch " + epoch + ")";
            events.publish(EventType.CLUSTER_INITIALISED, primary, note, actor);
        }
    }

    private void reportBootstrap(String note, EventType type) {
        if (!note.equals(bootstrapReported)) {
            bootstrapReported = note;
            events.publish(type, null, note, actor);
        }
    }

    // ---------------------------------------------------------------- operations bookkeeping

    private boolean roleOperationRunning() {
        OperationProgress op = roleOp;
        return op != null && !op.finished();
    }

    /** An operation recorded in the state that no thread here runs any more (watcher restart, leader change). */
    /**
     * An operation recorded in the state that no thread here runs any more (watcher restart, leader change).
     * One that another watcher started is left alone until any lease that watcher granted could have run out
     * (its own steps also stop once it notices it lost the lead). If its target already runs as the only primary,
     * that node is adopted as primary rather than fenced. Returns null while waiting.
     */
    private ClusterState clearStaleOperation(ClusterState st) {
        if (st.operation() == null) {
            return st;
        }
        ClusterState.OperationRef stale = st.operation();
        if (!store.selfId().equals(stale.owner()) && time.nanos() - leaderSinceNanos
                < TimeUnit.MILLISECONDS.toNanos(cfg.timing().leaseTtlMs() + cfg.timing().leaseMarginMs())) {
            return null;
        }
        ClusterView v = view;
        List<NodeView> running = v.runningPrimaries();
        String adopt = stale.target() != null && running.size() == 1 && running.get(0).name().equals(stale.target())
                && !running.get(0).fenced() ? stale.target() : null;
        long maxAgent = v.maxAgentEpoch();
        try {
            ClusterState cleared = repo.update(s -> s.operation() != null && stale.id().equals(s.operation().id()),
                    "operation already cleared",
                    s -> (adopt == null ? s : s.withPrimary(adopt)).withOperation(null)
                            .withEpoch(Math.max(s.epoch(), maxAgent) + 1).stamped(time.millis(), actor));
            announceEpoch(cleared.epoch());
            events.publish(EventType.OPERATION, stale.target(), stale.type() + " started by " + stale.startedBy()
                    + " on watcher " + stale.owner() + " did not finish (watcher restarted or lost the lead);"
                    + " the cluster was released with epoch " + cleared.epoch()
                    + (adopt == null ? ". Check the nodes." : "; " + adopt + " already runs as primary and was"
                    + " recorded as the primary."), actor);
            return cleared;
        } catch (StateConflictException e) {
            return readState();
        }
    }

    private void announceEpoch(long epoch) {
        for (AgentClient a : agents.values()) {
            try {
                a.ensureSlots(epoch, List.of());
            } catch (AgentCallException e) {
                LOG.fine("epoch announce to " + a.node() + " failed: " + e.detail());
            }
        }
    }

    private boolean epochConflict(ClusterView v, ClusterState st) {
        long max = v.maxAgentEpoch();
        String key = "epoch-conflict";
        if (max > st.epoch()) {
            if (activeAlerts.add(key)) {
                events.publish(EventType.EPOCH_CONFLICT, null, "An agent is at epoch " + max + ", newer than the"
                        + " watcher state (" + st.epoch() + "). Another watcher may be steering this cluster;"
                        + " automatic actions are paused until an operator starts an operation.", actor);
            }
            return true;
        }
        activeAlerts.remove(key);
        return false;
    }

    private OperationProgress.Snapshot submitRole(Operation op) {
        synchronized (history) {
            if (roleOperationRunning()) {
                throw new StateConflictException(roleOp.type() + " is already running");
            }
            roleOp = op.progress();
            remember(op.progress());
        }
        roleExecutor.execute(() -> {
            op.run();
            if (op instanceof FailoverOperation && op.progress().snapshot().state()
                    == OperationProgress.State.FAILED) {
                nextFailoverNanos = time.nanos() + TimeUnit.MILLISECONDS.toNanos(cfg.timing().failoverRetryMs());
            }
        });
        return op.progress().snapshot();
    }

    private OperationProgress.Snapshot submitNode(String node, Operation op) {
        synchronized (history) {
            OperationProgress cur = nodeOps.get(node);
            if (cur != null && !cur.finished()) {
                throw new StateConflictException(cur.type() + " is already running on " + node);
            }
            nodeOps.put(node, op.progress());
            remember(op.progress());
        }
        nodeExecutor.execute(() -> {
            op.run();
            nextRepairNanos.put(node, time.nanos() + TimeUnit.MILLISECONDS.toNanos(cfg.timing().repairRetryMs()));
        });
        return op.progress().snapshot();
    }

    private void remember(OperationProgress p) {
        history.addFirst(p);
        while (history.size() > KEEP_HISTORY) {
            history.removeLast();
        }
    }

    private boolean nodeOperationRunning(String node) {
        OperationProgress p = nodeOps.get(node);
        return p != null && !p.finished();
    }

    // ---------------------------------------------------------------- lease

    private void renewLease(ClusterView v, ClusterState st) {
        if (st.primary() == null || st.operation() != null) {
            return;
        }
        NodeView p = v.node(st.primary()).orElse(null);
        if (p == null || !p.runningPrimary() || p.fenced()) {
            return;
        }
        if (!store.confirmLeadership()) {
            LOG.warning("leadership not confirmed by a majority; not renewing the lease of " + st.primary());
            return;
        }
        lastRenewalNanos = time.nanos();
        renewedOnce = true;
        try {
            agents.get(st.primary()).grantLease(st.epoch(), cfg.timing().leaseTtlMs(), cfg.selfFence());
        } catch (AgentCallException e) {
            LOG.warning("lease renewal on " + st.primary() + " failed: " + e.detail());
        }
    }

    // ---------------------------------------------------------------- primary health and failover

    /** @return true when an operation was started (nothing else should act in this round) */
    private boolean handlePrimary(ClusterView v, ClusterState st) {
        String primary = st.primary();
        if (primary == null) {
            List<NodeView> running = v.runningPrimaries();
            if (running.size() == 1) {
                String p = running.get(0).name();
                try {
                    repo.update(s -> s.primary() == null && s.operation() == null, "state changed",
                            s -> s.withPrimary(p).stamped(time.millis(), actor));
                    events.publish(EventType.OPERATION, p, p + " runs as primary; recorded it as the cluster primary",
                            actor);
                } catch (StateConflictException ignored) {
                    // someone else recorded a primary
                }
            }
            return false;
        }
        if (!primary.equals(trackedPrimary)) {
            trackedPrimary = primary;
            primaryFailures = 0;
            unreachableReported = false;
            restartAttempted = false;
            blockedReason = null;
        }
        NodeView pv = v.node(primary).orElse(null);
        PrimaryHealth h = PrimaryHealth.of(pv);
        if (h == PrimaryHealth.OK) {
            if (unreachableReported) {
                events.publish(EventType.PRIMARY_BACK, primary, primary + " answers as primary again", actor);
            }
            primaryFailures = 0;
            unreachableReported = false;
            restartAttempted = false;
            blockedReason = null;
            return false;
        }
        primaryFailures++;
        if (primaryFailures < cfg.timing().failureThreshold()) {
            return false;
        }
        if (!unreachableReported) {
            unreachableReported = true;
            events.publish(EventType.PRIMARY_UNREACHABLE, primary, "Primary lost: " + h.describe(primary)
                    + (pv != null && pv.error() != null ? " (" + pv.error() + ")" : ""), actor);
        }
        if (!st.failoverArmed()) {
            blocked(primary, st.maintenance() ? "maintenance mode is on" : "automatic failover is off");
            return false;
        }
        if (time.nanos() - nextFailoverNanos < 0) {
            return false;
        }
        if (cfg.restartPrimaryFirst() && !restartAttempted && h.agentAnswered() && h != PrimaryHealth.NOT_PRIMARY
                && pv != null && !pv.busy() && !(pv.status().pgRunning() && h == PrimaryHealth.PG_DOWN)
                && v.runningPrimaries().stream().allMatch(n -> n.name().equals(primary))) {
            restartAttempted = true;
            submitRole(new StartPrimaryOperation(ctx, primary, true, actor));
            return true;
        }
        if (store.mode() == StateStore.Mode.RAFT) {
            String q = quorumDisagrees(primary, st);
            if (q != null) {
                blocked(primary, q);
                return false;
            }
        }
        long now = time.nanos();
        Set<String> excluded = new HashSet<>();
        vipCooldownUntil.forEach((n, until) -> {
            if (now - until < 0) {
                excluded.add(n);
            }
        });
        submitRole(new FailoverOperation(ctx, primary, st.epoch(), excluded, n -> vipCooldownUntil.put(n,
                time.nanos() + TimeUnit.MILLISECONDS.toNanos(5 * cfg.timing().failoverRetryMs())),
                why -> blocked(primary, why)));
        return true;
    }

    private synchronized void blocked(String primary, String why) {
        if (!why.equals(blockedReason)) {
            blockedReason = why;
            events.publish(EventType.FAILOVER_BLOCKED, primary, "Automatic failover not started: " + why, actor);
        }
    }

    /** Null when a majority of watchers freshly see the primary as gone, otherwise why not. */
    private String quorumDisagrees(String primary, ClusterState st) {
        List<StateStore.WatcherPeer> peers = store.peers();
        int total = Math.max(1, peers.size());
        long now = time.millis();
        int agree = 0;
        List<String> seeing = new ArrayList<>();
        for (Observation o : observations()) {
            boolean fresh = now - o.timeMillis() <= cfg.timing().observationFreshMs();
            if (!fresh || !primary.equals(o.primary()) || o.epoch() != st.epoch()) {
                continue;
            }
            if (o.primarySeen()) {
                seeing.add(o.watcherId());
            } else {
                agree++;
            }
        }
        if (agree * 2 > total) {
            return null;
        }
        return "only " + agree + " of " + total + " watchers see " + primary + " as gone"
                + (seeing.isEmpty() ? "" : " (" + String.join(", ", seeing) + " still see it)");
    }

    // ---------------------------------------------------------------- safety nets

    /** No VIP on a node that is not the primary (for example one left by a promote that never completed). */
    private void releaseStrayVips(ClusterView v, ClusterState st) {
        for (NodeView n : v.nodes()) {
            if (!n.reachable() || n.status().vipAddress() == null || n.busy() || n.name().equals(st.primary())
                    || !n.status().vipHeld()) {
                continue;
            }
            try {
                ApiResult r = agents.get(n.name()).vipRelease(st.epoch());
                events.publish(EventType.VIP, n.name(), "VIP " + n.status().vipAddress() + " was up on " + n.name()
                        + ", which is not the primary; removed it (" + r.message() + ")", actor);
            } catch (AgentCallException e) {
                alertOnce("stray-vip/" + n.name(), EventType.VIP, n.name(), "VIP " + n.status().vipAddress()
                        + " is up on non-primary " + n.name() + " and could not be removed: " + e.detail());
            }
        }
    }

    /** The running primary gets its VIP back if it lost it. */
    private void restorePrimaryVip(ClusterView v, ClusterState st) {
        NodeView p = v.node(st.primary()).orElse(null);
        if (p == null || !p.runningPrimary() || p.fenced() || p.busy() || p.status().vipAddress() == null
                || p.status().vipHeld() || !dueForRepair(p.name())) {
            return;
        }
        try {
            ApiResult r = agents.get(p.name()).vipAcquire(st.epoch());
            events.publish(EventType.VIP, p.name(), "VIP was not up on primary " + p.name() + "; brought it up ("
                    + r.message() + ")", actor);
        } catch (AgentCallException e) {
            nextRepairNanos.put(p.name(), time.nanos() + TimeUnit.MILLISECONDS.toNanos(cfg.timing().repairRetryMs()));
            alertOnce("primary-novip/" + p.name(), EventType.VIP, p.name(), "VIP " + p.status().vipAddress()
                    + " is not up on primary " + p.name() + ": " + e.detail());
        }
    }

    private void fenceRogues(ClusterView v, ClusterState st) {
        if (!cfg.fenceRoguePrimary()) {
            return;
        }
        for (NodeView n : v.runningPrimaries()) {
            if (n.name().equals(st.primary()) || n.busy() || nodeOperationRunning(n.name())) {
                continue;
            }
            String reason = n.name() + " runs as primary but " + st.primary() + " is the primary (epoch "
                    + st.epoch() + ")";
            try {
                ApiResult r = agents.get(n.name()).fence(st.epoch(), reason);
                events.publish(EventType.ROGUE_PRIMARY_FENCED, n.name(), "Fenced " + n.name() + ": " + reason, actor,
                        r.message());
            } catch (AgentCallException e) {
                alertOnce("rogue/" + n.name(), EventType.ROGUE_PRIMARY_FENCED, n.name(), reason
                        + "; fencing it failed: " + e.detail());
            }
        }
    }

    /** Old primaries and stopped or stray replicas come back as streaming replicas of the primary. */
    private void repairReplicas(ClusterView v, ClusterState st) {
        if (!cfg.autoRejoin() || st.maintenance()) {
            return;
        }
        NodeView p = v.node(st.primary()).orElse(null);
        if (p == null || !p.runningPrimary()) {
            return;
        }
        NodeConfig pc = p.config();
        long now = time.nanos();
        for (NodeView n : v.nodes()) {
            if (n.name().equals(st.primary())) {
                continue;
            }
            boolean healthy = !n.reachable() || n.busy() || n.runningPrimary()
                    || (n.runningReplica() && n.status().streaming() && !n.fenced()
                    && Objects.equals(n.status().upstreamHost(), pc.pgHost())
                    && Objects.equals(n.status().upstreamPort(), pc.pgPort()));
            if (healthy) {
                badSinceNanos.remove(n.name());
                continue;
            }
            Long since = badSinceNanos.putIfAbsent(n.name(), now);
            if (since == null || now - since < TimeUnit.MILLISECONDS.toNanos(cfg.timing().replicaGraceMs())) {
                continue;
            }
            if (nodeOperationRunning(n.name()) || !dueForRepair(n.name())) {
                continue;
            }
            if (n.reachable() && !n.status().pgRunning() && n.status().controlClusterState() == null) {
                // No (readable) data directory: only pg_basebackup can bring it back.
                if (st.autoReinit()) {
                    submitNode(n.name(), new NodeRepairOperation(ctx, n.name(), NodeRepairOperation.Kind.REINIT,
                            false, actor));
                } else {
                    alertOnce("nodata/" + n.name(), EventType.NODE_DOWN, n.name(), n.name() + " has no readable"
                            + " data directory; re-initialize it (or turn on auto reinit)");
                }
                continue;
            }
            // A fenced node was a primary: go straight to pg_rewind instead of starting it as it is.
            NodeRepairOperation.Kind kind = n.fenced() ? NodeRepairOperation.Kind.REJOIN
                    : NodeRepairOperation.Kind.FOLLOW;
            submitNode(n.name(), new NodeRepairOperation(ctx, n.name(), kind, st.autoReinit(), actor));
        }
    }

    private boolean dueForRepair(String node) {
        Long next = nextRepairNanos.get(node);
        return next == null || time.nanos() - next >= 0;
    }

    // ---------------------------------------------------------------- alerts

    private void alerts(ClusterView v, ClusterState st) {
        NotificationSettings ns = notifications.current();
        NodeView p = st.primary() == null ? null : v.node(st.primary()).orElse(null);
        for (NodeView n : v.nodes()) {
            String name = n.name();
            // fenced
            condition("fenced/" + name, n.fenced(),
                    () -> events.publish(EventType.AGENT_FENCED, name, name + " is fenced: " + n.status().fencedReason(),
                            actor),
                    null);
            if (name.equals(st.primary())) {
                continue;
            }
            // node down
            boolean down = n.failures() >= cfg.timing().failureThreshold()
                    || (n.reachable() && !n.status().pgRunning() && !n.busy());
            condition("down/" + name, down,
                    () -> events.publish(EventType.NODE_DOWN, name, n.reachable() ? "PostgreSQL on " + name
                            + " is not running" : "Agent on " + name + " does not answer (" + n.error() + ")", actor),
                    () -> events.publish(EventType.NODE_BACK, name, name + " is back", actor));
            // lag
            Long l = ClusterView.lagBytes(p, n);
            if (l != null) {
                long lagNow = l;
                condition("lag/" + name, lagNow > ns.lagAlertBytes(),
                        () -> events.publish(EventType.REPLICA_LAG, name, name + " lag " + Lsn.bytes(lagNow)
                                + ", above alert at " + Lsn.bytes(ns.lagAlertBytes()), actor),
                        () -> events.publish(EventType.REPLICA_LAG_RESOLVED, name, name + " lag back under "
                                + Lsn.bytes(ns.lagAlertBytes()), actor));
            }
        }
        if (p != null && p.runningPrimary() && p.status().slots() != null) {
            Set<String> seen = new HashSet<>();
            for (SlotInfo s : p.status().slots()) {
                String key = "slot/" + s.name();
                seen.add(key);
                boolean bad = !s.active() && s.retainedBytes() != null && s.retainedBytes() > ns.slotAlertBytes();
                condition(key, bad,
                        () -> events.publish(EventType.INACTIVE_SLOT, p.name(), "Inactive slot " + s.name()
                                + " holds " + Lsn.bytes(s.retainedBytes()) + " of WAL on " + p.name(), actor),
                        () -> events.publish(EventType.INACTIVE_SLOT_RESOLVED, p.name(), "Slot " + s.name()
                                + " no longer holds WAL above the alert", actor));
            }
            activeAlerts.removeIf(k -> k.startsWith("slot/") && !seen.contains(k));
        }
        if (store.mode() == StateStore.Mode.RAFT) {
            for (StateStore.WatcherPeer w : store.peers()) {
                if (w.self()) {
                    continue;
                }
                boolean lost = w.lastContactMs() == null || w.lastContactMs() > cfg.timing().observationFreshMs();
                condition("watcher/" + w.id(), lost,
                        () -> events.publish(EventType.WATCHER_LOST, null, "Watcher " + w.id() + " lost contact"
                                + (w.lastContactMs() == null ? "" : " " + w.lastContactMs() / 1000 + " s ago"), actor),
                        () -> events.publish(EventType.WATCHER_REJOINED, null, "Watcher " + w.id() + " is back",
                                actor));
            }
        }
    }

    private void condition(String key, boolean active, Runnable onRaise, Runnable onClear) {
        if (active) {
            if (activeAlerts.add(key)) {
                onRaise.run();
            }
        } else if (activeAlerts.remove(key) && onClear != null) {
            onClear.run();
        }
    }

    private void alertOnce(String key, EventType type, String node, String message) {
        if (activeAlerts.add(key)) {
            events.publish(type, node, message, actor);
        }
    }

    // ---------------------------------------------------------------- queries for the UI and API

    public ClusterConfig config() {
        return cfg;
    }

    public ClusterView view() {
        return view;
    }

    public ClusterState state() {
        return readState();
    }

    public List<ClusterEvent> events(int limit) {
        return events.recent(limit);
    }

    public EventLog eventLog() {
        return events;
    }

    public LagHistory lagHistory() {
        return lag;
    }

    public StateStore store() {
        return store;
    }

    public NotificationSettingsRepo notificationSettings() {
        return notifications;
    }

    public StateStore.Leadership leadership() {
        return store.leadership();
    }

    public List<StateStore.WatcherPeer> peers() {
        return store.peers();
    }

    public List<Observation> observations() {
        List<Observation> out = new ArrayList<>();
        try {
            for (StateStore.Versioned v : store.getPrefix(Observation.KEY_PREFIX).values()) {
                try {
                    out.add(Observation.fromJson(v.value()));
                } catch (RuntimeException ignored) {
                    // skip unreadable entries
                }
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "cannot read observations", e);
        }
        return out;
    }

    /** Running operations first, then finished ones, newest first. */
    public List<OperationProgress.Snapshot> operations() {
        List<OperationProgress.Snapshot> running = new ArrayList<>();
        List<OperationProgress.Snapshot> done = new ArrayList<>();
        synchronized (history) {
            for (OperationProgress p : history) {
                OperationProgress.Snapshot s = p.snapshot();
                (s.running() ? running : done).add(s);
            }
        }
        running.addAll(done);
        return running;
    }

    public Optional<OperationProgress.Snapshot> operation(String id) {
        synchronized (history) {
            for (OperationProgress p : history) {
                if (p.id().equals(id)) {
                    return Optional.of(p.snapshot());
                }
            }
        }
        return Optional.empty();
    }

    /** Every switchover check, including the VIP dry run on the target (takes a few seconds). */
    public List<PreCheck> switchoverChecks(String target) {
        return switchoverChecks(target, true);
    }

    public List<PreCheck> switchoverChecks(String target, boolean vipDryRun) {
        return SwitchoverOperation.preChecks(ctx, view, readState(), target, store.leadership().leader(), vipDryRun);
    }

    // ---------------------------------------------------------------- commands

    private void requireLeader() {
        StateStore.Leadership l = store.leadership();
        if (!l.leader()) {
            throw new NotLeaderException(l.leaderId());
        }
    }

    private void requireNode(String node) {
        if (cfg.node(node).isEmpty()) {
            throw new IllegalArgumentException("unknown node " + node);
        }
    }

    /**
     * Starts a switchover to {@code target}.
     *
     * @param confirmName the cluster name typed by the operator; must match
     */
    public OperationProgress.Snapshot switchover(String target, SwitchoverOperation.Options options, String user,
                                                 String confirmName) {
        requireLeader();
        requireNode(target);
        if (!cfg.name().equals(confirmName == null ? null : confirmName.trim())) {
            throw new IllegalArgumentException("type the cluster name " + cfg.name() + " to confirm");
        }
        List<PreCheck> checks = switchoverChecks(target, false);
        if (!PreCheck.allowed(checks)) {
            String why = checks.stream().filter(c -> c.level() == PreCheck.Level.FAIL)
                    .map(c -> c.title() + ": " + c.detail()).findFirst().orElse("pre-check failed");
            throw new StateConflictException("switchover refused: " + why);
        }
        return submitRole(new SwitchoverOperation(ctx, target, options, user));
    }

    public OperationProgress.Snapshot rejoin(String node, String user) {
        requireLeader();
        requireNode(node);
        ClusterState st = readState();
        return submitNode(node, new NodeRepairOperation(ctx, node, NodeRepairOperation.Kind.REJOIN,
                st != null && st.autoReinit(), user));
    }

    public OperationProgress.Snapshot reinit(String node, String user) {
        requireLeader();
        requireNode(node);
        return submitNode(node, new NodeRepairOperation(ctx, node, NodeRepairOperation.Kind.REINIT, false, user));
    }

    /** Starts a stopped node as primary (first start of a cluster, or after losing every node). */
    public OperationProgress.Snapshot startPrimary(String node, String user) {
        requireLeader();
        requireNode(node);
        ClusterState st = readState();
        if (st == null) {
            throw new StateConflictException("the watcher has not created the cluster state yet; wait for the first"
                    + " poll of every agent");
        }
        NodeView pv = st.primary() == null ? null : view.node(st.primary()).orElse(null);
        if (pv != null && pv.runningPrimary() && !st.primary().equals(node)) {
            throw new StateConflictException(st.primary() + " is running as primary; use a switchover");
        }
        return submitRole(new StartPrimaryOperation(ctx, node, false, user));
    }

    /** Changes the cluster switches. Null leaves a switch as it is. */
    public ClusterState setControls(Boolean autoFailover, Boolean maintenance, Boolean mailEnabled, Boolean autoReinit,
                                    String user) {
        requireLeader();
        ClusterState before = readState();
        if (before == null) {
            throw new StateConflictException("the cluster state is not created yet");
        }
        ClusterState after = repo.update(s -> true, "", s -> s.withControls(
                autoFailover == null ? s.autoFailover() : autoFailover,
                maintenance == null ? s.maintenance() : maintenance,
                mailEnabled == null ? s.mailEnabled() : mailEnabled,
                autoReinit == null ? s.autoReinit() : autoReinit).stamped(time.millis(), user));
        List<String> changes = new ArrayList<>();
        diff(changes, "auto failover", before.autoFailover(), after.autoFailover());
        diff(changes, "maintenance mode", before.maintenance(), after.maintenance());
        diff(changes, "mail notifications", before.mailEnabled(), after.mailEnabled());
        diff(changes, "auto reinit", before.autoReinit(), after.autoReinit());
        if (!changes.isEmpty()) {
            events.publish(EventType.CONTROL_CHANGED, null, String.join(", ", changes) + " by " + user, user);
        }
        if (Boolean.FALSE.equals(maintenance) || Boolean.TRUE.equals(autoFailover)) {
            nextFailoverNanos = time.nanos();
            blockedReason = null;
        }
        return after;
    }

    private static void diff(List<String> out, String name, boolean was, boolean now) {
        if (was != now) {
            out.add(name + (now ? " on" : " off"));
        }
    }

    /** Drops a physical slot on the primary. Slots of configured nodes are refused. */
    public void dropSlot(String slot, String user) {
        requireLeader();
        for (NodeConfig n : cfg.nodes()) {
            if (n.slot().equals(slot)) {
                throw new IllegalArgumentException("slot " + slot + " belongs to node " + n.name()
                        + "; rejoin or re-initialize that node instead");
            }
        }
        ClusterState st = readState();
        if (st == null || st.primary() == null) {
            throw new StateConflictException("no primary");
        }
        ApiResult r = agents.get(st.primary()).dropSlot(st.epoch(), slot);
        events.publish(EventType.SLOT_DROPPED, st.primary(), "Slot " + slot + " dropped on " + st.primary() + " by "
                + user, user, r.message());
    }

    private ClusterView pollNow() {
        ClusterView v = poller.poll();
        view = v;
        return v;
    }

    /** Lease of the current primary as its agent reports it, or null. */
    public NodeStatus primaryStatus() {
        ClusterState st = readState();
        if (st == null || st.primary() == null) {
            return null;
        }
        return view.node(st.primary()).map(NodeView::status).orElse(null);
    }
}
