package io.pgvip.agent;

/** A command could not be carried out; the message goes back to the watcher as is. */
public class AgentException extends RuntimeException {

    private final int httpStatus;

    public AgentException(String message) {
        this(500, message);
    }

    public AgentException(int httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public static AgentException conflict(String message) {
        return new AgentException(409, message);
    }

    public static AgentException badRequest(String message) {
        return new AgentException(400, message);
    }
}
