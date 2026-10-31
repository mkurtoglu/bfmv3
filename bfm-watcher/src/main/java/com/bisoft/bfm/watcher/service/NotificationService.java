package com.bisoft.bfm.watcher.service;

import com.bisoft.bfm.core.Watcher;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.EventType;
import com.bisoft.bfm.core.event.MailRule;
import com.bisoft.bfm.core.notify.MailMessage;
import com.bisoft.bfm.core.notify.NotificationDispatcher;
import com.bisoft.bfm.core.notify.NotificationSettings;
import com.bisoft.bfm.core.notify.SecretBox;
import com.bisoft.bfm.watcher.mail.MailNotifier;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and changes one cluster's mail settings; the SMTP password is stored encrypted and never returned. The SMTP
 * server is shared by every cluster of the watcher, recipients, rules and thresholds are the cluster's.
 */
public class NotificationService {

    private final Watcher watcher;
    private final SecretBox secrets;
    private final MailNotifier notifier;
    private final ZoneId zone;

    public NotificationService(Watcher watcher, SecretBox secrets, MailNotifier notifier, ZoneId zone) {
        this.watcher = watcher;
        this.secrets = secrets;
        this.notifier = notifier;
        this.zone = zone;
    }

    /** SMTP settings as shown to users: the password only as "set" or not. */
    public record SmtpView(String host, int port, NotificationSettings.Security security, String user,
                           boolean passwordSet, String from) {
    }

    public record SettingsView(SmtpView smtp, List<NotificationSettings.Recipient> recipients,
                               Map<MailRule, NotificationSettings.Rule> rules, long lagAlertBytes,
                               long slotAlertBytes) {
    }

    /**
     * A change of the SMTP settings.
     *
     * @param password new password; null keeps the stored one, an empty string removes it
     */
    public record SmtpChange(String host, int port, NotificationSettings.Security security, String user,
                             String password, String from) {
    }

    public NotificationSettings current() {
        return watcher.notificationSettings().current();
    }

    public SettingsView view() {
        NotificationSettings s = current();
        NotificationSettings.Smtp m = s.smtp();
        return new SettingsView(new SmtpView(m.host(), m.port(), m.security(), m.user(),
                m.passwordSecret() != null && !m.passwordSecret().isEmpty(), m.from()), s.recipients(), s.rules(),
                s.lagAlertBytes(), s.slotAlertBytes());
    }

    public void saveSmtp(SmtpChange c, String actor) {
        if (c.port() <= 0 || c.port() > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        watcher.notificationSettings().update(s -> {
            String secret = c.password() == null ? s.smtp().passwordSecret()
                    : c.password().isEmpty() ? null : secrets.encrypt(c.password());
            return s.withSmtp(new NotificationSettings.Smtp(blank(c.host()), c.port(),
                    c.security() == null ? NotificationSettings.Security.STARTTLS : c.security(), blank(c.user()),
                    secret, blank(c.from())));
        });
        audit("SMTP settings changed by " + actor, actor);
    }

    public void saveRecipients(List<NotificationSettings.Recipient> recipients, String actor) {
        List<NotificationSettings.Recipient> clean = new ArrayList<>();
        for (NotificationSettings.Recipient r : recipients) {
            if (r.address() == null || r.address().isBlank()) {
                throw new IllegalArgumentException("recipient " + r.name() + " has no address");
            }
            clean.add(new NotificationSettings.Recipient(r.name() == null || r.name().isBlank() ? r.address()
                    : r.name().trim(), r.address().trim(), r.filter() == null ? NotificationSettings.Filter.ALL
                    : r.filter()));
        }
        watcher.notificationSettings().update(s -> s.withRecipients(clean));
        audit("Mail recipients changed by " + actor, actor);
    }

    public void saveRules(Map<MailRule, NotificationSettings.Rule> rules, String actor) {
        watcher.notificationSettings().update(s -> s.withRules(rules));
        audit("Mail rules changed by " + actor, actor);
    }

    public void saveThresholds(long lagBytes, long slotBytes, String actor) {
        if (lagBytes <= 0 || slotBytes <= 0) {
            throw new IllegalArgumentException("thresholds must be positive");
        }
        watcher.notificationSettings().update(s -> s.withThresholds(lagBytes, slotBytes));
        audit("Alert thresholds changed by " + actor + ": lag " + com.bisoft.bfm.common.Lsn.bytes(lagBytes) + ", slot "
                + com.bisoft.bfm.common.Lsn.bytes(slotBytes), actor);
    }

    /** Sends a test mail to every recipient now; throws with the SMTP error when it fails. */
    public List<String> sendTest(String actor) throws Exception {
        NotificationSettings s = current();
        List<String> to = new ArrayList<>();
        for (NotificationSettings.Recipient r : s.recipients()) {
            to.add(r.address());
        }
        if (to.isEmpty()) {
            throw new IllegalArgumentException("add a recipient first");
        }
        notifier.send(s.smtp(), new MailMessage(to, "[BFM][" + watcher.config().name() + "] Test mail",
                "This is a test mail from BFM watcher " + watcher.store().selfId() + ", sent by " + actor + "."));
        audit("Test mail sent to " + String.join(", ", to) + " by " + actor, actor);
        return to;
    }

    /** What the mail for a failover would look like with the current settings. */
    public MailMessage preview() {
        NotificationSettings s = current();
        ClusterEvent e = new ClusterEvent("preview", System.currentTimeMillis(), EventType.FAILOVER_FINISHED,
                EventType.FAILOVER_FINISHED.severity(), "pg-a2", "Automatic failover pg-a1 -> pg-a2 completed",
                "watcher " + watcher.store().selfId(), "New primary: pg-a2 (192.168.0.27), timeline 4, epoch 15\n"
                + "VIP 192.168.0.50 moved to pg-a2, GARP sent\n"
                + "Data loss check: pg-a2 had received the last WAL position seen on pg-a1\n"
                + "Next step: pg-a1 rejoins with pg_rewind when it comes back.", false);
        List<String> to = new ArrayList<>();
        for (NotificationSettings.Recipient r : s.recipients()) {
            to.add(r.address());
        }
        return new NotificationDispatcher(watcher.config().name(), watcher.store(), watcher.notificationSettings(),
                notifier, Runnable::run, com.bisoft.bfm.core.TimeSource.SYSTEM, zone).render(e, to);
    }

    private void audit(String message, String actor) {
        watcher.eventLog().publish(EventType.CONTROL_CHANGED, null, message, actor);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
