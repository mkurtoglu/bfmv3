package com.bisoft.bfm.core.ops;

import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.monitor.ClusterView;
import com.bisoft.bfm.core.monitor.NodeView;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.StateStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Planned switchover from the current primary to a replica.
 *
 * <p>The old primary is stopped cleanly and gives up its VIP, the target replays everything up to the shutdown
 * checkpoint, then the VIP comes up on the target and only after that is the target promoted. If the VIP cannot be
 * brought up (the address still answers, or {@code ip addr add} fails), the target stays a replica, the old primary
 * starts again as primary with its VIP, and the switchover ends as failed.
 */
public final class SwitchoverOperation extends Operation {

    public static final String TYPE = "SWITCHOVER";

    /**
     * @param checkpointFirst  run CHECKPOINT on the primary before stopping it (shorter shutdown)
     * @param restartOldAsReplica start the old primary again as a replica of the new one
     * @param mail             mail the result
     */
    public record Options(boolean checkpointFirst, boolean restartOldAsReplica, boolean mail) {
        public static Options defaults() {
            return new Options(true, true, true);
        }
    }

    private final String target;
    private final Options options;
    private String oldPrimary;
    private boolean oldStopped;
    private boolean oldUnknown;
    private boolean vipOnTarget;
    private boolean promoted;
    private long epoch;

    public SwitchoverOperation(OpContext ctx, String target, Options options, String actor) {
        super(ctx, new OperationProgress(TYPE, target, actor, ctx.time(), plan(target)), actor);
        this.target = target;
        this.options = options;
    }

    private static List<String[]> plan(String t) {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"pause", "Pause automatic failover and raise the epoch"});
        p.add(new String[]{"checkpoint", "CHECKPOINT on the primary"});
        p.add(new String[]{"stop", "Stop the primary cleanly and remove its VIP (sudo ip addr del)"});
        p.add(new String[]{"catchup", "Wait for " + t + " to replay the final LSN"});
        p.add(new String[]{"vipcheck", "Check the VIP is free (arping -D)"});
        p.add(new String[]{"vip", "Bring up the VIP on " + t + " (sudo ip addr add) and send GARP"});
        p.add(new String[]{"promote", "Promote " + t + ", only if the VIP is up"});
        p.add(new String[]{"record", "Record the new primary"});
        p.add(new String[]{"oldreplica", "Start the old primary as a replica of " + t});
        p.add(new String[]{"replicas", "Point the other replicas at " + t});
        p.add(new String[]{"resume", "Resume automatic failover"});
        return p;
    }

    /**
     * Checks shown before the operator confirms; any FAIL blocks the switchover.
     *
     * @param vipDryRun also run the duplicate-address check on the target (arping, takes a few seconds)
     */
    public static List<PreCheck> preChecks(OpContext ctx, ClusterView view, ClusterState state, String target,
                                           boolean leader, boolean vipDryRun) {
        List<PreCheck> out = new ArrayList<>();
        if (!leader) {
            out.add(PreCheck.fail("This watcher leads the cluster", "switchovers run on the leading watcher"));
        }
        if (state == null || state.primary() == null) {
            out.add(PreCheck.fail("Cluster has a primary", "no primary is recorded yet"));
            return out;
        }
        if (state.operation() != null) {
            out.add(PreCheck.fail("No other operation is running", state.operation().type() + " on "
                    + state.operation().target() + " started by " + state.operation().startedBy()));
        }
        if (target == null || ctx.cfg().node(target).isEmpty()) {
            out.add(PreCheck.fail("Target node", "choose a replica"));
            return out;
        }
        if (target.equals(state.primary())) {
            out.add(PreCheck.fail("Target node", target + " is already the primary"));
            return out;
        }
        for (String n : List.of(state.primary(), target)) {
            String wrong = ctx.identity().get().problem(n);
            if (wrong != null) {
                out.add(PreCheck.fail(n + " is a node of this cluster", wrong));
                return out;
            }
        }
        NodeView p = view.node(state.primary()).orElse(null);
        NodeView t = view.node(target).orElse(null);
        if (p == null || !p.runningPrimary()) {
            out.add(PreCheck.fail("Primary " + state.primary() + " is running", p == null ? "not polled yet"
                    : p.stateLabel()));
            return out;
        }
        if (t == null || !t.reachable()) {
            out.add(PreCheck.fail("Agent on " + target + " online", t == null ? "not polled yet" : t.error()));
            return out;
        }
        Integer ptl = p.status().timeline();
        if (!t.runningReplica() || !t.status().streaming()) {
            out.add(PreCheck.fail("Target is streaming", t.stateLabel()));
        } else if (ptl != null && !ptl.equals(t.status().timeline())) {
            out.add(PreCheck.fail("Target is on the primary's timeline", "target on " + t.status().timeline()
                    + ", primary on " + ptl));
        } else {
            out.add(PreCheck.pass("Target is streaming on timeline " + ptl, "Pass"));
        }
        if (t.fenced()) {
            out.add(PreCheck.fail("Target is not fenced", t.status().fencedReason()));
        }
        if (t.busy() || p.busy()) {
            out.add(PreCheck.fail("Agents are idle", (t.busy() ? target : state.primary()) + " is busy"));
        }
        long e = state.epoch();
        if (p.status().epoch() > e || t.status().epoch() > e) {
            out.add(PreCheck.warn("Agents on both nodes online, epoch " + e, "an agent reports a newer epoch"));
        } else {
            out.add(PreCheck.pass("Agents on both nodes online, epoch " + e, "Pass"));
        }
        if (vipDryRun) {
            out.add(vipDryRun(ctx, target));
        }
        if (ctx.store().mode() == StateStore.Mode.RAFT) {
            List<StateStore.WatcherPeer> peers = ctx.store().peers();
            long up = peers.stream().filter(w -> w.self() || (w.lastContactMs() != null
                    && w.lastContactMs() < ctx.cfg().timing().observationFreshMs())).count();
            if (up * 2 > peers.size()) {
                out.add(PreCheck.pass("Watcher quorum", "Pass · " + up + "/" + peers.size()));
            } else {
                out.add(PreCheck.fail("Watcher quorum", up + "/" + peers.size() + " watchers in contact"));
            }
        } else {
            out.add(PreCheck.pass("Watcher quorum", "standalone watcher"));
        }
        Long lag = ClusterView.lagBytes(p, t);
        String sync = p.status().replicas() == null ? null : p.status().replicas().stream()
                .filter(r -> target.equals(r.applicationName())).map(r -> r.syncState()).findFirst().orElse(null);
        if (lag == null) {
            out.add(PreCheck.warn("Replay lag", "unknown"));
        } else if (lag == 0 || "sync".equalsIgnoreCase(sync) || "quorum".equalsIgnoreCase(sync)) {
            out.add(PreCheck.pass("Replay lag", Lsn.bytes(lag) + (sync == null ? "" : " (" + sync + ")")));
        } else {
            out.add(PreCheck.warn("Replay lag", Lsn.bytes(lag) + " (" + (sync == null ? "async" : sync)
                    + "). Writes stop first so nothing is lost."));
        }
        NodeConfig pc = ctx.cfg().requireNode(state.primary());
        NodeConfig tc = ctx.cfg().requireNode(target);
        if (Objects.equals(p.status().vipAddress(), t.status().vipAddress())) {
            out.add(PreCheck.pass("VIP can follow the new primary", Objects.equals(pc.dc(), tc.dc()) ? "same DC"
                    : "same VIP address"));
        } else {
            out.add(PreCheck.warn("VIP can follow the new primary", "cross-DC: " + tc.dc() + " uses VIP "
                    + t.status().vipAddress() + "; clients must move to it"));
        }
        return out;
    }

    /** Runs arping -D through the target agent: proves sudo works and shows whether the VIP is taken. */
    static PreCheck vipDryRun(OpContext ctx, String target) {
        String title = "Target agent may run sudo ip / arping (dry run)";
        try {
            ApiResult vc = ctx.agent(target).vipCheck();
            String msg = vc.message() == null ? "" : vc.message();
            boolean held = vc.data() != null && vc.data().bool("held", false);
            boolean free = vc.data() != null && vc.data().bool("free", false);
            if (msg.contains("disabled")) {
                return PreCheck.pass(title, "VIP management is off on " + target);
            }
            if (held) {
                return PreCheck.pass(title, "the VIP is already up on " + target);
            }
            if (free || vc.ok()) {
                return PreCheck.pass(title, "the VIP is free; " + target + " can bring it up");
            }
            if (msg.contains("answers from another host")) {
                // arping ran through sudo, so the rule works. Someone answers for the address: during a switchover
                // that is normally the current primary, which gives it up at the stop step.
                String primary = ctx.repo().current() == null ? null : ctx.repo().current().primary();
                boolean onPrimary = false;
                if (primary != null && !primary.equals(target)) {
                    try {
                        onPrimary = ctx.agent(primary).status().vipHeld();
                    } catch (AgentCallException ignored) {
                        // unknown: fall through to the warning
                    }
                }
                return onPrimary
                        ? PreCheck.pass(title, "the VIP is on " + primary + " now, as expected; it moves to " + target
                        + " after " + primary + " stops")
                        : PreCheck.warn(title, "the VIP answers from a host that is not " + (primary == null
                        ? "the primary" : primary) + "; the switchover stops at the VIP check unless it goes away");
            }
            return PreCheck.fail(title, msg);
        } catch (AgentCallException ex) {
            return PreCheck.fail(title, ex.detail());
        }
    }

    @Override
    protected void execute() throws InterruptedException {
        ClusterView view = pollNow();
        ClusterState state = ctx.repo().current();
        List<PreCheck> checks = preChecks(ctx, view, state, target, ctx.store().leadership().leader(), true);
        for (PreCheck c : checks) {
            log("pre-check", c.level() + " " + c.title() + (c.detail() == null ? "" : ": " + c.detail()));
        }
        if (!PreCheck.allowed(checks)) {
            String why = checks.stream().filter(c -> c.level() == PreCheck.Level.FAIL)
                    .map(c -> c.title() + ": " + c.detail()).findFirst().orElse("pre-check failed");
            progress.failed("pause", why);
            throw new Abort("pre-checks failed: " + why);
        }
        oldPrimary = state.primary();
        NodeConfig oc = node(oldPrimary);
        NodeConfig tc = node(target);
        String expectedPrimary = oldPrimary;

        // 1. Pause and epoch.
        progress.running("pause");
        ClusterState s = beginRoleOperation(c -> c.operation() == null && expectedPrimary.equals(c.primary()),
                "the cluster changed; review the switchover again", view.maxAgentEpoch(), target);
        epoch = s.epoch();
        progress.done("pause", "epoch " + state.epoch() + " -> " + epoch);
        event(EventType.SWITCHOVER_STARTED, target, "Switchover " + oldPrimary + " -> " + target + " started by "
                + actor + " (epoch " + epoch + ")");
        announceEpoch(epoch);

        try {
            // 2. Checkpoint.
            if (options.checkpointFirst()) {
                progress.running("checkpoint");
                try {
                    ApiResult r = agent(oldPrimary).checkpoint(epoch);
                    logResult(oldPrimary, "CHECKPOINT", r);
                    progress.done("checkpoint", r.message());
                } catch (AgentCallException e) {
                    progress.failed("checkpoint", e.detail());
                    throw new Abort("CHECKPOINT on " + oldPrimary + " failed: " + e.detail());
                }
            } else {
                progress.skipped("checkpoint", "not requested");
            }

            // 3. Stop the primary and drop its VIP.
            progress.running("stop");
            checkStillLeading("stopping " + oldPrimary);
            String finalLsn;
            try {
                ApiResult r = agent(oldPrimary).stop(epoch, "fast", true);
                oldStopped = true;
                logResult(oldPrimary, "pg_ctl stop -m fast", r);
                finalLsn = r.data() == null ? null : r.data().str("checkpointLsn");
                String cluster = r.data() == null ? null : r.data().str("clusterState");
                if (finalLsn == null || !"shut down".equals(cluster)) {
                    progress.failed("stop", "no clean shutdown checkpoint (" + cluster + ")");
                    throw new Abort(oldPrimary + " did not report a clean shutdown");
                }
                progress.done("stop", "final LSN " + finalLsn);
            } catch (AgentCallException e) {
                NodeStatus after = statusOrNull(oldPrimary);
                oldStopped = after != null && !after.pgRunning();
                oldUnknown = after == null;
                progress.failed("stop", e.detail());
                throw new Abort("stopping " + oldPrimary + " failed: " + e.detail());
            }
            progress.retitle("catchup", "Wait for " + target + " to replay final LSN " + finalLsn);

            // 4. Catch-up: the replica must replay past the shutdown checkpoint record.
            progress.running("catchup");
            String fin = finalLsn;
            String[] last = new String[1];
            waitFor(target + " to replay " + fin, ctx.cfg().timing().switchoverCatchupMs(), () -> {
                NodeStatus ts = statusOrNull(target);
                last[0] = ts == null ? null : ts.replayLsn();
                return ts != null && ts.replayLsn() != null && Lsn.compare(ts.replayLsn(), fin) > 0;
            });
            progress.done("catchup", "replayed " + last[0]);

            // 5. VIP check.
            progress.running("vipcheck");
            try {
                ApiResult vc = agent(target).vipCheck();
                boolean held = vc.data() != null && vc.data().bool("held", false);
                if (!vc.ok() && !held) {
                    progress.failed("vipcheck", vc.message());
                    throw new Abort("the VIP still answers: " + vc.message());
                }
                progress.done("vipcheck", vc.message());
            } catch (AgentCallException e) {
                progress.failed("vipcheck", e.detail());
                throw new Abort("VIP check on " + target + " failed: " + e.detail());
            }

            // 6. VIP on target.
            progress.running("vip");
            checkStillLeading("bringing up the VIP on " + target);
            try {
                ApiResult r = agent(target).vipAcquire(epoch);
                vipOnTarget = true;
                logResult(target, "vip acquire", r);
                progress.done("vip", r.message());
            } catch (AgentCallException e) {
                progress.failed("vip", e.detail());
                throw new Abort("VIP could not be brought up on " + target + ": " + e.detail()
                        + " (no VIP, no promote)");
            }

            // 7. Promote.
            progress.running("promote");
            checkStillLeading("promoting " + target);
            try {
                ApiResult r = agent(target).promote(epoch, ctx.cfg().timing().leaseTtlMs(), ctx.cfg().selfFence());
                promoted = true;
                madePrimary = target;
                ctx.lease().granted();
                logResult(target, "promote", r);
                progress.done("promote", r.message());
            } catch (AgentCallException e) {
                if (refusedBeforeActing(e)) {
                    progress.failed("promote", e.detail());
                    throw new Abort("promote of " + target + " refused: " + e.detail());
                }
                // From here on the target may become primary at any moment: no rollback to the old primary.
                promoted = true;
                log("watcher", "promote of " + target + " ended without a clear answer (" + e.detail() + "); waiting");
                if (settlesAsPrimary(target, SETTLE_MS)) {
                    madePrimary = target;
                    ctx.lease().granted();
                    progress.done("promote", "promoted (agent reported: " + e.detail() + ")");
                } else {
                    progress.failed("promote", e.detail());
                    throw new Abort("promote of " + target + " did not complete and its state is unknown ("
                            + e.detail() + "); neither node is started as primary, check both before acting");
                }
            }

            // 8. Record.
            progress.running("record");
            recordPrimary(target);
            progress.done("record", "primary is now " + target);
            ensureSlots(target, epoch);

            // 9. Old primary as replica.
            if (options.restartOldAsReplica()) {
                progress.running("oldreplica");
                String detail = restartOldAsReplica(oc, tc);
                progress.done("oldreplica", detail);
            } else {
                progress.skipped("oldreplica", "not requested; " + oldPrimary + " stays stopped");
            }

            // 10. Other replicas.
            progress.running("replicas");
            List<String> problems = pointReplicasAt(target, epoch, Set.of(oldPrimary));
            progress.done("replicas", problems.isEmpty() ? "done" : String.join("; ", problems));

            // 11. Resume.
            progress.running("resume");
            endRoleOperation(c -> c);
            progress.done("resume", "automatic failover resumed");
            // The target replayed past the shutdown checkpoint before promotion: nothing written was lost.
            recordRoleChange(oldPrimary, target, 0L, epoch);
            NodeStatus np = statusOrNull(target);
            String summary = "New primary: " + target + " (" + tc.pgHost() + ")"
                    + (np != null && np.timeline() != null ? ", timeline " + np.timeline() : "") + ", epoch " + epoch
                    + (np != null && np.vipAddress() != null ? "\nVIP " + np.vipAddress() + " is on " + target : "");
            ctx.events().publish(EventType.SWITCHOVER_FINISHED, target, "Switchover " + oldPrimary + " -> " + target
                    + " finished", actor, summary + "\n\n" + report(), !options.mail());
            progress.succeed(oldPrimary + " -> " + target);
        } catch (Abort a) {
            String rollback = rollback();
            endRoleOperation(c -> c);
            ctx.events().publish(EventType.SWITCHOVER_FAILED, target, "Switchover " + oldPrimary + " -> " + target
                    + " failed: " + a.getMessage() + (rollback == null ? "" : ". " + rollback), actor, report(),
                    !options.mail());
            throw new Abort(a.getMessage() + (rollback == null ? "" : ". " + rollback));
        }
    }

    @Override
    protected void onUnexpected(Exception e) {
        String rollback = rollback();
        endRoleOperation(c -> c);
        ctx.events().publish(EventType.SWITCHOVER_FAILED, target, "Switchover failed: " + e.getMessage()
                + (rollback == null ? "" : ". " + rollback), actor, report(), !options.mail());
    }

    /**
     * Brings the cluster back to the old primary: the target stays a replica and gives up the VIP, the old primary
     * gets its VIP back and starts as primary. Skipped once the target may have been promoted.
     */
    private String rollback() {
        if (oldPrimary == null || promoted) {
            return promoted ? "No rollback: " + target + " may already be primary" : null;
        }
        if (vipOnTarget) {
            try {
                logResult(target, "vip release", agent(target).vipRelease(epoch));
            } catch (AgentCallException e) {
                log("watcher", "rollback: VIP release on " + target + " failed: " + e.detail());
                return "Rollback stopped: the VIP could not be removed from " + target + " (" + e.detail()
                        + "); " + oldPrimary + " is left stopped";
            }
        }
        if (oldUnknown) {
            return target + " stays a replica; the agent on " + oldPrimary + " does not answer, so its state is"
                    + " unknown";
        }
        if (!oldStopped) {
            return target + " stays a replica; " + oldPrimary + " kept running as primary";
        }
        if (!ctx.store().confirmLeadership()) {
            return target + " stays a replica; this watcher lost the lead, so " + oldPrimary + " is left stopped"
                    + " for the leading watcher to restart";
        }
        StringBuilder sb = new StringBuilder(target + " stays a replica");
        try {
            logResult(oldPrimary, "vip acquire", agent(oldPrimary).vipAcquire(epoch));
        } catch (AgentCallException e) {
            log("watcher", "rollback: VIP acquire on " + oldPrimary + " failed: " + e.detail());
            sb.append("; VIP could not be brought back on ").append(oldPrimary).append(": ").append(e.detail());
        }
        try {
            logResult(oldPrimary, "start as primary", agent(oldPrimary).start(epoch, true));
            agent(oldPrimary).grantLease(epoch, ctx.cfg().timing().leaseTtlMs(), ctx.cfg().selfFence());
            ctx.lease().granted();
            sb.append("; ").append(oldPrimary).append(" started again as primary");
        } catch (AgentCallException e) {
            log("watcher", "rollback: start of " + oldPrimary + " failed: " + e.detail());
            sb.append("; ").append(oldPrimary).append(" could not be started again: ").append(e.detail());
        }
        return sb.toString();
    }

    private String restartOldAsReplica(NodeConfig oc, NodeConfig tc) throws InterruptedException {
        try {
            ApiResult r = agent(oc.name()).follow(epoch, tc.pgHost(), tc.pgPort(), oc.slot());
            logResult(oc.name(), "follow " + target, r);
        } catch (AgentCallException e) {
            log("watcher", "follow failed (" + e.detail() + "), trying pg_rewind");
            try {
                ApiResult r = agent(oc.name()).demote(epoch, tc.pgHost(), tc.pgPort(), oc.slot(), true, "fast");
                var st = awaitAgentOperation(oc.name(), r.operationId(), ctx.cfg().timing().operationTimeoutMs());
                if (st.state() != com.bisoft.bfm.common.model.OperationStatus.State.SUCCEEDED) {
                    return oc.name() + " could not rejoin: " + st.message() + "; rejoin or re-initialize it";
                }
            } catch (AgentCallException e2) {
                return oc.name() + " could not rejoin: " + e2.detail() + "; rejoin or re-initialize it";
            }
        }
        try {
            waitFor(oc.name() + " to stream from " + target, 60_000, () -> {
                NodeStatus s = statusOrNull(oc.name());
                return s != null && s.isReplica() && s.streaming();
            });
        } catch (Abort a) {
            return oc.name() + " started as a replica but is not streaming yet";
        }
        dropStaleSlots(oc.name(), epoch);
        return oc.name() + " streams from " + target;
    }
}
