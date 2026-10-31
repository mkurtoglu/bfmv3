package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.state.ExclusiveLock;

import java.nio.file.Path;

/** Run in a separate JVM by the tests: holds a lock until its standard input closes. */
public final class LockHolder {

    private LockHolder() {
    }

    public static void main(String[] args) throws Exception {
        try (ExclusiveLock ignored = ExclusiveLock.acquire(Path.of(args[0]), "test lock")) {
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() >= 0) {
                // wait for the test to let go
            }
        }
    }
}
