package com.bisoft.bfm.core.state;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The shared cluster state every watcher acts on.
 *
 * @param epoch          raised for every role change; agents refuse commands from older epochs
 * @param primary        node that is supposed to run as primary, null before bootstrap
 * @param autoFailover   automatic failover enabled: some node may be promoted (kept in step with
 *                       {@code noFailover} once that is set)
 * @param maintenance    maintenance mode: automatic failover paused, manual operations allowed
 * @param mailEnabled    mail notifications enabled
 * @param autoReinit     rebuild a replica with pg_basebackup when pg_rewind fails
 * @param operation      operation in progress, null when idle
 * @param changedAtMillis wall-clock time of the last change
 * @param changedBy      who made the last change
 * @param replication    how commits wait for replicas (asynchronous by default)
 * @param noFailover     nodes automatic failover never promotes, as chosen in the console; null until the first
 *                       choice there (then the nodes' {@code noFailover} setting decides, and every node when
 *                       {@code autoFailover} is off)
 */
public record ClusterState(long epoch, String primary, boolean autoFailover, boolean maintenance,
                           boolean mailEnabled, boolean autoReinit, OperationRef operation, long changedAtMillis,
                           String changedBy, Replication replication, List<String> noFailover)
        implements JsonWritable {

    public ClusterState {
        replication = replication == null ? Replication.ASYNC : replication;
        noFailover = noFailover == null ? null : noFailover.stream().distinct().sorted().toList();
    }

    /** A state as it was before the replication mode and the per-node failover choice existed. */
    public ClusterState(long epoch, String primary, boolean autoFailover, boolean maintenance, boolean mailEnabled,
                        boolean autoReinit, OperationRef operation, long changedAtMillis, String changedBy) {
        this(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation, changedAtMillis, changedBy,
                Replication.ASYNC, null);
    }

    public static ClusterState initial(long epoch, String primary, long now, String by) {
        return new ClusterState(epoch, primary, true, false, true, false, null, now, by, Replication.ASYNC, null);
    }

    /** Whether the watcher may fail over on its own right now. */
    public boolean failoverArmed() {
        return autoFailover && !maintenance && operation == null;
    }

    /**
     * The nodes automatic failover never promotes: the console's choice, or before one was made the nodes
     * configured {@code noFailover} (all nodes while automatic failover is off).
     */
    public Set<String> notPromotable(Collection<String> nodes, Predicate<String> configuredNoFailover) {
        if (noFailover != null) {
            return new TreeSet<>(noFailover);
        }
        Set<String> out = new TreeSet<>();
        for (String n : nodes) {
            if (!autoFailover || configuredNoFailover.test(n)) {
                out.add(n);
            }
        }
        return out;
    }

    public ClusterState withEpoch(long e) {
        return new ClusterState(e, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy, replication, noFailover);
    }

    public ClusterState withPrimary(String p) {
        return new ClusterState(epoch, p, autoFailover, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy, replication, noFailover);
    }

    public ClusterState withOperation(OperationRef op) {
        return new ClusterState(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, op,
                changedAtMillis, changedBy, replication, noFailover);
    }

    public ClusterState withControls(boolean autoFailover, boolean maintenance, boolean mailEnabled,
                                     boolean autoReinit) {
        return new ClusterState(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy, replication, noFailover);
    }

    public ClusterState withReplication(Replication r) {
        return new ClusterState(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy, r, noFailover);
    }

    /** The per-node failover choice; automatic failover is on while some node may be promoted. */
    public ClusterState withNoFailover(Collection<String> never, Collection<String> nodes) {
        boolean any = nodes.stream().anyMatch(n -> !never.contains(n));
        return new ClusterState(epoch, primary, any, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy, replication, List.copyOf(never));
    }

    public ClusterState stamped(long now, String by) {
        return new ClusterState(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation, now, by,
                replication, noFailover);
    }

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("epoch", epoch)
                .put("primary", primary)
                .put("autoFailover", autoFailover)
                .put("maintenance", maintenance)
                .put("mailEnabled", mailEnabled)
                .put("autoReinit", autoReinit)
                .put("operation", operation)
                .put("changedAtMillis", changedAtMillis)
                .put("changedBy", changedBy)
                .put("replication", replication)
                .put("noFailover", noFailover)
                .raw();
    }

    public static ClusterState fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        return new ClusterState(m.lng("epoch", 0), m.str("primary"), m.bool("autoFailover", true),
                m.bool("maintenance", false), m.bool("mailEnabled", true), m.bool("autoReinit", false),
                OperationRef.from(m.obj("operation")), m.lng("changedAtMillis", 0), m.str("changedBy"),
                Replication.from(m.obj("replication")), m.has("noFailover") ? m.strList("noFailover") : null);
    }

    /**
     * How commits wait for replicas. With no {@code standbys} they do not (asynchronous). Otherwise each commit
     * waits until {@code count} of the standbys have it on disk. {@code quorum}: while fewer of them stream, the
     * leading watcher drops the wait ({@code degraded}) until they are back and caught up, so writes never stop.
     * {@code strict}: commits always wait (writes stop without the standbys, no commit is lost), and automatic
     * failover only promotes a replica that is sure to have every commit.
     */
    public record Replication(String mode, int count, boolean degraded, List<String> standbys)
            implements JsonWritable {
        public static final Replication ASYNC = new Replication("quorum", 1, false, List.of());

        public Replication {
            mode = mode == null ? "quorum" : mode;
            if (!mode.equals("quorum") && !mode.equals("strict")) {
                throw new IllegalArgumentException("replication mode is quorum or strict: " + mode);
            }
            if (count < 1) {
                throw new IllegalArgumentException("the number of synchronous replicas is at least 1");
            }
            standbys = standbys == null ? List.of() : standbys.stream().distinct().sorted().toList();
        }

        /** Some node is chosen to be waited for. */
        public boolean synchronous() {
            return !standbys.isEmpty();
        }

        public boolean strict() {
            return mode.equals("strict");
        }

        /** Commits wait right now: strict, or quorum while it is not dropped. */
        public boolean waiting() {
            return synchronous() && (strict() || !degraded);
        }

        /** The standbys other than {@code primary}: the ones a commit on it can wait for. */
        public List<String> standbysOf(String primary) {
            return standbys.stream().filter(n -> !n.equals(primary)).toList();
        }

        @Override
        public Object toJsonValue() {
            return JsonMap.create().put("mode", mode).put("count", count).put("degraded", degraded)
                    .put("standbys", standbys).raw();
        }

        static Replication from(JsonMap m) {
            if (m == null) {
                return ASYNC;
            }
            return new Replication(m.str("mode", "quorum"), (int) m.lng("count", 1), m.bool("degraded", false),
                    m.strList("standbys"));
        }
    }

    /**
     * An operation holding the cluster.
     *
     * @param id        operation id
     * @param type      FAILOVER, SWITCHOVER, REINIT, REJOIN, START_PRIMARY
     * @param target    node the operation is about
     * @param owner     watcher running it
     * @param startedBy user or "watcher"
     * @param startedAtMillis start time
     */
    public record OperationRef(String id, String type, String target, String owner, String startedBy,
                               long startedAtMillis) implements JsonWritable {

        @Override
        public Object toJsonValue() {
            return JsonMap.create()
                    .put("id", id)
                    .put("type", type)
                    .put("target", target)
                    .put("owner", owner)
                    .put("startedBy", startedBy)
                    .put("startedAtMillis", startedAtMillis)
                    .raw();
        }

        static OperationRef from(JsonMap m) {
            if (m == null) {
                return null;
            }
            return new OperationRef(m.str("id"), m.str("type"), m.str("target"), m.str("owner"),
                    m.str("startedBy"), m.lng("startedAtMillis", 0));
        }
    }
}
