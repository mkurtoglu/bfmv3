package com.bisoft.bfm.watcher;

import com.bisoft.bfm.core.FakeCluster;
import com.bisoft.bfm.core.ManualTime;
import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.agent.AgentClientFactory;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.core.state.StateStore;
import com.bisoft.bfm.watcher.service.ClusterHub;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** One watcher, two clusters on shared hosts: each with its own engine, state, events and API. */
@SpringBootTest(properties = {
        "bfm.watcher-id=w1",
        "bfm.data-dir=${java.io.tmpdir}/bfm-multi-test-${random.uuid}",
        "bfm.clusters[0].name=erp",
        "bfm.clusters[0].environment=production",
        "bfm.clusters[0].nodes[0].name=erp-a1",
        "bfm.clusters[0].nodes[0].agent-url=https://10.0.0.1:8008",
        "bfm.clusters[0].nodes[0].pg-host=10.0.0.1",
        "bfm.clusters[0].nodes[0].dc=DC1",
        "bfm.clusters[0].nodes[1].name=erp-a2",
        "bfm.clusters[0].nodes[1].agent-url=https://10.0.0.2:8008",
        "bfm.clusters[0].nodes[1].pg-host=10.0.0.2",
        "bfm.clusters[0].nodes[1].dc=DC1",
        "bfm.clusters[1].name=crm",
        "bfm.clusters[1].environment=staging",
        "bfm.clusters[1].nodes[0].name=crm-a1",
        "bfm.clusters[1].nodes[0].agent-url=https://10.0.0.2:8009",
        "bfm.clusters[1].nodes[0].pg-host=10.0.0.12",
        "bfm.clusters[1].nodes[0].dc=DC1",
        "bfm.clusters[1].nodes[1].name=crm-a2",
        "bfm.clusters[1].nodes[1].agent-url=https://10.0.0.1:8009",
        "bfm.clusters[1].nodes[1].pg-host=10.0.0.11",
        "bfm.clusters[1].nodes[1].dc=DC1",
        "bfm.users[0].name=admin",
        "bfm.users[0].password-hash={noop}admin-pw",
        "bfm.users[0].role=ADMIN",
        "bfm.users[1].name=viewer",
        "bfm.users[1].password-hash={noop}viewer-pw",
        "bfm.users[1].role=VIEWER"})
@AutoConfigureMockMvc
class MultiClusterApiTest {

    static final ManualTime TIME = new ManualTime();
    static final FakeCluster CLUSTER = new FakeCluster(TIME);

    static {
        System.setProperty("bfm.watcher.manual", "true");
        // Two hosts, each carrying one node of each cluster.
        CLUSTER.primary("erp-a1", "10.0.0.1", "DC1").hostId = "host-1";
        CLUSTER.replica("erp-a2", "10.0.0.2", "DC1", "10.0.0.1").hostId = "host-2";
        FakeCluster.Node crm1 = CLUSTER.replica("crm-a1", "10.0.0.12", "DC1", null);
        crm1.role = com.bisoft.bfm.common.model.PgRole.PRIMARY;
        crm1.standbySignal = false;
        crm1.vip = "192.168.0.60";
        crm1.hostId = "host-2";
        crm1.systemIdentifier = "7000000000000000099";
        FakeCluster.Node crm2 = CLUSTER.replica("crm-a2", "10.0.0.11", "DC1", "10.0.0.12");
        crm2.vip = "192.168.0.60";
        crm2.hostId = "host-1";
        crm2.systemIdentifier = "7000000000000000099";
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

    private void tick() {
        TIME.advance(2000);
        for (ClusterHub.ClusterContext c : hub.clusters()) {
            c.watcher().tick();
        }
    }

    @Test
    void eachClusterRunsOnItsOwnAndTheApiNamesIt() throws Exception {
        tick();
        tick();
        tick();
        assertEquals(java.util.List.of("erp", "crm"), hub.names());
        String erpId = hub.require("erp").id();
        String crmId = hub.require("crm").id();
        assertNotEquals(erpId, crmId);
        assertEquals(erpId, CLUSTER.node("erp-a1").boundCluster);
        assertEquals(crmId, CLUSTER.node("crm-a2").boundCluster, "each agent bound to its own cluster");

        mvc.perform(get("/api/v1/clusters").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].name", hasItems("erp", "crm")));
        mvc.perform(get("/api/v1/cluster").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.clusters", hasItems("erp", "crm")));
        mvc.perform(get("/api/v1/clusters/erp/cluster").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cluster").value("erp"))
                .andExpect(jsonPath("$.primary").value("erp-a1"))
                .andExpect(jsonPath("$.environment").value("production"));
        mvc.perform(get("/api/v1/clusters/crm").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primary").value("crm-a1"))
                .andExpect(jsonPath("$.systemIdentifier").value("7000000000000000099"));
        mvc.perform(get("/api/v1/clusters/nope/cluster").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("no cluster named nope")));

        // A change in one cluster stays there: its state and its own event log.
        mvc.perform(put("/api/v1/clusters/crm/controls").with(httpBasic("viewer", "viewer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"maintenance\":true}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/clusters/crm/controls").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maintenance\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenance").value(true));
        assertTrue(hub.require("crm").watcher().state().maintenance());
        assertFalse(hub.require("erp").watcher().state().maintenance());
        assertTrue(hub.require("crm").watcher().events(50).stream().anyMatch(e -> e.type() == EventType.CONTROL_CHANGED
                && e.message().contains("maintenance mode on")));
        assertFalse(hub.require("erp").watcher().events(50).stream().anyMatch(e -> e.type()
                == EventType.CONTROL_CHANGED));

        // The hosts: each carries one node of each cluster, so no cluster lacks host redundancy.
        mvc.perform(get("/api/v1/hosts").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].instances.length()").value(2))
                .andExpect(jsonPath("$[0].sameCluster.length()").value(0));

        // A failover in one cluster does not touch the other.
        CLUSTER.crash("erp-a1");
        for (int i = 0; i < 6; i++) {
            tick();
            awaitIdle();
        }
        assertEquals("erp-a2", hub.require("erp").watcher().state().primary());
        assertEquals("crm-a1", hub.require("crm").watcher().state().primary());
        assertTrue(CLUSTER.node("crm-a1").pgRunning);
        assertFalse(CLUSTER.log.stream().anyMatch(l -> l.startsWith("crm-") && l.contains("fence")));
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (hub.clusters().stream().anyMatch(c -> c.watcher().operations().stream().anyMatch(
                o -> o.running())) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }
}
