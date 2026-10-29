package com.bisoft.bfm.core.event;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

/**
 * One entry of the event and audit log.
 *
 * @param id         unique id
 * @param timeMillis wall-clock time
 * @param type       what happened
 * @param severity   how bad
 * @param node       node it is about, or null
 * @param message    one line for people
 * @param actor      user name for operator actions, otherwise the watcher id
 * @param detail     optional extra lines (mail body, operation log)
 * @param quiet      logged but not mailed (the operator unticked "send a mail")
 */
public record ClusterEvent(String id, long timeMillis, EventType type, Severity severity, String node,
                           String message, String actor, String detail, boolean quiet) implements JsonWritable {

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("id", id)
                .put("timeMillis", timeMillis)
                .put("type", type)
                .put("severity", severity)
                .put("node", node)
                .put("message", message)
                .put("actor", actor)
                .put("detail", detail)
                .put("quiet", quiet)
                .raw();
    }

    public static ClusterEvent fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        EventType type = m.enumValue("type", EventType.class, EventType.OPERATION);
        return new ClusterEvent(m.str("id"), m.lng("timeMillis", 0), type,
                m.enumValue("severity", Severity.class, type.severity()), m.str("node"), m.str("message"),
                m.str("actor"), m.str("detail"), m.bool("quiet", false));
    }
}
