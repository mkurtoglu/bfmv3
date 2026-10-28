package io.pgvip.agent.pg;

import io.pgvip.agent.AgentConfig;
import io.pgvip.agent.exec.CommandTemplate;
import io.pgvip.agent.exec.ProcessResult;
import io.pgvip.agent.exec.ProcessRunner;
import io.pgvip.common.model.PgRole;
import io.pgvip.common.model.ReplicationPeer;
import io.pgvip.common.model.SlotInfo;

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

    public PgSnapshot collect() {
        if (!isRunning()) {
            return PgSnapshot.stopped(controlData(), null);
        }
        try {
            List<String> base = psql.query(
                    "SELECT pg_is_in_recovery(), current_setting('server_version_num'),"
                            + " (SELECT system_identifier FROM pg_control_system())", T).get(0);
            boolean inRecovery = "t".equals(base.get(0));
            Integer version = parseInt(base.get(1));
            String sysId = base.get(2);
            return inRecovery ? replica(version, sysId) : primary(version, sysId);
        } catch (PgException | RuntimeException e) {
            return new PgSnapshot(true, PgRole.UNKNOWN, null, null, null, null, null, null, null, null, null, null,
                    List.of(), List.of(), ControlData.UNKNOWN, e.getMessage());
        }
    }

    private PgSnapshot primary(Integer version, String sysId) throws PgException {
        List<String> row = psql.query(
                "SELECT pg_current_wal_lsn(),"
                        + " ('x' || substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8))::bit(32)::int", T).get(0);
        List<ReplicationPeer> peers = new ArrayList<>();
        for (List<String> r : psql.query(
                "SELECT application_name, coalesce(host(client_addr), ''), state, sync_state,"
                        + " sent_lsn, write_lsn, flush_lsn, replay_lsn,"
                        + " (extract(epoch FROM replay_lag) * 1000)::bigint"
                        + " FROM pg_stat_replication ORDER BY application_name", T)) {
            peers.add(new ReplicationPeer(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6),
                    r.get(7), parseLong(r.get(8))));
        }
        List<SlotInfo> slots = new ArrayList<>();
        for (List<String> r : psql.query(
                "SELECT slot_name, slot_type, active, restart_lsn,"
                        + " CASE WHEN restart_lsn IS NULL THEN NULL"
                        + " ELSE pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)::bigint END"
                        + " FROM pg_replication_slots ORDER BY slot_name", T)) {
            slots.add(new SlotInfo(r.get(0), r.get(1), "t".equals(r.get(2)), r.get(3), parseLong(r.get(4))));
        }
        return new PgSnapshot(true, PgRole.PRIMARY, parseInt(row.get(1)), sysId, version, row.get(0), null, null,
                null, null, null, null, peers, slots, ControlData.UNKNOWN, null);
    }

    private PgSnapshot replica(Integer version, String sysId) throws PgException {
        List<String> r = psql.query(
                "SELECT pg_last_wal_receive_lsn(), pg_last_wal_replay_lsn(),"
                        + " CASE WHEN pg_last_xact_replay_timestamp() IS NULL THEN NULL"
                        + " ELSE (extract(epoch FROM now() - pg_last_xact_replay_timestamp()) * 1000)::bigint END,"
                        + " (SELECT status FROM pg_stat_wal_receiver),"
                        + " (SELECT sender_host FROM pg_stat_wal_receiver),"
                        + " (SELECT sender_port FROM pg_stat_wal_receiver),"
                        + " coalesce((SELECT received_tli FROM pg_stat_wal_receiver),"
                        + " (SELECT timeline_id FROM pg_control_checkpoint()))", T).get(0);
        return new PgSnapshot(true, PgRole.REPLICA, parseInt(r.get(6)), sysId, version, null, r.get(0), r.get(1),
                parseLong(r.get(2)), r.get(3), r.get(4), parseInt(r.get(5)), List.of(), List.of(),
                ControlData.UNKNOWN, null);
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
