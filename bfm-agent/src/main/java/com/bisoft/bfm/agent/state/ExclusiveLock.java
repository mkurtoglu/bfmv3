package com.bisoft.bfm.agent.state;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * An exclusive lock on a file, held for the life of the agent process. Two agents must never share one state
 * directory (they would share an epoch, a fence flag and a lease), and only one agent per host may drive the
 * hardware watchdog (its reset takes every instance on the host down).
 */
public final class ExclusiveLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private ExclusiveLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * @param what what the lock protects, for the error message
     * @throws IllegalStateException when another process (or this one) holds it
     */
    public static ExclusiveLock acquire(Path file, String what) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.READ);
        FileLock l;
        try {
            l = ch.tryLock();
        } catch (OverlappingFileLockException e) {
            l = null;
        }
        if (l == null) {
            String holder = readHolder(ch);
            ch.close();
            throw new IllegalStateException(what + " is in use" + (holder.isEmpty() ? "" : " by PID " + holder)
                    + " (" + file + "); another agent instance is already running with it");
        }
        ch.truncate(0);
        ch.write(java.nio.ByteBuffer.wrap((ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.UTF_8)), 0);
        ch.force(false);
        return new ExclusiveLock(ch, l);
    }

    private static String readHolder(FileChannel ch) {
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(32);
            int n = ch.read(b, 0);
            return n <= 0 ? "" : new String(b.array(), 0, n, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
    }
}
