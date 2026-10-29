package com.bisoft.bfm.core.state;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

/**
 * The shared cluster state every watcher acts on.
 *
 * @param epoch          raised for every role change; agents refuse commands from older epochs
 * @param primary        node that is supposed to run as primary, null before bootstrap
 * @param autoFailover   automatic failover enabled
 * @param maintenance    maintenance mode: automatic failover paused, manual operations allowed
 * @param mailEnabled    mail notifications enabled
 * @param autoReinit     rebuild a replica with pg_basebackup when pg_rewind fails
 * @param operation      operation in progress, null when idle
 * @param changedAtMillis wall-clock time of the last change
 * @param changedBy      who made the last change
 */
public record ClusterState(long epoch, String primary, boolean autoFailover, boolean maintenance,
                           boolean mailEnabled, boolean autoReinit, OperationRef operation, long changedAtMillis,
                           String changedBy) implements JsonWritable {

    public static ClusterState initial(long epoch, String primary, long now, String by) {
        return new ClusterState(epoch, primary, true, false, true, false, null, now, by);
    }

    /** Whether the watcher may fail over on its own right now. */
    public boolean failoverArmed() {
        return autoFailover && !maintenance && operation == null;
    }

    public ClusterState withEpoch(long e) {
        return new ClusterState(e, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy);
    }

    public ClusterState withPrimary(String p) {
        return new ClusterState(epoch, p, autoFailover, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy);
    }

    public ClusterState withOperation(OperationRef op) {
        return new ClusterState(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, op,
                changedAtMillis, changedBy);
    }

    public ClusterState withControls(boolean autoFailover, boolean maintenance, boolean mailEnabled,
                                     boolean autoReinit) {
        return new ClusterState(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation,
                changedAtMillis, changedBy);
    }

    public ClusterState stamped(long now, String by) {
        return new ClusterState(epoch, primary, autoFailover, maintenance, mailEnabled, autoReinit, operation, now, by);
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
                .raw();
    }

    public static ClusterState fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        return new ClusterState(m.lng("epoch", 0), m.str("primary"), m.bool("autoFailover", true),
                m.bool("maintenance", false), m.bool("mailEnabled", true), m.bool("autoReinit", false),
                OperationRef.from(m.obj("operation")), m.lng("changedAtMillis", 0), m.str("changedBy"));
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
