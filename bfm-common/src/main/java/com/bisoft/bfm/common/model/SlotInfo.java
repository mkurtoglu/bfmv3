package com.bisoft.bfm.common.model;

import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

/** One row of pg_replication_slots on the primary. */
public record SlotInfo(String name, String type, boolean active, String restartLsn, Long retainedBytes)
        implements JsonWritable {

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("name", name)
                .put("type", type)
                .put("active", active)
                .put("restartLsn", restartLsn)
                .put("retainedBytes", retainedBytes)
                .raw();
    }

    public static SlotInfo from(JsonMap m) {
        return new SlotInfo(m.str("name"), m.str("type"), m.bool("active", false),
                m.str("restartLsn"), m.lngOrNull("retainedBytes"));
    }
}
