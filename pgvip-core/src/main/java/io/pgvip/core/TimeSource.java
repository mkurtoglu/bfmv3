package io.pgvip.core;

/** Wall clock, monotonic clock and sleeping, replaceable in tests. */
public interface TimeSource {

    /** Wall-clock milliseconds, for display and event times. */
    long millis();

    /** Monotonic nanoseconds, for every timeout and lease computation. */
    long nanos();

    void sleep(long millis) throws InterruptedException;

    TimeSource SYSTEM = new TimeSource() {
        @Override
        public long millis() {
            return System.currentTimeMillis();
        }

        @Override
        public long nanos() {
            return System.nanoTime();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };
}
