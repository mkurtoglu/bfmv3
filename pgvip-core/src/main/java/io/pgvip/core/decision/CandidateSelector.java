package io.pgvip.core.decision;

import io.pgvip.common.Lsn;
import io.pgvip.core.config.ClusterConfig;
import io.pgvip.core.config.FailoverScope;
import io.pgvip.core.config.NodeConfig;
import io.pgvip.core.monitor.ClusterView;
import io.pgvip.core.monitor.NodeView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Picks the replica an automatic failover promotes.
 *
 * <p>A replica is eligible when its agent answers, it runs in recovery, it is not fenced or busy, it is on the
 * primary's last timeline, it is not marked noFailover, it is in the old primary's data center (unless the scope is
 * ANY) and it is no more than {@code maxLagBytes} behind the newest WAL position seen on the primary. Eligible
 * replicas are ordered by received WAL position, then priority, then name.
 */
public final class CandidateSelector {

    private CandidateSelector() {
    }

    public record Candidate(NodeView view, long lsn, Long behindBytes) {
        public String name() {
            return view.name();
        }

        public NodeConfig config() {
            return view.config();
        }
    }

    public record Rejected(String node, String reason) {
    }

    public record Result(List<Candidate> eligible, List<Rejected> rejected) {
        public Optional<Candidate> best() {
            return eligible.isEmpty() ? Optional.empty() : Optional.of(eligible.get(0));
        }

        public String rejectedSummary() {
            if (rejected.isEmpty()) {
                return "no replicas configured";
            }
            StringBuilder sb = new StringBuilder();
            for (Rejected r : rejected) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(r.node()).append(": ").append(r.reason());
            }
            return sb.toString();
        }
    }

    /**
     * @param oldPrimary      the failed primary
     * @param primaryTimeline timeline last seen on the primary, null if never seen
     * @param primaryLsn      newest WAL position seen on the primary, null if never seen
     * @param excluded        nodes to skip (for example after a VIP failure), with no reason needed
     */
    public static Result rank(ClusterConfig cfg, ClusterView view, String oldPrimary, Integer primaryTimeline,
                              String primaryLsn, Set<String> excluded) {
        NodeConfig old = cfg.node(oldPrimary).orElse(null);
        List<Candidate> ok = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (NodeView v : view.nodes()) {
            if (v.name().equals(oldPrimary)) {
                continue;
            }
            String why = reject(cfg, v, old, primaryTimeline, primaryLsn, excluded);
            if (why != null) {
                rejected.add(new Rejected(v.name(), why));
                continue;
            }
            long lsn = Lsn.parse(v.status().bestLsn());
            Long behind = primaryLsn == null ? null : Lsn.diff(primaryLsn, v.status().bestLsn());
            ok.add(new Candidate(v, lsn, behind));
        }
        ok.sort(Comparator.comparingLong(Candidate::lsn).reversed()
                .thenComparing(Comparator.comparingInt((Candidate c) -> c.config().priority()).reversed())
                .thenComparing(Candidate::name));
        return new Result(List.copyOf(ok), List.copyOf(rejected));
    }

    private static String reject(ClusterConfig cfg, NodeView v, NodeConfig old, Integer primaryTimeline,
                                 String primaryLsn, Set<String> excluded) {
        if (excluded.contains(v.name())) {
            return "skipped after a failed attempt";
        }
        if (v.config().noFailover()) {
            return "marked noFailover";
        }
        if (cfg.scope() == FailoverScope.DC_LOCAL && old != null && !Objects.equals(old.dc(), v.config().dc())) {
            return "in data center " + v.config().dc() + ", automatic failover stays in " + old.dc();
        }
        if (!v.reachable()) {
            return "agent unreachable";
        }
        if (v.fenced()) {
            return "fenced";
        }
        if (v.busy()) {
            return "busy with " + v.status().busyOperation();
        }
        if (!v.runningReplica()) {
            return "not running as a replica";
        }
        if (v.status().bestLsn() == null) {
            return "WAL position unknown";
        }
        if (primaryTimeline != null && v.status().timeline() != null
                && !primaryTimeline.equals(v.status().timeline())) {
            return "on timeline " + v.status().timeline() + ", primary was on " + primaryTimeline;
        }
        if (primaryLsn != null) {
            Long behind = Lsn.diff(primaryLsn, v.status().bestLsn());
            if (behind != null && behind > cfg.timing().maxLagBytes()) {
                return Lsn.bytes(behind) + " behind the primary, limit " + Lsn.bytes(cfg.timing().maxLagBytes());
            }
        }
        return null;
    }
}
