package io.pgvip.watcher.mail;

import io.pgvip.core.notify.MailMessage;
import io.pgvip.core.notify.NotificationSettings;
import io.pgvip.core.notify.SecretBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mail chain against a real SMTP dialogue: plain, authenticated, STARTTLS, and the failure paths. */
class MailNotifierTest {

    private static final MailMessage FAILOVER = new MailMessage(
            List.of("dba-team@example.com", "oncall@example.com"),
            "[pgVIP][prod-main] CRITICAL: Automatic failover pg-a1 -> pg-a2 completed",
            "Automatic failover pg-a1 -> pg-a2 completed\n\nCluster: prod-main\nNode:    pg-a2\n"
                    + "New primary: pg-a2 (192.168.0.27), timeline 4, epoch 15\n");

    private static NotificationSettings.Smtp smtp(int port, NotificationSettings.Security security, String user,
                                                  String secret) {
        return new NotificationSettings.Smtp("127.0.0.1", port, security, user, secret, "pgvip@example.com");
    }

    @Test
    void sendsTheMessageWithItsRecipientsSubjectAndBody(@TempDir Path dir) throws Exception {
        MailNotifier notifier = new MailNotifier(SecretBox.fromKeyFile(dir.resolve("secret.key")));
        try (FakeSmtpServer server = new FakeSmtpServer(null, null, null)) {
            notifier.send(smtp(server.port(), NotificationSettings.Security.NONE, null, null), FAILOVER);
            assertTrue(server.awaitMessage(10_000), "no message arrived: " + server.dialogue());

            FakeSmtpServer.Received m = server.received().get(0);
            assertEquals("pgvip@example.com", m.from());
            assertEquals(List.of("dba-team@example.com", "oncall@example.com"), m.recipients());
            assertEquals("[pgVIP][prod-main] CRITICAL: Automatic failover pg-a1 -> pg-a2 completed", m.header("Subject"));
            assertEquals("pgvip@example.com", m.header("From"));
            assertEquals("dba-team@example.com, oncall@example.com", m.header("To"));
            assertNotNull(m.header("Date"));
            assertTrue(m.body().contains("New primary: pg-a2 (192.168.0.27), timeline 4, epoch 15"), m.body());
            assertFalse(server.tlsUsed());
        }
    }

    @Test
    void authenticatesWithTheStoredPassword(@TempDir Path dir) throws Exception {
        SecretBox box = SecretBox.fromKeyFile(dir.resolve("secret.key"));
        MailNotifier notifier = new MailNotifier(box);
        try (FakeSmtpServer server = new FakeSmtpServer(null, "pgvip-alerts", "s3cret")) {
            notifier.send(smtp(server.port(), NotificationSettings.Security.NONE, "pgvip-alerts",
                    box.encrypt("s3cret")), FAILOVER);
            assertTrue(server.awaitMessage(10_000), "no message arrived: " + server.dialogue());
            assertTrue(server.dialogue().contains("  auth user=pgvip-alerts"), server.dialogue().toString());
            assertEquals(1, server.received().size());
        }
    }

    @Test
    void startTlsEncryptsTheSessionBeforeTheMessage(@TempDir Path dir) throws Exception {
        SSLContext tls = FakeSmtpServer.selfSignedContext(dir);
        SecretBox box = SecretBox.fromKeyFile(dir.resolve("secret.key"));
        MailNotifier notifier = new MailNotifier(box);
        try (FakeSmtpServer server = new FakeSmtpServer(tls, "pgvip-alerts", "s3cret")) {
            // The test certificate is not in the JVM's trust store; an operator with an internal CA points at
            // one with -Djavax.net.ssl.trustStore (see the README).
            String old = System.getProperty("javax.net.ssl.trustStore");
            System.setProperty("javax.net.ssl.trustStore", dir.resolve("smtp.p12").toString());
            System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            try {
                notifier.send(smtp(server.port(), NotificationSettings.Security.STARTTLS, "pgvip-alerts",
                        box.encrypt("s3cret")), FAILOVER);
            } finally {
                if (old == null) {
                    System.clearProperty("javax.net.ssl.trustStore");
                    System.clearProperty("javax.net.ssl.trustStorePassword");
                    System.clearProperty("javax.net.ssl.trustStoreType");
                } else {
                    System.setProperty("javax.net.ssl.trustStore", old);
                }
            }
            assertTrue(server.awaitMessage(20_000), "no message arrived: " + server.dialogue());
            assertTrue(server.tlsUsed(), "the session was not upgraded: " + server.dialogue());
            int starttls = server.dialogue().indexOf("> STARTTLS");
            int auth = indexOfPrefix(server.dialogue(), "  auth user=");
            assertTrue(starttls >= 0 && auth > starttls, "credentials were sent before TLS: " + server.dialogue());
            assertEquals("[pgVIP][prod-main] CRITICAL: Automatic failover pg-a1 -> pg-a2 completed",
                    server.received().get(0).header("Subject"));
        }
    }

    @Test
    void startTlsIsRequiredWhenTheServerDoesNotOfferIt(@TempDir Path dir) throws Exception {
        MailNotifier notifier = new MailNotifier(SecretBox.fromKeyFile(dir.resolve("secret.key")));
        try (FakeSmtpServer server = new FakeSmtpServer(null, null, null)) {
            // The settings ask for STARTTLS, the server has none: the mail must fail rather than go out in clear.
            Exception e = assertThrows(Exception.class, () -> notifier.send(
                    smtp(server.port(), NotificationSettings.Security.STARTTLS, null, null), FAILOVER));
            assertTrue(message(e).contains("STARTTLS"), message(e));
            assertTrue(server.received().isEmpty());
        }
    }

    @Test
    void reportsWrongCredentialsAndAnUnreachableServer(@TempDir Path dir) throws Exception {
        SecretBox box = SecretBox.fromKeyFile(dir.resolve("secret.key"));
        MailNotifier notifier = new MailNotifier(box);
        try (FakeSmtpServer server = new FakeSmtpServer(null, "pgvip-alerts", "s3cret")) {
            Exception e = assertThrows(Exception.class, () -> notifier.send(smtp(server.port(),
                    NotificationSettings.Security.NONE, "pgvip-alerts", box.encrypt("wrong")), FAILOVER));
            assertTrue(message(e).contains("authentication failed"), message(e));
            assertTrue(server.received().isEmpty());
        }
        // Nothing listening on this port.
        Exception e = assertThrows(Exception.class, () -> notifier.send(
                smtp(1, NotificationSettings.Security.NONE, null, null), FAILOVER));
        assertTrue(message(e).toLowerCase(java.util.Locale.ROOT).contains("connect"), message(e));

        IllegalStateException unset = assertThrows(IllegalStateException.class, () -> notifier.send(
                new NotificationSettings.Smtp(null, 25, NotificationSettings.Security.NONE, null, null, null),
                FAILOVER));
        assertTrue(unset.getMessage().contains("not set"));
    }

    private static int indexOfPrefix(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    /** The whole chain of messages; Jakarta Mail puts the server's answer in a nested cause. */
    private static String message(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append(" | ");
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }
}
