package io.pgvip.core.ops;

import io.pgvip.common.Lsn;
import io.pgvip.common.model.ApiResult;
import io.pgvip.common.model.NodeStatus;
import io.pgvip.core.agent.AgentCallException;
import io.pgvip.core.config.NodeConfig;
import io.pgvip.core.decision.CandidateSelector;
import io.pgvip.core.decision.PrimaryHealth;
import io.pgvip.core.event.EventType;
import io.pgvip.core.monitor.ClusterView;
import io.pgvip.core.monitor.NodeView;
import io.pgvip.core.state.ClusterState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Automatic failover.
 *
 * <ol>
 *   <li>Confirm with every reachable replica that it cannot reach the primary either.</li>
 *   <li>Raise the epoch in the shared state (only one watcher can win this) and tell every agent.</li>
 *   <li>Fence the old primary if its agent answers; otherwise wait until any lease it holds has run out.</li>
 *   <li>Bring the VIP up on the target. <b>No VIP, no promote:</b> if that fails, the target stays a replica.</li>
 *   <li>Promote the target (the agent refuses again unless the VIP is up there), record it, re-point replicas.</li>
 * </ol>
 */
public final class FailoverOperation extends Operation {

    public static final String TYPE = "FAILOVER";

    private final String oldPrimary;
    private final long expectedEpoch;
    private final Set<String> excluded;
    private final Consumer<String> onVipFailure;

    /**
     * @param oldPrimary    node the shared state names as primary
     * @param expectedEpoch epoch the decision was made under; the failover stops if the state moved on
     * @param excluded      replicas not to promote (recent VIP failures)
     * @param onVipFailure  told which node could not bring the VIP up, so the next attempt skips it for a while
     */
    public FailoverOperation(OpContext ctx, String oldPrimary, long expectedEpoch, Set<String> excluded,
                             Consumer<String> onVipFailure) {
        super(ctx, new OperationProgress(TYPE, null, ctx.watcherActor(), ctx.time(), plan(ctx, oldPrimary)),
                ctx.watcherActor());
        this.oldPrimary = oldPrimary;
        this.expectedEpoch = expectedEpoch;
        this.excluded = excluded;
        this.onVipFailure = onVipFailure;
    }

    private static List<String[]> plan(OpContext ctx, String old) {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"confirm", "Confirm with the replicas that " + old + " is gone"});
        p.add(new String[]{"choose", "Choose the replica to promote"});
        p.add(new String[]{"epoch", "Raise the cluster epoch and pause other operations"});
        p.add(new String[]{"fence", "Fence " + old + " (release VIP, stop PostgreSQL) if its agent answers"});
        p.add(new String[]{"lease", ctx.cfg().selfFence() ? "Wait until the lease of " + old + " has run out"
                : "Lease wait (self-fencing is off)"});
        p.add(new String[]{"vip", "Bring up the VIP on the new primary (sudo ip addr add) and send GARP"});
        p.add(new String[]{"promote", "Promote, only if the VIP is up"});
        p.add(new String[]{"record", "Record the new primary"});
        p.add(new String[]{"replicas", "Point the other replicas at the new primary"});
        return p;
    }

    @Override
    protected void execute() throws InterruptedException {
        NodeConfig old = node(oldPrimary);

        // 1. Replica confirmation.
        progress.running("confirm");
        NodeStatus own = statusOrNull(oldPrimary);
        boolean ownWord = own != null && (!own.pgRunning() || own.isReplica());
        if (ownWord) {
            progress.skipped("confirm", "the agent on " + oldPrimary + " reports PostgreSQL "
                    + (own.pgRunning() ? "in recovery" : "not running"));
        } else if (ctx.cfg().confirmWithReplicas()) {
            String veto = confirmWithReplicas(old);
            if (veto != null) {
                progress.failed("confirm", veto);
                event(EventType.FAILOVER_BLOCKED, oldPrimary, "Automatic failover blocked: " + veto);
                throw new Abort("blocked: " + veto);
            }
        } else {
            progress.skipped("confirm", "replica confirmation is off");
        }

        // 2. Choose the target from a fresh poll.
        progress.running("choose");
        ClusterView fresh = pollNow();
        NodeView oldView = fresh.node(oldPrimary).orElse(null);
        if (PrimaryHealth.of(oldView) == PrimaryHealth.OK) {
            progress.failed("choose", oldPrimary + " is answering as primary again");
            event(EventType.PRIMARY_BACK, oldPrimary, oldPrimary + " answered again before the failover started");
            throw new Abort(oldPrimary + " is back; failover not needed");
        }
        OpContext.PrimaryInfo info = ctx.primaryInfo().get();
        CandidateSelector.Result ranked = CandidateSelector.rank(ctx.cfg(), fresh, oldPrimary, info.timeline(),
                info.lsn(), excluded);
        if (ranked.best().isEmpty()) {
            String why = "no eligible replica (" + ranked.rejectedSummary() + ")";
            progress.failed("choose", why);
            event(EventType.FAILOVER_BLOCKED, oldPrimary, "Automatic failover blocked: " + why);
            throw new Abort(why);
        }
        CandidateSelector.Candidate target = ranked.best().get();
        String t = target.name();
        NodeConfig tc = target.config();
        progress.done("choose", t + " at " + Lsn.format(target.lsn())
                + (target.behindBytes() == null ? "" : ", " + Lsn.bytes(target.behindBytes()) + " behind the last"
                + " position seen on " + oldPrimary));
        progress.retitle("vip", "Bring up the VIP on " + t + " (sudo ip addr add) and send GARP");
        progress.retitle("promote", "Promote " + t + ", only if the VIP is up");

        // 3. Epoch.
        progress.running("epoch");
        ClusterState st = beginRoleOperation(
                s -> s.epoch() == expectedEpoch && oldPrimary.equals(s.primary()) && s.failoverArmed(),
                "cluster state changed since the failover decision", fresh.maxAgentEpoch());
        long epoch = st.epoch();
        progress.done("epoch", "epoch " + expectedEpoch + " -> " + epoch);
        event(EventType.FAILOVER_STARTED, oldPrimary, "Automatic failover started: " + oldPrimary + " -> " + t
                + " (epoch " + epoch + ")");
        announceEpoch(epoch);

        try {
            // 4. Fence the old primary when its agent answers.
            progress.running("fence");
            fenceOld(old, "automatic failover to " + t + " (epoch " + epoch + ")");

            // 5. Lease.
            progress.running("lease");
            if (ctx.cfg().selfFence()) {
                long waitNanos = ctx.lease().safeAfterNanos() - ctx.time().nanos();
                if (waitNanos > 0) {
                    log("watcher", "waiting " + TimeUnit.NANOSECONDS.toMillis(waitNanos) / 1000.0
                            + " s for the lease of " + oldPrimary + " to run out");
                    ctx.time().sleep(TimeUnit.NANOSECONDS.toMillis(waitNanos) + 1);
                }
                progress.done("lease", "any lease of " + oldPrimary + " has run out");
            } else {
                progress.skipped("lease", "self-fencing is off; relying on the VIP duplicate check");
            }
            // The old primary may have come back meanwhile: it must not be running as primary now.
            NodeStatus again = statusOrNull(oldPrimary);
            if (again != null && again.isPrimary() && !again.fenced()) {
                fenceOld(old, "automatic failover to " + t + " (epoch " + epoch + ")");
                again = statusOrNull(oldPrimary);
                if (again != null && again.isPrimary()) {
                    throw new Abort(oldPrimary + " is running as primary again and could not be fenced");
                }
            }

            // 6. VIP first.
            progress.running("vip");
            NodeStatus ts = statusOrNull(t);
            if (ts == null || !ts.isReplica()) {
                progress.failed("vip", t + " is no longer a running replica");
                throw new Abort(t + " is no longer a running replica");
            }
            try {
                ApiResult r = agent(t).vipAcquire(epoch);
                logResult(t, "vip acquire", r);
                progress.done("vip", r.message());
            } catch (AgentCallException e) {
                progress.failed("vip", e.detail());
                onVipFailure.accept(t);
                throw new Abort("VIP could not be brought up on " + t + ": " + e.detail() + "; " + t
                        + " stays a replica (no VIP, no promote)");
            }

            // 7. Promote.
            progress.running("promote");
            try {
                ApiResult r = agent(t).promote(epoch, ctx.cfg().timing().leaseTtlMs(), ctx.cfg().selfFence());
                ctx.lease().granted();
                logResult(t, "promote", r);
                progress.done("promote", r.message());
            } catch (AgentCallException e) {
                NodeStatus after = statusOrNull(t);
                if (after != null && after.isPrimary()) {
                    ctx.lease().granted();
                    progress.done("promote", "promoted (agent reported: " + e.detail() + ")");
                } else {
                    progress.failed("promote", e.detail());
                    releaseVip(t, epoch);
                    throw new Abort("promote of " + t + " failed: " + e.detail());
                }
            }

            // 8. Record.
            progress.running("record");
            updateOwned(s -> s.withPrimary(t).stamped(ctx.time().millis(), actor));
            progress.done("record", "primary is now " + t);

            // 9. Slots and replicas.
            progress.running("replicas");
            ensureSlots(t, epoch);
            List<String> problems = pointReplicasAt(t, epoch, Set.of(oldPrimary));
            progress.done("replicas", problems.isEmpty() ? "all running replicas follow " + t
                    : String.join("; ", problems));

            endRoleOperation(s -> s);
            NodeStatus np = statusOrNull(t);
            String lossCheck = target.behindBytes() == null ? "Data loss check: no WAL position was seen on "
                    + oldPrimary + " before it failed"
                    : target.behindBytes() == 0 ? "Data loss check: " + t + " had received the last WAL position"
                    + " seen on " + oldPrimary
                    : "Data loss check: " + t + " was " + Lsn.bytes(target.behindBytes()) + " behind the last"
                    + " position seen on " + oldPrimary;
            String summary = "New primary: " + t + " (" + tc.pgHost() + ")"
                    + (np != null && np.timeline() != null ? ", timeline " + np.timeline() : "") + ", epoch " + epoch
                    + "\n" + (np != null && np.vipAddress() != null ? "VIP " + np.vipAddress() + " moved to " + t
                    + ", GARP sent\n" : "") + lossCheck + "\n"
                    + (ctx.cfg().autoRejoin() ? "Next step: " + oldPrimary + " rejoins with pg_rewind when it comes back."
                    : "Next step: rejoin or re-initialize " + oldPrimary + " from the console.");
            event(EventType.FAILOVER_FINISHED, t, "Automatic failover " + oldPrimary + " -> " + t + " completed",
                    summary + "\n\n" + report());
            progress.succeed(oldPrimary + " -> " + t);
        } catch (Abort a) {
            endRoleOperation(s -> s);
            event(EventType.FAILOVER_FAILED, t, "Automatic failover " + oldPrimary + " -> " + t + " failed: "
                    + a.getMessage(), report());
            throw a;
        }
    }

    @Override
    protected void onUnexpected(Exception e) {
        endRoleOperation(s -> s);
        event(EventType.FAILOVER_FAILED, oldPrimary, "Automatic failover failed: " + e.getMessage(), report());
    }

    /**
     * Asks every reachable replica whether it still reaches the primary's PostgreSQL port or streams from it.
     *
     * @return null when every answer was "no" (and there was at least one), otherwise why the failover must wait
     */
    private String confirmWithReplicas(NodeConfig old) {
        int answered = 0;
        List<String> seen = new ArrayList<>();
        long timeout = Math.max(500, ctx.cfg().timing().agentTimeoutMs() - 500);
        for (NodeConfig n : ctx.cfg().nodes()) {
            if (n.name().equals(old.name())) {
                continue;
            }
            try {
                ApiResult r = agent(n.name()).peerCheck(old.pgHost(), old.pgPort(), timeout);
                answered++;
                boolean reachable = r.data() != null && r.data().bool("reachable", false);
                boolean streaming = r.data() != null && "streaming".equalsIgnoreCase(r.data().str("walReceiverStatus"))
                        && Objects.equals(old.pgHost(), r.data().str("upstreamHost"));
                log("agent " + n.name(), "peer check " + old.pgHost() + ":" + old.pgPort() + ": "
                        + (reachable ? "reachable" : "not reachable (" + r.message() + ")")
                        + (streaming ? ", still streaming from it" : ""));
                if (reachable || streaming) {
                    seen.add(n.name());
                }
            } catch (AgentCallException e) {
                log("watcher", "peer check on " + n.name() + " failed: " + e.detail());
            }
        }
        if (!seen.isEmpty()) {
            return String.join(", ", seen) + " still reach" + (seen.size() == 1 ? "es " : " ") + old.name();
        }
        if (answered == 0) {
            return "no replica agent answered, so the outage cannot be confirmed";
        }
        progress.done("confirm", answered + " replica" + (answered == 1 ? "" : "s") + " cannot reach " + old.name()
                + " either");
        return null;
    }

    private void fenceOld(NodeConfig old, String reason) {
        try {
            ApiResult r = agent(old.name()).fence(reason);
            logResult(old.name(), "fence", r);
            progress.done("fence", r.message());
        } catch (AgentCallException e) {
            if (e.reachable()) {
                progress.done("fence", "agent refused: " + e.detail());
            } else {
                progress.skipped("fence", "agent unreachable");
            }
        }
    }

    private void releaseVip(String node, long epoch) {
        try {
            logResult(node, "vip release", agent(node).vipRelease(epoch));
        } catch (AgentCallException e) {
            log("watcher", "could not release the VIP on " + node + ": " + e.detail());
        }
    }
}
