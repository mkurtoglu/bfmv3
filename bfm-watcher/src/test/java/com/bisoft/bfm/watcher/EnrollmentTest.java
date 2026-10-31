package com.bisoft.bfm.watcher;

import com.bisoft.bfm.common.tls.Keytool;
import com.bisoft.bfm.core.FakeCluster;
import com.bisoft.bfm.core.ManualTime;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.core.state.LocalStateStore;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.mail.MailNotifier;
import com.bisoft.bfm.watcher.service.ClusterHub;
import com.bisoft.bfm.watcher.service.EnrollmentService;
import com.bisoft.bfm.watcher.service.WatcherCa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-time enrollment: a token buys one certificate for one node, signed by the watchers' CA. */
class EnrollmentTest {

    @TempDir
    Path dir;
    private final ManualTime time = new ManualTime();
    private final LocalStateStore store = LocalStateStore.inMemory("w1");
    private ClusterHub hub;
    private WatcherCa ca;
    private EnrollmentService enrollment;

    @BeforeEach
    void setUp() {
        Map<String, String> m = Map.of(
                "bfm.data-dir", dir.toString(),
                "bfm.cluster.name", "crm",
                "bfm.cluster.nodes[0].name", "crm-a1",
                "bfm.cluster.nodes[0].agent-url", "https://10.0.0.11:8009",
                "bfm.cluster.nodes[0].pg-host", "10.0.0.11",
                "bfm.cluster.nodes[0].pg-port", "5433",
                "bfm.cluster.nodes[1].name", "crm-a2",
                "bfm.cluster.nodes[1].agent-url", "https://db02.example.com:8009",
                "bfm.cluster.nodes[1].pg-host", "10.0.0.12",
                "bfm.cluster.nodes[1].pg-port", "5433");
        BfmProperties props = new Binder(new MapConfigurationPropertySource(m)).bind("bfm", BfmProperties.class)
                .get();
        SecretBox secrets = SecretBox.fromKeyFile(dir.resolve("secret.key"));
        hub = new ClusterHub(props, store, new FakeCluster(time), time, new MailNotifier(secrets), secrets);
        ca = new WatcherCa(store, secrets, dir.resolve("tmp"));
        enrollment = new EnrollmentService(hub, ca, time);
    }

    @AfterEach
    void tearDown() {
        hub.close();
    }

    /** What minipgagent setup does on the host: a key pair named after the node, and a request for it. */
    private String csr(String node) {
        Path p12 = dir.resolve(node + "-" + System.nanoTime() + ".p12");
        Keytool.createKeyPair(p12, "changeit", "node", node);
        return Keytool.certificateRequest(p12, "changeit", "node");
    }

    @Test
    void aTokenBuysOneCertificateForItsNode() throws Exception {
        assertThrows(IllegalStateException.class, () -> enrollment.issue(hub.require("crm"), "crm-a2", "admin"),
                "no CA, no enrollment");
        ca.create("BFM CA");
        EnrollmentService.Issued t = enrollment.issue(hub.require("crm"), "crm-a2", "admin");
        assertEquals(22, t.token().length(), "128 random bits");
        assertFalse(store.getPrefix(EnrollmentService.PREFIX).values().stream()
                .anyMatch(v -> v.value().contains(t.token())), "only its hash is stored");

        EnrollmentService.Enrollment e = enrollment.enroll(t.token(), csr("crm-a2"), "db02", "3f2a");
        assertEquals("crm", e.cluster());
        assertEquals(hub.require("crm").id(), e.clusterId());
        assertEquals(8009, e.agentPort());
        assertEquals(5433, e.pgPort());
        assertEquals(List.of("crm-a1"), e.peers().stream().map(EnrollmentService.Peer::name).toList());
        List<X509Certificate> chain = Keytool.parse(e.certificate());
        assertEquals(2, chain.size(), "leaf and CA");
        X509Certificate leaf = chain.get(0);
        assertTrue(leaf.getSubjectX500Principal().getName().contains("CN=crm-a2"));
        assertTrue(leaf.getSubjectAlternativeNames().stream().anyMatch(n -> n.get(1).equals("db02.example.com")),
                "the address the watcher reaches the agent at");
        assertTrue(leaf.getSubjectAlternativeNames().stream().anyMatch(n -> n.get(1).equals("10.0.0.12")));
        leaf.verify(chain.get(1).getPublicKey());
        assertTrue(hub.require("crm").watcher().events(10).stream().anyMatch(ev -> ev.message()
                .contains("crm-a2 enrolled from db02 (machine-id 3f2a)")));

        EnrollmentService.EnrollmentException again = assertThrows(EnrollmentService.EnrollmentException.class,
                () -> enrollment.enroll(t.token(), csr("crm-a2"), "elsewhere", null));
        assertTrue(again.getMessage().contains("already used"));
        assertTrue(hub.require("crm").watcher().events(10).stream().anyMatch(ev -> ev.type()
                == EventType.WRONG_IDENTITY && ev.message().contains("already used")));
    }

    @Test
    void aTokenIsOnlyForItsNodeAndOnlyFor30Minutes() {
        ca.create("BFM CA");
        EnrollmentService.Issued t = enrollment.issue(hub.require("crm"), "crm-a1", "admin");
        EnrollmentService.EnrollmentException wrong = assertThrows(EnrollmentService.EnrollmentException.class,
                () -> enrollment.enroll(t.token(), csr("crm-a2"), "db02", null));
        assertTrue(wrong.getMessage().contains("not for crm-a1"), wrong.getMessage());

        EnrollmentService.Issued late = enrollment.issue(hub.require("crm"), "crm-a1", "admin");
        time.advance(EnrollmentService.VALID_MS + 1);
        assertTrue(assertThrows(EnrollmentService.EnrollmentException.class,
                () -> enrollment.enroll(late.token(), csr("crm-a1"), "db01", null)).getMessage().contains("expired"));
        assertTrue(assertThrows(EnrollmentService.EnrollmentException.class,
                () -> enrollment.enroll("not-a-token", csr("crm-a1"), "db01", null)).getMessage()
                .contains("unknown token"));
        assertThrows(IllegalArgumentException.class, () -> enrollment.issue(hub.require("crm"), "crm-a9", "admin"));
    }

    @Test
    void theCaCanBeTakenOverFromAStore() {
        Path p12 = dir.resolve("ca-import.p12");
        Keytool.createCa(p12, "pw-123456", "Site CA");
        String pem = ca.importStore(p12, "pw-123456");
        assertTrue(ca.exists());
        assertTrue(Keytool.parse(pem).get(0).getSubjectX500Principal().getName().contains("Site CA"));
        assertThrows(IllegalStateException.class, () -> ca.create("again"), "one CA per watcher group");
    }
}
