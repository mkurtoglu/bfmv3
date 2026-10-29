package com.bisoft.bfm.common.model;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

import java.util.List;

/** Progress of a long-running agent operation (demote with rewind, reinit). */
public record OperationStatus(
        String id,
        String name,
        State state,
        String message,
        long startedAtMillis,
        Long finishedAtMillis,
        List<String> log) implements JsonWritable {

    public enum State { RUNNING, SUCCEEDED, FAILED }

    public boolean finished() {
        return state != State.RUNNING;
    }

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("id", id)
                .put("name", name)
                .put("state", state)
                .put("message", message)
                .put("startedAtMillis", startedAtMillis)
                .put("finishedAtMillis", finishedAtMillis)
                .put("log", log == null ? List.of() : log)
                .raw();
    }

    public static OperationStatus fromJson(String json) {
        return from(Json.parseObject(json));
    }

    public static OperationStatus from(JsonMap m) {
        return new OperationStatus(
                m.str("id"),
                m.str("name"),
                m.enumValue("state", State.class, State.FAILED),
                m.str("message"),
                m.lng("startedAtMillis", 0),
                m.lngOrNull("finishedAtMillis"),
                m.strList("log"));
    }
}
