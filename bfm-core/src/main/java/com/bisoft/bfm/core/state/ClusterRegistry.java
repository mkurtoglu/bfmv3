package com.bisoft.bfm.core.state;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The clusters one watcher (group) runs, kept in its store: each has a name and a generated id, and its state lives
 * under {@code clusters/<id>/} ({@link ScopedStateStore}). The id is what agents are bound to, so it never changes
 * for a cluster: renaming a cluster keeps it.
 *
 * <p>A store written by a watcher from before the registry holds one cluster's state at the top level. The first
 * leader that sees it copies that state, events included, under the cluster that owns it, keeping the cluster id
 * the agents are already bound to. The old keys stay where they are, so a downgrade still finds them.
 */
public final class ClusterRegistry {

    private static final Logger LOG = Logger.getLogger(ClusterRegistry.class.getName());

    public static final String KEY_PREFIX = "registry/";
    public static final String MIGRATED_KEY = "migrated-to-registry";
    /** Settings every cluster shares (the SMTP server), outside any cluster's scope. */
    public static final String GLOBAL_PREFIX = "global/";

    private static final int COPY_EVENTS = 5000;

    /**
     * @param source "file" when watcher.yml defines it, "console" when it was created there
     */
    public record Entry(String id, String name, String source, long createdMillis) {

        String toJson() {
            return JsonMap.create().put("id", id).put("name", name).put("source", source)
                    .put("createdMillis", createdMillis).toJson();
        }

        static Entry fromJson(String json) {
            JsonMap m = Json.parseObject(json);
            return new Entry(m.requireStr("id"), m.requireStr("name"), m.str("source", "file"),
                    m.lng("createdMillis", 0));
        }
    }

    private final StateStore root;

    public ClusterRegistry(StateStore root) {
        this.root = root;
    }

    public Entry find(String name) {
        StateStore.Versioned v = root.get(KEY_PREFIX + name);
        return v == null ? null : Entry.fromJson(v.value());
    }

    /** Every registered cluster, by name. */
    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (StateStore.Versioned v : root.getPrefix(KEY_PREFIX).values()) {
            try {
                out.add(Entry.fromJson(v.value()));
            } catch (RuntimeException e) {
                LOG.warning("unreadable registry entry: " + e.getMessage());
            }
        }
        out.sort(Comparator.comparing(Entry::name));
        return out;
    }

    /** True while the store holds a single cluster's state from before the registry and nobody migrated it. */
    public boolean legacyStatePending() {
        return root.get(MIGRATED_KEY) == null && root.get(ClusterStateRepo.KEY) != null;
    }

    /** The primary recorded in the pre-registry state, or null; tells which configured cluster owns it. */
    public String legacyPrimary() {
        StateStore.Versioned v = root.get(ClusterStateRepo.KEY);
        if (v == null) {
            return null;
        }
        try {
            return ClusterState.fromJson(v.value()).primary();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Registers a cluster. Only the leading watcher should call it; the id is written with a compare-and-set, so a
     * second caller gets the first one's entry.
     *
     * @param requestedId the id to use (a cluster moved from another watcher keeps the id its agents are bound to),
     *                    or null
     * @param adoptLegacy this cluster owns the pre-registry state: move it in and keep its cluster id
     */
    public Entry create(String name, String requestedId, String source, boolean adoptLegacy, long nowMillis) {
        Entry existing = find(name);
        if (existing != null) {
            return existing;
        }
        String id = requestedId;
        if (id != null && !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("bad cluster id " + id);
        }
        String legacy = adoptLegacy ? ClusterIdentity.existingClusterId(root) : null;
        if (id == null) {
            id = legacy;
        } else if (legacy != null && !legacy.equals(id)) {
            LOG.warning("cluster " + name + " is configured with id " + id + "; the agents of the state it takes over"
                    + " are bound to " + legacy + " and will refuse its commands until they are bound again");
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        for (Entry other : all()) {
            if (other.id().equals(id)) {
                throw new IllegalStateException("cluster id " + id + " already belongs to cluster " + other.name());
            }
        }
        StateStore scoped = new ScopedStateStore(root, id);
        if (adoptLegacy) {
            copyLegacy(scoped);
        }
        StateStore.Versioned have = scoped.get(ClusterIdentity.ID_KEY);
        if (have == null) {
            scoped.compareAndSet(ClusterIdentity.ID_KEY, 0, id);
        } else if (!have.value().equals(id)) {
            scoped.put(ClusterIdentity.ID_KEY, id);
        }
        Entry e = new Entry(id, name, source, nowMillis);
        if (!root.compareAndSet(KEY_PREFIX + name, 0, e.toJson())) {
            return find(name);
        }
        if (adoptLegacy) {
            root.put(MIGRATED_KEY, JsonMap.create().put("cluster", name).put("id", id).put("timeMillis", nowMillis)
                    .toJson());
            LOG.info("moved the state of cluster " + name + " (" + id + ") under " + ScopedStateStore.key(id, ""));
        }
        return e;
    }

    /** Copies every top-level key of the old layout and the default event log into the cluster's scope. */
    private void copyLegacy(StateStore scoped) {
        for (Map.Entry<String, StateStore.Versioned> kv : root.getPrefix("").entrySet()) {
            String k = kv.getKey();
            if (k.startsWith(KEY_PREFIX) || k.startsWith(ScopedStateStore.PREFIX) || k.startsWith(GLOBAL_PREFIX)
                    || k.equals(MIGRATED_KEY) || LocalStateStore.ephemeral(k)) {
                continue;
            }
            scoped.put(k, kv.getValue().value());
        }
        // An interrupted earlier run may have copied the events already.
        if (scoped.events(1).isEmpty()) {
            List<String> events = new ArrayList<>(root.events(COPY_EVENTS));
            for (int i = events.size() - 1; i >= 0; i--) {
                scoped.appendEvent(events.get(i));
            }
        }
    }
}
