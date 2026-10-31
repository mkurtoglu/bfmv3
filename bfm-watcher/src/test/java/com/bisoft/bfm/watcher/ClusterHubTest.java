package com.bisoft.bfm.watcher;

import com.bisoft.bfm.core.FakeCluster;
import com.bisoft.bfm.core.ManualTime;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.core.state.ClusterIdentity;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.ClusterStateRepo;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.mail.MailNotifier;
import com.bisoft.bfm.watcher.service.ClusterHub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which clusters the hub runs, how it upgrades a one-cluster store, and the configuration mistakes it refuses. */
class ClusterHubTest {

    @TempDir
    Path dir;

    private BfmProperties props(Map<String, String> extra) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bfm.data-dir", dir.toString());
        m.put("bfm.clusters[0].name", "erp");
        m.put("bfm.clusters[0].nodes[0].name", "erp-a1");
        m.put("bfm.clusters[0].nodes[0].agent-url", "https://10.0.0.1:8008");
        m.put("bfm.clusters[0].nodes[0].pg-host", "10.0.0.1");
        m.put("bfm.clusters[0].nodes[1].name", "erp-a2");
        m.put("bfm.clusters[0].nodes[1].agent-url", "https://10.0.0.2:8008");
        m.put("bfm.clusters[0].nodes[1].pg-host", "10.0.0.2");
        m.put("bfm.clusters[1].name", "crm");
        m.put("bfm.clusters[1].nodes[0].name", "crm-a1");
        m.put("bfm.clusters[1].nodes[0].agent-url", "https://10.0.0.1:8009");
        m.put("bfm.clusters[1].nodes[0].pg-host", "10.0.0.11");
        m.put("bfm.clusters[1].nodes[1].name", "crm-a2");
        m.put("bfm.clusters[1].nodes[1].agent-url", "https://10.0.0.2:8009");
        m.put("bfm.clusters[1].nodes[1].pg-host", "10.0.0.12");
        m.putAll(extra);
        return new Binder(new MapConfigurationPropertySource(m)).bind("bfm", BfmProperties.class).get();
    }

    private ClusterHub hub(BfmProperties p, LocalStateStore store) {
        ManualTime time = new ManualTime();
        SecretBox secrets = SecretBox.fromKeyFile(dir.resolve("secret.key"));
        return new ClusterHub(p, store, new FakeCluster(time), time, new MailNotifier(secrets), secrets);
    }

    @Test
    void theOneClusterStateMovesToTheClusterWhosePrimaryItRecords() {
        LocalStateStore store = LocalStateStore.inMemory("w1");
        new ClusterStateRepo(store).initialise(ClusterState.initial(4, "crm-a1", 1000, "test"));
        String legacyId = ClusterIdentity.clusterId(store);
        store.appendEvent("{\"id\":\"e1\"}");

        try (ClusterHub hub = hub(props(Map.of()), store)) {
            assertEquals(List.of("erp", "crm"), hub.names());
            assertEquals(legacyId, hub.require("crm").id(), "crm keeps the id its agents are bound to");
            assertNotEquals(legacyId, hub.require("erp").id());
            assertEquals("crm-a1", hub.require("crm").watcher().state().primary());
            assertEquals(4, hub.require("crm").watcher().state().epoch());
            assertEquals(null, hub.require("erp").watcher().state(), "erp starts empty");
            assertEquals(1, hub.require("crm").watcher().store().events(10).size());
            assertTrue(hub.multi());
            assertThrows(ClusterHub.ClusterRequiredException.class, hub::single);
            assertThrows(ClusterHub.UnknownClusterException.class, () -> hub.require("bi"));
        }
        // A restart finds the registry: same ids, nothing moved twice.
        try (ClusterHub again = hub(props(Map.of()), store)) {
            assertEquals(legacyId, again.require("crm").id());
            assertEquals(1, again.require("crm").watcher().store().events(10).size());
        }
    }

    @Test
    void aClusterMovedFromAnotherWatcherKeepsTheIdItsAgentsAreBoundTo() {
        LocalStateStore store = LocalStateStore.inMemory("w1");
        String crmId = "a5443743-ff8b-4a1f-b399-d9765c971c45";
        try (ClusterHub hub = hub(props(Map.of("bfm.clusters[1].id", crmId)), store)) {
            assertEquals(crmId, hub.require("crm").id());
            assertEquals(crmId, ClusterIdentity.clusterId(hub.require("crm").watcher().store()));
        }
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> hub(props(Map.of("bfm.clusters[1].id", "0b7e5a2c-0000-4000-8000-00000000c4a1")), store));
        assertTrue(e.getMessage().contains("registered it as " + crmId), e.getMessage());
    }

    @Test
    void oneAgentUrlForTwoNodesIsRefused() {
        BfmProperties p = props(Map.of("bfm.clusters[1].nodes[1].agent-url", "https://10.0.0.2:8008/"));
        IllegalStateException e = assertThrows(IllegalStateException.class, p::allClusters);
        assertTrue(e.getMessage().contains("erp/erp-a2 and for crm/crm-a2"), e.getMessage());
    }

    @Test
    void clusterNamesMustBeUniqueAndUsableInAUrl() {
        assertThrows(IllegalStateException.class, () -> props(Map.of("bfm.clusters[1].name", "erp")).allClusters());
        assertThrows(IllegalStateException.class, () -> props(Map.of("bfm.clusters[1].name", "c r m"))
                .allClusters());
    }
}
