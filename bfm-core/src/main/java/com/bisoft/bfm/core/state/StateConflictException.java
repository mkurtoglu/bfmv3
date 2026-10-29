package com.bisoft.bfm.core.state;

/** A state change was refused because its precondition no longer holds. */
public class StateConflictException extends RuntimeException {

    public StateConflictException(String message) {
        super(message);
    }
}
