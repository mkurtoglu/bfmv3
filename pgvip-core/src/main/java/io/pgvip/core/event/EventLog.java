package io.pgvip.core.event;

import io.pgvip.core.TimeSource;
import io.pgvip.core.state.StateStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes events to the shared store (so every watcher's UI shows the same list) and hands them to listeners
 * such as the mail notifier.
 */
public class EventLog {

    private static final Logger LOG = Logger.getLogger(EventLog.class.getName());

    private final StateStore store;
    private final TimeSource time;
    private final List<Consumer<ClusterEvent>> listeners = new CopyOnWriteArrayList<>();

    public EventLog(StateStore store, TimeSource time) {
        this.store = store;
        this.time = time;
    }

    public void addListener(Consumer<ClusterEvent> listener) {
        listeners.add(listener);
    }

    public ClusterEvent publish(EventType type, String node, String message, String actor) {
        return publish(type, node, message, actor, null);
    }

    public ClusterEvent publish(EventType type, String node, String message, String actor, String detail) {
        return publish(type, node, message, actor, detail, false);
    }

    public ClusterEvent publish(EventType type, String node, String message, String actor, String detail,
                                boolean quiet) {
        ClusterEvent e = new ClusterEvent(UUID.randomUUID().toString(), time.millis(), type, type.severity(), node,
                message, actor, detail, quiet);
        Level level = switch (type.severity()) {
            case CRITICAL -> Level.SEVERE;
            case WARNING -> Level.WARNING;
            case INFO -> Level.INFO;
        };
        LOG.log(level, "[" + type + "] " + (node == null ? "" : node + ": ") + message);
        try {
            store.appendEvent(e.toJson());
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "cannot store event: " + ex.getMessage());
        }
        for (Consumer<ClusterEvent> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "event listener failed", ex);
            }
        }
        return e;
    }

    /** Newest first. */
    public List<ClusterEvent> recent(int limit) {
        List<ClusterEvent> out = new ArrayList<>();
        for (String json : store.events(limit)) {
            try {
                out.add(ClusterEvent.fromJson(json));
            } catch (RuntimeException ex) {
                LOG.log(Level.FINE, "skipping unreadable event", ex);
            }
        }
        return out;
    }
}
