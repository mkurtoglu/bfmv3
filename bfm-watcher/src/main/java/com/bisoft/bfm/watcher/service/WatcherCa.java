package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.tls.Keytool;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.core.state.ClusterRegistry;
import com.bisoft.bfm.core.state.StateStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The CA that signs agent certificates at enrollment. Its key store lives in the shared store, encrypted with the
 * watcher's {@code secret.key}, so with Raft any leader can sign; on disk it exists only for the moment keytool
 * needs it, in a private temporary file.
 *
 * <p>A site that keeps its CA offline ({@code bfm-make-certs}) never creates one here and enrolls nothing: its node
 * setup takes a certificate bundle instead.
 */
public class WatcherCa {

    public static final String KEY = ClusterRegistry.GLOBAL_PREFIX + "ca";
    public static final String CERT_KEY = ClusterRegistry.GLOBAL_PREFIX + "ca-cert";

    private final StateStore root;
    private final SecretBox secrets;
    private final Path workDir;

    public WatcherCa(StateStore root, SecretBox secrets, Path workDir) {
        this.root = root;
        this.secrets = secrets;
        this.workDir = workDir;
    }

    public boolean exists() {
        return root.get(KEY) != null;
    }

    /** The CA certificate (PEM) agents trust, or null when there is no CA here. */
    public String certificatePem() {
        StateStore.Versioned v = root.get(CERT_KEY);
        return v == null ? null : v.value();
    }

    public X509Certificate certificate() {
        String pem = certificatePem();
        return pem == null ? null : Keytool.parse(pem).get(0);
    }

    /** Creates the CA; refuses when one exists (replacing it would orphan every enrolled agent). */
    public synchronized String create(String name) {
        if (exists()) {
            throw new IllegalStateException("this watcher group already has a CA");
        }
        String password = Keytool.randomPassword();
        Path dir = privateDir();
        Path store = dir.resolve("ca.p12");
        try {
            Keytool.createCa(store, password, name);
            String pem = Keytool.exportCertificate(store, password, "bfm-ca");
            save(store, password, pem);
            return pem;
        } finally {
            delete(dir);
        }
    }

    /**
     * Takes over an existing CA (the one {@code bfm-make-certs} made: {@code ca.crt} and {@code ca.key}), so agents
     * certified the old way and enrolled ones trust the same watchers. Needs {@code openssl}.
     */
    public synchronized String importCa(Path crt, Path key) {
        if (exists()) {
            throw new IllegalStateException("this watcher group already has a CA");
        }
        String password = Keytool.randomPassword();
        Path dir = privateDir();
        Path store = dir.resolve("ca.p12");
        try {
            ProcessBuilder p = new ProcessBuilder("openssl", "pkcs12", "-export", "-name", "bfm-ca", "-inkey",
                    key.toString(), "-in", crt.toString(), "-out", store.toString(), "-passout", "env:BFM_CA_PASS")
                    .redirectErrorStream(true);
            p.environment().put("BFM_CA_PASS", password);
            Process run = p.start();
            String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!run.waitFor(60, TimeUnit.SECONDS) || run.exitValue() != 0) {
                throw new IllegalStateException("openssl could not read the CA: " + out.trim());
            }
            String pem = Keytool.exportCertificate(store, password, "bfm-ca");
            save(store, password, pem);
            return pem;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        } finally {
            delete(dir);
        }
    }

    /**
     * Takes over a CA from a PKCS#12 store with the alias {@code bfm-ca} (what {@code bfm-watcher setup} writes for
     * the first start).
     */
    public synchronized String importStore(Path p12, String password) {
        if (exists()) {
            throw new IllegalStateException("this watcher group already has a CA");
        }
        String pem = Keytool.exportCertificate(p12, password, "bfm-ca");
        save(p12, password, pem);
        return pem;
    }

    /** PEM certificate and key (as {@code bfm-make-certs} writes them), through temporary files. */
    public synchronized String importPem(String certificatePem, String keyPem) {
        Path dir = privateDir();
        try {
            Path crt = dir.resolve("ca.crt");
            Path key = dir.resolve("ca.key");
            Files.writeString(crt, certificatePem, StandardCharsets.UTF_8);
            Files.writeString(key, keyPem, StandardCharsets.UTF_8);
            return importCa(crt, key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(dir);
        }
    }

    private void save(Path store, String password, String pem) {
        try {
            String blob = JsonMap.create().put("p12", Base64.getEncoder().encodeToString(Files.readAllBytes(store)))
                    .put("password", password).toJson();
            if (!root.compareAndSet(KEY, 0, secrets.encrypt(blob))) {
                throw new IllegalStateException("another watcher created a CA at the same time");
            }
            root.put(CERT_KEY, pem);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The common name a setup that does not know its node name yet puts in its certificate request. */
    public static final String PLACEHOLDER_CN = "minipgagent";

    /**
     * Signs a certificate request for {@code commonName}. The request may name that node or the placeholder of a
     * setup that learns its node name only now; the certificate always names the node.
     *
     * @param altNames keytool syntax, for example {@code DNS:db01,IP:10.0.0.11}
     * @return the certificate followed by the CA certificate, PEM
     */
    public String sign(String csrPem, String commonName, String altNames) {
        StateStore.Versioned v = root.get(KEY);
        if (v == null) {
            throw new IllegalStateException("this watcher has no CA: enrollment is off (offline certificates)");
        }
        JsonMap blob = Json.parseObject(secrets.decrypt(v.value()));
        Path dir = privateDir();
        try {
            String cn = Keytool.requestCommonName(csrPem, dir);
            if (!cn.equals(commonName) && !cn.equals(PLACEHOLDER_CN)) {
                throw new IllegalArgumentException("the certificate request is for " + cn + ", not for " + commonName);
            }
            Path store = dir.resolve("ca.p12");
            Files.write(store, Base64.getDecoder().decode(blob.requireStr("p12")));
            String leaf = Keytool.sign(store, blob.requireStr("password"), csrPem, altNames, commonName, dir);
            return leaf.trim() + "\n" + certificatePem();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(dir);
        }
    }

    /** Fingerprints of the CA, for the console. */
    public List<String> describe() {
        X509Certificate c = certificate();
        return c == null ? List.of() : List.of(c.getSubjectX500Principal().getName(), Keytool.fingerprint(c),
                "valid until " + c.getNotAfter().toInstant());
    }

    private Path privateDir() {
        try {
            Files.createDirectories(workDir);
            return Files.createTempDirectory(workDir, "ca", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path dir) {
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // a temporary directory under the data directory; the next run does not depend on it
        }
    }
}
