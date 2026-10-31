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
 * @param cluster   the PostgreSQL cluster
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
        @DefaultValue Agent agent,
        @DefaultValue Raft raft,
        List<User> users) {

    public BfmProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public ZoneId zone() {
        return timeZone == null || timeZone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timeZone);
    }

    /**
     * @param environment shown in the console header and on the login page so nobody mistakes one cluster's
     *                    console for another's: production, staging, test, development or any other word
     */
    public record Cluster(
            String name,
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
     * @param enabled run with three (or five) watchers in an Apache Ratis group
     * @param peers   every watcher, this one included
     * @param tls     use the agent TLS stores between watchers too
     */
    public record Raft(@DefaultValue("false") boolean enabled, List<Peer> peers, @DefaultValue("true") boolean tls) {
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

    public ClusterConfig toClusterConfig() {
        if (cluster == null || cluster.nodes() == null || cluster.nodes().isEmpty()) {
            throw new IllegalStateException("bfm.cluster.nodes is not configured");
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
