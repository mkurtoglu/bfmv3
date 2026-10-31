package com.bisoft.bfm.core.ops;

import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.OperationStatus;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.state.ClusterState;

import java.util.ArrayList;
import java.util.List;

/**
 * Brings a non-primary node back as a streaming replica of the current primary.
 *
 * <ul>
 *   <li>{@link Kind#FOLLOW}: start a stopped replica, or re-point a running one, at the primary; falls back to a rejoin
 *   when it does not stream.</li>
 *   <li>{@link Kind#REJOIN}: stop, pg_rewind against the primary, start as a replica (the agent's demote).</li>
 *   <li>{@link Kind#REINIT}: rebuild with pg_basebackup.</li>
 * </ul>
 * With {@code reinitOnFailure} a failed start or rejoin continues with a reinit.
 *
 * <p>These operations do not hold the cluster: automatic failover stays armed while a replica is being rebuilt.
 */
public final class NodeRepairOperation extends Operation {

    public enum Kind { FOLLOW, REJOIN, REINIT }

    private final String node;
    private final Kind kind;
    private final boolean reinitOnFailure;

    public NodeRepairOperation(OpContext ctx, String node, Kind kind, boolean reinitOnFailure, String actor) {
        super(ctx, new OperationProgress(kind.name(), node, actor, ctx.time(), plan(node, kind, reinitOnFailure)),
                actor);
        this.node = node;
        this.kind = kind;
        this.reinitOnFailure = reinitOnFailure;
    }

    private static List<String[]> plan(String node, Kind kind, boolean reinit) {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"check", "Check that " + node + " is not the primary and the primary is running"});
        if (kind == Kind.FOLLOW) {
            p.add(new String[]{"start", "Start or re-point " + node + " as a replica of the primary"});
        }
        if (kind == Kind.FOLLOW || kind == Kind.REJOIN) {
            p.add(new String[]{"rejoin", "Stop " + node + ", pg_rewind and start as a replica"});
        }
        if (kind == Kind.REINIT || reinit) {
            p.add(new String[]{"reinit", "Rebuild " + node + " with pg_basebackup"});
        }
        p.add(new String[]{"stream", "Wait for " + node + " to stream from the primary"});
        return p;
    }

    @Override
    protected void execute() throws InterruptedException {
        progress.running("check");
        ClusterState st = ctx.repo().current();
        if (st == null || st.primary() == null) {
            progress.failed("check", "no primary recorded");
            throw new Abort("the cluster has no primary to follow");
        }
        if (st.primary().equals(node)) {
            progress.failed("check", node + " is the primary");
            throw new Abort(node + " is the primary");
        }
        if (st.operation() != null) {
            progress.failed("check", st.operation().type() + " is running");
            throw new Abort(st.operation().type() + " is running");
        }
        String primary = st.primary();
        NodeStatus ps = statusOrNull(primary);
        if (ps == null || !ps.isPrimary()) {
            progress.failed("check", primary + " is not running as primary");
            throw new Abort("primary " + primary + " is not running");
        }
        NodeStatus me = rawStatus(node);
        if (me == null) {
            progress.failed("check", "agent unreachable");
            throw new Abort("the agent on " + node + " does not answer");
        }
        String problem = ctx.identity().get().problem(me);
        // Another database in this node's data directory can be replaced by a re-initialize an operator asked
        // for; another cluster's agent is never touched.
        if (problem != null && (kind != Kind.REINIT || ctx.identity().get().otherCluster(me))) {
            progress.failed("check", problem);
            throw new Abort(node + " is left alone: " + problem);
        }
        if (me.busyOperation() != null) {
            progress.failed("check", "busy with " + me.busyOperation());
            throw new Abort(node + " is busy with " + me.busyOperation());
        }
        long epoch = st.epoch();
        NodeConfig pc = node(primary);
        NodeConfig nc = node(node);
        progress.done("check", "primary " + primary + " runs; epoch " + epoch);
        ensureSlots(primary, epoch);

        boolean ok = false;
        String last = null;
        if (kind == Kind.FOLLOW) {
            // follow on a stopped node writes standby.signal and the primary's address, then starts it.
            progress.running("start");
            try {
                logResult(node, "follow " + primary, agent(node).follow(epoch, pc.pgHost(), pc.pgPort(), nc.slot()));
                waitFor(node + " to stream", Math.max(15_000, ctx.cfg().timing().replicaGraceMs()), () -> {
                    NodeStatus s = statusOrNull(node);
                    return s != null && s.isReplica() && s.streaming();
                });
                progress.done("start", "started and streaming");
                ok = true;
            } catch (AgentCallException e) {
                last = e.detail();
                progress.failed("start", e.detail());
            } catch (Abort a) {
                last = a.getMessage();
                progress.failed("start", a.getMessage() + "; it probably needs pg_rewind");
            }
        }
        if (!ok && (kind == Kind.FOLLOW || kind == Kind.REJOIN)) {
            progress.running("rejoin");
            try {
                ApiResult r = agent(node).demote(epoch, pc.pgHost(), pc.pgPort(), nc.slot(), true, "fast");
                OperationStatus os = awaitAgentOperation(node, r.operationId(), ctx.cfg().timing().operationTimeoutMs());
                if (os.state() == OperationStatus.State.SUCCEEDED) {
                    progress.done("rejoin", os.message());
                    ok = true;
                } else {
                    last = os.message();
                    progress.failed("rejoin", os.message());
                }
            } catch (AgentCallException e) {
                last = e.detail();
                progress.failed("rejoin", e.detail());
            }
        }
        boolean rebuilt = false;
        if (!ok && (kind == Kind.REINIT || reinitOnFailure)) {
            progress.running("reinit");
            try {
                ApiResult r = agent(node).reinit(epoch, pc.pgHost(), pc.pgPort(), nc.slot());
                OperationStatus os = awaitAgentOperation(node, r.operationId(), ctx.cfg().timing().operationTimeoutMs());
                if (os.state() == OperationStatus.State.SUCCEEDED) {
                    progress.done("reinit", os.message());
                    ok = true;
                    rebuilt = true;
                } else {
                    last = os.message();
                    progress.failed("reinit", os.message());
                }
            } catch (AgentCallException e) {
                last = e.detail();
                progress.failed("reinit", e.detail());
            }
        }
        EventType failType = kind == Kind.REINIT || rebuilt ? EventType.REINIT_FAILED : EventType.REJOIN_FAILED;
        if (!ok) {
            event(failType, node, describe() + " of " + node + " failed: " + last, report());
            throw new Abort(last);
        }

        progress.running("stream");
        try {
            waitFor(node + " to stream from " + primary, 120_000, () -> {
                NodeStatus s = statusOrNull(node);
                return s != null && s.isReplica() && s.streaming();
            });
        } catch (Abort a) {
            progress.failed("stream", a.getMessage());
            event(failType, node, node + " started as a replica but does not stream from " + primary, report());
            throw a;
        }
        progress.done("stream", "streaming from " + primary);
        dropStaleSlots(node, epoch);
        EventType doneType = kind == Kind.REINIT || rebuilt ? EventType.REINIT_FINISHED : EventType.REJOIN_FINISHED;
        event(doneType, node, node + (rebuilt ? " re-initialized from " : " rejoined as a replica of ") + primary
                + (actor.startsWith("watcher ") ? "" : " (by " + actor + ")"), report());
        progress.succeed(node + " streams from " + primary);
    }

    private String describe() {
        return switch (kind) {
            case FOLLOW -> "Restart";
            case REJOIN -> "Rejoin";
            case REINIT -> "Re-initialization";
        };
    }
}
