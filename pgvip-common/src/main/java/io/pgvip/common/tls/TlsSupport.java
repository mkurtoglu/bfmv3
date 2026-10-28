package io.pgvip.common.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Builds the mutual-TLS context shared by agents and watchers.
 *
 * <p>Both stores are PKCS#12 files. The key store holds this process's certificate and private key;
 * the trust store holds the CA that signed every agent and watcher certificate. With
 * {@code needClientAuth} on the agent side, only holders of a certificate from that CA can call it.
 */
public final class TlsSupport {

    private TlsSupport() {
    }

    public static SSLContext context(Path keyStore, char[] keyStorePassword,
                                     Path trustStore, char[] trustStorePassword) {
        try {
            KeyManagerFactory kmf = null;
            if (keyStore != null) {
                KeyStore ks = load(keyStore, keyStorePassword);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, keyStorePassword);
            }
            TrustManagerFactory tmf = null;
            if (trustStore != null) {
                KeyStore ts = load(trustStore, trustStorePassword);
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ts);
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf == null ? null : kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot build TLS context: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(Path path, char[] password) throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, password);
        }
        return ks;
    }
}
