package io.pgvip.core.ops;

import io.pgvip.core.TimeSource;
import io.pgvip.core.agent.AgentClient;
import io.pgvip.core.config.ClusterConfig;
import io.pgvip.core.event.EventLog;
import io.pgvip.core.monitor.ClusterView;
import io.pgvip.core.state.ClusterStateRepo;
import io.pgvip.core.state.StateStore;

import java.util.Map;
import java.util.function.Supplier;

/**
 * What operations need from the watcher.
 *
 * @param pollNow      polls every agent right now
 * @param primaryInfo  newest WAL position and timeline seen on the primary
 * @param lease        lease bookkeeping, so a failover knows how long the old primary's lease may still run
 */
public record OpContext(ClusterConfig cfg, Map<String, AgentClient> agents, StateStore store, ClusterStateRepo repo,
                        EventLog events, TimeSource time, Supplier<ClusterView> pollNow,
                        Supplier<PrimaryInfo> primaryInfo, LeaseClock lease) {

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
