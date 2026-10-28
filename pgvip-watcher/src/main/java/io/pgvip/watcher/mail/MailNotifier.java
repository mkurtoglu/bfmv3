package io.pgvip.watcher.mail;

import io.pgvip.core.notify.MailMessage;
import io.pgvip.core.notify.NotificationSettings;
import io.pgvip.core.notify.Notifier;
import io.pgvip.core.notify.SecretBox;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/** Sends mail through the SMTP server configured on the notifications screen. */
@Component
public class MailNotifier implements Notifier {

    private final SecretBox secrets;

    public MailNotifier(SecretBox secrets) {
        this.secrets = secrets;
    }

    @Override
    public void send(NotificationSettings.Smtp smtp, MailMessage message) {
        if (!smtp.configured()) {
            throw new IllegalStateException("SMTP host and from address are not set");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        switch (smtp.security()) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
            case SMTPS -> {
                sender.setProtocol("smtps");
                p.put("mail.smtps.ssl.checkserveridentity", "true");
            }
            case NONE -> {
            }
        }
        if (smtp.user() != null && !smtp.user().isBlank()) {
            sender.setUsername(smtp.user());
            sender.setPassword(secrets.decrypt(smtp.passwordSecret()));
            p.put(smtp.security() == NotificationSettings.Security.SMTPS ? "mail.smtps.auth" : "mail.smtp.auth", "true");
        }
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(smtp.from());
        m.setTo(message.to().toArray(String[]::new));
        m.setSubject(message.subject());
        m.setText(message.body());
        sender.send(m);
    }
}
