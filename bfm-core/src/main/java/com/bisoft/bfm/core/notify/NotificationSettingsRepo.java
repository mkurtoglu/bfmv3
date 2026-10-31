package com.bisoft.bfm.core.notify;

import com.bisoft.bfm.core.state.ClusterRegistry;
import com.bisoft.bfm.core.state.StateStore;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Reads and writes {@link NotificationSettings} in the shared store. With several clusters per watcher, recipients,
 * rules and thresholds belong to each cluster while the SMTP server is one for all of them: it is kept outside the
 * clusters' scopes, and a cluster's own copy (from before) is used only until the shared one is first saved.
 */
public final class NotificationSettingsRepo {

    /** The SMTP server every cluster of the watcher uses. */
    public static final String SMTP_KEY = ClusterRegistry.GLOBAL_PREFIX + "smtp";

    private final StateStore store;
    private final StateStore shared;

    public NotificationSettingsRepo(StateStore store) {
        this(store, null);
    }

    /**
     * @param store  the cluster's store
     * @param shared the store for settings every cluster shares, or null to keep everything in {@code store}
     */
    public NotificationSettingsRepo(StateStore store, StateStore shared) {
        this.store = store;
        this.shared = shared;
    }

    public NotificationSettings current() {
        NotificationSettings own = read(store.get(NotificationSettings.KEY));
        StateStore.Versioned g = shared == null ? null : shared.get(SMTP_KEY);
        return g == null ? own : own.withSmtp(read(g).smtp());
    }

    public NotificationSettings update(UnaryOperator<NotificationSettings> change) {
        for (int i = 0; i < 8; i++) {
            StateStore.Versioned v = store.get(NotificationSettings.KEY);
            StateStore.Versioned g = shared == null ? null : shared.get(SMTP_KEY);
            NotificationSettings own = read(v);
            NotificationSettings cur = g == null ? own : own.withSmtp(read(g).smtp());
            NotificationSettings next = change.apply(cur);
            if (shared != null && (g == null || !Objects.equals(cur.smtp(), next.smtp()))) {
                String smtp = NotificationSettings.defaults().withSmtp(next.smtp()).toJson();
                if (!shared.compareAndSet(SMTP_KEY, g == null ? 0 : g.version(), smtp)) {
                    continue;
                }
            }
            if (store.compareAndSet(NotificationSettings.KEY, v == null ? 0 : v.version(), next.toJson())) {
                return next;
            }
        }
        throw new IllegalStateException("notification settings keep changing; try again");
    }

    private static NotificationSettings read(StateStore.Versioned v) {
        if (v == null) {
            return NotificationSettings.defaults();
        }
        try {
            return NotificationSettings.fromJson(v.value());
        } catch (RuntimeException e) {
            return NotificationSettings.defaults();
        }
    }
}
