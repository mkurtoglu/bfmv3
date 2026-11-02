package com.bisoft.bfm.agent.pg;

import com.bisoft.bfm.agent.AgentConfig;
import com.bisoft.bfm.agent.exec.CommandTemplate;
import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;
import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.common.model.ReplicationPeer;
import com.bisoft.bfm.common.model.SlotInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the state of the local PostgreSQL: role, timeline, WAL positions, replication and slots. */
public class PgProbe {

    private static final int T = 5;

    private final AgentConfig cfg;
    private final ProcessRunner runner;
    private final Psql psql;

    public PgProbe(AgentConfig cfg, ProcessRunner runner, Psql psql) {
        this.cfg = cfg;
        this.runner = runner;
        this.psql = psql;
    }

    /** pg_ctl status: exit 0 = running, 3 = not running, 4 = no data directory. */
    public boolean isRunning() {
        ProcessResult r = runner.run(CommandTemplate.expand(cfg.pgStatusCommand, placeholders()),
                Duration.ofSeconds(cfg.commandTimeoutSec));
        return r.ok();
    }

    public ControlData controlData() {
        ProcessResult r = runner.run(
                List.of(cfg.pgBin.resolve("pg_controldata").toString(), "-D", cfg.pgData.toString()),
                Duration.ofSeconds(cfg.commandTimeoutSec), Map.of("LC_ALL", "C", "LANG", "C"));
        if (!r.ok()) {
            return ControlData.UNKNOWN;
        }
        return ControlData.parse(r.stdout());
    }

    /**
     * Everything one probe reads, in a single statement and so a single psql process and connection per probe (it
     * used to be four on a primary: about three new connections a second per node; cloud test 2026-11-02, user decision
     * "one psql per probe"). Every row starts with its kind; functions that fail or cost during recovery, or on a
     * primary, sit behind CASE so they are only evaluated where they apply. Ten text columns.
     */
    static final String PROBE_SQL = "SELECT 'base', pg_is_in_recovery()::text, current_setting('server_version_num'),"
            + " (SELECT system_identifier::text FROM pg_control_system()),"
            + " current_setting('synchronous_standby_names'), NULL, NULL, NULL, NULL, NULL"
            + " UNION ALL SELECT 'wal',"
            + " CASE WHEN pg_is_in_recovery() THEN NULL ELSE pg_current_wal_lsn()::text END,"
            + " CASE WHEN pg_is_in_recovery() THEN NULL"
            + " ELSE ('x' || substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8))::bit(32)::int::text END,"
            + " NULL, NULL, NULL, NULL, NULL, NULL, NULL"
            + " UNION ALL SELECT 'rep', application_name, coalesce(host(client_addr), ''), state, sync_state,"
            + " sent_lsn::text, write_lsn::text, flush_lsn::text, replay_lsn::text,"
            + " (extract(epoch FROM replay_lag) * 1000)::bigint::text FROM pg_stat_replication"
            + " UNION ALL SELECT 'slot', slot_name::text, slot_type, active::text, restart_lsn::text,"
            + " CASE WHEN restart_lsn IS NULL OR pg_is_in_recovery() THEN NULL"
            + " ELSE pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint::text END, NULL, NULL, NULL, NULL"
            + " FROM pg_replication_slots"
            + " UNION ALL SELECT 'rcv', pg_last_wal_receive_lsn()::text, pg_last_wal_replay_lsn()::text,"
            + " CASE WHEN pg_last_xact_replay_timestamp() IS NULL THEN NULL"
            + " ELSE (extract(epoch FROM now() - pg_last_xact_replay_timestamp()) * 1000)::bigint::text END,"
            + " (SELECT status FROM pg_stat_wal_receiver), (SELECT sender_host FROM pg_stat_wal_receiver),"
            + " (SELECT sender_port FROM pg_stat_wal_receiver)::text,"
            + " (SELECT received_tli FROM pg_stat_wal_receiver)::text,"
            + " (SELECT timeline_id FROM pg_control_checkpoint())::text,"
            + " CASE WHEN pg_is_in_recovery()"
            + " THEN (SELECT max(name) FROM pg_ls_waldir() WHERE name ~ '^[0-9A-F]{8}[.]history$') END"
            + " WHERE pg_is_in_recovery()"
            + " ORDER BY 1, 2";

    public PgSnapshot collect() {
        if (!isRunning()) {
            return PgSnapshot.stopped(controlData(), null);
        }
        try {
            return snapshot(psql.query(PROBE_SQL, T));
        } catch (PgException | RuntimeException e) {
            return new PgSnapshot(true, PgRole.UNKNOWN, null, null, null, null, null, null, null, null, null, null,
                    List.of(), List.of(), ControlData.UNKNOWN, e.getMessage());
        }
    }

    /** The snapshot of a running server from the rows of {@link #PROBE_SQL}. */
    static PgSnapshot snapshot(List<List<String>> rows) {
        List<String> base = null;
        List<String> wal = null;
        List<String> rcv = null;
        List<ReplicationPeer> peers = new ArrayList<>();
        List<SlotInfo> slots = new ArrayList<>();
        for (List<String> r : rows) {
            switch (r.get(0)) {
                case "base" -> base = r;
                case "wal" -> wal = r;
                case "rcv" -> rcv = r;
                case "rep" -> peers.add(new ReplicationPeer(r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6),
                        r.get(7), r.get(8), parseLong(r.get(9))));
                case "slot" -> slots.add(new SlotInfo(r.get(1), r.get(2), truth(r.get(3)), r.get(4),
                        parseLong(r.get(5))));
                default -> {
                    // a kind of a newer agent: ignored
                }
            }
        }
        if (base == null) {
            throw new IllegalStateException("the probe returned no base row");
        }
        boolean inRecovery = truth(base.get(1));
        Integer version = parseInt(base.get(2));
        String sysId = base.get(3);
        // "" when none is set; null from an older row layout
        String syncNames = base.size() > 4 ? base.get(4) : null;
        if (!inRecovery) {
            return new PgSnapshot(true, PgRole.PRIMARY, wal == null ? null : parseInt(wal.get(2)), sysId, version,
                    wal == null ? null : wal.get(1), null, null, null, null, null, null, peers, slots,
                    ControlData.UNKNOWN, null, syncNames == null ? null : syncNames);
        }
        List<String> r = rcv == null ? java.util.Arrays.asList(new String[10]) : rcv;
        Integer timeline = replicaTimeline(parseInt(r.get(7)), parseInt(r.get(8)), r.get(9));
        // a replica reports no replication peers or slots, as before (they belong to the primary's view)
        return new PgSnapshot(true, PgRole.REPLICA, timeline, sysId, version, null, r.get(1), r.get(2),
                parseLong(r.get(3)), r.get(4), r.get(5), parseInt(r.get(6)), List.of(), List.of(),
                ControlData.UNKNOWN, null, syncNames);
    }

    /** A boolean as psql prints it: {@code t} for a boolean column, {@code true} for boolean::text. */
    static boolean truth(String s) {
        return "t".equals(s) || "true".equals(s);
    }

    /**
     * The timeline a replica follows. While its WAL receiver runs that is the received timeline. Without one (the
     * primary is gone) the last restartpoint can still be on an older timeline, so the newest timeline history file
     * in pg_wal, which the replica fetched when it followed a promotion, counts too.
     */
    static Integer replicaTimeline(Integer receivedTli, Integer checkpointTli, String newestHistoryFile) {
        if (receivedTli != null) {
            return receivedTli;
        }
        Integer history = null;
        if (newestHistoryFile != null && newestHistoryFile.length() >= 8) {
            try {
                history = Integer.parseInt(newestHistoryFile.substring(0, 8), 16);
            } catch (NumberFormatException ignored) {
                // not a history file name
            }
        }
        if (history == null) {
            return checkpointTli;
        }
        return checkpointTli == null ? history : Math.max(history, checkpointTli);
    }

    Map<String, String> placeholders() {
        return Map.of(
                "bin", cfg.pgBin.toString(),
                "data", cfg.pgData.toString(),
                "port", Integer.toString(cfg.pgPort),
                "state", cfg.stateDir.toString(),
                "node", cfg.nodeName);
    }

    static Integer parseInt(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
