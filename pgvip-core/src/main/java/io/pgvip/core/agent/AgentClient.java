package io.pgvip.core.agent;

import io.pgvip.common.model.ApiResult;
import io.pgvip.common.model.NodeStatus;
import io.pgvip.common.model.OperationStatus;

import java.util.List;

/**
 * Calls one node's agent. Every method either returns the agent's successful reply or throws
 * {@link AgentCallException}: a refusal (the agent answered with ok=false) carries the agent's message,
 * a transport failure is marked as unreachable.
 */
public interface AgentClient {

    String node();

    NodeStatus status();

    ApiResult grantLease(long epoch, long ttlMs, boolean fenceOnExpiry);

    ApiResult vipAcquire(long epoch);

    ApiResult vipRelease(long epoch);

    /** Duplicate address check for the node's VIP; never changes anything. Returns ok=false when it is taken. */
    ApiResult vipCheck();

    /** Promotes the node. The agent refuses unless its VIP is up locally. */
    ApiResult promote(long epoch, long leaseTtlMs, boolean fenceOnExpiry);

    ApiResult checkpoint(long epoch);

    /** Stops PostgreSQL; the reply's data carries the shutdown checkpoint LSN. */
    ApiResult stop(long epoch, String mode, boolean releaseVip);

    ApiResult start(long epoch, boolean allowPrimary);

    ApiResult follow(long epoch, String host, int port, String slot);

    /** Background: stop, optionally pg_rewind, start as a replica of host:port. Returns the operation id. */
    ApiResult demote(long epoch, String host, int port, String slot, boolean rewind, String stopMode);

    /** Background: rebuild from host:port with pg_basebackup. Returns the operation id. */
    ApiResult reinit(long epoch, String host, int port, String slot);

    OperationStatus operation(String id);

    ApiResult fence(String reason);

    ApiResult clearFence(long epoch);

    /** Asks the node whether it can reach host:port; the reply's data has {@code reachable} and WAL receiver state. */
    ApiResult peerCheck(String host, int port, long timeoutMs);

    ApiResult ensureSlots(long epoch, List<String> names);

    ApiResult dropSlot(long epoch, String name);
}
