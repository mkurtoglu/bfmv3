package io.pgvip.common.json;

import io.pgvip.common.model.LeaseInfo;
import io.pgvip.common.model.NodeStatus;
import io.pgvip.common.model.PgRole;
import io.pgvip.common.model.ReplicationPeer;
import io.pgvip.common.model.SlotInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void roundTripsNestedValues() {
        String json = "{\"a\":1,\"b\":[true,false,null],\"c\":{\"d\":\"x\\\"y\\n\"},\"e\":-2.5,\"f\":\"\\u00e7\"}";
        Object parsed = Json.parse(json);
        assertTrue(parsed instanceof Map<?, ?>);
        JsonMap m = JsonMap.wrap((Map<?, ?>) parsed);
        assertEquals(1L, m.lng("a", 0));
        assertEquals("x\"y\n", m.obj("c").str("d"));
        assertEquals("ç", m.str("f"));
        assertEquals(parsed, Json.parse(Json.write(parsed)));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2"));
        assertThrows(JsonException.class, () -> Json.parse("{} x"));
    }

    @Test
    void nodeStatusRoundTrip() {
        NodeStatus s = new NodeStatus("pg-a1", "0.1.0", 123L, true, PgRole.PRIMARY, 3, "7312", 160004,
                "0/13000318", null, null, null, null, null, null,
                List.of(new ReplicationPeer("pg-a2", "10.0.0.2", "streaming", "sync", "0/1", "0/1", "0/1", "0/1", 4L)),
                List.of(new SlotInfo("pg_a2", "physical", true, "0/1", 16L)),
                "192.168.0.50", true, 14, new LeaseInfo(true, 25000, 14, true), false, null,
                null, null, null, null, null);
        NodeStatus back = NodeStatus.fromJson(s.toJson());
        assertEquals(s, back);
        assertNull(back.receiveLsn());
        assertEquals("0/13000318", back.bestLsn());
    }
}
