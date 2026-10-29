package com.bisoft.bfm.core;

import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when told to; sleeping moves it forward at once. */
public final class ManualTime implements TimeSource {

    private static final long WALL_BASE = 1_800_000_000_000L;
    private final AtomicLong nanos = new AtomicLong(1_000_000_000_000L);

    @Override
    public long millis() {
        return WALL_BASE + nanos.get() / 1_000_000L;
    }

    @Override
    public long nanos() {
        return nanos.get();
    }

    @Override
    public void sleep(long millis) throws InterruptedException {
        advance(millis);
        Thread.sleep(0, 200_000);
    }

    public void advance(long millis) {
        nanos.addAndGet(millis * 1_000_000L);
    }
}
