package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.ReplicationPeer;
import com.bisoft.bfm.common.model.SlotInfo;
import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.decision.PrimaryHealth;
import com.bisoft.bfm.core.monitor.ClusterView;
import com.bisoft.bfm.core.monitor.NodeView;
import com.bisoft.bfm.core.monitor.Observation;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.RoleChange;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.config.BfmProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the read models shown in the console and returned by the REST API. */
@Service
public class ConsoleService {

    private final Watcher watcher;
    private final BfmProperties props;

    public ConsoleService(Watcher watcher, BfmProperties props) {
        this.watcher = watcher;
        this.props = props;
    }

    public Watcher watcher() {
        return watcher;
    }

    public record NodeRow(String name, String address, String dc, String role, String state, String lsn,
                          Long lagBytes, Long replayDelayMs, Integer timeline, String sync, boolean agentOnline,
                          long lastCheckMillis, boolean fenced, String fencedReason, boolean vipHeld, String vipAddress,
                          long epoch, String error, String busy, boolean noFailover, int priority,
                          boolean isPrimary, boolean streaming, String upstream, long stateSinceMillis) {
    }

    /** The last change of one cluster switch, from the event log. */
    public record ControlChange(boolean on, String by, long atMillis) {
    }

    public record SlotRow(String name, String consumer, boolean active, Long retainedBytes) {
    }

    public record WatcherRow(String id, String address, boolean self, boolean leader, Long lastContactMs,
                             Boolean seesPrimary, Long observationAgeMs, String url) {
    }

    public record Lease(boolean granted, long remainingMs, long ttlMs, boolean fenceOnExpiry) {
    }

    public record Overview(String cluster, String watcherId, String mode, boolean leader, String leaderId,
                           String leaderUrl, long term, long epoch, String primary, String primaryAddress,
                           String primaryDc, Integer timeline, String health, boolean autoFailover,
                           boolean maintenance, boolean mailEnabled, boolean autoReinit, String scope,
                           boolean selfFence, String vipAddress, String vipHolder, Long maxLagBytes,
                           String maxLagNode, Long maxLagDelayMs, Lease lease, ClusterState.OperationRef operation,
                           List<NodeRow> nodes, List<SlotRow> slots, List<WatcherRow> watchers,
                           long observedAtMillis, String environment, List<String> healthReasons,
                           String failoverBlockedReason, RoleChange roleChange, Map<String, ControlChange> controls) {
        /**
         * Where the running operation's steps and log can be followed. They live in the memory of the watcher
         * that runs it, so on any other watcher this is that watcher's console (from {@code raft.peers[].url}),
         * or null when its address is not configured.
         */
        public String operationConsoleUrl() {
            if (operation == null || operation.owner() == null || operation.owner().equals(watcherId)) {
                return null;
            }
            for (WatcherRow w : watchers) {
                if (w.id().equals(operation.owner()) && w.url() != null && !w.url().isBlank()) {
                    String base = w.url().endsWith("/") ? w.url().substring(0, w.url().length() - 1) : w.url();
                    return base + "/operations";
                }
            }
            return null;
        }

        public boolean failoverActive() {
            return autoFailover && !maintenance;
        }

        /** The row of the recorded primary, or null. */
        public NodeRow primaryRow() {
            return nodes.stream().filter(NodeRow::isPrimary).findFirst().orElse(null);
        }

        /** The last role change while nobody has acknowledged it; null otherwise. */
        public RoleChange unacknowledgedRoleChange() {
            return roleChange == null || roleChange.acknowledged() ? null : roleChange;
        }
    }

    public Overview overview() {
        ClusterConfig cfg = watcher.config();
        ClusterView view = watcher.view();
        ClusterState st = watcher.state();
        StateStore.Leadership lead = watcher.leadership();
        String primary = st == null ? null : st.primary();
        NodeView pv = primary == null ? null : view.node(primary).orElse(null);

        Map<String, String> sync = new HashMap<>();
        if (pv != null && pv.runningPrimary() && pv.status().replicas() != null) {
            for (ReplicationPeer r : pv.status().replicas()) {
                sync.put(r.applicationName(), r.syncState());
            }
        }
        List<NodeRow> rows = new ArrayList<>();
        Long maxLag = null;
        String maxLagNode = null;
        Long maxDelay = null;
        String vipHolder = null;
        String vipAddress = null;
        for (NodeConfig nc : cfg.nodes()) {
            NodeView v = view.node(nc.name()).orElse(null);
            NodeStatus s = v == null ? null : v.status();
            Long lag = ClusterView.lagBytes(pv, v);
            if (lag != null && (maxLag == null || lag > maxLag)) {
                maxLag = lag;
                maxLagNode = nc.name();
                maxDelay = s.replayDelayMs();
            }
            if (s != null && s.vipHeld()) {
                vipHolder = nc.name();
            }
            if (s != null && s.vipAddress() != null && (vipAddress == null || nc.name().equals(primary))) {
                vipAddress = s.vipAddress();
            }
            String role = s == null ? "Unknown" : s.isPrimary() ? "Primary" : s.isReplica() ? "Replica"
                    : s.pgRunning() ? "Unknown" : "Stopped";
            rows.add(new NodeRow(nc.name(), nc.pgHost() + ":" + nc.pgPort(), nc.dc(), role,
                    v == null ? "Not polled yet" : v.stateLabel(),
                    s == null ? null : s.isPrimary() ? s.currentLsn() : s.replayLsn() != null ? s.replayLsn()
                            : s.controlCheckpointLsn(),
                    lag, s == null || s.isPrimary() ? null : s.replayDelayMs(),
                    s == null ? null : s.timeline() != null ? s.timeline() : s.controlTimeline(),
                    s != null && s.isPrimary() ? null : sync.get(nc.name()),
                    s != null, v == null ? 0 : v.observedAtMillis(), s != null && s.fenced(),
                    s == null ? null : s.fencedReason(), s != null && s.vipHeld(), s == null ? null : s.vipAddress(),
                    s == null ? 0 : s.epoch(), v == null ? null : v.error() != null ? v.error() : s == null ? null
                    : s.error(), s == null ? null : s.busyOperation(), nc.noFailover(), nc.priority(),
                    nc.name().equals(primary), s != null && s.streaming(),
                    s == null || s.upstreamHost() == null ? null : s.upstreamHost() + ":" + s.upstreamPort(),
                    since(nc.name())));
        }

        List<SlotRow> slots = new ArrayList<>();
        if (pv != null && pv.runningPrimary() && pv.status().slots() != null) {
            for (SlotInfo si : pv.status().slots()) {
                String consumer = cfg.nodes().stream().filter(n -> n.slot().equals(si.name())).map(NodeConfig::name)
                        .findFirst().orElse("none");
                slots.add(new SlotRow(si.name(), consumer, si.active(), si.retainedBytes()));
            }
        }

        Map<String, Observation> obs = new HashMap<>();
        for (Observation o : watcher.observations()) {
            obs.put(o.watcherId(), o);
        }
        long now = System.currentTimeMillis();
        List<WatcherRow> watchers = new ArrayList<>();
        Map<String, String> urls = new HashMap<>();
        for (BfmProperties.Peer p : props.raft().peers()) {
            urls.put(p.id(), p.url());
        }
        for (StateStore.WatcherPeer w : watcher.peers()) {
            Observation o = obs.get(w.id());
            watchers.add(new WatcherRow(w.id(), w.address(), w.self(), w.leader(), w.lastContactMs(),
                    o == null ? null : o.primarySeen(), o == null ? null : now - o.timeMillis(), urls.get(w.id())));
        }

        Lease lease = null;
        if (pv != null && pv.status() != null && pv.status().lease() != null) {
            var l = pv.status().lease();
            lease = new Lease(l.granted(), l.remainingMs(), cfg.timing().leaseTtlMs(), l.fenceOnExpiry());
        }
        PrimaryHealth h = primary == null ? null : PrimaryHealth.of(pv);
        List<String> reasons = healthReasons(st, primary, h, rows, slots, vipAddress, vipHolder);
        String health;
        if (st == null) {
            health = "Starting";
        } else if (primary == null) {
            health = "No primary";
        } else if (h != PrimaryHealth.OK) {
            health = "Primary down";
        } else if (reasons.isEmpty()) {
            health = "Healthy";
        } else {
            health = "Degraded";
        }
        NodeConfig pc = primary == null ? null : cfg.node(primary).orElse(null);
        return new Overview(cfg.name(), watcher.store().selfId(), watcher.store().mode().name(), lead.leader(),
                lead.leaderId(), lead.leaderId() == null ? null : urls.get(lead.leaderId()), lead.term(),
                st == null ? 0 : st.epoch(), primary, pc == null ? null : pc.pgHost() + ":" + pc.pgPort(),
                pc == null ? null : pc.dc(),
                pv != null && pv.status() != null ? pv.status().timeline() : null, health,
                st != null && st.autoFailover(), st != null && st.maintenance(), st != null && st.mailEnabled(),
                st != null && st.autoReinit(), cfg.scope().name(), cfg.selfFence(), vipAddress, vipHolder, maxLag,
                maxLagNode, maxDelay, lease, st == null ? null : st.operation(), rows, slots, watchers,
                view.observedAtMillis(), props.cluster().environment(), reasons,
                lead.leader() ? watcher.failoverBlockedReason() : null, watcher.lastRoleChange(), controls());
    }

    private long since(String node) {
        Watcher.StateSince s = watcher.stateSince(node);
        return s == null ? 0 : s.sinceMillis();
    }

    /**
     * Why the cluster is not healthy, one short line per problem, worst first. Empty when it is healthy: the primary
     * runs and holds the VIP, every replica streams, no lag or idle slot is over its alert threshold.
     */
    List<String> healthReasons(ClusterState st, String primary, PrimaryHealth h, List<NodeRow> rows,
                               List<SlotRow> slots, String vipAddress, String vipHolder) {
        List<String> out = new ArrayList<>();
        if (st == null) {
            out.add("the watcher has not read the cluster state yet");
            return out;
        }
        if (primary == null) {
            out.add("no node is recorded as primary");
            return out;
        }
        NodeRow p = rows.stream().filter(NodeRow::isPrimary).findFirst().orElse(null);
        if (h != PrimaryHealth.OK) {
            out.add(primary + ": " + (p == null ? "not polled yet" : lower(p.state())));
        } else if (vipAddress != null && vipHolder == null) {
            out.add("VIP " + vipAddress + " is not up on " + primary);
        }
        if (vipHolder != null && !vipHolder.equals(primary)) {
            out.add("VIP " + vipAddress + " is on " + vipHolder + ", not on the primary");
        }
        NotificationSettings ns = watcher.notificationSettings().current();
        for (NodeRow r : rows) {
            if (r.isPrimary()) {
                continue;
            }
            if (!r.agentOnline()) {
                out.add(r.name() + ": agent unreachable");
            } else if (r.fenced()) {
                out.add(r.name() + ": fenced");
            } else if (r.busy() != null) {
                out.add(r.name() + ": " + lower(Terms.operation(r.busy())) + " running");
            } else if (!r.streaming()) {
                out.add(r.name() + ": " + lower(r.state()));
            } else if (r.lagBytes() != null && r.lagBytes() > ns.lagAlertBytes()) {
                out.add(r.name() + ": " + Lsn.bytes(r.lagBytes()) + " behind (alert at " + Lsn.bytes(ns.lagAlertBytes())
                        + ")");
            }
        }
        for (SlotRow sl : slots) {
            if (!sl.active() && sl.retainedBytes() != null && sl.retainedBytes() > ns.slotAlertBytes()) {
                out.add("inactive slot " + sl.name() + " holds " + Lsn.bytes(sl.retainedBytes()) + " of WAL");
            }
        }
        return out;
    }

    private static String lower(String s) {
        return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** When each switch was last turned on or off, and by whom, from the shared event log. */
    Map<String, ControlChange> controls() {
        Map<String, ControlChange> out = new LinkedHashMap<>();
        String[] names = {"auto failover", "maintenance mode", "mail notifications", "auto reinit"};
        // The switches change rarely; the latest few hundred events are enough and keep each refresh cheap.
        for (ClusterEvent e : watcher.events(200)) {
            if (e.type() != EventType.CONTROL_CHANGED || e.message() == null) {
                continue;
            }
            for (String n : names) {
                if (out.containsKey(n)) {
                    continue;
                }
                if (e.message().contains(n + " on")) {
                    out.put(n, new ControlChange(true, e.actor(), e.timeMillis()));
                } else if (e.message().contains(n + " off")) {
                    out.put(n, new ControlChange(false, e.actor(), e.timeMillis()));
                }
            }
            if (out.size() == names.length) {
                break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- replication

    /**
     * One replica, seen from both sides: what its own agent reports and what the primary's pg_stat_replication
     * says about it. The two differ while WAL is in flight, and the difference is what a failover would lose.
     *
     * @param flushBehindBytes  written on the primary but not yet durable on this replica (lost in a crash failover)
     * @param replayBehindBytes written on the primary but not yet replayed here (how stale reads are)
     */
    public record ReplicaRow(String node, String dc, String address, boolean agentOnline, String state,
                             boolean streaming, String upstream, Integer timeline, String receiveLsn,
                             String replayLsn, Long replayBehindBytes, Long replayDelayMs, String syncState,
                             String slot, Boolean slotActive, Long slotRetainedBytes, String peerState,
                             String clientAddr, String sentLsn, String writeLsn, String flushLsn,
                             Long flushBehindBytes, Long peerReplayLagMs, boolean fenced, String fencedReason,
                             String busy, String error, boolean noFailover) {

        /** The primary has an open walsender for this node. */
        public boolean connectedToPrimary() {
            return peerState != null;
        }
    }

    /**
     * @param walRatePerMinute bytes of WAL the primary wrote per minute over the last two polls, null until known
     */
    public record Replication(String cluster, String primary, String primaryAddress, String primaryLsn,
                              Integer primaryTimeline, boolean primaryRunning, int streamingCount,
                              Long worstFlushBehindBytes, Long worstReplayBehindBytes, Long walRatePerMinute,
                              List<ReplicaRow> replicas, List<SlotRow> slots, long observedAtMillis) {
    }

    public Replication replication() {
        ClusterConfig cfg = watcher.config();
        ClusterView view = watcher.view();
        ClusterState st = watcher.state();
        String primary = st == null ? null : st.primary();
        NodeView pv = primary == null ? null : view.node(primary).orElse(null);
        NodeStatus ps = pv == null ? null : pv.status();
        boolean primaryRunning = pv != null && pv.runningPrimary();
        String primaryLsn = primaryRunning ? ps.currentLsn() : null;

        Map<String, ReplicationPeer> peers = new HashMap<>();
        if (primaryRunning && ps.replicas() != null) {
            for (ReplicationPeer r : ps.replicas()) {
                peers.put(r.applicationName(), r);
            }
        }
        Map<String, SlotInfo> slotsByName = new HashMap<>();
        if (primaryRunning && ps.slots() != null) {
            for (SlotInfo si : ps.slots()) {
                slotsByName.put(si.name(), si);
            }
        }

        List<ReplicaRow> rows = new ArrayList<>();
        Long worstFlush = null;
        Long worstReplay = null;
        int streaming = 0;
        for (NodeConfig nc : cfg.nodes()) {
            if (nc.name().equals(primary)) {
                continue;
            }
            NodeView v = view.node(nc.name()).orElse(null);
            NodeStatus s = v == null ? null : v.status();
            ReplicationPeer peer = peers.get(nc.name());
            SlotInfo slot = slotsByName.get(nc.slot());
            Long replayBehind = ClusterView.lagBytes(pv, v);
            Long flushBehind = peer == null ? null : Lsn.diff(primaryLsn, peer.flushLsn());
            if (s != null && s.isReplica() && s.streaming()) {
                streaming++;
            }
            if (flushBehind != null && (worstFlush == null || flushBehind > worstFlush)) {
                worstFlush = flushBehind;
            }
            if (replayBehind != null && (worstReplay == null || replayBehind > worstReplay)) {
                worstReplay = replayBehind;
            }
            rows.add(new ReplicaRow(nc.name(), nc.dc(), nc.pgHost() + ":" + nc.pgPort(), s != null,
                    v == null ? "Not polled yet" : v.stateLabel(), s != null && s.streaming(),
                    s == null || s.upstreamHost() == null ? null : s.upstreamHost() + ":" + s.upstreamPort(),
                    s == null ? null : s.timeline(), s == null ? null : s.receiveLsn(),
                    s == null ? null : s.replayLsn(), replayBehind, s == null ? null : s.replayDelayMs(),
                    peer == null ? null : peer.syncState(), nc.slot(), slot == null ? null : slot.active(),
                    slot == null ? null : slot.retainedBytes(), peer == null ? null : peer.state(),
                    peer == null ? null : peer.clientAddr(), peer == null ? null : peer.sentLsn(),
                    peer == null ? null : peer.writeLsn(), peer == null ? null : peer.flushLsn(), flushBehind,
                    peer == null ? null : peer.replayLagMs(), s != null && s.fenced(),
                    s == null ? null : s.fencedReason(), s == null ? null : s.busyOperation(),
                    v == null ? null : v.error() != null ? v.error() : s == null ? null : s.error(),
                    nc.noFailover()));
        }

        List<SlotRow> slotRows = new ArrayList<>();
        if (primaryRunning && ps.slots() != null) {
            for (SlotInfo si : ps.slots()) {
                String consumer = cfg.nodes().stream().filter(n -> n.slot().equals(si.name())).map(NodeConfig::name)
                        .findFirst().orElse("none");
                slotRows.add(new SlotRow(si.name(), consumer, si.active(), si.retainedBytes()));
            }
        }
        return new Replication(cfg.name(), primary, pv == null ? null : pv.config().pgHost() + ":"
                + pv.config().pgPort(), primaryLsn, primaryRunning ? ps.timeline() : null, primaryRunning,
                streaming, worstFlush, worstReplay, walRate(primaryLsn, view.observedAtMillis()), rows, slotRows,
                view.observedAtMillis());
    }

    private String rateLsn;
    private long rateAtMillis;
    private Long walRatePerMinute;

    /** WAL the primary wrote per minute, from the movement of its position between two polls. */
    private synchronized Long walRate(String lsn, long atMillis) {
        if (lsn == null) {
            rateLsn = null;
            return null;
        }
        if (rateLsn != null && atMillis > rateAtMillis) {
            Long moved = Lsn.diff(lsn, rateLsn);
            long elapsed = atMillis - rateAtMillis;
            if (moved != null && elapsed >= 10_000) {
                walRatePerMinute = Math.round(moved * 60_000.0 / elapsed);
                rateLsn = lsn;
                rateAtMillis = atMillis;
            }
        } else {
            rateLsn = lsn;
            rateAtMillis = atMillis;
        }
        return walRatePerMinute;
    }

    /** Human readable bytes. */
    public static String bytes(Long b) {
        return b == null ? "—" : Lsn.bytes(b);
    }

    public static boolean same(String a, String b) {
        return Objects.equals(a, b);
    }
}
