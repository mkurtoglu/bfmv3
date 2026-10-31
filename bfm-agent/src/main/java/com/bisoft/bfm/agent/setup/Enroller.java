package com.bisoft.bfm.agent.setup;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonException;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.tls.Keytool;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

/**
 * Talks to the watcher's console during setup. The watcher is recognised by the fingerprint of its certificate,
 * which the console printed in the setup command: a self-signed console certificate works, and nothing else
 * answering at that address does.
 */
public final class Enroller {

    private Enroller() {
    }

    /** Thrown with a message for the operator. */
    public static final class EnrollException extends RuntimeException {
        public EnrollException(String message) {
            super(message);
        }
    }

    /** "SHA256:4B:9E..." and "4b9e..." compare equal. */
    static String normalize(String fingerprint) {
        String f = fingerprint.trim().toLowerCase(Locale.ROOT);
        if (f.startsWith("sha256:")) {
            f = f.substring(7);
        }
        return f.replace(":", "");
    }

    /**
     * Posts the token and the certificate request; returns the watcher's answer (cluster, node, certificate ...).
     *
     * @param fingerprint the console certificate's SHA-256 fingerprint; null only with {@code insecureHttp}
     */
    public static JsonMap enroll(String baseUrl, String fingerprint, boolean insecureHttp, JsonMap request) {
        URI uri;
        try {
            uri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/v1/enroll");
        } catch (IllegalArgumentException e) {
            throw new EnrollException("bad watcher address " + baseUrl);
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !insecureHttp) {
            throw new EnrollException("the watcher address is not https; add --insecure-http only in a lab");
        }
        if (https && (fingerprint == null || fingerprint.isBlank())) {
            throw new EnrollException("give the console certificate's fingerprint (--fingerprint), as the console"
                    + " printed it");
        }
        try {
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
            if (c instanceof HttpsURLConnection s) {
                s.setSSLSocketFactory(pinned(fingerprint).getSocketFactory());
                // The pinned fingerprint names the watcher; the host name in its certificate need not match.
                s.setHostnameVerifier((host, session) -> true);
            }
            c.setConnectTimeout(10_000);
            c.setReadTimeout(60_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(request.toJson().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            String body;
            try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) {
                body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonMap m;
            try {
                m = body.isBlank() ? JsonMap.create() : Json.parseObject(body);
            } catch (JsonException e) {
                throw new EnrollException("the watcher answered " + status + " with no JSON");
            }
            if (status >= 300) {
                throw new EnrollException("the watcher refused: " + m.str("error", "HTTP " + status));
            }
            return m;
        } catch (javax.net.ssl.SSLHandshakeException e) {
            throw new EnrollException("TLS with the watcher failed: " + rootMessage(e));
        } catch (IOException e) {
            throw new EnrollException("cannot reach the watcher at " + baseUrl + ": " + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? e.toString() : t.getMessage();
    }

    static SSLContext pinned(String fingerprint) {
        String want = normalize(fingerprint);
        X509TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                String got = Keytool.fingerprint(chain[0]);
                if (!normalize(got).equals(want)) {
                    throw new CertificateException("the watcher presents " + got + ", not the fingerprint the console"
                            + " printed: this is not that watcher, or its certificate changed");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
