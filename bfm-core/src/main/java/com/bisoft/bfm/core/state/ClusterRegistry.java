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

    public static final String FILE = "file";
    public static final String CONSOLE = "console";
    public static final String ACTIVE = "active";
    public static final String DRAFT = "draft";

    /**
     * @param source "file" when watcher.yml defines it, "console" when it was created there
     * @param state  "active", or "draft" while the console's Add cluster wizard is not finished
     */
    public record Entry(String id, String name, String source, long createdMillis, String state) {

        public Entry(String id, String name, String source, long createdMillis) {
            this(id, name, source, createdMillis, ACTIVE);
        }

        public boolean draft() {
            return DRAFT.equals(state);
        }

        public boolean console() {
            return CONSOLE.equals(source);
        }

        String toJson() {
            return JsonMap.create().put("id", id).put("name", name).put("source", source)
                    .put("createdMillis", createdMillis).put("state", state).toJson();
        }

        static Entry fromJson(String json) {
            JsonMap m = Json.parseObject(json);
            if (m.bool("removed", false)) {
                return null;
            }
            return new Entry(m.requireStr("id"), m.requireStr("name"), m.str("source", FILE),
                    m.lng("createdMillis", 0), m.str("state", ACTIVE));
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

    /** The entry with this id, or null. */
    public Entry byId(String id) {
        for (Entry e : all()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** Every registered cluster, by name. */
    public List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (StateStore.Versioned v : root.getPrefix(KEY_PREFIX).values()) {
            try {
                Entry e = Entry.fromJson(v.value());
                if (e != null) {
                    out.add(e);
                }
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
        if (!root.compareAndSet(KEY_PREFIX + name, versionOf(name), e.toJson())) {
            return find(name);
        }
        if (adoptLegacy) {
            root.put(MIGRATED_KEY, JsonMap.create().put("cluster", name).put("id", id).put("timeMillis", nowMillis)
                    .toJson());
            LOG.info("moved the state of cluster " + name + " (" + id + ") under " + ScopedStateStore.key(id, ""));
        }
        return e;
    }

    /** The registry key's version (0 when absent; a removed cluster's tombstone counts, so its name can be reused). */
    private long versionOf(String name) {
        StateStore.Versioned v = root.get(KEY_PREFIX + name);
        return v == null ? 0 : v.version();
    }

    // ---------------------------------------------------------------- clusters created in the console

    /** A new console cluster in the draft state, with its spec; null when the name is taken. */
    public Entry createDraft(com.bisoft.bfm.core.config.ClusterSpec spec, long nowMillis) {
        if (find(spec.name()) != null) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        StateStore scoped = new ScopedStateStore(root, id);
        scoped.compareAndSet(ClusterIdentity.ID_KEY, 0, id);
        scoped.put(com.bisoft.bfm.core.config.ClusterSpec.KEY, spec.toJson());
        Entry e = new Entry(id, spec.name(), CONSOLE, nowMillis, DRAFT);
        return root.compareAndSet(KEY_PREFIX + spec.name(), versionOf(spec.name()), e.toJson()) ? e : null;
    }

    /** The spec of a console cluster and its version (to tell an engine that it changed), or null. */
    public StateStore.Versioned spec(String id) {
        return new ScopedStateStore(root, id).get(com.bisoft.bfm.core.config.ClusterSpec.KEY);
    }

    public void saveSpec(String id, com.bisoft.bfm.core.config.ClusterSpec spec) {
        new ScopedStateStore(root, id).put(com.bisoft.bfm.core.config.ClusterSpec.KEY, spec.toJson());
    }

    /** Turns a draft into a running cluster: from now on every watcher of the group runs its engine. */
    public Entry activate(String name) {
        StateStore.Versioned v = root.get(KEY_PREFIX + name);
        Entry e = v == null ? null : Entry.fromJson(v.value());
        if (e == null || !e.draft()) {
            throw new IllegalStateException("no draft named " + name);
        }
        Entry active = new Entry(e.id(), e.name(), e.source(), e.createdMillis(), ACTIVE);
        if (!root.compareAndSet(KEY_PREFIX + name, v.version(), active.toJson())) {
            throw new IllegalStateException("cluster " + name + " changed meanwhile; try again");
        }
        return active;
    }

    /**
     * Removes a console cluster from the registry (its engine stops on every watcher). Its state and events stay
     * under its id, for the record; the name can be used again.
     */
    public void remove(String name, String by, long nowMillis) {
        StateStore.Versioned v = root.get(KEY_PREFIX + name);
        Entry e = v == null ? null : Entry.fromJson(v.value());
        if (e == null) {
            throw new IllegalStateException("no cluster named " + name);
        }
        if (!e.console()) {
            throw new IllegalStateException(name + " is defined in watcher.yml: remove it there");
        }
        String tomb = JsonMap.create().put("removed", true).put("id", e.id()).put("name", name).put("by", by)
                .put("timeMillis", nowMillis).toJson();
        if (!root.compareAndSet(KEY_PREFIX + name, v.version(), tomb)) {
            throw new IllegalStateException("cluster " + name + " changed meanwhile; try again");
        }
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
