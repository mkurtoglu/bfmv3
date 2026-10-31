package com.bisoft.bfm.watcher;

import com.bisoft.bfm.core.FakeCluster;
import com.bisoft.bfm.core.ManualTime;
import com.bisoft.bfm.core.config.ClusterSpec;
import com.bisoft.bfm.core.config.FailoverScope;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.mail.MailNotifier;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ClusterWizardService;
import com.bisoft.bfm.watcher.service.EnrollmentService;
import com.bisoft.bfm.watcher.service.WatcherCa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The console's wizards: a cluster drafted, checked, created; a node added; the cluster detached. */
class ClusterWizardTest {

    @TempDir
    Path dir;
    private final ManualTime time = new ManualTime();
    private final FakeCluster cluster = new FakeCluster(time);
    private final LocalStateStore store = LocalStateStore.inMemory("w1");
    private ClusterHub hub;
    private ClusterWizardService wizard;

    @BeforeEach
    void setUp() {
        cluster.primary("bi-a1", "10.0.0.31", "DC1");
        cluster.replica("bi-a2", "10.0.0.32", "DC1", "10.0.0.31");
        cluster.replica("bi-a3", "10.0.0.33", "DC1", "10.0.0.31");
        BfmProperties props = new Binder(new MapConfigurationPropertySource(Map.of("bfm.data-dir", dir.toString())))
                .bind("bfm", BfmProperties.class).get();
        SecretBox secrets = SecretBox.fromKeyFile(dir.resolve("secret.key"));
        hub = new ClusterHub(props, store, cluster, time, new MailNotifier(secrets), secrets);
        wizard = new ClusterWizardService(hub, new EnrollmentService(hub, new WatcherCa(store, secrets,
                dir.resolve("tmp")), time));
    }

    @AfterEach
    void tearDown() {
        hub.close();
    }

    private static ClusterSpec.NodeSpec node(String name, String ip) {
        return new ClusterSpec.NodeSpec(name, "https://" + ip + ":8008", ip, 5432, "DC1", 100, false);
    }

    private void tick() {
        time.advance(2000);
        for (ClusterHub.ClusterContext c : hub.clusters()) {
            c.watcher().tick();
        }
    }

    @Test
    void aClusterIsDraftedCheckedCreatedGrownAndDetached() throws Exception {
        assertTrue(hub.names().isEmpty(), "a watcher may start with no cluster");
        wizard.createDraft("bi", "test", "admin");
        assertThrows(IllegalArgumentException.class, () -> wizard.createDraft("bi", null, "admin"));
        assertThrows(IllegalArgumentException.class, () -> wizard.createDraft("b i", null, "admin"));
        wizard.saveBasics("bi", "test", FailoverScope.ANY, false, 2000, 3, 16L * 1024 * 1024, false);
        assertThrows(IllegalArgumentException.class, () -> wizard.saveNodes("bi", List.of(node("bi-a1",
                "10.0.0.31"))), "two nodes at least");
        assertThrows(IllegalArgumentException.class, () -> wizard.saveNodes("bi", List.of(node("bi-a1",
                "10.0.0.31"), node("bi-a2", "10.0.0.31"))), "one agent address per node");
        assertThrows(IllegalArgumentException.class, () -> wizard.saveNodes("bi", List.of(node("bi-a1",
                "10.0.0.31"), new ClusterSpec.NodeSpec("bi-a2", "https://10.0.0.32:184010", "10.0.0.32", 5432, "DC1",
                100, false))), "a port that cannot exist");
        assertThrows(IllegalArgumentException.class, () -> wizard.saveNodes("bi", List.of(node("bi-a1",
                "10.0.0.31"), new ClusterSpec.NodeSpec("bi-a2", "https://10.0.0.32:8008/v1", "10.0.0.32", 5432,
                "DC1", 100, false))), "an address, not a path");
        wizard.saveNodes("bi", List.of(node("bi-a1", "10.0.0.31"), node("bi-a2", "10.0.0.32")));
        assertEquals(1, wizard.drafts().size());
        assertTrue(hub.names().isEmpty(), "a draft runs no engine");

        String id = wizard.draft("bi").id();
        List<ClusterWizardService.Check> checks = wizard.checks(id, wizard.draft("bi").spec());
        assertFalse(checks.stream().anyMatch(ClusterWizardService.Check::blocks), checks.toString());
        assertTrue(checks.stream().anyMatch(c -> c.name().equals("At most one primary")
                && c.detail().contains("bi-a1 runs as primary")));

        assertThrows(IllegalArgumentException.class, () -> wizard.create("bi", ClusterWizardService.StartMode.ADOPT,
                null, "wrong", "admin"), "typed name");
        wizard.create("bi", ClusterWizardService.StartMode.ADOPT, null, "bi", "admin");
        assertEquals(List.of("bi"), hub.names());
        ClusterHub.ClusterContext c = hub.require("bi");
        assertEquals(id, c.id(), "the id its agents enrolled with");
        assertTrue(c.fromConsole());
        tick();
        tick();
        assertEquals("bi-a1", c.watcher().state().primary());
        assertEquals(id, cluster.node("bi-a1").boundCluster);

        wizard.addNode("bi", node("bi-a3", "10.0.0.33"), "admin");
        ClusterHub.ClusterContext grown = hub.require("bi");
        assertEquals(3, grown.watcher().config().nodes().size(), "rebuilt with the new node");
        assertEquals(id, grown.id());
        tick();
        assertEquals("bi-a1", grown.watcher().state().primary(), "same state: nothing re-bootstrapped");

        assertThrows(IllegalArgumentException.class, () -> wizard.detach("bi", "nope", "admin"));
        List<String> out = wizard.detach("bi", "bi", "admin");
        assertEquals(4, out.size(), out.toString());
        assertTrue(hub.names().isEmpty());
        assertNull(cluster.node("bi-a2").boundCluster, "every agent forgot the cluster");
        assertFalse(cluster.node("bi-a1").fenceOnExpiry, "the primary's last lease never fences");
        assertTrue(cluster.node("bi-a1").pgRunning, "PostgreSQL keeps running");
        assertNull(hub.registry().find("bi"), "the name can be used again");
    }

    @Test
    void twoRunningPrimariesBlockCreation() {
        cluster.node("bi-a2").role = com.bisoft.bfm.common.model.PgRole.PRIMARY;
        wizard.createDraft("bi", null, "admin");
        wizard.saveNodes("bi", List.of(node("bi-a1", "10.0.0.31"), node("bi-a2", "10.0.0.32")));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> wizard.create("bi",
                ClusterWizardService.StartMode.ADOPT, null, "bi", "admin"));
        assertTrue(e.getMessage().contains("At most one primary"), e.getMessage());
        assertTrue(hub.names().isEmpty());
    }
}
