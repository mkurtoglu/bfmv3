package io.pgvip.core.ops;

import io.pgvip.common.model.ApiResult;
import io.pgvip.common.model.NodeStatus;
import io.pgvip.core.agent.AgentCallException;
import io.pgvip.core.event.EventType;
import io.pgvip.core.monitor.ClusterView;
import io.pgvip.core.monitor.NodeView;
import io.pgvip.core.state.ClusterState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Starts a stopped node as primary: the watcher's restart in place when the primary's PostgreSQL went down but its
 * agent still answers, and the operator's way to bring up the first primary of a new cluster.
 *
 * <p>The VIP comes up first; without it PostgreSQL is not started. It refuses while any other node runs as primary.
 */
public final class StartPrimaryOperation extends Operation {

    public static final String TYPE = "START_PRIMARY";

    private final String node;
    private final boolean automatic;

    public StartPrimaryOperation(OpContext ctx, String node, boolean automatic, String actor) {
        super(ctx, new OperationProgress(TYPE, node, actor, ctx.time(), List.of(
                new String[]{"check", "Check that no other node runs as primary"},
                new String[]{"epoch", "Raise the cluster epoch"},
                new String[]{"old", "Fence the recorded primary, or wait for its lease, if it is another node"},
                new String[]{"fence", "Clear the fence on " + node},
                new String[]{"vip", "Bring up the VIP on " + node + " (sudo ip addr add)"},
                new String[]{"start", "Start PostgreSQL on " + node + " as primary"},
                new String[]{"record", "Record " + node + " as primary and grant its lease"},
                new String[]{"replicas", "Point the replicas at " + node})), actor);
        this.node = node;
        this.automatic = automatic;
    }

    @Override
    protected void execute() throws InterruptedException {
        progress.running("check");
        ClusterView view = pollNow();
        List<NodeView> primaries = view.runningPrimaries().stream().filter(v -> !v.name().equals(node)).toList();
        if (!primaries.isEmpty()) {
            progress.failed("check", primaries.get(0).name() + " runs as primary");
            throw new Abort(primaries.get(0).name() + " already runs as primary");
        }
        NodeView me = view.node(node).orElseThrow();
        if (!me.reachable()) {
            progress.failed("check", "agent unreachable");
            throw new Abort("the agent on " + node + " does not answer");
        }
        if (me.runningReplica()) {
            progress.failed("check", node + " runs as a replica");
            throw new Abort(node + " runs as a replica; use a switchover to make it primary");
        }
        ClusterState before = ctx.repo().current();
        String recorded = before == null ? null : before.primary();
        NodeView rv = recorded == null || recorded.equals(node) ? null : view.node(recorded).orElse(null);
        if (rv != null && rv.reachable() && rv.status().pgRunning()) {
            progress.failed("check", recorded + " still runs PostgreSQL");
            throw new Abort(recorded + " is the recorded primary and PostgreSQL still runs there");
        }
        progress.done("check", "no node runs as primary");

        progress.running("epoch");
        ClusterState s = beginRoleOperation(c -> c.operation() == null
                        && (automatic ? node.equals(c.primary()) && c.failoverArmed()
                        : c.primary() == null || c.primary().equals(node) || !primaryRunning(view, c.primary())),
                "the cluster changed; " + (automatic ? "restart skipped" : "review the cluster again"),
                view.maxAgentEpoch(), node);
        long epoch = s.epoch();
        progress.done("epoch", "epoch " + (before == null ? 0 : before.epoch()) + " -> " + epoch);
        announceEpoch(epoch);
        try {
            // Another node is recorded as primary: treat it like a failover would, so it cannot be running as a
            // second primary somewhere we cannot see.
            progress.running("old");
            if (rv == null) {
                progress.skipped("old", recorded == null ? "no primary recorded" : node + " is the recorded primary");
            } else if (rv.reachable()) {
                logResult(recorded, "fence", agent(recorded).fence("starting " + node + " as primary (epoch "
                        + epoch + ")"));
                progress.done("old", recorded + " fenced");
            } else {
                if (ctx.cfg().selfFence()) {
                    long waitMs = TimeUnit.NANOSECONDS.toMillis(ctx.lease().safeAfterNanos() - ctx.time().nanos());
                    if (waitMs > 0) {
                        log("watcher", "waiting " + waitMs / 1000.0 + " s for any lease of " + recorded + " to run out");
                        ctx.time().sleep(waitMs + 1);
                    }
                }
                progress.done("old", recorded + " does not answer; " + (ctx.cfg().selfFence()
                        ? "its lease has run out" : "relying on the VIP duplicate check"));
            }

            progress.running("fence");
            if (me.fenced()) {
                logResult(node, "clear fence", agent(node).clearFence(epoch));
                progress.done("fence", "fence cleared (was: " + me.status().fencedReason() + ")");
            } else {
                progress.skipped("fence", "not fenced");
            }

            progress.running("vip");
            checkStillLeading("bringing up the VIP on " + node);
            try {
                ApiResult r = agent(node).vipAcquire(epoch);
                logResult(node, "vip acquire", r);
                progress.done("vip", r.message());
            } catch (AgentCallException e) {
                progress.failed("vip", e.detail());
                throw new Abort("VIP could not be brought up on " + node + ": " + e.detail()
                        + "; PostgreSQL is not started");
            }

            progress.running("start");
            if (me.runningPrimary()) {
                progress.skipped("start", "already running");
            } else {
                checkStillLeading("starting " + node + " as primary");
                try {
                    ApiResult r = agent(node).start(epoch, true);
                    logResult(node, "start", r);
                    progress.done("start", r.message());
                } catch (AgentCallException e) {
                    if (!refusedBeforeActing(e) && settlesAsPrimary(node, SETTLE_MS)) {
                        progress.done("start", "started (agent reported: " + e.detail() + ")");
                    } else {
                        progress.failed("start", e.detail());
                        if (refusedBeforeActing(e)) {
                            try {
                                agent(node).vipRelease(epoch);
                            } catch (AgentCallException ignored) {
                                log("watcher", "VIP release after the refused start also failed");
                            }
                        }
                        throw new Abort("start of " + node + " failed: " + e.detail());
                    }
                }
                waitFor(node + " to accept writes", 60_000, () -> {
                    NodeStatus st = statusOrNull(node);
                    return st != null && st.isPrimary();
                });
            }

            progress.running("record");
            String previous = ctx.repo().current().primary();
            recordPrimary(node);
            try {
                agent(node).grantLease(epoch, ctx.cfg().timing().leaseTtlMs(), ctx.cfg().selfFence());
                ctx.lease().granted();
            } catch (AgentCallException e) {
                log("watcher", "first lease grant failed (" + e.detail() + "); the watcher loop renews it");
            }
            progress.done("record", "primary is " + node);

            progress.running("replicas");
            ensureSlots(node, epoch);
            if (node.equals(previous)) {
                progress.skipped("replicas", "replicas already follow " + node);
            } else {
                List<String> problems = pointReplicasAt(node, epoch, Set.of());
                progress.done("replicas", problems.isEmpty() ? "done" : String.join("; ", problems));
            }
            endRoleOperation(c -> c);
            event(automatic ? EventType.PRIMARY_RESTARTED : EventType.OPERATION, node,
                    (automatic ? "PostgreSQL on " + node + " was down; restarted it in place as primary"
                            : node + " started as primary by " + actor) + " (epoch " + epoch + ")", report());
            progress.succeed(node + " runs as primary");
        } catch (Abort a) {
            endRoleOperation(c -> c);
            event(EventType.OPERATION, node, "Starting " + node + " as primary failed: " + a.getMessage(), report());
            throw a;
        } catch (AgentCallException e) {
            endRoleOperation(c -> c);
            event(EventType.OPERATION, node, "Starting " + node + " as primary failed: " + e.detail(), report());
            throw new Abort(e.detail());
        }
    }

    @Override
    protected void onUnexpected(Exception e) {
        endRoleOperation(c -> c);
    }

    private static boolean primaryRunning(ClusterView view, String primary) {
        return view.node(primary).map(NodeView::runningPrimary).orElse(false);
    }
}
