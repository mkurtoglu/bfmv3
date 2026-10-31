package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.monitor.NodeView;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.RoleChange;
import com.bisoft.bfm.watcher.service.ClusterHub.ClusterContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The read models that span clusters: the fleet (one line per cluster) and the hosts (instances per server). */
public class FleetService {

    private final ClusterHub hub;

    public FleetService(ClusterHub hub) {
        this.hub = hub;
    }

    /**
     * One cluster in one line.
     *
     * @param primaryHost the host name the primary's agent reports, null when unknown
     * @param roleChange  the last failover or switchover, null when none
     */
    public record ClusterSummary(String name, String id, String environment, String health,
                                 List<String> healthReasons, String primary, String primaryHost, int streaming,
                                 int replicas, Long maxLagBytes, RoleChange roleChange, boolean failoverActive) {

        /** Worst first: down, then unverified or no primary, degraded, starting, healthy. */
        public int severity() {
            return switch (health) {
                case "Primary down", "Primary unverified" -> 0;
                case "No primary" -> 1;
                case "Degraded" -> 2;
                case "Starting" -> 3;
                default -> 4;
            };
        }
    }

    /** Every configured cluster, worst first, then by name; a cluster whose engine is not running yet is "Starting". */
    public List<ClusterSummary> clusters() {
        List<ClusterSummary> out = new ArrayList<>();
        for (String name : hub.names()) {
            ClusterContext c = hub.find(name).orElse(null);
            if (c == null) {
                out.add(new ClusterSummary(name, null, hub.environment(name), "Starting",
                        List.of("waiting for the leading watcher to register it"), null, null, 0, 0, null, null,
                        false));
                continue;
            }
            out.add(summary(c));
        }
        out.sort(Comparator.comparingInt(ClusterSummary::severity).thenComparing(ClusterSummary::name));
        return out;
    }

    public ClusterSummary summary(ClusterContext c) {
        ConsoleService.Overview o = c.console().overview();
        int replicas = 0;
        int streaming = 0;
        for (ConsoleService.NodeRow r : o.nodes()) {
            if (!r.isPrimary()) {
                replicas++;
                if (r.streaming()) {
                    streaming++;
                }
            }
        }
        String host = null;
        if (o.primary() != null) {
            NodeStatus s = c.watcher().view().node(o.primary()).map(NodeView::status).orElse(null);
            host = s == null ? null : s.identity().hostname();
        }
        return new ClusterSummary(c.name(), c.id(), c.environment(), o.health(), o.healthReasons(), o.primary(),
                host, streaming, replicas, o.maxLagBytes(), o.roleChange(), o.failoverActive());
    }

    // ---------------------------------------------------------------- hosts

    /** One agent instance (one PostgreSQL instance) on a host. */
    public record Instance(String cluster, String node, String role, String state, String instance, int pgPort,
                           String agent, String vip, boolean vipHeld) {
    }

    /**
     * One server and what runs on it. Known by the {@code /etc/machine-id} its agents report; an agent that does not
     * answer is placed by its PostgreSQL address.
     *
     * @param sameCluster clusters with more than one node on this host: losing the host takes more than one of
     *                    their nodes (no protection against losing it)
     */
    public record Host(String key, String name, String hostId, int primaries, int replicas, List<Instance> instances,
                       List<String> sameCluster) {
    }

    public List<Host> hosts() {
        Map<String, List<Instance>> byHost = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> ids = new LinkedHashMap<>();
        for (ClusterContext c : hub.clusters()) {
            ClusterState st = c.watcher().state();
            String primary = st == null ? null : st.primary();
            for (NodeConfig n : c.watcher().config().nodes()) {
                NodeView v = c.watcher().view().node(n.name()).orElse(null);
                NodeStatus s = v == null ? null : v.status();
                String hostId = s == null ? null : s.identity().hostId();
                String hostname = s == null ? null : s.identity().hostname();
                String key = hostId != null ? "id:" + hostId : hostname != null ? "name:" + hostname
                        : "addr:" + n.pgHost();
                names.putIfAbsent(key, hostname != null ? hostname : n.pgHost());
                if (hostId != null) {
                    ids.putIfAbsent(key, hostId);
                }
                String role = s == null ? "Unknown" : s.isPrimary() ? (n.name().equals(primary) ? "Primary"
                        : "Primary (not recorded)") : s.isReplica() ? "Replica" : s.pgRunning() ? "Unknown"
                        : "Stopped";
                byHost.computeIfAbsent(key, k -> new ArrayList<>()).add(new Instance(c.name(), n.name(), role,
                        v == null ? "Not polled yet" : v.stateLabel(), s == null ? null : s.identity().instance(),
                        n.pgPort(), agentAddress(n.agentUrl()), s == null ? null : s.vipAddress(),
                        s != null && s.vipHeld()));
            }
        }
        List<Host> out = new ArrayList<>();
        byHost.forEach((key, list) -> {
            list.sort(Comparator.comparing(Instance::cluster).thenComparing(Instance::node));
            int primaries = (int) list.stream().filter(i -> i.role().startsWith("Primary")).count();
            int replicas = (int) list.stream().filter(i -> i.role().equals("Replica")).count();
            Map<String, Integer> perCluster = new LinkedHashMap<>();
            for (Instance i : list) {
                perCluster.merge(i.cluster(), 1, Integer::sum);
            }
            List<String> same = perCluster.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey)
                    .toList();
            out.add(new Host(key, names.get(key), ids.get(key), primaries, replicas, List.copyOf(list), same));
        });
        out.sort(Comparator.comparing(h -> Objects.requireNonNullElse(h.name(), h.key())));
        return out;
    }

    /** "host:port" of an agent URL, as typed in the configuration. */
    static String agentAddress(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() == null ? url : u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
        } catch (IllegalArgumentException e) {
            return url;
        }
    }
}
