package com.bisoft.bfm.agent.state;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Remembers on disk that this node fenced itself, so a restarted agent does not forget it.
 * A fenced node may start again only as a replica.
 */
public class FenceState {

    private final Path file;
    private String reason;

    public FenceState(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        this.file = stateDir.resolve("fenced");
        this.reason = Files.exists(file) ? Files.readString(file).trim() : null;
    }

    public synchronized boolean fenced() {
        return reason != null;
    }

    public synchronized String reason() {
        return reason;
    }

    public synchronized void set(String why) {
        try {
            Files.writeString(file, why, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reason = why;
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        reason = null;
    }
}
