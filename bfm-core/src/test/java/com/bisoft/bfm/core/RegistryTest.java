package com.bisoft.bfm.core;

import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.core.notify.NotificationSettingsRepo;
import com.bisoft.bfm.core.state.ClusterIdentity;
import com.bisoft.bfm.core.state.ClusterRegistry;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.ClusterStateRepo;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.core.state.ScopedStateStore;
import com.bisoft.bfm.core.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several clusters in one store: scopes, separate event logs, the registry and the move from one cluster. */
class RegistryTest {

    @Test
    void eachClusterHasItsOwnKeysAndEventLog(@TempDir Path dir) {
        LocalStateStore root = new LocalStateStore("w1", dir);
        StateStore erp = new ScopedStateStore(root, "id-erp");
        StateStore crm = new ScopedStateStore(root, "id-crm");
        assertTrue(erp.compareAndSet("cluster", 0, "erp-state"));
        assertTrue(crm.compareAndSet("cluster", 0, "crm-state"));
        erp.put("observation/w1", "{}");
        erp.appendEvent("{\"n\":1}");
        crm.appendEvent("{\"n\":2}");
        crm.appendEvent("{\"n\":3}");

        assertEquals("erp-state", erp.get("cluster").value());
        assertEquals("crm-state", root.get("clusters/id-crm/cluster").value());
        assertEquals(List.of("observation/w1"), List.copyOf(erp.getPrefix("observation/").keySet()));
        assertEquals(List.of("{\"n\":1}"), erp.events(10));
        assertEquals(List.of("{\"n\":3}", "{\"n\":2}"), crm.events(10));
        assertTrue(root.events(10).isEmpty(), "the default log is nobody's");
        assertTrue(Files.exists(dir.resolve("events-id-crm.jsonl")));

        LocalStateStore again = new LocalStateStore("w1", dir);
        assertEquals(List.of("{\"n\":3}", "{\"n\":2}"), new ScopedStateStore(again, "id-crm").events(10));
        assertEquals("erp-state", new ScopedStateStore(again, "id-erp").get("cluster").value());
        assertNull(new ScopedStateStore(again, "id-erp").get("observation/w1"), "observations stay in memory");
    }

    @Test
    void aNewClusterGetsAnIdOfItsOwn() {
        LocalStateStore root = LocalStateStore.inMemory("w1");
        ClusterRegistry reg = new ClusterRegistry(root);
        ClusterRegistry.Entry erp = reg.create("erp", null, "file", false, 1000);
        ClusterRegistry.Entry crm = reg.create("crm", null, "file", false, 1000);
        assertNotEquals(erp.id(), crm.id());
        assertEquals(erp, reg.create("erp", null, "file", false, 2000), "created once");
        assertEquals(List.of("crm", "erp"), reg.all().stream().map(ClusterRegistry.Entry::name).toList());
        assertEquals(erp.id(), ClusterIdentity.clusterId(new ScopedStateStore(root, erp.id())),
                "the engine reads the registry's id as its cluster id");
    }

    @Test
    void theStateOfASingleClusterWatcherMovesUnderItsClusterKeepingTheIdAgentsAreBoundTo() {
        LocalStateStore root = LocalStateStore.inMemory("w1");
        // What a watcher from before the registry left behind.
        new ClusterStateRepo(root).initialise(ClusterState.initial(7, "pg-a1", 1000, "test"));
        String legacyId = ClusterIdentity.clusterId(root);
        ClusterIdentity.recordSystemIdentifierIfAbsent(root, "7000000000000000001");
        new NotificationSettingsRepo(root).update(s -> s.withThresholds(1, 2));
        root.appendEvent("{\"n\":1}");
        root.appendEvent("{\"n\":2}");
        root.put("observation/w1", "{}");

        ClusterRegistry reg = new ClusterRegistry(root);
        assertTrue(reg.legacyStatePending());
        assertEquals("pg-a1", reg.legacyPrimary());
        ClusterRegistry.Entry e = reg.create("prod-main", null, "file", true, 5000);

        assertEquals(legacyId, e.id(), "agents bound to the old id keep working");
        StateStore scoped = new ScopedStateStore(root, e.id());
        assertEquals("pg-a1", new ClusterStateRepo(scoped).current().primary());
        assertEquals(7, new ClusterStateRepo(scoped).current().epoch());
        assertEquals("7000000000000000001", ClusterIdentity.systemIdentifier(scoped));
        assertEquals(1, new NotificationSettingsRepo(scoped).current().lagAlertBytes());
        assertEquals(List.of("{\"n\":2}", "{\"n\":1}"), scoped.events(10), "history kept, in order");
        assertNull(scoped.get("observation/w1"));
        assertNotNull(root.get(ClusterStateRepo.KEY), "the old keys stay for a downgrade");
        assertFalse(reg.legacyStatePending());
        assertNotNull(root.get(ClusterRegistry.MIGRATED_KEY));
    }

    @Test
    void theSmtpServerIsSharedAndRecipientsAreEachClusters() {
        LocalStateStore root = LocalStateStore.inMemory("w1");
        StateStore erp = new ScopedStateStore(root, "id-erp");
        StateStore crm = new ScopedStateStore(root, "id-crm");
        NotificationSettingsRepo erpRepo = new NotificationSettingsRepo(erp, root);
        NotificationSettingsRepo crmRepo = new NotificationSettingsRepo(crm, root);
        NotificationSettings.Smtp smtp = new NotificationSettings.Smtp("mail.example.com", 587,
                NotificationSettings.Security.STARTTLS, null, null, "bfm@example.com");
        erpRepo.update(s -> s.withSmtp(smtp).withRecipients(List.of(new NotificationSettings.Recipient("dba",
                "dba@example.com", NotificationSettings.Filter.ALL))));
        crmRepo.update(s -> s.withThresholds(9, 9));

        assertEquals("mail.example.com", crmRepo.current().smtp().host(), "one SMTP server for every cluster");
        assertTrue(crmRepo.current().recipients().isEmpty(), "recipients stay with their cluster");
        assertEquals(1, erpRepo.current().recipients().size());
        assertEquals(9, crmRepo.current().lagAlertBytes());
        assertEquals(512L * 1024, erpRepo.current().lagAlertBytes());
    }

    @Test
    void aClustersOwnSmtpCopyServesUntilTheSharedOneIsSaved() {
        LocalStateStore root = LocalStateStore.inMemory("w1");
        StateStore erp = new ScopedStateStore(root, "id-erp");
        NotificationSettings.Smtp old = new NotificationSettings.Smtp("old.example.com", 25,
                NotificationSettings.Security.NONE, null, null, "bfm@example.com");
        new NotificationSettingsRepo(erp).update(s -> s.withSmtp(old));
        NotificationSettingsRepo repo = new NotificationSettingsRepo(erp, root);
        assertEquals("old.example.com", repo.current().smtp().host());
        repo.update(s -> s.withThresholds(5, 5));
        assertEquals("old.example.com", new NotificationSettingsRepo(new ScopedStateStore(root, "id-crm"), root)
                .current().smtp().host(), "the first save makes it the shared one");
    }
}
