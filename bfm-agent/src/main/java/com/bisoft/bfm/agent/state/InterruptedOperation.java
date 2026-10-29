package com.bisoft.bfm.agent.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers on disk that a rewind or a rebuild was rewriting the data directory, so an agent that was restarted
 * in the middle of it does not start, or rewind again, a data directory that may be half written. Only a
 * finished rebuild (pg_basebackup) clears it, since that is the one operation that starts from nothing.
 */
public class InterruptedOperation {

    private final Path file;
    private String name;

    public InterruptedOperation(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        this.file = stateDir.resolve("operation");
        this.name = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : null;
        if (name != null && name.isEmpty()) {
            name = "unknown";
        }
    }

    /** The operation that was rewriting the data directory, or null when it is intact. */
    public synchronized String name() {
        return name;
    }

    public synchronized boolean present() {
        return name != null;
    }

    /** Why the data directory must not be started or rewound now, or null when it may. */
    public synchronized String refusal() {
        return name == null ? null : name + " was interrupted; the data directory may be incomplete,"
                + " re-initialize this node";
    }

    public synchronized void begin(String operation) {
        try {
            Files.writeString(file, operation, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        name = operation;
    }

    public synchronized void end() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        name = null;
    }
}
