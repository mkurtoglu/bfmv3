package io.pgvip.watcher.mail;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A small SMTP server for the tests: enough of the protocol for Jakarta Mail to deliver a message, with optional
 * STARTTLS and AUTH LOGIN. It records the whole dialogue so a test can check what the watcher actually sent.
 */
final class FakeSmtpServer implements AutoCloseable {

    /** One delivered message. */
    record Received(String from, List<String> recipients, String data) {
        String header(String name) {
            String prefix = name + ":";
            StringBuilder value = new StringBuilder();
            boolean in = false;
            for (String line : data.split("\r\n")) {
                if (line.isEmpty() && in) {
                    break;
                }
                if (in && (line.startsWith(" ") || line.startsWith("\t"))) {
                    value.append(line);               // folded line: unfolding keeps the leading whitespace
                    continue;
                }
                if (in) {
                    break;
                }
                if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    in = true;
                    value.append(line.substring(prefix.length()).trim());
                }
            }
            return in ? value.toString() : null;
        }

        String body() {
            int blank = data.indexOf("\r\n\r\n");
            return blank < 0 ? "" : data.substring(blank + 4).replace("\r\n", "\n");
        }
    }

    private final ServerSocket server;
    private final Thread thread;
    private final SSLContext tls;
    private final String user;
    private final String password;
    private final boolean offerStartTls;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<String> dialogue = new CopyOnWriteArrayList<>();
    private final CountDownLatch delivered = new CountDownLatch(1);
    private volatile boolean tlsUsed;
    private volatile boolean stopping;

    /**
     * @param tls      context for STARTTLS, or null to refuse it
     * @param user     expected user name, or null when the server wants no authentication
     * @param password expected password
     */
    FakeSmtpServer(SSLContext tls, String user, String password) throws IOException {
        this.tls = tls;
        this.offerStartTls = tls != null;
        this.user = user;
        this.password = password;
        this.server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        this.server.setSoTimeout(30_000);
        this.thread = new Thread(this::acceptLoop, "fake-smtp");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    int port() {
        return server.getLocalPort();
    }

    List<Received> received() {
        return received;
    }

    List<String> dialogue() {
        return dialogue;
    }

    boolean tlsUsed() {
        return tlsUsed;
    }

    boolean awaitMessage(long millis) throws InterruptedException {
        return delivered.await(millis, TimeUnit.MILLISECONDS);
    }

    /** A TLS context from a throw-away certificate made with the JDK's keytool. */
    static SSLContext selfSignedContext(Path dir) throws Exception {
        Path store = dir.resolve("smtp.p12");
        String pass = "changeit";
        if (!Files.exists(store)) {
            Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                    "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost,IP:127.0.0.1",
                    "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", pass, "-keypass", pass)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + out);
            }
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, pass.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private void acceptLoop() {
        while (!stopping) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(30_000);
                session(socket);
            } catch (IOException e) {
                if (!stopping) {
                    dialogue.add("! " + e);
                }
                return;
            }
        }
    }

    private void session(Socket plain) throws IOException {
        Socket socket = plain;
        BufferedReader in = reader(socket);
        OutputStream out = socket.getOutputStream();
        say(out, "220 fake-smtp ESMTP ready");
        String from = null;
        List<String> rcpts = new ArrayList<>();
        boolean authenticated = user == null;
        while (true) {
            String line = in.readLine();
            if (line == null) {
                return;
            }
            dialogue.add("> " + (line.length() > 120 ? line.substring(0, 120) + "..." : line));
            String upper = line.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                // The first line of a multiline EHLO reply is the greeting, not a capability.
                List<String> caps = new ArrayList<>();
                caps.add("fake-smtp greets you");
                if (offerStartTls && socket == plain) {
                    caps.add("STARTTLS");
                }
                if (user != null) {
                    caps.add("AUTH LOGIN");
                }
                caps.add("8BITMIME");
                for (int i = 0; i < caps.size(); i++) {
                    say(out, "250" + (i == caps.size() - 1 ? " " : "-") + caps.get(i));
                }
            } else if (upper.startsWith("STARTTLS")) {
                if (!offerStartTls) {
                    say(out, "454 TLS not available");
                    continue;
                }
                say(out, "220 ready to start TLS");
                SSLSocket ssl = (SSLSocket) tls.getSocketFactory()
                        .createSocket(socket, null, socket.getPort(), false);
                ssl.setUseClientMode(false);
                ssl.setSoTimeout(30_000);
                ssl.startHandshake();
                tlsUsed = true;
                socket = ssl;
                in = reader(socket);
                out = socket.getOutputStream();
            } else if (upper.startsWith("AUTH LOGIN")) {
                say(out, "334 " + b64("Username:"));
                String u = unb64(in.readLine());
                say(out, "334 " + b64("Password:"));
                String pw = unb64(in.readLine());
                dialogue.add("  auth user=" + u);
                if (u.equals(user) && pw.equals(password)) {
                    authenticated = true;
                    say(out, "235 2.7.0 authentication succeeded");
                } else {
                    say(out, "535 5.7.8 authentication failed");
                }
            } else if (upper.startsWith("MAIL FROM")) {
                if (!authenticated) {
                    say(out, "530 5.7.0 authentication required");
                    continue;
                }
                from = between(line, '<', '>');
                rcpts = new ArrayList<>();
                say(out, "250 2.1.0 sender ok");
            } else if (upper.startsWith("RCPT TO")) {
                rcpts.add(between(line, '<', '>'));
                say(out, "250 2.1.5 recipient ok");
            } else if (upper.startsWith("DATA")) {
                say(out, "354 end with <CRLF>.<CRLF>");
                StringBuilder data = new StringBuilder();
                String l;
                while ((l = in.readLine()) != null && !l.equals(".")) {
                    data.append(l.startsWith("..") ? l.substring(1) : l).append("\r\n");
                }
                received.add(new Received(from, List.copyOf(rcpts), data.toString()));
                say(out, "250 2.0.0 queued");
                delivered.countDown();
            } else if (upper.startsWith("QUIT")) {
                say(out, "221 2.0.0 bye");
                return;
            } else if (upper.startsWith("RSET") || upper.startsWith("NOOP")) {
                say(out, "250 2.0.0 ok");
            } else {
                say(out, "502 5.5.2 not implemented");
            }
        }
    }

    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }

    private void say(OutputStream out, String line) throws IOException {
        dialogue.add("< " + line);
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return s == null ? "" : new String(Base64.getDecoder().decode(s.trim()), StandardCharsets.UTF_8);
    }

    private static String between(String line, char open, char close) {
        int a = line.indexOf(open);
        int b = line.lastIndexOf(close);
        return a >= 0 && b > a ? line.substring(a + 1, b) : line;
    }

    @Override
    public void close() {
        stopping = true;
        try {
            server.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        thread.interrupt();
    }
}
