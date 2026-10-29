package com.bisoft.bfm.core.config;

import java.util.List;
import java.util.Optional;

/**
 * Static cluster definition and failover policy for one watcher.
 *
 * @param name                 cluster name shown in the UI and in mail subjects
 * @param nodes                PostgreSQL nodes
 * @param timing               polling, lease and timeout settings
 * @param scope                which replicas automatic failover may promote
 * @param selfFence            grant the primary a lease with fenceOnExpiry (the agent stops a primary it can no
 *                             longer reach the watchers from). Recommended with Raft; off by default in standalone
 *                             mode, where a single watcher outage would otherwise stop the primary
 * @param autoRejoin           bring a returning old primary back as a replica (pg_rewind) without an operator
 * @param restartPrimaryFirst  when the primary's agent answers but PostgreSQL is down, try restarting it in place
 *                             before failing over
 * @param fenceRoguePrimary    fence any node that runs as primary although the cluster state names another one
 * @param confirmWithReplicas  before an automatic failover, ask every reachable replica whether it still reaches
 *                             the primary; any "yes" blocks the failover
 */
public record ClusterConfig(String name, List<NodeConfig> nodes, Timing timing, FailoverScope scope,
                            boolean selfFence, boolean autoRejoin, boolean restartPrimaryFirst,
                            boolean fenceRoguePrimary, boolean confirmWithReplicas) {

    public ClusterConfig {
        if (nodes == null || nodes.size() < 2) {
            throw new IllegalArgumentException("a cluster needs at least two nodes");
        }
        long distinct = nodes.stream().map(NodeConfig::name).distinct().count();
        if (distinct != nodes.size()) {
            throw new IllegalArgumentException("node names must be unique");
        }
        long slots = nodes.stream().map(NodeConfig::slot).distinct().count();
        if (slots != nodes.size()) {
            throw new IllegalArgumentException("replication slot names must be unique");
        }
        nodes = List.copyOf(nodes);
        timing = timing == null ? Timing.defaults() : timing;
        scope = scope == null ? FailoverScope.DC_LOCAL : scope;
        name = name == null || name.isBlank() ? "cluster" : name;
    }

    /** Defaults for a standalone watcher: no self-fencing; rejoin, restart in place and replica confirmation on. */
    public static ClusterConfig of(String name, List<NodeConfig> nodes, Timing timing) {
        return new ClusterConfig(name, nodes, timing, FailoverScope.DC_LOCAL, false, true, true, true, true);
    }

    public Optional<NodeConfig> node(String nodeName) {
        return nodes.stream().filter(n -> n.name().equals(nodeName)).findFirst();
    }

    public NodeConfig requireNode(String nodeName) {
        return node(nodeName).orElseThrow(() -> new IllegalArgumentException("unknown node " + nodeName));
    }
}
