package com.bisoft.bfm.agent.pg;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgHelpersTest {

    @Test
    void parsesPsqlOutputWithNullsAndSeparators() {
        String out = "t" + Psql.FS + "\\N" + Psql.FS + "a|b" + Psql.RS + "f" + Psql.FS + "x" + Psql.FS + "" + "\n";
        List<List<String>> rows = Psql.parse(out);
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("t", null, "a|b"), rows.get(0));
        assertEquals(Arrays.asList("f", "x", ""), rows.get(1));
        assertTrue(Psql.parse("\n").isEmpty());
    }

    @Test
    void quotesConninfoValuesThatNeedIt() {
        assertEquals("host=10.0.0.1", PgController.kv("host", "10.0.0.1"));
        assertEquals("passfile='/var/lib/my dir/.pgpass'", PgController.kv("passfile", "/var/lib/my dir/.pgpass"));
        assertEquals("password='it\\'s'", PgController.kv("password", "it's"));
    }

    @Test
    void quotesConfValues() {
        assertEquals("'host=a password=''x'''", PgController.confLiteral("host=a password='x'"));
        assertEquals("'it''s'", Psql.literal("it's"));
        assertNull(PgProbe.parseInt("x"));
    }

    @Test
    void replicaTimelineUsesHistoryWhenTheReceiverIsGone() {
        assertEquals(Integer.valueOf(3), PgProbe.replicaTimeline(3, 1, "00000002.history"));
        assertEquals(Integer.valueOf(2), PgProbe.replicaTimeline(null, 1, "00000002.history"));
        assertEquals(Integer.valueOf(4), PgProbe.replicaTimeline(null, 4, "00000002.history"));
        assertEquals(Integer.valueOf(1), PgProbe.replicaTimeline(null, 1, null));
        assertEquals(Integer.valueOf(10), PgProbe.replicaTimeline(null, null, "0000000A.history"));
    }

    private static List<String> row(String... v) {
        return Arrays.asList(v);
    }

    @Test
    void oneProbeQueryGivesThePrimaryAndTheReplicaView() {
        // cloud test 2026-11-02: one psql per probe instead of four (about three new connections a second)
        PgSnapshot p = PgProbe.snapshot(List.of(
                row("base", "false", "160015", "7703427320212155677", null, null, null, null, null, null),
                row("rep", "db-1", "10.0.0.2", "streaming", "async", "0/7000060", "0/7000060", "0/7000060", "0/7000060", "4"),
                row("slot", "db_1", "physical", "true", "0/7000060", "0"),
                row("slot", "db_9", "physical", "false", null, null),
                row("wal", "0/7000060", "3", null, null, null, null, null, null, null)));
        assertEquals(com.bisoft.bfm.common.model.PgRole.PRIMARY, p.role());
        assertEquals(Integer.valueOf(3), p.timeline());
        assertEquals("0/7000060", p.currentLsn());
        assertEquals(Integer.valueOf(160015), p.serverVersionNum());
        assertEquals(1, p.replicas().size());
        assertEquals(Long.valueOf(4), p.replicas().get(0).replayLagMs());
        assertEquals(2, p.slots().size());
        assertTrue(p.slots().get(0).active());
        assertEquals(false, p.slots().get(1).active());

        PgSnapshot r = PgProbe.snapshot(List.of(
                row("base", "true", "160015", "7703427320212155677", null, null, null, null, null, null),
                row("rcv", "0/7000060", "0/7000060", "190", "streaming", "10.0.0.4", "5432", "3", "2", null),
                row("wal", null, null, null, null, null, null, null, null, null)));
        assertEquals(com.bisoft.bfm.common.model.PgRole.REPLICA, r.role());
        assertEquals("streaming", r.walReceiverStatus());
        assertEquals("10.0.0.4", r.upstreamHost());
        assertEquals(Integer.valueOf(5432), r.upstreamPort());
        assertEquals(Long.valueOf(190), r.replayDelayMs());
        assertEquals(Integer.valueOf(3), r.timeline(), "the received timeline wins");
        assertEquals(0, r.replicas().size());

        // primary gone: no WAL receiver, the newest history file is newer than the last restartpoint
        PgSnapshot cut = PgProbe.snapshot(List.of(
                row("base", "true", "160015", "7703427320212155677", null, null, null, null, null, null),
                row("rcv", "0/7000060", "0/7000060", null, null, null, null, null, "3", "00000004.history")));
        assertEquals(Integer.valueOf(4), cut.timeline());
        assertNull(cut.walReceiverStatus());
    }
}
