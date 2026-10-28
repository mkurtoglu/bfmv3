package io.pgvip.core;

import io.pgvip.core.monitor.Observation;
import io.pgvip.core.state.LocalStateStore;
import io.pgvip.core.state.StateStore;

import java.util.List;
import java.util.Map;

/** An in-memory store that can pretend to be a Raft member: leadership, confirmation and peers are settable. */
final class TestStore implements StateStore {

    private final LocalStateStore inner;
    private final Mode mode;
    volatile boolean leader = true;
    volatile String leaderId;
    volatile boolean confirm = true;
    volatile long term = 3;
    /** The next n compare-and-set writes fail as if the Raft group had no leader. */
    final java.util.concurrent.atomic.AtomicInteger failWrites = new java.util.concurrent.atomic.AtomicInteger();

    TestStore(String id, Mode mode) {
        this.inner = LocalStateStore.inMemory(id);
        this.mode = mode;
        this.leaderId = id;
    }

    void observe(String watcher, long time, long epoch, String primary, boolean seen) {
        inner.put(Observation.KEY_PREFIX + watcher, new Observation(watcher, time, epoch, primary, seen,
                seen ? null : "gone", null, null).toJson());
    }

    @Override
    public String selfId() {
        return inner.selfId();
    }

    @Override
    public Mode mode() {
        return mode;
    }

    @Override
    public Versioned get(String key) {
        return inner.get(key);
    }

    @Override
    public Map<String, Versioned> getPrefix(String prefix) {
        return inner.getPrefix(prefix);
    }

    @Override
    public boolean compareAndSet(String key, long expectedVersion, String value) {
        if (failWrites.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new io.pgvip.core.state.StateUnavailableException("no raft leader (test)");
        }
        return inner.compareAndSet(key, expectedVersion, value);
    }

    @Override
    public void put(String key, String value) {
        inner.put(key, value);
    }

    @Override
    public void appendEvent(String json) {
        inner.appendEvent(json);
    }

    @Override
    public List<String> events(int limit) {
        return inner.events(limit);
    }

    @Override
    public Leadership leadership() {
        return new Leadership(leader, leaderId, term);
    }

    @Override
    public boolean confirmLeadership() {
        return leader && confirm;
    }

    @Override
    public List<WatcherPeer> peers() {
        return List.of(new WatcherPeer(selfId(), "local", true, leader, 0L),
                new WatcherPeer("w2", "10.0.9.2:9870", false, false, 500L),
                new WatcherPeer("w3", "10.0.9.3:9870", false, false, 700L));
    }

    @Override
    public void close() {
    }
}
