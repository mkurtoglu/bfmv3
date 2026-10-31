package com.bisoft.bfm.core.config;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;

import java.util.ArrayList;
import java.util.List;

/**
 * A cluster created in the console: what {@code watcher.yml} holds for a file-defined one, kept in the shared state
 * ({@code clusters/<id>/spec}) so every watcher of the group runs it the same way. A draft may still lack nodes.
 *
 * @param allowColocation lab only: lets two nodes of this cluster share a host (no protection against losing it)
 */
public record ClusterSpec(String name, String environment, FailoverScope scope, boolean selfFence,
                          long pollIntervalMs, int failureThreshold, long maxLagBytes, boolean allowColocation,
                          List<NodeSpec> nodes) {

    public static final String KEY = "spec";

    public ClusterSpec {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        scope = scope == null ? FailoverScope.DC_LOCAL : scope;
        Timing d = Timing.defaults();
        pollIntervalMs = pollIntervalMs <= 0 ? d.pollIntervalMs() : pollIntervalMs;
        failureThreshold = failureThreshold <= 0 ? d.failureThreshold() : failureThreshold;
        maxLagBytes = maxLagBytes <= 0 ? d.maxLagBytes() : maxLagBytes;
    }

    /**
     * @param agentUrl where the watcher reaches the node's agent, for example {@code https://db01:8009}
     * @param pgHost   the node's own address replicas stream from (never the VIP)
     */
    public record NodeSpec(String name, String agentUrl, String pgHost, int pgPort, String dc, int priority,
                           boolean noFailover) {
    }

    public static ClusterSpec draft(String name, String environment) {
        return new ClusterSpec(name, environment, FailoverScope.DC_LOCAL, false, 0, 0, 0, false, List.of());
    }

    public ClusterSpec withNodes(List<NodeSpec> list) {
        return new ClusterSpec(name, environment, scope, selfFence, pollIntervalMs, failureThreshold, maxLagBytes,
                allowColocation, list);
    }

    public ClusterSpec withBasics(String env, FailoverScope s, boolean fence, long poll, int threshold, long lag,
                                  boolean coloc) {
        return new ClusterSpec(name, env, s, fence, poll, threshold, lag, coloc, nodes);
    }

    public ClusterSpec withName(String n) {
        return new ClusterSpec(n, environment, scope, selfFence, pollIntervalMs, failureThreshold, maxLagBytes,
                allowColocation, nodes);
    }

    /** The engine's configuration; needs at least two nodes. */
    public ClusterConfig toConfig() {
        List<NodeConfig> list = new ArrayList<>();
        for (NodeSpec n : nodes) {
            list.add(new NodeConfig(n.name(), n.agentUrl(), n.pgHost(), n.pgPort(), n.dc(), n.priority(),
                    n.noFailover(), null));
        }
        Timing d = Timing.defaults();
        long lease = Math.max(d.leaseTtlMs(), 3 * pollIntervalMs);
        long fresh = Math.max(d.observationFreshMs(), 2 * pollIntervalMs);
        Timing t = new Timing(pollIntervalMs, failureThreshold, d.agentTimeoutMs(), lease, d.leaseMarginMs(), fresh,
                maxLagBytes, d.switchoverCatchupMs(), d.operationTimeoutMs(), d.failoverRetryMs(), d.repairRetryMs(),
                d.replicaGraceMs());
        return new ClusterConfig(name, list, t, scope, selfFence, true, true, true, true);
    }

    public String toJson() {
        List<Object> ns = new ArrayList<>();
        for (NodeSpec n : nodes) {
            ns.add(JsonMap.create().put("name", n.name()).put("agentUrl", n.agentUrl()).put("pgHost", n.pgHost())
                    .put("pgPort", n.pgPort()).put("dc", n.dc()).put("priority", n.priority())
                    .put("noFailover", n.noFailover()).raw());
        }
        return JsonMap.create().put("name", name).put("environment", environment).put("scope", scope)
                .put("selfFence", selfFence).put("pollIntervalMs", pollIntervalMs)
                .put("failureThreshold", failureThreshold).put("maxLagBytes", maxLagBytes)
                .put("allowColocation", allowColocation).put("nodes", ns).toJson();
    }

    public static ClusterSpec fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        List<NodeSpec> nodes = m.objList("nodes", n -> new NodeSpec(n.requireStr("name"), n.str("agentUrl"),
                n.str("pgHost"), n.integer("pgPort", 5432), n.str("dc", "default"), n.integer("priority", 100),
                n.bool("noFailover", false)));
        return new ClusterSpec(m.requireStr("name"), m.str("environment"),
                m.enumValue("scope", FailoverScope.class, FailoverScope.DC_LOCAL), m.bool("selfFence", false),
                m.lng("pollIntervalMs", 0), m.integer("failureThreshold", 0), m.lng("maxLagBytes", 0),
                m.bool("allowColocation", false), nodes);
    }
}
