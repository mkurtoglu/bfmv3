package com.bisoft.bfm.common.model;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

/**
 * Reply to an agent command.
 *
 * @param ok          whether the command did what was asked
 * @param message     human readable outcome or error
 * @param operationId id of the started background operation, when the command is asynchronous
 * @param data        optional extra fields (for example {@code reachable} on a peer check)
 */
public record ApiResult(boolean ok, String message, String operationId, JsonMap data) implements JsonWritable {

    public static ApiResult ok(String message) {
        return new ApiResult(true, message, null, null);
    }

    public static ApiResult ok(String message, JsonMap data) {
        return new ApiResult(true, message, null, data);
    }

    public static ApiResult started(String operationId, String message) {
        return new ApiResult(true, message, operationId, null);
    }

    public static ApiResult fail(String message) {
        return new ApiResult(false, message, null, null);
    }

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("ok", ok)
                .put("message", message)
                .put("operationId", operationId)
                .put("data", data == null ? null : data.raw())
                .raw();
    }

    public static ApiResult fromJson(String json) {
        JsonMap m = Json.parseObject(json);
        return new ApiResult(m.bool("ok", false), m.str("message"), m.str("operationId"), m.obj("data"));
    }
}
