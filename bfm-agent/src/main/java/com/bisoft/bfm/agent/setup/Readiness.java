package com.bisoft.bfm.agent.setup;

import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether a PostgreSQL instance is ready to be a node: settings, replication access from the other nodes, the
 * replication role, the database it holds and room for a rebuild. Each check that fails says how to fix it; the
 * setup offers those fixes one by one, and never restarts PostgreSQL itself.
 *
 * <p>The same checks answer the agent's {@code POST /v1/checks}, so the console can show them live.
 */
public final class Readiness {

    /** Runs one SQL statement as a superuser and returns its rows. */
    @FunctionalInterface
    public interface Sql {
        List<List<String>> query(String sql) throws Exception;
    }

    public enum Level { OK, WARN, FAIL }

    /**
     * @param fixSql        statements that fix it (ALTER SYSTEM, CREATE ROLE ...), or empty
     * @param hbaLines      lines to append to pg_hba.conf, or empty
     * @param needsRestart  the fix takes effect only after PostgreSQL restarts
     */
    public record Check(String name, Level level, String detail, String fix, List<String> fixSql,
                        List<String> hbaLines, boolean needsRestart) implements JsonWritable {

        static Check ok(String name, String detail) {
            return new Check(name, Level.OK, detail, null, List.of(), List.of(), false);
        }

        @Override
        public Object toJsonValue() {
            return JsonMap.create().put("name", name).put("level", level).put("detail", detail).put("fix", fix)
                    .put("needsRestart", needsRestart).raw();
        }
    }

    /** A node the instance must accept replication from. */
    public record Peer(String name, String address) {
    }

    private Readiness() {
    }

    /**
     * @param clusterSystemId the cluster's PostgreSQL system identifier, or null when none is recorded yet
     * @param dataDir         for the free-space check, or null
     */
    public static List<Check> run(Sql sql, String replicationUser, List<Peer> peers, String clusterSystemId,
                                  String dataDir) {
        List<Check> out = new ArrayList<>();
        List<List<String>> settings;
        try {
            settings = sql.query("SELECT name, setting FROM pg_settings WHERE name IN ('server_version',"
                    + " 'server_version_num', 'wal_level', 'wal_log_hints', 'data_checksums', 'max_wal_senders',"
                    + " 'max_replication_slots', 'hot_standby', 'hba_file')");
        } catch (Exception e) {
            out.add(new Check("PostgreSQL answers", Level.FAIL, "cannot query it: " + e.getMessage(),
                    "start PostgreSQL (or check the socket directory and port), then run the checks again",
                    List.of(), List.of(), false));
            return out;
        }
        java.util.Map<String, String> s = new java.util.HashMap<>();
        for (List<String> r : settings) {
            s.put(r.get(0), r.get(1));
        }
        int num = parse(s.get("server_version_num"), 0);
        out.add(num >= 130000 ? Check.ok("Version", s.get("server_version"))
                : new Check("Version", Level.FAIL, s.get("server_version") + " is too old", "PostgreSQL 13 or later",
                List.of(), List.of(), false));

        out.add(systemIdentifier(sql, clusterSystemId));

        String wal = s.getOrDefault("wal_level", "");
        out.add(wal.equals("replica") || wal.equals("logical") ? Check.ok("wal_level", wal)
                : new Check("wal_level", Level.FAIL, "wal_level is " + wal + ": replicas cannot stream",
                        "ALTER SYSTEM SET wal_level = replica, then restart", List.of(
                        "ALTER SYSTEM SET wal_level = replica"), List.of(), true));

        boolean hints = "on".equals(s.get("wal_log_hints")) || "on".equals(s.get("data_checksums"));
        out.add(hints ? Check.ok("pg_rewind possible", "on".equals(s.get("data_checksums")) ? "data checksums on"
                : "wal_log_hints on")
                : new Check("pg_rewind possible", Level.WARN, "wal_log_hints and data checksums are off: an old"
                        + " primary can only come back by re-initialize", "ALTER SYSTEM SET wal_log_hints = on,"
                        + " then restart", List.of("ALTER SYSTEM SET wal_log_hints = on"), List.of(), true));

        int nodes = peers.size() + 1;
        int senders = parse(s.get("max_wal_senders"), 0);
        int slots = parse(s.get("max_replication_slots"), 0);
        if (senders >= nodes + 2 && slots >= nodes) {
            out.add(Check.ok("Senders and slots", "max_wal_senders " + senders + ", max_replication_slots " + slots));
        } else {
            List<String> fix = new ArrayList<>();
            if (senders < nodes + 2) {
                fix.add("ALTER SYSTEM SET max_wal_senders = " + Math.max(10, nodes + 2));
            }
            if (slots < nodes) {
                fix.add("ALTER SYSTEM SET max_replication_slots = " + Math.max(10, nodes));
            }
            out.add(new Check("Senders and slots", Level.WARN, "max_wal_senders " + senders
                    + ", max_replication_slots " + slots + " for " + nodes + " nodes", String.join("; ", fix)
                    + ", then restart", fix, List.of(), true));
        }

        out.add("on".equals(s.get("hot_standby")) ? Check.ok("hot_standby", "on")
                : new Check("hot_standby", Level.WARN, "off: a replica answers no queries", "ALTER SYSTEM SET"
                + " hot_standby = on, then restart", List.of("ALTER SYSTEM SET hot_standby = on"), List.of(), true));

        out.add(role(sql, replicationUser));
        out.add(hba(sql, replicationUser, peers, s.get("hba_file")));
        if (dataDir != null) {
            out.add(space(sql, dataDir));
        }
        return out;
    }

    private static Check systemIdentifier(Sql sql, String cluster) {
        try {
            String mine = sql.query("SELECT system_identifier FROM pg_control_system()").get(0).get(0);
            if (cluster == null) {
                return Check.ok("Database", "system identifier " + mine + " (the cluster has none recorded yet)");
            }
            if (cluster.equals(mine)) {
                return Check.ok("Database", "system identifier " + mine + ", the cluster's");
            }
            return new Check("Database", Level.WARN, "this instance holds another database (system identifier "
                    + mine + ", the cluster's is " + cluster + ")", "re-initialize the node from the console once"
                    + " its agent runs: it is copied from the primary", List.of(), List.of(), false);
        } catch (Exception e) {
            return new Check("Database", Level.WARN, "system identifier unknown: " + e.getMessage(), null,
                    List.of(), List.of(), false);
        }
    }

    private static Check role(Sql sql, String user) {
        try {
            List<List<String>> r = sql.query("SELECT rolreplication, rolcanlogin FROM pg_roles WHERE rolname = "
                    + literal(user));
            if (r.isEmpty()) {
                return new Check("Replication role", Level.FAIL, "role " + user + " does not exist",
                        "CREATE ROLE " + user + " WITH REPLICATION LOGIN PASSWORD '...' (on the primary; replicas"
                        + " get it through replication)", List.of(), List.of(), false);
            }
            boolean ok = "t".equals(r.get(0).get(0)) && "t".equals(r.get(0).get(1));
            return ok ? Check.ok("Replication role", user + " can log in and replicate")
                    : new Check("Replication role", Level.FAIL, user + " lacks REPLICATION or LOGIN",
                    "ALTER ROLE " + user + " WITH REPLICATION LOGIN (on the primary)", List.of(), List.of(), false);
        } catch (Exception e) {
            return new Check("Replication role", Level.WARN, "cannot check: " + e.getMessage(), null, List.of(),
                    List.of(), false);
        }
    }

    /** Every peer must be allowed to connect for replication (and pg_rewind uses a normal connection). */
    private static Check hba(Sql sql, String user, List<Peer> peers, String hbaFile) {
        if (peers.isEmpty()) {
            return Check.ok("pg_hba.conf", "no other node yet");
        }
        List<List<String>> rules;
        try {
            rules = sql.query("SELECT type, database::text, user_name::text, address, netmask FROM pg_hba_file_rules"
                    + " WHERE error IS NULL AND type LIKE 'host%'");
        } catch (Exception e) {
            return new Check("pg_hba.conf", Level.WARN, "cannot read the rules: " + e.getMessage(), null, List.of(),
                    List.of(), false);
        }
        List<String> missing = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (Peer p : peers) {
            if (!allowed(rules, user, p.address())) {
                missing.add(p.name() + " (" + p.address() + ")");
                String addr = p.address().contains(":") ? p.address() + "/128" : p.address() + "/32";
                lines.add("host    replication     " + user + "    " + addr + "    scram-sha-256    # " + p.name());
                lines.add("host    postgres        " + user + "    " + addr + "    scram-sha-256    # " + p.name()
                        + " (pg_rewind)");
            }
        }
        if (missing.isEmpty()) {
            return Check.ok("pg_hba.conf", "every other node may replicate");
        }
        return new Check("pg_hba.conf", Level.FAIL, "no replication entry for " + String.join(", ", missing),
                "add " + lines.size() + " lines to " + (hbaFile == null ? "pg_hba.conf" : hbaFile) + " and reload",
                List.of(), lines, false);
    }

    static boolean allowed(List<List<String>> rules, String user, String address) {
        for (List<String> r : rules) {
            String db = r.get(1) == null ? "" : r.get(1);
            String users = r.get(2) == null ? "" : r.get(2);
            String addr = r.get(3);
            String mask = r.get(4);
            // "all" does not cover replication connections; only "replication" does.
            if (!db.contains("replication")) {
                continue;
            }
            if (!users.contains(user) && !users.contains("all")) {
                continue;
            }
            if (addr == null || addr.equals("all") || addr.equals("samenet")) {
                return true;
            }
            try {
                int prefix = mask == null ? (address.contains(":") ? 128 : 32) : prefixOf(mask);
                if (!address.contains(":") && !addr.contains(":") && Network.sameNetwork(addr, address, prefix)) {
                    return true;
                }
            } catch (Exception ignored) {
                // a host name rule: not checked
            }
        }
        return false;
    }

    private static int prefixOf(String mask) throws java.net.UnknownHostException {
        byte[] b = java.net.InetAddress.getByName(mask).getAddress();
        int bits = 0;
        for (byte x : b) {
            bits += Integer.bitCount(x & 0xff);
        }
        return bits;
    }

    private static Check space(Sql sql, String dataDir) {
        try {
            long size = Long.parseLong(sql.query("SELECT sum(pg_database_size(oid)) FROM pg_database").get(0).get(0));
            File dir = new File(dataDir).getParentFile();
            long free = dir == null ? 0 : dir.getUsableSpace();
            String d = com.bisoft.bfm.common.Lsn.bytes(free) + " free next to " + com.bisoft.bfm.common.Lsn.bytes(size)
                    + " of databases";
            return free > size ? Check.ok("Room for a re-initialize", d)
                    : new Check("Room for a re-initialize", Level.WARN, d + ": a re-initialize keeps the old"
                    + " directory until the copy is done", "free space, or set reinit.keep.old=false", List.of(),
                    List.of(), false);
        } catch (Exception e) {
            return new Check("Room for a re-initialize", Level.WARN, "unknown: " + e.getMessage(), null, List.of(),
                    List.of(), false);
        }
    }

    private static int parse(String s, int dflt) {
        try {
            return Integer.parseInt(s);
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    static String literal(String v) {
        return "'" + v.replace("'", "''") + "'";
    }
}
