package com.bisoft.bfm.core.agent;

/**
 * An agent call that did not succeed.
 *
 * <p>{@link #reachable()} tells the two cases apart: false means the agent could not be reached (connection
 * refused, timeout, TLS failure), true means the agent answered and refused or failed the command.
 */
public class AgentCallException extends RuntimeException {

    private final String node;
    private final int httpStatus;

    public AgentCallException(String node, int httpStatus, String message) {
        super(message);
        this.node = node;
        this.httpStatus = httpStatus;
    }

    public AgentCallException(String node, String message, Throwable cause) {
        super(message, cause);
        this.node = node;
        this.httpStatus = 0;
    }

    public static AgentCallException unreachable(String node, String message) {
        return new AgentCallException(node, 0, message);
    }

    public String node() {
        return node;
    }

    /** HTTP status of the agent's answer, 0 when it did not answer. */
    public int httpStatus() {
        return httpStatus;
    }

    public boolean reachable() {
        return httpStatus != 0;
    }

    @Override
    public String getMessage() {
        return node + ": " + super.getMessage();
    }

    /** The agent's own message without the node prefix. */
    public String detail() {
        return super.getMessage();
    }
}
