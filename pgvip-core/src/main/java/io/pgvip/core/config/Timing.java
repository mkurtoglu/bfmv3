package io.pgvip.core.config;

/**
 * Watcher timing.
 *
 * @param pollIntervalMs            how often every node is polled
 * @param failureThreshold          consecutive failed polls before the primary counts as down
 * @param agentTimeoutMs            timeout for one agent call
 * @param leaseTtlMs                lifetime of the primary's lease
 * @param leaseMarginMs             extra wait after the lease has run out, before promoting
 * @param observationFreshMs        how old another watcher's observation may be and still count (Raft mode)
 * @param maxLagBytes               replicas further behind than this are not promoted automatically
 * @param switchoverCatchupMs       how long a switchover waits for the target to replay the final WAL
 * @param operationTimeoutMs        how long long-running agent operations (rewind, reinit) may take
 */
public record Timing(long pollIntervalMs, int failureThreshold, long agentTimeoutMs, long leaseTtlMs,
                     long leaseMarginMs, long observationFreshMs, long maxLagBytes, long switchoverCatchupMs,
                     long operationTimeoutMs) {

    public Timing {
        if (pollIntervalMs <= 0 || failureThreshold <= 0 || agentTimeoutMs <= 0 || leaseTtlMs <= 0) {
            throw new IllegalArgumentException("timing values must be positive");
        }
        if (leaseTtlMs < 3 * pollIntervalMs) {
            throw new IllegalArgumentException("leaseTtlMs must be at least three poll intervals");
        }
    }

    public static Timing defaults() {
        return new Timing(2_000, 3, 3_000, 30_000, 5_000, 10_000, 16L * 1024 * 1024, 60_000, 6L * 3_600_000);
    }
}
