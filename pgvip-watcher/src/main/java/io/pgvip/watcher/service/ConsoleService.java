package io.pgvip.watcher.service;

import io.pgvip.common.Lsn;
import io.pgvip.common.model.NodeStatus;
import io.pgvip.common.model.ReplicationPeer;
import io.pgvip.common.model.SlotInfo;
import io.pgvip.core.Watcher;
import io.pgvip.core.config.ClusterConfig;
import io.pgvip.core.config.NodeConfig;
import io.pgvip.core.decision.PrimaryHealth;
import io.pgvip.core.monitor.ClusterView;
import io.pgvip.core.monitor.NodeView;
import io.pgvip.core.monitor.Observation;
import io.pgvip.core.state.ClusterState;
import io.pgvip.core.state.StateStore;
import io.pgvip.watcher.config.PgvipProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the read models shown in the console and returned by the REST API. */
@Service
public class ConsoleService {

    private final Watcher watcher;
    private final PgvipProperties props;

    public ConsoleService(Watcher watcher, PgvipProperties props) {
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
                          boolean isPrimary, boolean streaming, String upstream) {
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
                           long observedAtMillis) {
        public boolean failoverActive() {
            return autoFailover && !maintenance;
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
                    s == null || s.upstreamHost() == null ? null : s.upstreamHost() + ":" + s.upstreamPort()));
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
        for (PgvipProperties.Peer p : props.raft().peers()) {
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
        String health;
        if (st == null) {
            health = "Starting";
        } else if (primary == null) {
            health = "No primary";
        } else if (h != PrimaryHealth.OK) {
            health = "Primary down";
        } else if (rows.stream().allMatch(r -> r.isPrimary() || (r.streaming() && !r.fenced()))) {
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
                view.observedAtMillis());
    }

    /** Human readable bytes. */
    public static String bytes(Long b) {
        return b == null ? "—" : Lsn.bytes(b);
    }

    public static boolean same(String a, String b) {
        return Objects.equals(a, b);
    }
}
