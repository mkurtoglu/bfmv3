package com.bisoft.bfm.core.state;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One cluster's view of the watcher's store: its keys live under {@code clusters/<id>/} and its events in an event
 * log of its own, so every cluster engine runs the same code it ran alone. Leadership is the store's: one leader
 * runs every cluster.
 */
public final class ScopedStateStore implements StateStore {

    public static final String PREFIX = "clusters/";

    private final StateStore root;
    private final String id;
    private final String prefix;

    public ScopedStateStore(StateStore root, String clusterId) {
        if (clusterId == null || !clusterId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("bad cluster id " + clusterId);
        }
        this.root = root;
        this.id = clusterId;
        this.prefix = PREFIX + clusterId + "/";
    }

    /** The key under which {@code key} of cluster {@code clusterId} is stored. */
    public static String key(String clusterId, String key) {
        return PREFIX + clusterId + "/" + key;
    }

    public StateStore root() {
        return root;
    }

    public String clusterId() {
        return id;
    }

    @Override
    public String selfId() {
        return root.selfId();
    }

    @Override
    public Mode mode() {
        return root.mode();
    }

    @Override
    public Versioned get(String key) {
        return root.get(prefix + key);
    }

    @Override
    public Map<String, Versioned> getPrefix(String keyPrefix) {
        Map<String, Versioned> out = new LinkedHashMap<>();
        root.getPrefix(prefix + keyPrefix).forEach((k, v) -> out.put(k.substring(prefix.length()), v));
        return out;
    }

    @Override
    public boolean compareAndSet(String key, long expectedVersion, String value) {
        return root.compareAndSet(prefix + key, expectedVersion, value);
    }

    @Override
    public void put(String key, String value) {
        root.put(prefix + key, value);
    }

    @Override
    public void appendEvent(String log, String json) {
        root.appendEvent(log == null || log.isEmpty() ? id : id + "." + log, json);
    }

    @Override
    public List<String> events(String log, int limit) {
        return root.events(log == null || log.isEmpty() ? id : id + "." + log, limit);
    }

    @Override
    public Leadership leadership() {
        return root.leadership();
    }

    @Override
    public boolean confirmLeadership() {
        return root.confirmLeadership();
    }

    @Override
    public List<WatcherPeer> peers() {
        return root.peers();
    }

    /** The root store belongs to the watcher, not to one cluster. */
    @Override
    public void close() {
    }
}
