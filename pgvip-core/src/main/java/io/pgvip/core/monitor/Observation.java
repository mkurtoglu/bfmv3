package io.pgvip.core.monitor;

import io.pgvip.common.json.Json;
import io.pgvip.common.json.JsonMap;
import io.pgvip.common.json.JsonWritable;

/**
 * What one watcher last saw, published to the shared store. In Raft mode the leader fails over only when a
 * majority of watchers have fresh observations that the primary is gone.
 *
 * @param watcherId        publishing watcher
 * @param timeMillis       when it was observed
 * @param epoch            cluster epoch the watcher had
 * @param primary          node the watcher considered primary
 * @param primarySeen      whether that node was answering as a running primary
 * @param detail           why not, when it was not
 * @param lastPrimaryLsn   newest WAL position the watcher has ever seen on the primary under this epoch
 * @param lastPrimaryTimeline timeline of that position
 */
public record Observation(String watcherId, long timeMillis, long epoch, String primary, boolean primarySeen,
                          String detail, String lastPrimaryLsn, Integer lastPrimaryTimeline)
        implements JsonWritable {

    public static final String KEY_PREFIX = "observation/";

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("watcherId", watcherId)
                .put("timeMillis", timeMillis)
                .put("epoch", epoch)
                .put("primary", primary)
                .put("primarySeen", primarySeen)
                .put("detail", detail)
                .put("lastPrimaryLsn", lastPrimaryLsn)
                .put("lastPrimaryTimeline", lastPrimaryTimeline)
                .raw();
    }

    public static Observation fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        return new Observation(m.str("watcherId"), m.lng("timeMillis", 0), m.lng("epoch", 0), m.str("primary"),
                m.bool("primarySeen", false), m.str("detail"), m.str("lastPrimaryLsn"),
                m.intOrNull("lastPrimaryTimeline"));
    }
}
