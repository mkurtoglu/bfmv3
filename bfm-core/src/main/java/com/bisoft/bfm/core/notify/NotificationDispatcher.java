package com.bisoft.bfm.core.notify;

import com.bisoft.bfm.core.TimeSource;
import com.bisoft.bfm.core.event.ClusterEvent;
import com.bisoft.bfm.core.event.MailRule;
import com.bisoft.bfm.core.event.Severity;
import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.core.state.ClusterStateRepo;
import com.bisoft.bfm.core.state.StateStore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns events into mails: only on the leading watcher, only when mail is enabled for the cluster and for the
 * event's rule, and not again for the same event and node within the rule's throttle window.
 */
public class NotificationDispatcher implements Consumer<ClusterEvent> {

    private static final Logger LOG = Logger.getLogger(NotificationDispatcher.class.getName());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final String clusterName;
    private final StateStore store;
    private final ClusterStateRepo state;
    private final NotificationSettingsRepo settings;
    private final Notifier notifier;
    private final Executor executor;
    private final TimeSource time;
    private final ZoneId zone;
    private final Map<String, Long> lastSent = new HashMap<>();

    public NotificationDispatcher(String clusterName, StateStore store, NotificationSettingsRepo settings,
                                  Notifier notifier, Executor executor, TimeSource time, ZoneId zone) {
        this.clusterName = clusterName;
        this.store = store;
        this.state = new ClusterStateRepo(store);
        this.settings = settings;
        this.notifier = notifier;
        this.executor = executor;
        this.time = time;
        this.zone = zone;
    }

    @Override
    public void accept(ClusterEvent e) {
        MailMessage m = plan(e);
        if (m == null) {
            return;
        }
        NotificationSettings.Smtp smtp = settings.current().smtp();
        executor.execute(() -> {
            try {
                notifier.send(smtp, m);
                LOG.info("mail sent to " + m.to() + ": " + m.subject());
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "mail to " + m.to() + " failed: " + ex.getMessage());
            }
        });
    }

    /** The mail this event should produce, or null. Records the send time for throttling. */
    synchronized MailMessage plan(ClusterEvent e) {
        if (e.quiet()) {
            return null;
        }
        MailRule rule = e.type().rule();
        if (rule == null) {
            return null;
        }
        try {
            if (!store.leadership().leader()) {
                return null;
            }
            ClusterState cs = state.current();
            if (cs != null && !cs.mailEnabled()) {
                return null;
            }
        } catch (RuntimeException ex) {
            LOG.log(Level.FINE, "cannot read cluster state for mail", ex);
        }
        NotificationSettings s = settings.current();
        NotificationSettings.Rule r = s.rule(rule);
        if (!r.enabled() || !s.smtp().configured()) {
            return null;
        }
        List<String> to = recipients(s, e);
        if (to.isEmpty()) {
            return null;
        }
        String key = e.type() + "/" + e.node();
        Long last = lastSent.get(key);
        long now = time.millis();
        if (last != null && r.throttleMs() > 0 && now - last < r.throttleMs()) {
            return null;
        }
        lastSent.put(key, now);
        return render(e, to);
    }

    static List<String> recipients(NotificationSettings s, ClusterEvent e) {
        List<String> to = new ArrayList<>();
        for (NotificationSettings.Recipient rc : s.recipients()) {
            boolean match = switch (rc.filter()) {
                case ALL -> true;
                case CRITICAL -> e.severity() == Severity.CRITICAL;
                case ROLE_CHANGES -> e.type().roleChange();
            };
            if (match && rc.address() != null && !rc.address().isBlank()) {
                for (String a : rc.address().split("[,;\\s]+")) {
                    if (!a.isBlank() && !to.contains(a.trim())) {
                        to.add(a.trim());
                    }
                }
            }
        }
        return to;
    }

    public MailMessage render(ClusterEvent e, List<String> to) {
        String subject = "[BFM][" + clusterName + "] " + e.severity() + ": " + e.message();
        StringBuilder body = new StringBuilder();
        body.append(e.message()).append("\n\n");
        body.append("Cluster: ").append(clusterName).append('\n');
        if (e.node() != null) {
            body.append("Node:    ").append(e.node()).append('\n');
        }
        body.append("Time:    ").append(TIME.format(Instant.ofEpochMilli(e.timeMillis()).atZone(zone))).append('\n');
        body.append("By:      ").append(e.actor()).append('\n');
        if (e.detail() != null && !e.detail().isBlank()) {
            body.append('\n').append(e.detail().strip()).append('\n');
        }
        body.append("\nOpen the BFM console to follow up.\n");
        return new MailMessage(List.copyOf(to), subject, body.toString());
    }
}
