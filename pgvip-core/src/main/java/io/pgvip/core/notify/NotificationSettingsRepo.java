package io.pgvip.core.notify;

import io.pgvip.core.state.StateStore;

import java.util.function.UnaryOperator;

/** Reads and writes {@link NotificationSettings} in the shared store. */
public final class NotificationSettingsRepo {

    private final StateStore store;

    public NotificationSettingsRepo(StateStore store) {
        this.store = store;
    }

    public NotificationSettings current() {
        StateStore.Versioned v = store.get(NotificationSettings.KEY);
        if (v == null) {
            return NotificationSettings.defaults();
        }
        try {
            return NotificationSettings.fromJson(v.value());
        } catch (RuntimeException e) {
            return NotificationSettings.defaults();
        }
    }

    public NotificationSettings update(UnaryOperator<NotificationSettings> change) {
        for (int i = 0; i < 8; i++) {
            StateStore.Versioned v = store.get(NotificationSettings.KEY);
            NotificationSettings cur = v == null ? NotificationSettings.defaults()
                    : NotificationSettings.fromJson(v.value());
            NotificationSettings next = change.apply(cur);
            if (store.compareAndSet(NotificationSettings.KEY, v == null ? 0 : v.version(), next.toJson())) {
                return next;
            }
        }
        throw new IllegalStateException("notification settings keep changing; try again");
    }
}
