package io.pgvip.agent.pg;

import io.pgvip.agent.AgentConfig;
import io.pgvip.agent.exec.ProcessResult;
import io.pgvip.agent.exec.ProcessRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Runs SQL through the local {@code psql} binary over the Unix socket.
 *
 * <p>The agent runs as the PostgreSQL OS user, so peer authentication applies and no password is stored.
 * Using psql keeps the agent free of a JDBC driver and works with any server version psql can talk to.
 */
public class Psql {

    static final String FS = "\u001f";
    static final String RS = "\u001e";
    static final String NULL = "\\N";

    private final AgentConfig cfg;
    private final ProcessRunner runner;

    public Psql(AgentConfig cfg, ProcessRunner runner) {
        this.cfg = cfg;
        this.runner = runner;
    }

    /** Runs one statement and returns its rows; SQL NULL comes back as Java null. */
    public List<List<String>> query(String sql, int timeoutSec) throws PgException {
        ProcessResult r = runner.run(argv(sql), Duration.ofSeconds(timeoutSec + 10L), env(timeoutSec));
        if (!r.ok()) {
            boolean conn = r.exitCode() == 2 || r.timedOut();
            throw new PgException(r.summary(), conn);
        }
        return parse(r.stdout());
    }

    public void exec(String sql, int timeoutSec) throws PgException {
        query(sql, timeoutSec);
    }

    /** First column of the first row, or null when there are no rows. */
    public String scalar(String sql, int timeoutSec) throws PgException {
        List<List<String>> rows = query(sql, timeoutSec);
        return rows.isEmpty() || rows.get(0).isEmpty() ? null : rows.get(0).get(0);
    }

    static List<List<String>> parse(String stdout) {
        String out = stdout;
        if (out.endsWith("\n")) {
            out = out.substring(0, out.length() - 1);
        }
        List<List<String>> rows = new ArrayList<>();
        if (out.isEmpty()) {
            return rows;
        }
        for (String record : out.split(RS, -1)) {
            if (record.isEmpty()) {
                continue;
            }
            List<String> row = new ArrayList<>(Arrays.asList(record.split(FS, -1)));
            row.replaceAll(v -> NULL.equals(v) ? null : v);
            rows.add(row);
        }
        return rows;
    }

    private List<String> argv(String sql) {
        List<String> a = new ArrayList<>();
        a.add(cfg.pgBin.resolve("psql").toString());
        a.add("-X");
        a.add("-q");
        a.add("-A");
        a.add("-t");
        a.add("-F");
        a.add(FS);
        a.add("-R");
        a.add(RS);
        a.add("-P");
        a.add("null=" + NULL);
        a.add("-v");
        a.add("ON_ERROR_STOP=1");
        a.add("-h");
        a.add(cfg.pgSocketDir);
        a.add("-p");
        a.add(Integer.toString(cfg.pgPort));
        a.add("-U");
        a.add(cfg.pgUser);
        a.add("-d");
        a.add(cfg.pgDatabase);
        a.add("-c");
        a.add(sql);
        return a;
    }

    private static Map<String, String> env(int timeoutSec) {
        return Map.of(
                "PGCONNECT_TIMEOUT", "5",
                "PGAPPNAME", "pgvip-agent",
                "PGOPTIONS", "-c statement_timeout=" + (timeoutSec * 1000L),
                "LC_ALL", "C");
    }

    /** SQL string literal: 'it''s'. */
    public static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
