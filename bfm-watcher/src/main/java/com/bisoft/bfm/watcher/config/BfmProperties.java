package com.bisoft.bfm.watcher.config;

import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.config.FailoverScope;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.config.Timing;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Watcher settings under {@code bfm.*} in {@code application.yml}; see {@code packaging/watcher.yml.example}.
 *
 * @param watcherId this watcher's id; with Raft it must be one of the peers
 * @param dataDir   state, events, Raft log and {@code secret.key}
 * @param timeZone  zone used for times in the UI and in mails
 * @param cluster   the PostgreSQL cluster, when the watcher runs one
 * @param clusters  the PostgreSQL clusters, when it runs several (one console for all of them); {@code cluster}, if
 *                  also given, comes first
 * @param agent     how to reach the agents
 * @param raft      optional three-watcher consensus
 * @param users     console and API users
 */
@ConfigurationProperties("bfm")
public record BfmProperties(
        @DefaultValue("w1") String watcherId,
        @DefaultValue("/var/lib/bfm-watcher") Path dataDir,
        String timeZone,
        Cluster cluster,
        List<Cluster> clusters,
        @DefaultValue Agent agent,
        @DefaultValue Raft raft,
        List<User> users) {

    public BfmProperties {
        users = users == null ? List.of() : List.copyOf(users);
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
    }

    /** Every configured cluster: {@code bfm.cluster} first, then {@code bfm.clusters}. Names are checked. */
    public List<Cluster> allClusters() {
        List<Cluster> out = new ArrayList<>();
        if (cluster != null && cluster.nodes() != null && !cluster.nodes().isEmpty()) {
            out.add(cluster);
        }
        out.addAll(clusters);
        if (out.isEmpty()) {
            throw new IllegalStateException("no cluster configured: set bfm.cluster or bfm.clusters");
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Map<String, String> urls = new java.util.HashMap<>();
        for (Cluster c : out) {
            if (c.name() == null || !c.name().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")) {
                throw new IllegalStateException("cluster name '" + c.name() + "': use letters, digits, '.', '_' and"
                        + " '-' (it is part of the console's URLs)");
            }
            if (!names.add(c.name())) {
                throw new IllegalStateException("two clusters are named " + c.name());
            }
            for (Node n : c.nodes() == null ? List.<Node>of() : c.nodes()) {
                String url = n.agentUrl() == null ? null : n.agentUrl().replaceAll("/+$", "").toLowerCase();
                String other = url == null ? null : urls.put(url, c.name() + "/" + n.name());
                if (other != null) {
                    throw new IllegalStateException("agent-url " + n.agentUrl() + " is given for " + other + " and for "
                            + c.name() + "/" + n.name() + ": one agent serves one node of one cluster");
                }
            }
        }
        return out;
    }

    public ZoneId zone() {
        return timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone);
    }

    /**
     * @param environment shown in the console header and on the login page so nobody mistakes one cluster's
     *                    console for another's: production, staging, test, development or any other word
     * @param id          optional cluster id, the one its agents are bound to; set it when moving a cluster from
     *                    another watcher (its Overview page shows the id), otherwise one is generated
     */
    public record Cluster(
            String name,
            String id,
            String environment,
            @DefaultValue("DC_LOCAL") FailoverScope scope,
            @DefaultValue("false") boolean selfFence,
            @DefaultValue("true") boolean autoRejoin,
            @DefaultValue("true") boolean restartPrimaryFirst,
            @DefaultValue("true") boolean fenceRoguePrimary,
            @DefaultValue("true") boolean confirmWithReplicas,
            @DefaultValue TimingProps timing,
            List<Node> nodes) {
    }

    public record Node(
            String name,
            String agentUrl,
            String pgHost,
            @DefaultValue("5432") int pgPort,
            @DefaultValue("default") String dc,
            @DefaultValue("100") int priority,
            @DefaultValue("false") boolean noFailover,
            String slot) {
    }

    public record TimingProps(
            @DefaultValue("2s") Duration pollInterval,
            @DefaultValue("3") int failureThreshold,
            @DefaultValue("3s") Duration agentTimeout,
            @DefaultValue("30s") Duration leaseTtl,
            @DefaultValue("5s") Duration leaseMargin,
            @DefaultValue("10s") Duration observationFresh,
            @DefaultValue("16MB") DataSize maxLag,
            @DefaultValue("60s") Duration switchoverCatchup,
            @DefaultValue("6h") Duration operationTimeout,
            @DefaultValue("60s") Duration failoverRetry,
            @DefaultValue("60s") Duration repairRetry,
            @DefaultValue("30s") Duration replicaGrace) {

        Timing toTiming() {
            return new Timing(pollInterval.toMillis(), failureThreshold, agentTimeout.toMillis(), leaseTtl.toMillis(),
                    leaseMargin.toMillis(), observationFresh.toMillis(), maxLag.toBytes(),
                    switchoverCatchup.toMillis(), operationTimeout.toMillis(), failoverRetry.toMillis(),
                    repairRetry.toMillis(), replicaGrace.toMillis());
        }
    }

    /**
     * @param tls            mutual TLS towards the agents (recommended)
     * @param tokenFile      optional bearer token file (required when TLS is off)
     * @param commandTimeout how long a command (promote, stop, start, CHECKPOINT, VIP) may take on the agent; keep it
     *                       above the agent's pg.start.timeout.seconds + 15 s and pg.promote.timeout.seconds
     */
    public record Agent(@DefaultValue Tls tls, Path tokenFile, @DefaultValue("180s") Duration commandTimeout) {
    }

    /**
     * PKCS#12 stores shared by agent calls and, with Raft, by the watchers among themselves.
     *
     * @param verifyNodeName require each agent's certificate to name its node (common name or DNS name), so a
     *                       mistyped agent port reaches no other cluster's agent; turn off only for certificates
     *                       issued without node names
     */
    public record Tls(
            @DefaultValue("true") boolean enabled,
            Path keystore,
            Path keystorePasswordFile,
            Path truststore,
            Path truststorePasswordFile,
            @DefaultValue("true") boolean verifyNodeName) {
    }

    /**
     * @param enabled   run with three (or five) watchers in an Apache Ratis group
     * @param peers     every watcher, this one included
     * @param tls       use the agent TLS stores between watchers too
     * @param groupName the Raft group's name; defaults to the first cluster's name
     */
    public record Raft(@DefaultValue("false") boolean enabled, List<Peer> peers, @DefaultValue("true") boolean tls,
                       String groupName) {
        public Raft {
            peers = peers == null ? List.of() : List.copyOf(peers);
        }
    }

    /**
     * @param url the watcher's console address, shown on followers as a link to the leader
     */
    public record Peer(String id, String host, @DefaultValue("9870") int port, String url) {
    }

    /**
     * @param passwordHash a Spring Security encoded password, for example {@code {bcrypt}$2a$10$...}
     * @param role         ADMIN (operates) or VIEWER (reads)
     */
    public record User(String name, String passwordHash, @DefaultValue("VIEWER") Role role) {
    }

    public enum Role { ADMIN, VIEWER }

    /** The first configured cluster (the only one in a single-cluster setup). */
    public ClusterConfig toClusterConfig() {
        return toClusterConfig(allClusters().get(0));
    }

    /**
     * The Raft group's name: {@code raft.group-name}, or the first cluster's name (what one-cluster watchers always
     * used, so their Raft log stays theirs). Set it when the first cluster may change.
     */
    public String raftGroupName() {
        return raft.groupName() != null && !raft.groupName().isBlank() ? raft.groupName()
                : allClusters().get(0).name();
    }

    public static ClusterConfig toClusterConfig(Cluster cluster) {
        if (cluster == null || cluster.nodes() == null || cluster.nodes().isEmpty()) {
            throw new IllegalStateException("nodes of cluster " + (cluster == null ? "?" : cluster.name())
                    + " are not configured");
        }
        List<NodeConfig> nodes = new ArrayList<>();
        for (Node n : cluster.nodes()) {
            nodes.add(new NodeConfig(n.name(), n.agentUrl(), n.pgHost(), n.pgPort(), n.dc(), n.priority(),
                    n.noFailover(), n.slot()));
        }
        TimingProps t = cluster.timing();
        return new ClusterConfig(cluster.name(), nodes, t.toTiming(), cluster.scope(), cluster.selfFence(),
                cluster.autoRejoin(), cluster.restartPrimaryFirst(), cluster.fenceRoguePrimary(),
                cluster.confirmWithReplicas());
    }
}
