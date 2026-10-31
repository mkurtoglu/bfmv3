package com.bisoft.bfm.watcher;

import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.common.tls.Keytool;
import com.bisoft.bfm.core.FakeCluster;
import com.bisoft.bfm.core.ManualTime;
import com.bisoft.bfm.core.NotLeaderException;
import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.core.agent.AgentClientFactory;
import com.bisoft.bfm.core.config.ClusterSpec;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.core.ops.OperationProgress;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.raft.RaftStateStore;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.mail.MailNotifier;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.ClusterWizardService;
import com.bisoft.bfm.watcher.service.EnrollmentService;
import com.bisoft.bfm.watcher.service.WatcherCa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three watchers in one Raft group, several clusters: every watcher runs every cluster with the same ids, only the
 * leader acts, a failure in one cluster fails over exactly once and leaves the others alone, and a new leader carries
 * on for all of them. A cluster created in the console on the leader appears on the other watchers.
 */
class RaftManyClustersTest {

    @TempDir
    Path dir;

    private final ManualTime time = new ManualTime();
    /** Two PostgreSQL clusters, each with its own VIP and its own database (system identifier). */
    private final FakeCluster erp = new FakeCluster(time);
    private final FakeCluster crm = new FakeCluster(time);
    private final FakeCluster bi = new FakeCluster(time);
    private final List<RaftStateStore> stores = new ArrayList<>();
    private final List<ClusterHub> hubs = new ArrayList<>();
    private final List<ClusterWizardService> wizards = new ArrayList<>();
    private final List<WatcherCa> cas = new ArrayList<>();
    private final List<EnrollmentService> enrollments = new ArrayList<>();

    @AfterEach
    void close() {
        hubs.stream().filter(java.util.Objects::nonNull).forEach(ClusterHub::close);
        stores.forEach(RaftStateStore::close);
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void await(String what, BooleanSupplier c) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static void node(Map<String, Object> m, int c, int n, String name, String ip, String dc) {
        String p = "bfm.clusters[" + c + "].nodes[" + n + "].";
        m.put(p + "name", name);
        m.put(p + "agent-url", "https://" + ip + ":8008");
        m.put(p + "pg-host", ip);
        m.put(p + "dc", dc);
    }

    private BfmProperties props(Path data) {
        Map<String, Object> m = new HashMap<>();
        m.put("bfm.data-dir", data.toString());
        m.put("bfm.clusters[0].name", "erp");
        m.put("bfm.clusters[0].environment", "production");
        node(m, 0, 0, "erp-a1", "10.0.1.1", "DC1");
        node(m, 0, 1, "erp-a2", "10.0.1.2", "DC1");
        node(m, 0, 2, "erp-a3", "10.0.1.3", "DC1");
        m.put("bfm.clusters[1].name", "crm");
        m.put("bfm.clusters[1].environment", "staging");
        node(m, 1, 0, "crm-a1", "10.0.2.1", "DC1");
        node(m, 1, 1, "crm-a2", "10.0.2.2", "DC1");
        return new Binder(new MapConfigurationPropertySource(m)).bind("bfm", BfmProperties.class).get();
    }

    private void start() throws IOException {
        erp.primary("erp-a1", "10.0.1.1", "DC1");
        erp.replica("erp-a2", "10.0.1.2", "DC1", "10.0.1.1");
        erp.replica("erp-a3", "10.0.1.3", "DC1", "10.0.1.1");
        crm.primary("crm-a1", "10.0.2.1", "DC1");
        crm.replica("crm-a2", "10.0.2.2", "DC1", "10.0.2.1");
        for (String n : List.of("crm-a1", "crm-a2")) {
            crm.node(n).systemIdentifier = "7000000000000000002";
            crm.node(n).vip = "192.168.0.60";
        }
        bi.primary("bi-a1", "10.0.3.1", "DC1");
        bi.replica("bi-a2", "10.0.3.2", "DC1", "10.0.3.1");
        for (String n : List.of("bi-a1", "bi-a2")) {
            bi.node(n).systemIdentifier = "7000000000000000003";
            bi.node(n).vip = "192.168.0.70";
        }
        AgentClientFactory agents = n -> erp.node(n.name()) != null ? erp.create(n)
                : crm.node(n.name()) != null ? crm.create(n) : bi.create(n);

        List<RaftStateStore.Peer> peers = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            peers.add(new RaftStateStore.Peer("w" + i, "127.0.0.1", freePort()));
        }
        for (RaftStateStore.Peer p : peers) {
            stores.add(new RaftStateStore(new RaftStateStore.Settings(p.id(), peers, dir.resolve(p.id() + "/raft"),
                    "bfm", Duration.ofSeconds(3), null, null)));
        }
        for (RaftStateStore s : stores) {
            s.start();
        }
        await("a leader", () -> leaderIndex() >= 0);

        // secret.key is copied from the first watcher to the others (docs/install.md), so any leader reads the CA.
        SecretBox.fromKeyFile(dir.resolve("w1/secret.key"));
        for (int i = 0; i < 3; i++) {
            Path data = dir.resolve("w" + (i + 1));
            if (i > 0) {
                Files.copy(dir.resolve("w1/secret.key"), data.resolve("secret.key"));
            }
            SecretBox secrets = SecretBox.fromKeyFile(data.resolve("secret.key"));
            ClusterHub hub = new ClusterHub(props(data), stores.get(i), agents, time, new MailNotifier(secrets),
                    secrets);
            hubs.add(hub);
            WatcherCa ca = new WatcherCa(stores.get(i), secrets, data.resolve("tmp"));
            EnrollmentService enrollment = new EnrollmentService(hub, ca, time);
            cas.add(ca);
            enrollments.add(enrollment);
            wizards.add(new ClusterWizardService(hub, enrollment));
        }
        await("every watcher runs both clusters", () -> {
            hubs.forEach(ClusterHub::refresh);
            return hubs.stream().allMatch(h -> h.names().equals(List.of("erp", "crm")));
        });
    }

    private int leaderIndex() {
        for (int i = 0; i < stores.size(); i++) {
            try {
                if (hubs.size() <= i || hubs.get(i) != null) {
                    if (stores.get(i).leadership().leader()) {
                        return i;
                    }
                }
            } catch (RuntimeException ignored) {
                // closed
            }
        }
        return -1;
    }

    private List<Watcher> engines() {
        List<Watcher> out = new ArrayList<>();
        for (ClusterHub h : hubs) {
            if (h != null) {
                h.clusters().forEach(c -> out.add(c.watcher()));
            }
        }
        return out;
    }

    private void tick(int rounds) {
        for (int i = 0; i < rounds; i++) {
            time.advance(2000);
            for (Watcher w : engines()) {
                w.tick();
            }
            long deadline = System.currentTimeMillis() + 15_000;
            while (engines().stream().anyMatch(w -> w.operations().stream()
                    .anyMatch(OperationProgress.Snapshot::running))) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("operation did not finish");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        }
    }

    private void tickUntil(String what, BooleanSupplier c) {
        for (int i = 0; i < 60 && !c.getAsBoolean(); i++) {
            tick(1);
        }
        assertTrue(c.getAsBoolean(), "timed out waiting for " + what);
    }

    private ClusterState state(int watcher, String cluster) {
        return hubs.get(watcher).require(cluster).watcher().state();
    }

    private long failovers(int watcher, String cluster) {
        return hubs.get(watcher).require(cluster).watcher().events(500).stream()
                .filter(e -> e.type() == EventType.FAILOVER_FINISHED).count();
    }

    @Test
    void threeWatchersRunSeveralClustersAndFailOverEachExactlyOnce() throws Exception {
        start();
        for (String c : List.of("erp", "crm")) {
            String id = hubs.get(0).require(c).id();
            for (ClusterHub h : hubs) {
                assertEquals(id, h.require(c).id(), "one id for " + c + " on every watcher");
            }
        }
        tickUntil("both clusters bootstrapped", () -> state(0, "erp") != null && state(0, "crm") != null);
        tick(2);
        for (int w = 0; w < 3; w++) {
            assertEquals("erp-a1", state(w, "erp").primary());
            assertEquals("crm-a1", state(w, "crm").primary());
        }
        assertEquals(hubs.get(0).require("erp").id(), erp.node("erp-a2").boundCluster);
        assertEquals(hubs.get(0).require("crm").id(), crm.node("crm-a2").boundCluster);
        long erpEpoch = state(0, "erp").epoch();
        long crmEpoch = state(0, "crm").epoch();

        // erp loses its primary: one failover in erp, nothing in crm.
        erp.crash("erp-a1");
        tickUntil("erp failed over", () -> "erp-a2".equals(state(1, "erp").primary())
                || "erp-a3".equals(state(1, "erp").primary()));
        tick(3);
        String erpPrimary = state(2, "erp").primary();
        assertEquals(erpEpoch + 1, state(2, "erp").epoch(), "exactly one failover in erp");
        assertEquals(PgRole.PRIMARY, erp.node(erpPrimary).role);
        for (int w = 0; w < 3; w++) {
            assertEquals(1, failovers(w, "erp"), "the same erp events on every watcher");
            assertEquals(0, failovers(w, "crm"), "crm never moved");
            assertEquals(crmEpoch, state(w, "crm").epoch());
        }
        assertEquals(PgRole.PRIMARY, crm.node("crm-a1").role);
        assertEquals(PgRole.REPLICA, crm.node("crm-a2").role);

        // The leading watcher dies; the other two elect a new leader that keeps running both clusters.
        int dead = leaderIndex();
        hubs.get(dead).close();
        stores.get(dead).close();
        hubs.set(dead, null);
        await("a new leader", () -> leaderIndex() >= 0 && leaderIndex() != dead);
        int live = leaderIndex();
        crm.crash("crm-a1");
        tickUntil("crm failed over under the new leader", () -> "crm-a2".equals(state(live, "crm").primary()));
        tick(3);
        assertEquals(crmEpoch + 1, state(live, "crm").epoch(), "exactly one failover in crm");
        assertEquals(1, failovers(live, "crm"));
        assertEquals(erpPrimary, state(live, "erp").primary(), "erp keeps its primary");
        assertEquals(1, failovers(live, "erp"));
        assertEquals(PgRole.PRIMARY, crm.node("crm-a2").role);
    }

    @Test
    void aClusterCreatedInTheConsoleRunsOnEveryWatcher() throws Exception {
        start();
        int lead = leaderIndex();
        int other = (lead + 1) % 3;
        // Followers send the wizards to the leader: they change the shared registry, so one watcher decides.
        NotLeaderException refused = assertThrows(NotLeaderException.class, () -> wizards.get(other)
                .createDraft("bi", "test", "admin"));
        assertEquals("w" + (lead + 1), refused.leaderId());
        ClusterWizardService.Elsewhere away = wizards.get(other).elsewhere();
        assertNotNull(away);
        assertEquals("w" + (lead + 1), away.leaderId());
        assertNull(wizards.get(lead).elsewhere());

        wizards.get(lead).createDraft("bi", "test", "admin");
        wizards.get(lead).saveNodes("bi", List.of(
                new ClusterSpec.NodeSpec("bi-a1", "https://10.0.3.1:8008", "10.0.3.1", 5432, "DC1", 100, false),
                new ClusterSpec.NodeSpec("bi-a2", "https://10.0.3.2:8008", "10.0.3.2", 5432, "DC1", 100, false)));
        // The CA is created on one watcher and kept in the shared state, encrypted with the copied secret.key, so
        // whichever watcher leads signs the agents' certificates.
        cas.get(other).create("BFM CA");
        String token = wizards.get(lead).token("bi", "bi-a1", "admin").token();
        Path p12 = dir.resolve("bi-a1.p12");
        Keytool.createKeyPair(p12, "changeit", "node", "bi-a1");
        EnrollmentService.Enrollment e = enrollments.get(lead).enroll(token, Keytool.certificateRequest(p12,
                "changeit", "node"), "db31", "3f2a");
        assertEquals(wizards.get(lead).draft("bi").id(), e.clusterId(), "the agent enrolls with the draft's id");
        assertTrue(Keytool.parse(e.certificate()).get(0).getSubjectX500Principal().getName().contains("CN=bi-a1"));
        assertEquals(1, wizards.get(other).drafts().size(), "a follower sees the draft in the shared state");
        assertTrue(hubs.get(other).names().equals(List.of("erp", "crm")), "a draft runs no engine");

        String id = wizards.get(lead).create("bi", ClusterWizardService.StartMode.ADOPT, null, "bi", "admin").id();
        await("every watcher runs bi", () -> {
            hubs.forEach(ClusterHub::refresh);
            return hubs.stream().allMatch(h -> h.names().equals(List.of("erp", "crm", "bi")));
        });
        for (ClusterHub h : hubs) {
            assertEquals(id, h.require("bi").id());
            assertTrue(h.require("bi").fromConsole());
        }
        tickUntil("bi bootstrapped", () -> state(other, "bi") != null && "bi-a1".equals(state(other, "bi").primary()));
        tickUntil("bi's agents bound", () -> id.equals(bi.node("bi-a1").boundCluster)
                && id.equals(bi.node("bi-a2").boundCluster));
        assertEquals("erp-a1", state(other, "erp").primary(), "the other clusters are untouched");

        // Detached on the leader: every watcher stops running it, the others stay.
        wizards.get(lead).detach("bi", "bi", "admin");
        await("bi gone everywhere", () -> {
            hubs.forEach(ClusterHub::refresh);
            return hubs.stream().allMatch(h -> h.names().equals(List.of("erp", "crm")));
        });
        assertEquals(PgRole.PRIMARY, bi.node("bi-a1").role, "PostgreSQL keeps running");
    }
}
