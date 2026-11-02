package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.agent.AgentClient;
import com.bisoft.bfm.core.config.ClusterSpec;
import com.bisoft.bfm.core.config.FailoverScope;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.event.EventLog;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.state.ClusterRegistry;
import com.bisoft.bfm.core.state.ScopedStateStore;
import com.bisoft.bfm.core.state.StateConflictException;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.service.ClusterHub.ClusterContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the console's wizards do: a cluster is drafted (basics, nodes), its nodes enroll, live checks run across them,
 * and it is created, adopting the running primary or building it; nodes are added to a running cluster; and a cluster
 * is detached. Drafts live in the shared state, so an installation spread over a day can be resumed, by any admin,
 * on any watcher.
 */
public class ClusterWizardService {

    private static final Logger LOG = Logger.getLogger(ClusterWizardService.class.getName());

    private final ClusterHub hub;
    private final EnrollmentService enrollment;
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bfm-cluster-build");
        t.setDaemon(true);
        return t;
    });

    public ClusterWizardService(ClusterHub hub, EnrollmentService enrollment) {
        this.hub = hub;
        this.enrollment = enrollment;
    }

    /** A cluster being created. */
    public record Draft(String id, String name, ClusterSpec spec, long createdMillis) {
    }

    // ---------------------------------------------------------------- drafts

    public List<Draft> drafts() {
        List<Draft> out = new ArrayList<>();
        for (ClusterRegistry.Entry e : hub.registry().all()) {
            if (e.console() && e.draft()) {
                StateStore.Versioned v = hub.registry().spec(e.id());
                if (v != null) {
                    out.add(new Draft(e.id(), e.name(), ClusterSpec.fromJson(v.value()), e.createdMillis()));
                }
            }
        }
        return out;
    }

    public Draft draft(String name) {
        ClusterRegistry.Entry e = hub.registry().find(name);
        if (e == null || !e.draft()) {
            throw new IllegalArgumentException("no draft named " + name);
        }
        return new Draft(e.id(), e.name(), ClusterSpec.fromJson(hub.registry().spec(e.id()).value()),
                e.createdMillis());
    }

    public Draft createDraft(String name, String environment, String user) {
        requireLeader();
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,62}")) {
            throw new IllegalArgumentException("a cluster name is letters, digits, '.', '_' and '-' (it is part of"
                    + " the console's URLs)");
        }
        if (hub.names().contains(name) || hub.registry().find(name) != null) {
            throw new IllegalArgumentException("there is a cluster or draft named " + name + " already");
        }
        ClusterRegistry.Entry e = hub.registry().createDraft(ClusterSpec.draft(name, blank(environment)),
                hub.time().millis());
        if (e == null) {
            throw new IllegalStateException("could not create the draft; try again");
        }
        events(e.id()).publish(EventType.OPERATION, null, "Cluster " + name + " drafted by " + user, user);
        return draft(name);
    }

    public Draft saveBasics(String name, String environment, FailoverScope scope, boolean selfFence,
                            long pollIntervalMs, int failureThreshold, long maxLagBytes, boolean allowColocation) {
        requireLeader();
        Draft d = draft(name);
        if (pollIntervalMs < 500 || pollIntervalMs > 60_000) {
            throw new IllegalArgumentException("the poll interval is 0.5 to 60 seconds");
        }
        if (failureThreshold < 1 || failureThreshold > 20) {
            throw new IllegalArgumentException("the failure threshold is 1 to 20 polls");
        }
        hub.registry().saveSpec(d.id(), d.spec().withBasics(blank(environment), scope, selfFence, pollIntervalMs,
                failureThreshold, maxLagBytes, allowColocation));
        return draft(name);
    }

    public Draft saveNodes(String name, List<ClusterSpec.NodeSpec> nodes) {
        requireLeader();
        Draft d = draft(name);
        validateNodes(d.id(), nodes);
        hub.registry().saveSpec(d.id(), d.spec().withNodes(nodes));
        return draft(name);
    }

    /** Names, agent addresses (unique across every cluster here) and PostgreSQL addresses. */
    static boolean validAgentUrl(String url) {
        if (url == null || !url.matches("https?://[^/\\s]+/?")) {
            return false;
        }
        try {
            java.net.URI u = new java.net.URI(url);
            return u.getHost() != null && (u.getPort() == -1 || u.getPort() >= 1 && u.getPort() <= 65535);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    void validateNodes(String clusterId, List<ClusterSpec.NodeSpec> nodes) {
        if (nodes.size() < 2) {
            throw new IllegalArgumentException("a cluster needs at least two nodes");
        }
        Set<String> names = new HashSet<>();
        Set<String> urls = new HashSet<>();
        Map<String, String> taken = new HashMap<>();
        for (ClusterContext c : hub.clusters()) {
            if (c.id().equals(clusterId)) {
                continue;
            }
            for (NodeConfig n : c.watcher().config().nodes()) {
                taken.put(normalize(n.agentUrl()), c.name() + "/" + n.name());
            }
        }
        for (ClusterSpec.NodeSpec n : nodes) {
            if (n.name() == null || !n.name().matches("[a-z0-9][a-z0-9_-]{0,40}")) {
                throw new IllegalArgumentException("node names are lowercase letters, digits, '-' and '_': "
                        + n.name());
            }
            if (!names.add(n.name())) {
                throw new IllegalArgumentException("two nodes are named " + n.name());
            }
            if (!validAgentUrl(n.agentUrl())) {
                throw new IllegalArgumentException(n.name() + ": the agent address is https://host:port (port 1 to"
                        + " 65535)");
            }
            String u = normalize(n.agentUrl());
            if (!urls.add(u)) {
                throw new IllegalArgumentException("two nodes have the agent address " + n.agentUrl());
            }
            if (taken.containsKey(u)) {
                throw new IllegalArgumentException(n.agentUrl() + " is already the agent of " + taken.get(u));
            }
            if (n.pgHost() == null || n.pgHost().isBlank()) {
                throw new IllegalArgumentException(n.name() + ": the PostgreSQL address is required (the node's own,"
                        + " never the VIP)");
            }
            if (n.pgPort() < 1 || n.pgPort() > 65535) {
                throw new IllegalArgumentException(n.name() + ": bad PostgreSQL port");
            }
        }
    }

    private static String normalize(String url) {
        return url == null ? "" : url.replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT);
    }

    public EnrollmentService.Issued token(String cluster, String node, String user) {
        requireLeader();
        return enrollment.issue(cluster, node, user);
    }

    public void discard(String name, String user) {
        requireLeader();
        Draft d = draft(name);
        hub.registry().remove(name, user, hub.time().millis());
        events(d.id()).publish(EventType.OPERATION, null, "Draft " + name + " discarded by " + user, user);
    }

    // ---------------------------------------------------------------- live state and checks

    /** What one node's agent says right now. */
    public record NodeLive(String node, boolean online, String error, NodeStatus status) {
    }

    public List<NodeLive> live(ClusterSpec spec) {
        List<NodeLive> out = new ArrayList<>();
        for (ClusterSpec.NodeSpec n : spec.nodes()) {
            try {
                NodeStatus s = client(n).status();
                out.add(new NodeLive(n.name(), true, null, s));
            } catch (AgentCallException | IllegalArgumentException e) {
                out.add(new NodeLive(n.name(), false, e instanceof AgentCallException a ? a.detail()
                        : e.getMessage(), null));
            }
        }
        return out;
    }

    private AgentClient client(ClusterSpec.NodeSpec n) {
        return hub.agents().create(new NodeConfig(n.name(), n.agentUrl(), n.pgHost(), n.pgPort(),
                n.dc() == null ? "default" : n.dc(), n.priority(), n.noFailover(), null));
    }

    /**
     * @param node the node, or null for a check across nodes
     */
    public record Check(String node, String name, String level, String detail) {

        public boolean blocks() {
            return "FAIL".equals(level);
        }
    }

    /** The checks of step 4: across the nodes, and each node's own (its agent's readiness checks). */
    public List<Check> checks(String clusterId, ClusterSpec spec) {
        List<NodeLive> live = live(spec);
        List<Check> out = new ArrayList<>();
        List<NodeLive> online = live.stream().filter(NodeLive::online).toList();
        for (NodeLive l : live) {
            if (!l.online()) {
                out.add(new Check(l.node(), "Agent answers", "FAIL", "not enrolled or not running: " + l.error()));
            } else if (l.status().identity().clusterId() != null
                    && !l.status().identity().clusterId().equals(clusterId)) {
                out.add(new Check(l.node(), "Agent belongs to this cluster", "FAIL", "it is bound to cluster "
                        + l.status().identity().cluster()));
            }
        }
        Set<Integer> majors = new HashSet<>();
        Map<String, List<String>> sysids = new LinkedHashMap<>();
        List<String> primaries = new ArrayList<>();
        Map<String, List<String>> hosts = new LinkedHashMap<>();
        long now = hub.time().millis();
        for (NodeLive l : online) {
            NodeStatus s = l.status();
            if (s.serverVersionNum() != null) {
                majors.add(s.serverVersionNum() / 10000);
            }
            if (s.systemIdentifier() != null) {
                sysids.computeIfAbsent(s.systemIdentifier(), k -> new ArrayList<>()).add(l.node());
            }
            if (s.isPrimary()) {
                primaries.add(l.node());
            }
            String host = s.identity().hostId() != null ? s.identity().hostId() : s.identity().hostname();
            if (host != null) {
                hosts.computeIfAbsent(host, k -> new ArrayList<>()).add(l.node());
            }
            long skew = Math.abs(s.agentTimeMillis() - now);
            if (skew > 2000) {
                out.add(new Check(l.node(), "Clock", "WARN", "its clock is " + skew / 1000.0 + " s off the"
                        + " watcher's; leases and the event log need chrony on every host"));
            }
        }
        out.add(majors.size() <= 1 ? new Check(null, "Same PostgreSQL major version", "OK",
                majors.isEmpty() ? "unknown yet" : "PostgreSQL " + majors.iterator().next())
                : new Check(null, "Same PostgreSQL major version", "FAIL", "versions " + majors
                + ": streaming replication needs one major version"));
        out.add(primaries.size() <= 1 ? new Check(null, "At most one primary", "OK", primaries.isEmpty()
                ? "none runs as primary: build the cluster in step 5" : primaries.get(0) + " runs as primary")
                : new Check(null, "At most one primary", "FAIL", String.join(", ", primaries) + " all run as"
                + " primary: stop PostgreSQL on every node but the one that holds the data (Build copies it to the"
                + " others)"));
        if (sysids.size() <= 1) {
            out.add(new Check(null, "One database", "OK", sysids.isEmpty() ? "no system identifier yet"
                    : "system identifier " + sysids.keySet().iterator().next()));
        } else {
            String primarySys = primaries.size() == 1 ? online.stream().filter(l -> l.node().equals(primaries.get(0)))
                    .map(l -> l.status().systemIdentifier()).findFirst().orElse(null) : null;
            List<String> other = new ArrayList<>();
            sysids.forEach((id, nodes) -> {
                if (!Objects.equals(id, primarySys)) {
                    other.addAll(nodes);
                }
            });
            out.add(new Check(null, "One database", primarySys == null ? "FAIL" : "WARN", primarySys == null
                    ? "the nodes hold " + sysids.size() + " different databases and none runs as primary"
                    : String.join(", ", other) + (other.size() == 1 ? " holds" : " hold")
                    + " another database than " + primaries.get(0) + ": re-initialize "
                    + (other.size() == 1 ? "it" : "them") + " from " + primaries.get(0) + " after creation"));
        }
        boolean same = false;
        for (Map.Entry<String, List<String>> h : hosts.entrySet()) {
            if (h.getValue().size() > 1) {
                same = true;
                out.add(new Check(null, "One node per host", spec.allowColocation() ? "WARN" : "FAIL",
                        and(h.getValue()) + " run on one host: losing it takes " + (h.getValue().size() == 2
                                ? "both" : "all " + h.getValue().size())
                                + (spec.allowColocation() ? " (allowed: lab only)" : "; tick 'allow co-location"
                                + " (lab only)' in step 1 to go on anyway")));
            }
        }
        if (!same && !hosts.isEmpty()) {
            out.add(new Check(null, "One node per host", "OK", hosts.size() + " hosts"));
        }
        // Each node's own checks: settings, pg_hba.conf for every node (itself too: the file is copied with the data
        // when another node is built from it), the replication role, room.
        for (NodeLive l : online) {
            ClusterSpec.NodeSpec me = spec.nodes().stream().filter(n -> n.name().equals(l.node())).findFirst()
                    .orElseThrow();
            List<Map<String, String>> peers = new ArrayList<>();
            for (ClusterSpec.NodeSpec n : spec.nodes()) {
                peers.add(Map.of("name", n.name(), "address", n.pgHost()));
            }
            if (l.status() != null && !l.status().pgRunning()) {
                out.add(new Check(l.node(), "PostgreSQL running", "WARN", "PostgreSQL is stopped, so its settings"
                        + " cannot be checked now; Build re-initializes it from the primary, or start it and check"
                        + " again"));
                continue;
            }
            try {
                JsonMap r = client(me).checks(peers, null);
                for (JsonMap c : r.objList("checks", m -> m)) {
                    String level = c.str("level", "WARN");
                    if (c.str("name", "").equals("Database")) {
                        continue; // judged across nodes above
                    }
                    // Settings that only make rejoin slower, or need a restart, never block creation.
                    if ("FAIL".equals(level) && !c.str("name", "").equals("pg_hba.conf")
                            && !c.str("name", "").equals("Replication role") && !c.str("name", "").equals("Version")
                            && !c.str("name", "").equals("wal_level")) {
                        level = "WARN";
                    }
                    out.add(new Check(l.node(), c.str("name"), level, c.str("detail") + (c.str("fix") == null
                            || "OK".equals(level) ? "" : " — fix: " + c.str("fix"))));
                }
            } catch (AgentCallException e) {
                out.add(new Check(l.node(), "Readiness checks", "WARN", "the agent cannot run them (" + e.detail()
                        + "); update it, or check with 'minipgagent setup --dry-run'"));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- create

    public enum StartMode { ADOPT, BUILD }

    /**
     * Creates the cluster: the draft becomes active and every watcher starts its engine. Adopt keeps the running
     * primary; Build starts {@code buildPrimary} as primary and re-initializes the others from it, in the
     * background (the steps show under Operations).
     */
    public ClusterContext create(String name, StartMode mode, String buildPrimary, String confirm, String user) {
        requireLeader();
        Draft d = draft(name);
        if (!name.equals(confirm == null ? null : confirm.trim())) {
            throw new IllegalArgumentException("type the cluster name " + name + " to create it");
        }
        List<Check> checks = checks(d.id(), d.spec());
        List<Check> blocking = checks.stream().filter(Check::blocks).toList();
        if (!blocking.isEmpty()) {
            throw new IllegalStateException("not created: " + blocking.get(0).name() + (blocking.get(0).node() == null
                    ? "" : " on " + blocking.get(0).node()) + ": " + blocking.get(0).detail());
        }
        if (mode == StartMode.BUILD && d.spec().nodes().stream().noneMatch(n -> n.name().equals(buildPrimary))) {
            throw new IllegalArgumentException("choose the node to start as primary");
        }
        hub.registry().activate(name);
        hub.refresh();
        ClusterContext c = hub.require(name);
        c.watcher().eventLog().publish(EventType.OPERATION, null, "Cluster " + name + " created in the console by "
                + user + " (" + d.spec().nodes().size() + " nodes, " + (mode == StartMode.BUILD ? "built from "
                + buildPrimary : "adopting the running primary") + ")", user);
        if (mode == StartMode.BUILD) {
            builder.execute(() -> build(c, buildPrimary, user));
        }
        return c;
    }

    /** Start as primary, then re-initialize the others one by one. */
    private void build(ClusterContext c, String primary, String user) {
        try {
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (c.watcher().state() == null && System.nanoTime() < deadline) {
                Thread.sleep(500);
            }
            await(c, c.watcher().startPrimary(primary, user));
            for (NodeConfig n : c.watcher().config().nodes()) {
                if (!n.name().equals(primary)) {
                    await(c, c.watcher().reinit(n.name(), user));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "building cluster " + c.name() + " stopped", e);
            c.watcher().eventLog().publish(EventType.OPERATION, primary, "Building cluster " + c.name() + " stopped: "
                    + e.getMessage(), user);
        }
    }

    private static void await(ClusterContext c, OperationProgress.Snapshot started) throws InterruptedException {
        while (c.watcher().operation(started.id()).map(OperationProgress.Snapshot::running).orElse(false)) {
            Thread.sleep(500);
        }
        OperationProgress.Snapshot done = c.watcher().operation(started.id()).orElseThrow();
        if (done.state() != OperationProgress.State.SUCCEEDED) {
            throw new IllegalStateException(done.type() + " " + done.target() + ": " + done.result());
        }
    }

    // ---------------------------------------------------------------- a running cluster

    /** Adds a node to a cluster created in the console; its engine is rebuilt with it once no operation runs. */
    public void addNode(String cluster, ClusterSpec.NodeSpec node, String user) {
        requireLeader();
        ClusterContext c = consoleCluster(cluster);
        ClusterSpec spec = ClusterSpec.fromJson(hub.registry().spec(c.id()).value());
        List<ClusterSpec.NodeSpec> nodes = new ArrayList<>(spec.nodes());
        nodes.add(node);
        validateNodes(c.id(), nodes);
        hub.registry().saveSpec(c.id(), spec.withNodes(nodes));
        c.watcher().eventLog().publish(EventType.CONTROL_CHANGED, node.name(), "Node " + node.name() + " ("
                + node.agentUrl() + ") added to " + cluster + " by " + user, user);
        hub.refresh();
    }

    /** Takes a replica out of a cluster created in the console; its agent forgets the cluster. */
    public void removeNode(String cluster, String node, String user) {
        requireLeader();
        ClusterContext c = consoleCluster(cluster);
        var st = c.watcher().state();
        if (st != null && node.equals(st.primary())) {
            throw new StateConflictException(node + " is the primary: switch over first");
        }
        ClusterSpec spec = ClusterSpec.fromJson(hub.registry().spec(c.id()).value());
        List<ClusterSpec.NodeSpec> nodes = spec.nodes().stream().filter(n -> !n.name().equals(node)).toList();
        if (nodes.size() == spec.nodes().size()) {
            throw new IllegalArgumentException("no node " + node + " in " + cluster);
        }
        if (nodes.size() < 2) {
            throw new IllegalArgumentException("a cluster keeps at least two nodes; detach it instead");
        }
        ClusterSpec.NodeSpec gone = spec.nodes().stream().filter(n -> n.name().equals(node)).findFirst()
                .orElseThrow();
        String unbind;
        try {
            AgentClient a = client(gone);
            a.useCluster(c.id(), c.name());
            ApiResult r = a.unbind(st == null ? 0 : st.epoch());
            unbind = r.message();
        } catch (AgentCallException e) {
            unbind = "its agent was not unbound (" + e.detail() + ")";
        }
        hub.registry().saveSpec(c.id(), spec.withNodes(nodes));
        c.watcher().eventLog().publish(EventType.CONTROL_CHANGED, node, "Node " + node + " removed from " + cluster
                + " by " + user + "; " + unbind + ". PostgreSQL on it keeps running.", user);
        hub.refresh();
    }

    /**
     * Detaches a cluster created in the console: failover paused, the primary's last lease never fences, every
     * agent unbound, the engine stopped and the cluster taken out of the registry. PostgreSQL keeps running on
     * every node; the events stay for the record.
     */
    public List<String> detach(String cluster, String confirm, String user) {
        requireLeader();
        ClusterContext c = consoleCluster(cluster);
        if (!cluster.equals(confirm == null ? null : confirm.trim())) {
            throw new IllegalArgumentException("type the cluster name " + cluster + " to detach it");
        }
        List<String> out = c.watcher().detach(user);
        hub.registry().remove(cluster, user, hub.time().millis());
        hub.refresh();
        return out;
    }

    private ClusterContext consoleCluster(String name) {
        ClusterContext c = hub.require(name);
        if (!c.fromConsole()) {
            throw new IllegalStateException(name + " is defined in watcher.yml: change it there and restart the"
                    + " watchers");
        }
        return c;
    }

    private EventLog events(String id) {
        return new EventLog(new ScopedStateStore(hub.store(), id), hub.time());
    }

    /**
     * Why this watcher cannot change clusters: it follows another one, which runs the wizards. Null when it leads.
     *
     * @param consoleUrl the leader's console ({@code raft.peers[].url}), null when not configured or no leader
     */
    public record Elsewhere(String self, String leaderId, String consoleUrl) {
    }

    public Elsewhere elsewhere() {
        StateStore.Leadership l = hub.store().leadership();
        if (l.leader()) {
            return null;
        }
        String url = null;
        for (com.bisoft.bfm.watcher.config.BfmProperties.Peer p : hub.peers()) {
            if (p.id().equals(l.leaderId())) {
                url = p.url();
            }
        }
        return new Elsewhere(hub.watcherId(), l.leaderId(), url);
    }

    private void requireLeader() {
        StateStore.Leadership l = hub.store().leadership();
        if (!l.leader()) {
            throw new com.bisoft.bfm.core.NotLeaderException(l.leaderId());
        }
    }

    /** "a", "a and b", "a, b and c". */
    static String and(List<String> items) {
        if (items.size() < 2) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** "host:port" of an agent URL. */
    static String hostPort(String url) {
        URI u = URI.create(url);
        return u.getHost() + ":" + (u.getPort() == -1 ? 443 : u.getPort());
    }
}
