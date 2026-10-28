package io.pgvip.core;

/** A command was sent to a watcher that does not lead the cluster. */
public class NotLeaderException extends RuntimeException {

    private final String leaderId;

    public NotLeaderException(String leaderId) {
        super(leaderId == null ? "no watcher leads the cluster right now"
                : "this watcher does not lead the cluster; watcher " + leaderId + " does");
        this.leaderId = leaderId;
    }

    public String leaderId() {
        return leaderId;
    }
}
