package io.pgvip.agent.state;

import io.pgvip.common.model.LeaseInfo;

import java.util.function.LongSupplier;

/**
 * The primary's lease from the watcher.
 *
 * <p>The watcher renews the lease well before it runs out. If renewals stop (the watcher quorum cannot reach
 * this node, or this node cannot reach them) and the lease was granted with {@code fenceOnExpiry}, the agent
 * fences its own primary when the lease expires. The watcher waits for the same TTL plus a margin before it
 * promotes anything, so the old primary is down before a new one exists. Time is measured with a monotonic
 * clock, so wall-clock jumps cannot stretch or shorten a lease.
 */
public class LeaseGuard {

    private final LongSupplier nanoClock;
    private boolean granted;
    private long epoch;
    private long deadlineNanos;
    private boolean fenceOnExpiry;

    public LeaseGuard() {
        this(System::nanoTime);
    }

    public LeaseGuard(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    public synchronized void grant(long epoch, long ttlMillis, boolean fenceOnExpiry) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.granted = true;
        this.epoch = epoch;
        this.deadlineNanos = nanoClock.getAsLong() + ttlMillis * 1_000_000L;
        this.fenceOnExpiry = fenceOnExpiry;
    }

    /** Drops the lease, for example after this node became a replica. */
    public synchronized void revoke() {
        granted = false;
        fenceOnExpiry = false;
    }

    public synchronized boolean expired() {
        return granted && nanoClock.getAsLong() - deadlineNanos >= 0;
    }

    /** True when the lease ran out and the agent is expected to fence a primary. */
    public synchronized boolean mustFence() {
        return granted && fenceOnExpiry && expired();
    }

    public synchronized LeaseInfo info() {
        if (!granted) {
            return LeaseInfo.NONE;
        }
        long remaining = Math.max(0, (deadlineNanos - nanoClock.getAsLong()) / 1_000_000L);
        return new LeaseInfo(true, remaining, epoch, fenceOnExpiry);
    }
}
