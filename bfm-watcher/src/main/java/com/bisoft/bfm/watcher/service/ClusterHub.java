package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.core.agent.AgentClientFactory;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.notify.NotificationDispatcher;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.core.state.ClusterRegistry;
import com.bisoft.bfm.core.state.ScopedStateStore;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.mail.MailNotifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every cluster this watcher runs: one engine ({@link Watcher}) per cluster, each with its own poll loop, operations
 * and part of the store, and the console and mail services built on it. One store, one leader for all of them.
 *
 * <p>A cluster gets its engine once it is in the registry. The leading watcher registers the configured clusters
 * (moving a one-cluster store's state into its cluster first); a follower waits until the leader has, so every
 * watcher of a group uses the same cluster ids.
 */
public final class ClusterHub implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ClusterHub.class.getName());

    /** One cluster as the console and the API see it. */
    public record ClusterContext(String name, String id, String environment, Watcher watcher,
                                 ConsoleService console, NotificationService notifications) {
    }

    /** The cluster named in a request does not exist here. */
    public static final class UnknownClusterException extends RuntimeException {
        public UnknownClusterException(String message) {
            super(message);
        }
    }

    /** A request that names no cluster on a watcher that runs several. */
    public static final class ClusterRequiredException extends RuntimeException {
        private final List<String> clusters;

        public ClusterRequiredException(List<String> clusters) {
            super("this watcher runs " + clusters.size() + " clusters (" + String.join(", ", clusters)
                    + "): name one, for example /api/v1/clusters/" + clusters.get(0) + "/...");
            this.clusters = List.copyOf(clusters);
        }

        public List<String> clusters() {
            return clusters;
        }
    }

    private final BfmProperties props;
    private final StateStore root;
    private final AgentClientFactory agents;
    private final TimeSource time;
    private final MailNotifier mail;
    private final SecretBox secrets;
    private final ClusterRegistry registry;
    private final List<BfmProperties.Cluster> configured;
    private final Map<String, ClusterContext> contexts = new ConcurrentHashMap<>();
    private final ExecutorService mailExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "bfm-mail"));
    private ScheduledExecutorService resolver;
    private boolean poll;

    public ClusterHub(BfmProperties props, StateStore root, AgentClientFactory agents, TimeSource time,
                      MailNotifier mail, SecretBox secrets) {
        this.props = props;
        this.root = root;
        this.agents = agents;
        this.time = time;
        this.mail = mail;
        this.secrets = secrets;
        this.registry = new ClusterRegistry(root);
        this.configured = props.allClusters();
        resolve();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Starts every engine's poll loop, now and as their clusters get registered. */
    public synchronized void start() {
        poll = true;
        for (ClusterContext c : contexts.values()) {
            c.watcher().start();
        }
        if (resolver == null && contexts.size() < configured.size()) {
            resolver = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "bfm-registry"));
            resolver.scheduleWithFixedDelay(this::resolveSafely, 1, 1, TimeUnit.SECONDS);
        }
    }

    private void resolveSafely() {
        try {
            resolve();
        } catch (IllegalStateException e) {
            LOG.severe(e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "cluster registry not readable yet", e);
        }
    }

    /** Creates the engines of the clusters that are registered (registering them first on the leader). */
    synchronized void resolve() {
        boolean leader;
        try {
            leader = root.leadership().leader();
        } catch (RuntimeException e) {
            return;
        }
        String owner = leader && registry.legacyStatePending() ? legacyOwner() : null;
        for (BfmProperties.Cluster c : configured) {
            if (contexts.containsKey(c.name())) {
                continue;
            }
            ClusterRegistry.Entry e;
            try {
                e = registry.find(c.name());
                if (e == null && leader) {
                    e = registry.create(c.name(), blankToNull(c.id()), "file", c.name().equals(owner),
                            time.millis());
                }
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "cannot register cluster " + c.name() + ": " + ex.getMessage());
                continue;
            }
            if (e == null) {
                continue; // a follower waits for the leader to register it
            }
            if (c.id() != null && !c.id().isBlank() && !c.id().trim().equals(e.id())) {
                // The configuration and the store disagree about which cluster this is: steer nothing.
                throw new IllegalStateException("cluster " + c.name() + " is configured with id " + c.id().trim()
                        + " but this watcher's state registered it as " + e.id() + "; correct bfm.clusters[].id");
            }
            ClusterContext ctx = build(c, e);
            contexts.put(c.name(), ctx);
            LOG.info("cluster " + c.name() + " (" + e.id() + "): " + c.nodes().size() + " nodes");
            if (poll) {
                ctx.watcher().start();
            }
        }
        if (contexts.size() == configured.size() && resolver != null) {
            resolver.shutdown();
            resolver = null;
        }
    }

    /**
     * Which configured cluster owns a one-cluster store's state: the only one, or the one whose nodes include the
     * primary that state records.
     */
    private String legacyOwner() {
        if (configured.size() == 1) {
            return configured.get(0).name();
        }
        String primary = registry.legacyPrimary();
        for (BfmProperties.Cluster c : configured) {
            if (primary != null && c.nodes().stream().anyMatch(n -> primary.equals(n.name()))) {
                return c.name();
            }
        }
        return configured.get(0).name();
    }

    private ClusterContext build(BfmProperties.Cluster c, ClusterRegistry.Entry e) {
        Watcher w = new Watcher(BfmProperties.toClusterConfig(c), new ScopedStateStore(root, e.id()), agents, time,
                root);
        w.eventLog().addListener(new NotificationDispatcher(c.name(), w.store(), w.notificationSettings(), mail,
                mailExecutor, time, props.zone()));
        ConsoleService console = new ConsoleService(w, c.environment(), props.raft().peers());
        NotificationService notifications = new NotificationService(w, secrets, mail, props.zone());
        return new ClusterContext(c.name(), e.id(), c.environment(), w, console, notifications);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---------------------------------------------------------------- lookups

    /** Every configured cluster's name, in the order of the configuration. */
    public List<String> names() {
        return configured.stream().map(BfmProperties.Cluster::name).toList();
    }

    /** True when this watcher runs more than one cluster: the console shows the fleet and the switcher. */
    public boolean multi() {
        return configured.size() > 1;
    }

    /** The clusters whose engines run, in the order of the configuration. */
    public List<ClusterContext> clusters() {
        List<ClusterContext> out = new ArrayList<>();
        for (BfmProperties.Cluster c : configured) {
            ClusterContext ctx = contexts.get(c.name());
            if (ctx != null) {
                out.add(ctx);
            }
        }
        return out;
    }

    public Optional<ClusterContext> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(contexts.get(name));
    }

    /** The named cluster; with no name, the only one. */
    public ClusterContext require(String name) {
        if (name == null || name.isBlank()) {
            return single();
        }
        ClusterContext c = contexts.get(name);
        if (c != null) {
            return c;
        }
        if (names().contains(name)) {
            throw new UnknownClusterException("cluster " + name + " is starting: its registration is waiting for"
                    + " the leading watcher");
        }
        throw new UnknownClusterException("no cluster named " + name + " (clusters: " + String.join(", ", names())
                + ")");
    }

    /** The cluster of a one-cluster watcher; requests that name no cluster use it. */
    public ClusterContext single() {
        if (multi()) {
            throw new ClusterRequiredException(names());
        }
        return require(configured.get(0).name());
    }

    /** The environment configured for a cluster (shown before its engine runs too). */
    public String environment(String name) {
        return configured.stream().filter(c -> c.name().equals(name)).map(BfmProperties.Cluster::environment)
                .findFirst().orElse(null);
    }

    /** Every node of every configured cluster, for checks that span clusters. */
    public List<NodeConfig> allNodes() {
        List<NodeConfig> out = new ArrayList<>();
        for (BfmProperties.Cluster c : configured) {
            out.addAll(BfmProperties.toClusterConfig(c).nodes());
        }
        return out;
    }

    public StateStore store() {
        return root;
    }

    @Override
    public synchronized void close() {
        if (resolver != null) {
            resolver.shutdownNow();
        }
        for (ClusterContext c : contexts.values()) {
            c.watcher().close();
        }
        mailExecutor.shutdownNow();
    }
}
