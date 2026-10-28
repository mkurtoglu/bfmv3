package io.pgvip.agent.pg;

public class PgException extends Exception {

    private final boolean connectionFailure;

    public PgException(String message, boolean connectionFailure) {
        super(message);
        this.connectionFailure = connectionFailure;
    }

    /** True when psql could not connect at all (server down, socket missing, too many clients). */
    public boolean connectionFailure() {
        return connectionFailure;
    }
}
