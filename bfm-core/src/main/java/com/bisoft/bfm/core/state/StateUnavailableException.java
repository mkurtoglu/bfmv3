package com.bisoft.bfm.core.state;

/** The shared state could not be read or written (for example, no Raft majority). */
public class StateUnavailableException extends RuntimeException {

    public StateUnavailableException(String message) {
        super(message);
    }

    public StateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
