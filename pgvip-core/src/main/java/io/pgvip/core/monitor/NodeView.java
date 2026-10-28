package io.pgvip.core.monitor;

import io.pgvip.common.model.NodeStatus;
import io.pgvip.common.model.PgRole;
import io.pgvip.core.config.NodeConfig;

/**
 * What the watcher saw of one node in one poll.
 *
 * @param config           static node definition
 * @param status           the agent's report, null when the agent did not answer
 * @param error            why the agent did not answer
 * @param observedAtMillis wall-clock time of the poll
 * @param failures         consecutive polls without an answer
 */
public record NodeView(NodeConfig config, NodeStatus status, String error, long observedAtMillis, int failures) {

    public String name() {
        return config.name();
    }

    public boolean reachable() {
        return status != null;
    }

    public boolean runningPrimary() {
        return status != null && status.isPrimary();
    }

    public boolean runningReplica() {
        return status != null && status.isReplica();
    }

    public boolean fenced() {
        return status != null && status.fenced();
    }

    public boolean busy() {
        return status != null && status.busyOperation() != null;
    }

    public PgRole role() {
        return status == null ? PgRole.UNKNOWN : status.role();
    }

    /** Short state for the UI: Running, Streaming, Stopped, Fenced, Unreachable, ... */
    public String stateLabel() {
        if (status == null) {
            return "Agent unreachable";
        }
        if (status.fenced()) {
            return status.pgRunning() ? "Fenced (running)" : "Fenced";
        }
        if (status.busyOperation() != null) {
            return "Busy: " + status.busyOperation();
        }
        if (!status.pgRunning()) {
            return "Stopped";
        }
        if (status.role() == PgRole.PRIMARY) {
            return "Running";
        }
        if (status.role() == PgRole.REPLICA) {
            return status.streaming() ? "Streaming" : "Not streaming";
        }
        return "Not answering SQL";
    }
}
