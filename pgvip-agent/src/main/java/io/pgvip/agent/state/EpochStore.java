package io.pgvip.agent.state;

import io.pgvip.agent.AgentException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The highest cluster epoch this agent has seen, kept on disk.
 *
 * <p>Every role-changing command carries the epoch the watcher acted under. A command from an older epoch
 * comes from a watcher that has been superseded and is refused, so two watchers can never both steer a node.
 */
public class EpochStore {

    private final Path file;
    private long current;

    public EpochStore(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        this.file = stateDir.resolve("epoch");
        this.current = Files.exists(file) ? Long.parseLong(Files.readString(file).trim()) : 0L;
    }

    public synchronized long current() {
        return current;
    }

    /** Accepts {@code epoch} if it is not older than the stored one and remembers it. */
    public synchronized void accept(long epoch) {
        if (epoch < current) {
            throw AgentException.conflict("stale epoch " + epoch + " (agent is at " + current + ")");
        }
        if (epoch > current) {
            try {
                Path tmp = file.resolveSibling("epoch.tmp");
                Files.writeString(tmp, Long.toString(epoch), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new AgentException("cannot persist epoch: " + e.getMessage());
            }
            current = epoch;
        }
    }
}
