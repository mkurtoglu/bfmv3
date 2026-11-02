package com.bisoft.bfm.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent writes only the synchronous_standby_names values the watcher builds from node names. */
class SyncStandbysTest {

    @Test
    void acceptsWhatTheWatcherWrites() {
        assertTrue(SyncStandbys.valid(""));
        assertTrue(SyncStandbys.valid("FIRST 1 (\"br-a1\")"));
        assertTrue(SyncStandbys.valid("ANY 1 (\"br-a1\", \"br-a2\")"));
        assertTrue(SyncStandbys.valid("ANY 2 (\"pg_a1\", \"pg-a2\", \"pg3\")"));
    }

    @Test
    void refusesAnythingElse() {
        assertFalse(SyncStandbys.valid(null));
        assertFalse(SyncStandbys.valid("*"));
        assertFalse(SyncStandbys.valid("FIRST 2 (\"a\", \"b\")"));
        assertFalse(SyncStandbys.valid("ANY 0 (\"a\")"));
        assertFalse(SyncStandbys.valid("ANY 1 (\"A\")"));
        assertFalse(SyncStandbys.valid("ANY 1 (\"a\"); DROP TABLE t"));
        assertFalse(SyncStandbys.valid("ANY 1 (\"a'\")"));
        assertFalse(SyncStandbys.valid("ANY 1 (a)"));
    }
}
