package com.bisoft.bfm.core.ops;

import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.OperationStatus;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.agent.AgentClient;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.monitor.ClusterView;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.RoleChange;
import com.bisoft.bfm.core.state.StateConflictException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Shared plumbing for watcher operations. Subclasses implement {@link #execute()}. */
public abstract class Operation implements Runnable {

    private static final Logger LOG = Logger.getLogger(Operation.class.getName());

    /** How long to wait for a command whose outcome is unknown (transport failure, timeout) to show its effect. */
    static final long SETTLE_MS = 150_000;

    protected final OpContext ctx;
    protected final OperationProgress progress;
    protected final String actor;
    /** Set once a node has been made primary by this operation; the cluster is never released without it. */
    protected volatile String madePrimary;

    protected Operation(OpContext ctx, OperationProgress progress, String actor) {
        this.ctx = ctx;
        this.progress = progress;
        this.actor = actor;
    }

    public OperationProgress progress() {
        return progress;
    }

    @Override
    public final void run() {
        try {
            execute();
            if (!progress.finished()) {
                progress.succeed("done");
            }
        } catch (Abort a) {
            progress.fail(a.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, progress.type() + " failed unexpectedly", e);
            progress.log("watcher", "unexpected error: " + e);
            onUnexpected(e);
            progress.fail("unexpected error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            progress.fail("interrupted");
            onUnexpected(e);
        }
    }

    protected abstract void execute() throws InterruptedException;

    /** Called when the operation dies on an unexpected error, to release whatever it holds. */
    protected void onUnexpected(Exception e) {
    }

    /** Ends the operation as failed with {@code message}. */
    protected static final class Abort extends RuntimeException {
        public Abort(String message) {
            super(message);
        }
    }

    // ---- logging ----

    protected void log(String source, String text) {
        progress.log(source, text);
    }

    protected void logResult(String node, String what, ApiResult r) {
        log("agent " + node, what + (r.message() == null ? " ok" : ": " + r.message()));
    }

    // ---- agents ----

    protected AgentClient agent(String node) {
        return ctx.agent(node);
    }

    protected NodeConfig node(String name) {
        return ctx.cfg().requireNode(name);
    }

    /**
     * The node's status, or null when its agent does not answer or the node is not this cluster's (another
     * cluster's agent behind this node's URL, or another database in its data directory): either way, nothing is
     * done to it.
     */
    protected NodeStatus statusOrNull(String node) {
        NodeStatus s = rawStatus(node);
        if (s != null && ctx.identity().get().problem(s) != null) {
            log("watcher", node + " left alone: " + ctx.identity().get().problem(s));
            return null;
        }
        return s;
    }

    /** The node's status whatever its identity, or null when the agent does not answer. */
    protected NodeStatus rawStatus(String node) {
        try {
            return agent(node).status();
        } catch (AgentCallException e) {
            return null;
        }
    }

    /** Tells every reachable agent about {@code epoch}, so commands from older epochs are refused from now on. */
    protected void announceEpoch(long epoch) {
        java.util.Map<String, String> problems = ctx.identity().get().problems();
        for (NodeConfig n : ctx.cfg().nodes()) {
            if (problems.containsKey(n.name())) {
                log("watcher", "epoch " + epoch + " not sent to " + n.name() + ": " + problems.get(n.name()));
                continue;
            }
            try {
                agent(n.name()).ensureSlots(epoch, List.of());
            } catch (AgentCallException e) {
                log("watcher", "could not announce epoch " + epoch + " to " + n.name() + ": " + e.detail());
            }
        }
    }

    // ---- shared state ----

    /**
     * Takes the cluster for a role change: raises the epoch above every epoch the agents have seen and records the
     * operation and its target. Fails when {@code precondition} no longer holds (another watcher or operation got
     * there first).
     */
    protected ClusterState beginRoleOperation(Predicate<ClusterState> precondition, String why, long agentEpoch,
                                              String target) {
        ClusterState.OperationRef ref = new ClusterState.OperationRef(progress.id(), progress.type(),
                target, ctx.selfId(), actor, ctx.time().millis());
        try {
            ClusterState before = ctx.repo().current();
            ClusterState s = ctx.repo().update(precondition, why, cur -> cur
                    .withEpoch(Math.max(cur.epoch(), agentEpoch) + 1)
                    .withOperation(ref)
                    .stamped(ctx.time().millis(), actor));
            progress.epochs(before == null ? 0 : before.epoch(), s.epoch());
            return s;
        } catch (StateConflictException e) {
            throw new Abort(e.getMessage());
        }
    }

    /** Applies {@code change} while this operation still holds the cluster. */
    protected ClusterState updateOwned(java.util.function.UnaryOperator<ClusterState> change) {
        return ctx.repo().update(s -> s.operation() != null && progress.id().equals(s.operation().id()),
                "operation " + progress.id() + " no longer holds the cluster", change);
    }

    /**
     * Releases the cluster; a no-op if this operation no longer holds it. A node this operation made primary is
     * always recorded on the way out, so the watcher's own guards never fence it as a rogue primary.
     */
    protected void endRoleOperation(java.util.function.UnaryOperator<ClusterState> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                String p = madePrimary;
                updateOwned(s -> {
                    ClusterState c = change.apply(s);
                    return (p == null ? c : c.withPrimary(p)).withOperation(null).stamped(ctx.time().millis(), actor);
                });
                return;
            } catch (StateConflictException e) {
                log("watcher", "cluster state was already released: " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "cannot release cluster state", e);
                log("watcher", "cannot release cluster state: " + e.getMessage());
                if (madePrimary == null || attempt >= 30) {
                    return;
                }
                try {
                    ctx.time().sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Records {@code node} as primary right after it was made one, retrying while the shared state cannot be
     * written (Raft election, disk error). Throws {@link Abort} when another watcher already took the cluster over;
     * that watcher adopts the node from the operation's target.
     */
    protected void recordPrimary(String node) throws InterruptedException {
        madePrimary = node;
        for (int attempt = 1; ; attempt++) {
            try {
                updateOwned(s -> s.withPrimary(node).stamped(ctx.time().millis(), actor));
                return;
            } catch (StateConflictException e) {
                throw new Abort(node + " is primary but this watcher no longer holds the cluster (" + e.getMessage()
                        + "); the leading watcher adopts it");
            } catch (RuntimeException e) {
                log("watcher", "recording " + node + " as primary failed (attempt " + attempt + "): " + e.getMessage());
                if (attempt >= 30) {
                    throw new Abort(node + " is primary but could not be recorded: " + e.getMessage());
                }
                ctx.time().sleep(1000);
            }
        }
    }

    /**
     * Before an irreversible step: this watcher must still lead (confirmed with a majority in Raft mode) and this
     * operation must still hold the cluster. Otherwise another watcher may already be acting.
     */
    protected void checkStillLeading(String step) {
        if (!ctx.store().confirmLeadership()) {
            throw new Abort("this watcher lost the lead before: " + step);
        }
        ClusterState s = ctx.repo().current();
        if (s == null || s.operation() == null || !progress.id().equals(s.operation().id())) {
            throw new Abort("the operation no longer holds the cluster before: " + step);
        }
    }

    /**
     * Whether a failed call is a refusal the agent made before changing anything (409, 400). A timeout, a lost
     * connection or a 500 during execution leaves the outcome unknown.
     */
    protected static boolean refusedBeforeActing(AgentCallException e) {
        return e.reachable() && (e.httpStatus() == 409 || e.httpStatus() == 400);
    }

    /** Polls {@code node} until it runs as primary or {@code timeoutMs} pass; returns whether it does. */
    protected boolean settlesAsPrimary(String node, long timeoutMs) throws InterruptedException {
        long deadline = ctx.time().nanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            NodeStatus s = statusOrNull(node);
            if (s != null && s.isPrimary()) {
                return true;
            }
            if (ctx.time().nanos() - deadline >= 0) {
                return false;
            }
            ctx.time().sleep(1000);
        }
    }

    // ---- waiting ----

    protected void waitFor(String what, long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = ctx.time().nanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long step = Math.max(50, Math.min(1000, ctx.cfg().timing().pollIntervalMs() / 2));
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            if (ctx.time().nanos() - deadline >= 0) {
                throw new Abort("timed out after " + timeoutMs / 1000.0 + " s waiting for " + what);
            }
            ctx.time().sleep(step);
        }
    }

    /** Polls an agent background operation until it ends; returns its final status. */
    protected OperationStatus awaitAgentOperation(String node, String opId, long timeoutMs)
            throws InterruptedException {
        long deadline = ctx.time().nanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int shown = 0;
        int failures = 0;
        while (true) {
            OperationStatus st;
            try {
                st = agent(node).operation(opId);
                failures = 0;
            } catch (AgentCallException e) {
                if (++failures > 30) {
                    throw new Abort("lost contact with " + node + " while waiting for " + opId + ": " + e.detail());
                }
                ctx.time().sleep(1000);
                continue;
            }
            List<String> lines = st.log() == null ? List.of() : st.log();
            for (int i = shown; i < lines.size(); i++) {
                log("agent " + node, lines.get(i));
            }
            shown = Math.max(shown, lines.size());
            if (st.finished()) {
                return st;
            }
            if (ctx.time().nanos() - deadline >= 0) {
                throw new Abort(st.name() + " on " + node + " did not finish within " + timeoutMs / 1000 + " s");
            }
            ctx.time().sleep(1000);
        }
    }

    // ---- replication wiring ----

    /** Makes sure the primary has a physical slot for every other node. */
    protected void ensureSlots(String primary, long epoch) {
        List<String> names = new ArrayList<>();
        java.util.Map<String, String> problems = ctx.identity().get().problems();
        for (NodeConfig n : ctx.cfg().nodes()) {
            // A slot nobody of this cluster uses would hold WAL on the primary for good.
            if (!n.name().equals(primary) && !problems.containsKey(n.name())) {
                names.add(n.slot());
            }
        }
        try {
            ApiResult r = agent(primary).ensureSlots(epoch, names);
            logResult(primary, "slots " + String.join(", ", names), r);
        } catch (AgentCallException e) {
            log("watcher", "could not create slots on " + primary + ": " + e.detail());
        }
    }

    /**
     * Points every running replica (other than {@code skip}) at the new primary. Failures are logged and left to
     * the automatic repair, which rewinds or rebuilds replicas that cannot follow.
     */
    protected List<String> pointReplicasAt(String primary, long epoch, Set<String> skip) {
        NodeConfig p = node(primary);
        List<String> problems = new ArrayList<>();
        for (NodeConfig n : ctx.cfg().nodes()) {
            if (n.name().equals(primary) || skip.contains(n.name())) {
                continue;
            }
            NodeStatus s = rawStatus(n.name());
            String wrong = s == null ? null : ctx.identity().get().problem(s);
            if (wrong != null) {
                problems.add(n.name() + " left alone: " + wrong);
                log("watcher", n.name() + " left alone: " + wrong);
                continue;
            }
            if (s == null) {
                problems.add(n.name() + " unreachable");
                log("watcher", n.name() + " is unreachable; it will be pointed at " + primary + " when it returns");
                continue;
            }
            if (s.isPrimary()) {
                problems.add(n.name() + " runs as primary");
                continue;
            }
            if (!s.pgRunning()) {
                log("watcher", n.name() + " is stopped; it will be repaired when the watcher sees it");
                continue;
            }
            if (Objects.equals(s.upstreamHost(), p.pgHost()) && Objects.equals(s.upstreamPort(), p.pgPort())
                    && s.streaming()) {
                log("watcher", n.name() + " already streams from " + primary);
                continue;
            }
            try {
                logResult(n.name(), "follow " + primary, agent(n.name()).follow(epoch, p.pgHost(), p.pgPort(), n.slot()));
            } catch (AgentCallException e) {
                problems.add(n.name() + ": " + e.detail());
                log("watcher", "could not point " + n.name() + " at " + primary + ": " + e.detail());
            }
        }
        return problems;
    }

    /** Drops slots meant for other nodes on a node that has just become a replica (they would hold WAL there). */
    protected void dropStaleSlots(String node, long epoch) {
        for (NodeConfig n : ctx.cfg().nodes()) {
            if (n.name().equals(node)) {
                continue;
            }
            try {
                agent(node).dropSlot(epoch, n.slot());
            } catch (AgentCallException e) {
                log("watcher", "slot " + n.slot() + " left on " + node + ": " + e.detail());
            }
        }
    }

    protected ClusterView pollNow() {
        return ctx.pollNow().get();
    }

    /**
     * Keeps the change of primary for the console until someone acknowledges it. A failure here never fails the
     * operation: the new primary runs either way and the event log has the same facts.
     */
    protected void recordRoleChange(String from, String to, Long lostBytes, long epoch) {
        long now = ctx.time().millis();
        RoleChange rc = new RoleChange(progress.type(), from, to, now, now - progress.snapshot().startedAtMillis(),
                lostBytes, epoch, actor, null, 0);
        try {
            ctx.store().put(RoleChange.KEY, rc.toJson());
        } catch (RuntimeException e) {
            log("watcher", "could not keep the role change for the console: " + e.getMessage());
        }
    }

    protected void event(EventType type, String node, String message) {
        ctx.events().publish(type, node, message, actor);
    }

    protected void event(EventType type, String node, String message, String detail) {
        ctx.events().publish(type, node, message, actor, detail);
    }

    /** The operation's step list and log as plain text, for mails and the event detail. */
    protected String report() {
        StringBuilder sb = new StringBuilder();
        for (OperationProgress.Step s : progress.snapshot().steps()) {
            sb.append(String.format("%-8s %s", s.state(), s.title()));
            if (s.detail() != null) {
                sb.append(" - ").append(s.detail());
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
