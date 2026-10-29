package com.bisoft.bfm.core.monitor;

import com.bisoft.bfm.common.Lsn;

import java.util.List;
import java.util.Optional;

/** One poll of every node. */
public record ClusterView(List<NodeView> nodes, long observedAtMillis) {

    public static final ClusterView EMPTY = new ClusterView(List.of(), 0);

    public ClusterView {
        nodes = List.copyOf(nodes);
    }

    public Optional<NodeView> node(String name) {
        return nodes.stream().filter(n -> n.name().equals(name)).findFirst();
    }

    public List<NodeView> runningPrimaries() {
        return nodes.stream().filter(NodeView::runningPrimary).toList();
    }

    /** Highest epoch any reachable agent reports. */
    public long maxAgentEpoch() {
        return nodes.stream().filter(NodeView::reachable).mapToLong(n -> n.status().epoch()).max().orElse(0);
    }

    /**
     * Bytes {@code replica} is behind {@code primary}: the primary's current LSN minus what the replica has
     * replayed (or received, when nothing has been replayed yet). Null when either side is unknown.
     */
    public static Long lagBytes(NodeView primary, NodeView replica) {
        if (primary == null || replica == null || !primary.runningPrimary() || !replica.runningReplica()) {
            return null;
        }
        String behind = replica.status().replayLsn() != null ? replica.status().replayLsn()
                : replica.status().receiveLsn();
        return Lsn.diff(primary.status().currentLsn(), behind);
    }
}
