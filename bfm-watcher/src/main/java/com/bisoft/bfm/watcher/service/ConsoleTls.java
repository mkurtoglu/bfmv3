package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.common.tls.Keytool;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The console's own TLS certificate ({@code server.ssl.key-store}). Its fingerprint goes into the node setup
 * command, so a database host knows it talks to this watcher even when the console certificate is self-signed.
 */
public class ConsoleTls {

    private final Environment env;

    public ConsoleTls(Environment env) {
        this.env = env;
    }

    /** "SHA256:..." of the console certificate, or null when the console runs without TLS. */
    public String fingerprint() {
        String store = env.getProperty("server.ssl.key-store");
        if (store == null || store.isBlank() || !env.getProperty("server.ssl.enabled", Boolean.class, true)) {
            return null;
        }
        String path = store.startsWith("file:") ? store.substring(5) : store;
        String pw = env.getProperty("server.ssl.key-store-password", "");
        try {
            return Keytool.fingerprint(Keytool.firstCertificate(Path.of(path), pw.toCharArray()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
