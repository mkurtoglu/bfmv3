package com.bisoft.bfm.common.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Certificates with the JDK's own {@code keytool}: a CA, key pairs, certificate requests and signing. No library
 * beyond the JDK is needed, on the watcher or on a database host.
 *
 * <p>Every call runs keytool from the running JVM's {@code java.home}; passwords go on the command line of a
 * short-lived child process owned by the same user, never into logs.
 */
public final class Keytool {

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----");

    private Keytool() {
    }

    /** keytool failed; the message carries its output. */
    public static final class KeytoolException extends RuntimeException {
        public KeytoolException(String message) {
            super(message);
        }
    }

    static Path binary() {
        Path p = Path.of(System.getProperty("java.home"), "bin", "keytool");
        return Files.isExecutable(p) ? p : Path.of("keytool");
    }

    /** Runs keytool and returns what it printed. */
    public static String run(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary().toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new KeytoolException("keytool " + args[0] + " did not finish");
            }
            if (p.exitValue() != 0) {
                throw new KeytoolException("keytool " + args[0] + " failed: " + out.trim());
            }
            return out;
        } catch (IOException e) {
            throw new KeytoolException("cannot run keytool: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KeytoolException("interrupted");
        }
    }

    /** A new CA in a PKCS#12 store: RSA 3072, valid ten years, allowed to sign leaf certificates only. */
    public static void createCa(Path store, String password, String name) {
        run("-genkeypair", "-alias", "bfm-ca", "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
                "-dname", "CN=" + name, "-ext", "bc:c=ca:true,pathlen:0", "-ext", "ku:c=keyCertSign,cRLSign",
                "-validity", "3650", "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password);
    }

    /** A new RSA 3072 key pair under {@code alias}, with a placeholder certificate until the signed one comes. */
    public static void createKeyPair(Path store, String password, String alias, String commonName) {
        run("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA",
                "-dname", "CN=" + commonName, "-validity", "2", "-keystore", store.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password);
    }

    /** A certificate request (PEM) for the key under {@code alias}. */
    public static String certificateRequest(Path store, String password, String alias) {
        String out = run("-certreq", "-alias", alias, "-keystore", store.toString(), "-storepass", password);
        int start = out.indexOf("-----BEGIN");
        if (start < 0) {
            throw new KeytoolException("keytool printed no certificate request");
        }
        return out.substring(start).trim() + "\n";
    }

    /** The subject's common name of a certificate request (PEM), checked before it is signed. */
    public static String requestCommonName(String csrPem, Path workDir) throws IOException {
        Path f = Files.createTempFile(workDir, "req", ".pem");
        try {
            Files.writeString(f, csrPem, StandardCharsets.UTF_8);
            String out = run("-printcertreq", "-file", f.toString());
            Matcher m = Pattern.compile("Subject:\\s*CN=([^,\\n]+)").matcher(out);
            if (!m.find()) {
                throw new KeytoolException("the certificate request names no CN");
            }
            return m.group(1).trim();
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /**
     * Signs a certificate request with the CA in {@code caStore}: a server and client certificate valid 825 days.
     *
     * @param altNames    the subject alternative names, keytool syntax ({@code DNS:db01,IP:10.0.0.11})
     * @param commonName  the certificate's common name, whatever the request asked for; null keeps the request's
     * @return the signed certificate, PEM
     */
    public static String sign(Path caStore, String password, String csrPem, String altNames, String commonName,
                              Path workDir) throws IOException {
        Path in = Files.createTempFile(workDir, "req", ".pem");
        Path out = Files.createTempFile(workDir, "crt", ".pem");
        try {
            Files.writeString(in, csrPem, StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>(List.of("-gencert", "-alias", "bfm-ca", "-keystore", caStore.toString(),
                    "-storepass", password, "-infile", in.toString(), "-outfile", out.toString(), "-rfc",
                    "-validity", "825", "-sigalg", "SHA256withRSA", "-ext", "EKU=serverAuth,clientAuth",
                    "-ext", "KU=digitalSignature,keyEncipherment"));
            if (altNames != null && !altNames.isBlank()) {
                args.add("-ext");
                args.add("SAN=" + altNames);
            }
            if (commonName != null) {
                args.add("-dname");
                args.add("CN=" + commonName);
            }
            run(args.toArray(String[]::new));
            return Files.readString(out, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(in);
            Files.deleteIfExists(out);
        }
    }

    /** The CA's certificate, PEM. */
    public static String exportCertificate(Path store, String password, String alias) {
        return run("-exportcert", "-rfc", "-alias", alias, "-keystore", store.toString(), "-storepass", password)
                .trim() + "\n";
    }

    /** Imports a trusted certificate (PEM) under {@code alias}; creates the store when missing. */
    public static void importTrusted(Path store, String password, String alias, String pem, Path workDir)
            throws IOException {
        Path f = Files.createTempFile(workDir, "ca", ".pem");
        try {
            Files.writeString(f, pem, StandardCharsets.UTF_8);
            run("-importcert", "-noprompt", "-alias", alias, "-file", f.toString(), "-keystore", store.toString(),
                    "-storetype", "PKCS12", "-storepass", password);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /**
     * Replaces the placeholder certificate of {@code alias} with its signed chain (leaf first, then the CA). keytool
     * checks that the chain matches the key and ends in a trusted CA.
     */
    public static void importChain(Path store, String password, String alias, String chainPem, Path workDir)
            throws IOException {
        Path f = Files.createTempFile(workDir, "chain", ".pem");
        try {
            Files.writeString(f, chainPem, StandardCharsets.UTF_8);
            run("-importcert", "-noprompt", "-trustcacerts", "-alias", alias, "-file", f.toString(),
                    "-keystore", store.toString(), "-storepass", password);
        } finally {
            Files.deleteIfExists(f);
        }
    }

    /** Every certificate in a PEM text, in order. */
    public static List<X509Certificate> parse(String pem) {
        List<X509Certificate> out = new ArrayList<>();
        Matcher m = PEM.matcher(pem);
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            while (m.find()) {
                out.add((X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(
                        m.group().getBytes(StandardCharsets.US_ASCII))));
            }
        } catch (CertificateException e) {
            throw new KeytoolException("unreadable certificate: " + e.getMessage());
        }
        return out;
    }

    /** "SHA256:4b:9e:..." of a certificate, as browsers and the setup command show it. */
    public static String fingerprint(Certificate cert) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return "SHA256:" + HexFormat.ofDelimiter(":").withLowerCase().formatHex(d);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first certificate of a PKCS#12 store, for its fingerprint. */
    public static X509Certificate firstCertificate(Path store, char[] password) throws IOException {
        try (InputStream in = Files.newInputStream(store)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            var aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                Certificate c = ks.getCertificate(aliases.nextElement());
                if (c instanceof X509Certificate x) {
                    return x;
                }
            }
            throw new IOException("no certificate in " + store);
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("cannot read " + store + ": " + e.getMessage(), e);
        }
    }

    /** A random password for a key store nobody types. */
    public static String randomPassword() {
        byte[] b = new byte[18];
        new java.security.SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
