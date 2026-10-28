package io.pgvip.common.model;

import io.pgvip.common.json.JsonMap;
import io.pgvip.common.json.JsonWritable;

/**
 * The primary lease as the agent sees it.
 *
 * @param granted        whether a lease was ever granted since the agent started
 * @param remainingMs    milliseconds until expiry (0 when expired)
 * @param epoch          epoch the lease was granted under
 * @param fenceOnExpiry  whether the agent fences itself when the lease runs out
 */
public record LeaseInfo(boolean granted, long remainingMs, long epoch, boolean fenceOnExpiry) implements JsonWritable {

    public static final LeaseInfo NONE = new LeaseInfo(false, 0, 0, false);

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("granted", granted)
                .put("remainingMs", remainingMs)
                .put("epoch", epoch)
                .put("fenceOnExpiry", fenceOnExpiry)
                .raw();
    }

    public static LeaseInfo from(JsonMap m) {
        if (m == null) {
            return NONE;
        }
        return new LeaseInfo(m.bool("granted", false), m.lng("remainingMs", 0), m.lng("epoch", 0),
                m.bool("fenceOnExpiry", false));
    }
}
