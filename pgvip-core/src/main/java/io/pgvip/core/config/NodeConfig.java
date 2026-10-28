package io.pgvip.core.config;

import java.util.Locale;

/**
 * One PostgreSQL node as the watcher knows it.
 *
 * @param name       unique node name, the same as the agent's node.name
 * @param agentUrl   base URL of the agent API, for example https://10.0.0.11:8008
 * @param pgHost     address clients and replicas use to reach this PostgreSQL (not the VIP)
 * @param pgPort     PostgreSQL port
 * @param dc         data center label; automatic failover stays inside the old primary's DC by default
 * @param priority   higher wins between replicas that are equally up to date
 * @param noFailover never promoted automatically (manual switchover is still allowed)
 * @param slot       physical replication slot this node streams through
 */
public record NodeConfig(String name, String agentUrl, String pgHost, int pgPort, String dc, int priority,
                         boolean noFailover, String slot) {

    public NodeConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("node name is required");
        }
        if (agentUrl == null || agentUrl.isBlank()) {
            throw new IllegalArgumentException("agentUrl is required for node " + name);
        }
        if (pgHost == null || pgHost.isBlank()) {
            throw new IllegalArgumentException("pgHost is required for node " + name);
        }
        if (pgPort <= 0) {
            pgPort = 5432;
        }
        if (dc == null || dc.isBlank()) {
            dc = "default";
        }
        if (slot == null || slot.isBlank()) {
            slot = defaultSlot(name);
        }
        agentUrl = agentUrl.endsWith("/") ? agentUrl.substring(0, agentUrl.length() - 1) : agentUrl;
    }

    public static NodeConfig of(String name, String agentUrl, String pgHost, int pgPort, String dc) {
        return new NodeConfig(name, agentUrl, pgHost, pgPort, dc, 100, false, null);
    }

    public static String defaultSlot(String nodeName) {
        String s = nodeName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return s.length() > 63 ? s.substring(0, 63) : s;
    }
}
