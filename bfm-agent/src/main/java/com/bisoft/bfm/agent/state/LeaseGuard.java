package com.bisoft.bfm.agent.state;

import com.bisoft.bfm.common.model.LeaseInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The primary's lease from the watcher.
 *
 * <p>The watcher renews the lease well before it runs out. If renewals stop (the watcher quorum cannot reach
 * this node, or this node cannot reach them) and the lease was granted with {@code fenceOnExpiry}, the agent
 * fences its own primary when the lease expires. The watcher waits for the same TTL plus a margin before it
 * promotes anything, so the old primary is down before a new one exists. Time is measured with a monotonic
 * clock, so wall-clock jumps cannot stretch or shorten a running lease.
 *
 * <p>A persistent guard also writes the lease to {@code state.dir/lease} with its wall-clock deadline, so an agent
 * restarted while cut off from the watchers still fences when the lease it had runs out. On restore the remaining
 * time is never more than the TTL.
 */
public class LeaseGuard {

    private static final Logger LOG = Logger.getLogger(LeaseGuard.class.getName());

    private final LongSupplier nanoClock;
    private final LongSupplier wallClock;
    private final Path file;
    private boolean granted;
    private long epoch;
    private long deadlineNanos;
    private boolean fenceOnExpiry;

    public LeaseGuard() {
        this(System::nanoTime);
    }

    public LeaseGuard(LongSupplier nanoClock) {
        this(nanoClock, System::currentTimeMillis, null);
    }

    public LeaseGuard(LongSupplier nanoClock, LongSupplier wallClock, Path file) {
        this.nanoClock = nanoClock;
        this.wallClock = wallClock;
        this.file = file;
        restore();
    }

    /** A guard that keeps the lease in {@code stateDir/lease} across agent restarts. */
    public static LeaseGuard persistent(Path stateDir) {
        return new LeaseGuard(System::nanoTime, System::currentTimeMillis, stateDir.resolve("lease"));
    }

    public synchronized void grant(long epoch, long ttlMillis, boolean fenceOnExpiry) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.granted = true;
        this.epoch = epoch;
        this.deadlineNanos = nanoClock.getAsLong() + ttlMillis * 1_000_000L;
        this.fenceOnExpiry = fenceOnExpiry;
        save(ttlMillis, wallClock.getAsLong() + ttlMillis);
    }

    /** Drops the lease, for example after this node became a replica. */
    public synchronized void revoke() {
        granted = false;
        fenceOnExpiry = false;
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "cannot remove " + file, e);
            }
        }
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

    private void save(long ttlMillis, long deadlineWallMillis) {
        if (file == null) {
            return;
        }
        String line = epoch + " " + ttlMillis + " " + fenceOnExpiry + " " + deadlineWallMillis + "\n";
        try {
            Path tmp = file.resolveSibling("lease.tmp");
            Files.writeString(tmp, line, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "cannot persist the lease to " + file, e);
        }
    }

    private synchronized void restore() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            String[] f = Files.readString(file, StandardCharsets.UTF_8).trim().split("\\s+");
            long savedEpoch = Long.parseLong(f[0]);
            long ttl = Long.parseLong(f[1]);
            boolean foe = Boolean.parseBoolean(f[2]);
            long deadlineWall = Long.parseLong(f[3]);
            long remaining = Math.max(0, Math.min(ttl, deadlineWall - wallClock.getAsLong()));
            granted = true;
            epoch = savedEpoch;
            fenceOnExpiry = foe;
            deadlineNanos = nanoClock.getAsLong() + remaining * 1_000_000L;
            LOG.info("restored lease of epoch " + savedEpoch + ": " + remaining + " ms left"
                    + (foe ? ", fencing on expiry" : ""));
        } catch (IOException | RuntimeException e) {
            // An unreadable lease file must not disarm fencing: treat it as an expired fencing lease.
            LOG.log(Level.WARNING, "cannot read " + file + "; treating the lease as expired", e);
            granted = true;
            fenceOnExpiry = true;
            deadlineNanos = nanoClock.getAsLong();
        }
    }
}
