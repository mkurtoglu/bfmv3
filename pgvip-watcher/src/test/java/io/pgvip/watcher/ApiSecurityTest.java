package io.pgvip.watcher;

import io.pgvip.core.FakeCluster;
import io.pgvip.core.ManualTime;
import io.pgvip.core.TimeSource;
import io.pgvip.core.Watcher;
import io.pgvip.core.agent.AgentClientFactory;
import io.pgvip.core.event.EventType;
import io.pgvip.core.state.LocalStateStore;
import io.pgvip.core.state.StateStore;
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
        "pgvip.watcher-id=w1",
        "pgvip.data-dir=${java.io.tmpdir}/pgvip-api-test-${random.uuid}",
        "pgvip.cluster.name=prod-main",
        "pgvip.cluster.nodes[0].name=pg-a1",
        "pgvip.cluster.nodes[0].agent-url=https://10.0.0.1:8008",
        "pgvip.cluster.nodes[0].pg-host=10.0.0.1",
        "pgvip.cluster.nodes[0].dc=DC1",
        "pgvip.cluster.nodes[1].name=pg-a2",
        "pgvip.cluster.nodes[1].agent-url=https://10.0.0.2:8008",
        "pgvip.cluster.nodes[1].pg-host=10.0.0.2",
        "pgvip.cluster.nodes[1].dc=DC1",
        "pgvip.cluster.nodes[2].name=pg-a3",
        "pgvip.cluster.nodes[2].agent-url=https://10.0.0.3:8008",
        "pgvip.cluster.nodes[2].pg-host=10.0.0.3",
        "pgvip.cluster.nodes[2].dc=DC1",
        "pgvip.users[0].name=admin",
        "pgvip.users[0].password-hash={noop}admin-pw",
        "pgvip.users[0].role=ADMIN",
        "pgvip.users[1].name=viewer",
        "pgvip.users[1].password-hash={noop}viewer-pw",
        "pgvip.users[1].role=VIEWER"})
@AutoConfigureMockMvc
class ApiSecurityTest {

    static final ManualTime TIME = new ManualTime();
    static final FakeCluster CLUSTER = new FakeCluster(TIME);

    static {
        System.setProperty("pgvip.watcher.manual", "true");
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
    Watcher watcher;

    private void tick() {
        TIME.advance(2000);
        watcher.tick();
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
        assertTrue(watcher.events(20).stream().anyMatch(e -> e.type() == EventType.CONTROL_CHANGED
                && "admin".equals(e.actor()) && e.message().contains("maintenance mode on")));
        mvc.perform(put("/api/v1/controls").with(httpBasic("admin", "admin-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"maintenance\":false}")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/switchover").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("SWITCHOVER"));
        long deadline = System.currentTimeMillis() + 10_000;
        while (watcher.operations().get(0).running() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals("pg-a2", watcher.state().primary());
        mvc.perform(get("/api/v1/operations").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("SUCCEEDED"))
                .andExpect(jsonPath("$[0].startedBy").value("admin"));

        mvc.perform(put("/api/v1/notifications/smtp").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"smtp.example.com\",\"port\":587,\"security\":\"STARTTLS\","
                                + "\"user\":\"alerts\",\"password\":\"s3cret\",\"from\":\"pgvip@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.smtp.passwordSet").value(true))
                .andExpect(jsonPath("$.smtp.passwordSecret").doesNotExist());
        String stored = watcher.store().get("notifications").value();
        assertTrue(!stored.contains("s3cret"), "the SMTP password is stored encrypted");
    }
}
