package com.bisoft.bfm.core.state;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

/**
 * The last completed change of primary, kept in the shared store so every watcher's console can show it until an
 * operator has acknowledged it. A failover finishes in seconds, often before anyone looks; this is what tells the
 * person who arrives afterwards that it happened, how long it took and what it cost.
 *
 * @param type          FAILOVER or SWITCHOVER
 * @param from          the primary before, null when there was none
 * @param to            the primary after
 * @param atMillis      when the new primary was recorded
 * @param durationMs    from the start of the operation to the new primary being recorded
 * @param lostBytes     WAL the new primary did not have from the old one; null when unknown, 0 for a switchover
 * @param epoch         epoch the change ran under
 * @param by            user or watcher that started it
 * @param ackBy         who acknowledged it, null while it is not acknowledged
 * @param ackAtMillis   when it was acknowledged, 0 while it is not
 */
public record RoleChange(String type, String from, String to, long atMillis, long durationMs, Long lostBytes,
                         long epoch, String by, String ackBy, long ackAtMillis) implements JsonWritable {

    public static final String KEY = "role-change";

    public boolean acknowledged() {
        return ackBy != null;
    }

    public RoleChange acknowledgedBy(String user, long now) {
        return new RoleChange(type, from, to, atMillis, durationMs, lostBytes, epoch, by, user, now);
    }

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("type", type)
                .put("from", from)
                .put("to", to)
                .put("atMillis", atMillis)
                .put("durationMs", durationMs)
                .put("lostBytes", lostBytes)
                .put("epoch", epoch)
                .put("by", by)
                .put("ackBy", ackBy)
                .put("ackAtMillis", ackAtMillis)
                .raw();
    }

    public static RoleChange fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        return new RoleChange(m.str("type"), m.str("from"), m.str("to"), m.lng("atMillis", 0),
                m.lng("durationMs", 0), m.lngOrNull("lostBytes"), m.lng("epoch", 0),
                m.str("by"), m.str("ackBy"), m.lng("ackAtMillis", 0));
    }
}
