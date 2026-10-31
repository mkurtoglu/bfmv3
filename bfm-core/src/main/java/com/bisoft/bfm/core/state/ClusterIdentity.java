package com.bisoft.bfm.core.state;

import com.bisoft.bfm.common.model.NodeStatus;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What makes a cluster this cluster, kept in the shared store: a generated id that every agent of the cluster is
 * bound to, and the PostgreSQL system identifier its data directories share.
 *
 * <p>Together they stop a watcher from steering the wrong PostgreSQL when several clusters share hosts: an agent
 * of another cluster refuses the commands (it is bound to another id), and a data directory of another database is
 * recognised by its system identifier and never promoted, rewound, followed, fenced or given a slot.
 */
public final class ClusterIdentity {

    public static final String ID_KEY = "cluster-id";
    public static final String SYSTEM_ID_KEY = "system-identifier";

    /** Prefix of the error a node view carries when the node is left alone for its identity. */
    public static final String WRONG_IDENTITY = "wrong identity: ";

    private ClusterIdentity() {
    }

    /** The cluster id, created on first use; every watcher of the group reads the same one. */
    public static String clusterId(StateStore store) {
        StateStore.Versioned v = store.get(ID_KEY);
        if (v != null) {
            return v.value();
        }
        String created = UUID.randomUUID().toString();
        if (store.compareAndSet(ID_KEY, 0, created)) {
            return created;
        }
        return store.get(ID_KEY).value();
    }

    /** The cluster id when one was created, otherwise null; never creates one. */
    public static String existingClusterId(StateStore store) {
        StateStore.Versioned v = store.get(ID_KEY);
        return v == null ? null : v.value();
    }

    /** The recorded system identifier, or null before the cluster's primary was first seen. */
    public static String systemIdentifier(StateStore store) {
        StateStore.Versioned v = store.get(SYSTEM_ID_KEY);
        return v == null ? null : v.value();
    }

    /** Records the system identifier unless one is recorded already; returns whether this call recorded it. */
    public static boolean recordSystemIdentifierIfAbsent(StateStore store, String id) {
        return store.get(SYSTEM_ID_KEY) == null && store.compareAndSet(SYSTEM_ID_KEY, 0, id);
    }

    /** Replaces the recorded system identifier: an operator started a node holding another database as primary. */
    public static void replaceSystemIdentifier(StateStore store, String id) {
        store.put(SYSTEM_ID_KEY, id);
    }

    /** True when the node reports a system identifier and it is not the cluster's. */
    public static boolean foreign(String recorded, NodeStatus s) {
        return recorded != null && s != null && s.systemIdentifier() != null
                && !recorded.equals(s.systemIdentifier());
    }

    /** True when the agent says it belongs to another cluster. */
    public static boolean boundElsewhere(String clusterId, NodeStatus s) {
        return clusterId != null && s != null && s.identity().clusterId() != null
                && !clusterId.equals(s.identity().clusterId());
    }

    /**
     * The identity as of the last poll.
     *
     * @param clusterId this cluster's id, null while the shared store could not be read
     * @param systemId  the recorded system identifier, null before the primary was first seen
     * @param problems    why each node with a wrong identity is left alone, by node name
     * @param foreignData the nodes among them whose agent is this cluster's but whose data directory holds another
     *                    database: only re-initialize (or, deliberately, start as primary) applies to them
     */
    public record Check(String clusterId, String systemId, Map<String, String> problems, Set<String> foreignData) {

        public static final Check NONE = new Check(null, null, Map.of(), Set.of());

        public Check {
            problems = Map.copyOf(problems);
            foreignData = Set.copyOf(foreignData);
        }

        /** Why this status must not be acted on, or null when it is this cluster's node. */
        public String problem(NodeStatus s) {
            if (boundElsewhere(clusterId, s)) {
                return "its agent belongs to cluster " + s.identity().cluster() + " (check the agent URL and port)";
            }
            if (foreign(systemId, s)) {
                return "it holds another database (system identifier " + s.systemIdentifier() + ", this cluster's is "
                        + systemId + ")";
            }
            return null;
        }

        /** True when the problem is that the agent belongs to another cluster, not only its data. */
        public boolean otherCluster(NodeStatus s) {
            return boundElsewhere(clusterId, s);
        }

        /** Why {@code node} is left alone, or null. */
        public String problem(String node) {
            return problems.get(node);
        }
    }
}
