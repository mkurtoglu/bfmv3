package com.bisoft.bfm.common.json;

import com.bisoft.bfm.common.model.AgentIdentity;
import com.bisoft.bfm.common.model.LeaseInfo;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.common.model.ReplicationPeer;
import com.bisoft.bfm.common.model.SlotInfo;
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
                null, null, null, null, null,
                new AgentIdentity("5f2c0d4e-0000-4000-8000-000000000001", "erp", "erp", "3f2a", "db01"));
        NodeStatus back = NodeStatus.fromJson(s.toJson());
        assertEquals(s, back);
        assertEquals("erp (5f2c0d4e-0000-4000-8000-000000000001)", back.identity().cluster());
        assertNull(back.receiveLsn());
        assertEquals("0/13000318", back.bestLsn());
    }

    @Test
    void nodeStatusFromAnOlderAgentHasNoIdentity() {
        NodeStatus s = new NodeStatus("pg-a1", "0.1.0", 123L, true, PgRole.PRIMARY, 3, "7312", 160004,
                "0/13000318", null, null, null, null, null, null, List.of(), List.of(), null, false, 14, null, false,
                null, null, null, null, null, null, null);
        JsonMap m = Json.parseObject(s.toJson());
        m.raw().remove("identity");
        NodeStatus back = NodeStatus.fromJson(m.toJson());
        assertEquals(AgentIdentity.UNKNOWN, back.identity());
        assertNull(back.identity().cluster());
    }
}
