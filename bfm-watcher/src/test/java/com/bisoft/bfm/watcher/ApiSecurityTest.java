package com.bisoft.bfm.watcher;

import com.bisoft.bfm.core.FakeCluster;
import com.bisoft.bfm.core.ManualTime;
import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.core.agent.AgentClientFactory;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.core.state.StateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The REST API on a fake three-node cluster: who may read, who may act, and what lands in the event log. */
@SpringBootTest(properties = {
        "bfm.watcher-id=w1",
        "bfm.data-dir=${java.io.tmpdir}/bfm-api-test-${random.uuid}",
        "bfm.cluster.name=prod-main",
        "bfm.cluster.nodes[0].name=pg-a1",
        "bfm.cluster.nodes[0].agent-url=https://10.0.0.1:8008",
        "bfm.cluster.nodes[0].pg-host=10.0.0.1",
        "bfm.cluster.nodes[0].dc=DC1",
        "bfm.cluster.nodes[1].name=pg-a2",
        "bfm.cluster.nodes[1].agent-url=https://10.0.0.2:8008",
        "bfm.cluster.nodes[1].pg-host=10.0.0.2",
        "bfm.cluster.nodes[1].dc=DC1",
        "bfm.cluster.nodes[2].name=pg-a3",
        "bfm.cluster.nodes[2].agent-url=https://10.0.0.3:8008",
        "bfm.cluster.nodes[2].pg-host=10.0.0.3",
        "bfm.cluster.nodes[2].dc=DC1",
        "bfm.users[0].name=admin",
        "bfm.users[0].password-hash={noop}admin-pw",
        "bfm.users[0].role=ADMIN",
        "bfm.users[1].name=viewer",
        "bfm.users[1].password-hash={noop}viewer-pw",
        "bfm.users[1].role=VIEWER"})
@AutoConfigureMockMvc
class ApiSecurityTest {

    static final ManualTime TIME = new ManualTime();
    static final FakeCluster CLUSTER = new FakeCluster(TIME);

    static {
        System.setProperty("bfm.watcher.manual", "true");
        CLUSTER.primary("pg-a1", "10.0.0.1", "DC1");
        CLUSTER.replica("pg-a2", "10.0.0.2", "DC1", "10.0.0.1");
        CLUSTER.replica("pg-a3", "10.0.0.3", "DC1", "10.0.0.1");
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        AgentClientFactory fakeAgents() {
            return CLUSTER;
        }

        @Bean
        @Primary
        TimeSource fakeTime() {
            return TIME;
        }

        @Bean(destroyMethod = "close")
        @Primary
        StateStore memoryStore() {
            return LocalStateStore.inMemory("w1");
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ClusterHub hub;

    private Watcher watcher() {
        return hub.single().watcher();
    }

    private void tick() {
        TIME.advance(2000);
        watcher().tick();
    }

    @Test
    void readsNeedALoginAndChangesNeedAdmin() throws Exception {
        tick();
        tick();
        mvc.perform(get("/api/v1/cluster")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/cluster").with(httpBasic("viewer", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/cluster").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value("pg-a1"))
                .andExpect(jsonPath("$.health").value("Healthy"))
                .andExpect(jsonPath("$.nodes.length()").value(3));

        // Replication, seen from both sides: the primary's walsenders and what each replica reports itself.
        mvc.perform(get("/api/v1/replication").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value("pg-a1"))
                .andExpect(jsonPath("$.primaryRunning").value(true))
                .andExpect(jsonPath("$.streamingCount").value(2))
                .andExpect(jsonPath("$.replicas.length()").value(2))
                .andExpect(jsonPath("$.replicas[0].node").value("pg-a2"))
                .andExpect(jsonPath("$.replicas[0].peerState").value("streaming"))
                .andExpect(jsonPath("$.replicas[0].streaming").value(true))
                .andExpect(jsonPath("$.replicas[0].flushBehindBytes").value(0))
                .andExpect(jsonPath("$.replicas[0].slot").value("pg_a2"))
                // The watcher does not create slots on a cluster it has only been watching; the first operation does.
                .andExpect(jsonPath("$.slots.length()").value(0));

        String body = "{\"target\":\"pg-a2\",\"confirm\":\"prod-main\"}";
        mvc.perform(post("/api/v1/switchover").with(httpBasic("viewer", "viewer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/switchover").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"pg-a2\",\"confirm\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("type the cluster name prod-main to confirm"));

        mvc.perform(put("/api/v1/controls").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maintenance\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenance").value(true));
        assertTrue(watcher().events(20).stream().anyMatch(e -> e.type() == EventType.CONTROL_CHANGED
                && "admin".equals(e.actor()) && e.message().contains("maintenance mode on")));
        mvc.perform(put("/api/v1/controls").with(httpBasic("admin", "admin-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"maintenance\":false}")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/switchover").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("SWITCHOVER"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (watcher().operations().get(0).running() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals("pg-a2", watcher().state().primary());
        mvc.perform(get("/api/v1/operations").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].startedBy").value("admin"));

        // After the switchover the new primary has a slot for every other node, and the old primary streams back.
        // The loop is driven by hand in this test, so poll once for the console's view to catch up.
        tick();
        mvc.perform(get("/api/v1/replication").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value("pg-a2"))
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.replicas[?(@.node == 'pg-a1')].peerState").value("streaming"))
                .andExpect(jsonPath("$.replicas[?(@.node == 'pg-a1')].slotActive").value(true));

        // The switchover is kept as the last role change until an admin acknowledges it.
        mvc.perform(get("/api/v1/role-change").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SWITCHOVER"))
                .andExpect(jsonPath("$.from").value("pg-a1"))
                .andExpect(jsonPath("$.to").value("pg-a2"))
                .andExpect(jsonPath("$.lostBytes").value(0))
                .andExpect(jsonPath("$.ackBy").doesNotExist());
        mvc.perform(get("/api/v1/cluster").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(jsonPath("$.roleChange.to").value("pg-a2"))
                .andExpect(jsonPath("$.healthReasons.length()").value(0));
        mvc.perform(post("/api/v1/role-change/acknowledge").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/role-change/acknowledge").with(httpBasic("admin", "admin-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ackBy").value("admin"));

        mvc.perform(put("/api/v1/notifications/smtp").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"smtp.example.com\",\"port\":587,\"security\":\"STARTTLS\","
                                + "\"user\":\"alerts\",\"password\":\"s3cret\",\"from\":\"bfm@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smtp.passwordSet").value(true))
                .andExpect(jsonPath("$.smtp.passwordSecret").doesNotExist());
        String stored = hub.store().get("global/smtp").value();
        assertTrue(stored.contains("smtp.example.com"), "one SMTP server for every cluster of the watcher");
        assertTrue(!stored.contains("s3cret"), "the SMTP password is stored encrypted");
    }
}
