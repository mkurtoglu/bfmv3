package com.bisoft.bfm.core.state;

import java.util.List;
import java.util.Map;

/**
 * Shared watcher state: a small versioned key-value map plus an event list.
 *
 * <p>{@link LocalStateStore} keeps it in a file for a single watcher. The Raft store keeps it in an Apache Ratis
 * group of three watchers, where every write goes through the replicated log, so a compare-and-set that bumps the
 * cluster epoch can succeed for only one watcher, and only for one that still has a majority behind it.
 */
public interface StateStore extends AutoCloseable {

    enum Mode { STANDALONE, RAFT }

    /** This watcher's id. */
    String selfId();

    Mode mode();

    /** The value under {@code key}, or null. */
    Versioned get(String key);

    /** All entries whose key starts with {@code prefix}. */
    Map<String, Versioned> getPrefix(String prefix);

    /**
     * Writes {@code value} if the key is still at {@code expectedVersion} (0 = absent).
     *
     * @return false when another write got there first
     * @throws StateUnavailableException when the store cannot commit (no Raft majority)
     */
    boolean compareAndSet(String key, long expectedVersion, String value);

    /** Unconditional write, used for per-watcher observations. */
    void put(String key, String value);

    void appendEvent(String json);

    /** Newest first. */
    List<String> events(int limit);

    Leadership leadership();

    /**
     * Confirms, with a majority round trip where there is one, that this watcher still leads. The watcher calls
     * it before renewing the primary's lease, so a watcher cut off from the others stops renewing.
     */
    boolean confirmLeadership();

    List<WatcherPeer> peers();

    @Override
    void close();

    /** A stored value and its version (starts at 1 and grows with every write of the key). */
    record Versioned(String value, long version) {
    }

    /**
     * @param leader   whether this watcher is the one that acts
     * @param leaderId id of the acting watcher, null while there is none
     * @param term     Raft term (0 in standalone mode)
     */
    record Leadership(boolean leader, String leaderId, long term) {
    }

    /**
     * @param id            watcher id
     * @param address       Raft address
     * @param self          this watcher
     * @param leader        currently leading
     * @param lastContactMs milliseconds since the leader last heard from it (null when unknown)
     */
    record WatcherPeer(String id, String address, boolean self, boolean leader, Long lastContactMs) {
    }
}
