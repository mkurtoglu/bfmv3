package io.pgvip.common.model;

import io.pgvip.common.json.JsonMap;
import io.pgvip.common.json.JsonWritable;

/** One row of pg_stat_replication as seen on the primary. */
public record ReplicationPeer(
        String applicationName,
        String clientAddr,
        String state,
        String syncState,
        String sentLsn,
        String writeLsn,
        String flushLsn,
        String replayLsn,
        Long replayLagMs) implements JsonWritable {

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("applicationName", applicationName)
                .put("clientAddr", clientAddr)
                .put("state", state)
                .put("syncState", syncState)
                .put("sentLsn", sentLsn)
                .put("writeLsn", writeLsn)
                .put("flushLsn", flushLsn)
                .put("replayLsn", replayLsn)
                .put("replayLagMs", replayLagMs)
                .raw();
    }

    public static ReplicationPeer from(JsonMap m) {
        return new ReplicationPeer(
                m.str("applicationName"),
                m.str("clientAddr"),
                m.str("state"),
                m.str("syncState"),
                m.str("sentLsn"),
                m.str("writeLsn"),
                m.str("flushLsn"),
                m.str("replayLsn"),
                m.lngOrNull("replayLagMs"));
    }
}
