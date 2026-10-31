package com.bisoft.bfm.core.ops;

import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.agent.AgentClient;
import com.bisoft.bfm.core.config.ClusterConfig;
import com.bisoft.bfm.core.event.EventLog;
import com.bisoft.bfm.core.monitor.ClusterView;
import com.bisoft.bfm.core.state.ClusterIdentity;
import com.bisoft.bfm.core.state.ClusterStateRepo;
import com.bisoft.bfm.core.state.StateStore;

import java.util.Map;
import java.util.function.Supplier;

/**
 * What operations need from the watcher.
 *
 * @param pollNow      polls every agent right now
 * @param primaryInfo  newest WAL position and timeline seen on the primary
 * @param lease        lease bookkeeping, so a failover knows how long the old primary's lease may still run
 * @param identity     the cluster's identity as of the last poll; nodes with a wrong one are left alone
 */
public record OpContext(ClusterConfig cfg, Map<String, AgentClient> agents, StateStore store, ClusterStateRepo repo,
                        EventLog events, TimeSource time, Supplier<ClusterView> pollNow,
                        Supplier<PrimaryInfo> primaryInfo, LeaseClock lease, Supplier<ClusterIdentity.Check> identity) {

    public String selfId() {
        return store.selfId();
    }

    public String watcherActor() {
        return "watcher " + store.selfId();
    }

    public AgentClient agent(String node) {
        AgentClient c = agents.get(node);
        if (c == null) {
            throw new IllegalArgumentException("unknown node " + node);
        }
        return c;
    }

    /** Last primary WAL position and timeline the watchers have seen (either may be null). */
    public record PrimaryInfo(String lsn, Integer timeline) {
    }

    /** Monotonic bookkeeping of lease renewals. */
    public interface LeaseClock {
        /** Monotonic time after which no lease this watcher group granted to the old primary can still run. */
        long safeAfterNanos();

        /** Records that the new primary got a lease at about this moment. */
        void granted();
    }
}
