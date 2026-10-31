package com.bisoft.bfm.core.agent;

import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.core.config.NodeConfig;
import com.bisoft.bfm.core.state.ClusterIdentity;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The certificate name check through a real TLS handshake with the JDK HTTP client: the agent of another node
 * behind a mistyped port is refused before any request reaches it, and reported as a wrong identity rather than
 * as an unreachable node.
 */
class NodeNameTrustManagerTest {

    private static final String PASS = "changeit";

    @TempDir
    static Path dir;
    private static HttpsServer server;
    private static String url;
    private static KeyStore trust;
    private static int requests;

    @BeforeAll
    static void startAgent() throws Exception {
        Path store = dir.resolve("pg-b1.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "pg-b1", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1",
                "-dname", "CN=pg-b1", "-ext", "SAN=IP:127.0.0.1",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + out);
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, PASS.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASS.toCharArray());
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);

        trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        Certificate cert = ks.getCertificate("pg-b1");
        trust.setCertificateEntry("pg-b1", cert);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        server.createContext("/", ex -> {
            synchronized (NodeNameTrustManagerTest.class) {
                requests++;
            }
            byte[] b = new NodeStatus("pg-b1", "test", 1, true, PgRole.PRIMARY, 1, "7", 160004, "0/1", null, null,
                    null, null, null, null, List.of(), List.of(), null, false, 1, null, false, null, null, null, null,
                    null, null, null).toJson().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        url = "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopAgent() {
        server.stop(0);
    }

    private static AgentClient client(String node) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        NodeConfig n = NodeConfig.of(node, url, "127.0.0.1", 5432, "DC1");
        TrustManager[] tms = NodeNameTrustManager.wrap(tmf.getTrustManagers(), List.of(n));
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tms, null);
        return HttpAgentClient.factory(ctx, null, Duration.ofSeconds(5), Duration.ofSeconds(5)).create(n);
    }

    private static synchronized int requests() {
        return requests;
    }

    @Test
    void theRightNodesAgentIsAccepted() throws Exception {
        assertEquals("pg-b1", client("pg-b1").status().node());
    }

    @Test
    void anotherNodesAgentIsRefusedInTheHandshakeAsAWrongIdentity() throws Exception {
        int before = requests();
        AgentClient c = client("pg-a2");
        AgentCallException e = assertThrows(AgentCallException.class, () -> c.checkpoint(4));
        assertTrue(e.detail().startsWith(ClusterIdentity.WRONG_IDENTITY), e.detail());
        assertTrue(e.detail().contains("presents a certificate for pg-b1, not for node pg-a2"), e.detail());
        assertFalse(e.reachable());
        assertEquals(before, requests(), "the command never reached the other agent");
        AgentCallException s = assertThrows(AgentCallException.class, c::status);
        assertTrue(s.detail().startsWith(ClusterIdentity.WRONG_IDENTITY), s.detail());
    }

    @Test
    void readsTheCommonNameAndTheDnsNames() throws Exception {
        X509Certificate cert = (X509Certificate) trust.getCertificate("pg-b1");
        assertEquals(List.of("pg-b1"), List.copyOf(NodeNameTrustManager.names(cert)));
    }
}
