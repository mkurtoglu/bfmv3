package com.bisoft.bfm.watcher;

import com.bisoft.bfm.common.tls.Keytool;
import com.bisoft.bfm.watcher.config.BfmProperties;
import com.bisoft.bfm.watcher.setup.WatcherSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** bfm-watcher setup: the files a first start needs, without hand-editing. */
class WatcherSetupTest {

    @TempDir
    Path dir;

    private int setup(String answers) throws Exception {
        Path a = dir.resolve("answers");
        Files.writeString(a, answers);
        return WatcherSetup.main(new String[]{"--answers", a.toString(), "--etc", dir.resolve("etc").toString(),
                "--no-systemd"});
    }

    @Test
    void writesASettingsFileTheWatcherStartsWithAndACaForEnrollment() throws Exception {
        assertEquals(0, setup("watcher-id=w1\ndata-dir=" + dir.resolve("data") + "\ntime-zone=UTC\nport=8443\n"
                + "names=bfm.example.com,10.0.9.1\nadmin=admin\nadmin-password=a-long-password\ncertificates=ca\n"
                + "peers=w1=10.0.9.1,w2=10.0.9.2,w3=10.0.9.3\nproceed=yes\n"));
        Path etc = dir.resolve("etc");
        String yml = Files.readString(etc.resolve("watcher.yml"));
        assertTrue(yml.contains("group-name: bfm"), "a Raft group's name never depends on its clusters");

        // The file binds to the same properties the watcher reads.
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", new Yaml().load(yml), flat);
        BfmProperties p = new Binder(new MapConfigurationPropertySource(flat)).bind("bfm", BfmProperties.class).get();
        assertEquals("w1", p.watcherId());
        assertTrue(p.allClusters().isEmpty(), "clusters come later");
        assertEquals(3, p.raft().peers().size());
        assertTrue(PasswordEncoderFactories.createDelegatingPasswordEncoder().matches("a-long-password",
                p.users().get(0).passwordHash()));
        assertFalse(yml.contains("a-long-password"));

        String pass = Files.readString(etc.resolve("w1.pass")).trim();
        X509Certificate console = Keytool.firstCertificate(etc.resolve("w1.p12"), pass.toCharArray());
        assertTrue(console.getSubjectX500Principal().getName().contains("CN=w1"));
        assertTrue(console.getSubjectAlternativeNames().stream().anyMatch(n -> n.get(1).equals("bfm.example.com")));
        assertTrue(Files.readString(etc.resolve("watcher.env")).contains("BFM_KEYSTORE_PASSWORD=" + pass));
        assertTrue(Files.exists(etc.resolve("w2.p12")) && Files.exists(etc.resolve("w3.p12")),
                "the other watchers' certificates, ready to copy");
        assertTrue(Files.exists(dir.resolve("data/ca-import.p12")), "the CA waits for the first start");
        assertTrue(Files.exists(dir.resolve("data/secret.key")));
    }

    @Test
    void saysThatASingleWatchersStateDoesNotMoveIntoARaftGroup() throws Exception {
        // cloud test 2026-11-02: setup on a watcher that already ran alone started an empty group without a word,
        // and its review said "create secret.key" for the key copied from the first watcher
        Path data = dir.resolve("data");
        Files.createDirectories(data);
        Files.writeString(data.resolve("state.json"), "{}");
        java.security.SecureRandom r = new java.security.SecureRandom();
        byte[] key = new byte[32];
        r.nextBytes(key);
        Files.writeString(data.resolve("secret.key"), java.util.Base64.getEncoder().encodeToString(key));
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.out;
        int code;
        try {
            System.setOut(new java.io.PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
            code = setup("watcher-id=w1\ndata-dir=" + data + "\ntime-zone=UTC\nport=8443\nnames=10.0.9.1\n"
                    + "admin=admin\nadmin-password=a-long-password\ncertificates=ca\n"
                    + "peers=w1=10.0.9.1,w2=10.0.9.2,w3=10.0.9.3\nproceed=yes\n");
        } finally {
            System.setOut(old);
        }
        String out = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertTrue(out.contains("does not move into the Raft group"), out);
        assertTrue(out.contains("keep    " + data.resolve("secret.key")), out);
    }

    @Test
    void refusesARaftGroupOfTwoAndAShortPassword() throws Exception {
        assertEquals(1, setup("data-dir=" + dir.resolve("d") + "\nadmin-password=a-long-password\n"
                + "peers=w1=a,w2=b\nproceed=yes\n"));
        assertEquals(1, setup("data-dir=" + dir.resolve("d") + "\nadmin-password=short\nproceed=yes\n"));
        assertFalse(Files.exists(dir.resolve("etc/watcher.yml")));
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object v, Map<String, String> out) {
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> flatten(prefix.isEmpty() ? k.toString() : prefix + "." + k, x, out));
        } else if (v instanceof java.util.List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                flatten(prefix + "[" + i + "]", l.get(i), out);
            }
        } else if (v != null) {
            out.put(prefix, v.toString());
        }
    }

    @Test
    void thePackagedExampleIsNotAConfigurationButAnEarlierOneIs() throws Exception {
        Path example = Files.writeString(dir.resolve("watcher.yml.example"), "server:\n  port: 8443\n");
        Path yml = dir.resolve("watcher.yml");
        assertFalse(WatcherSetup.isConfiguration(yml, example), "nothing there");
        Files.copy(example, yml);
        assertFalse(WatcherSetup.isConfiguration(yml, example), "the example the package put there");
        Files.writeString(yml, "server:\n  port: 9443\n");
        assertTrue(WatcherSetup.isConfiguration(yml, example), "settings kept from an earlier installation");
        assertTrue(WatcherSetup.isConfiguration(yml, dir.resolve("absent")), "no example to compare: a lab");
    }
}
