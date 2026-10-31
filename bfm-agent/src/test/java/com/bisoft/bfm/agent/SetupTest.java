package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.setup.Discovery;
import com.bisoft.bfm.agent.setup.Host;
import com.bisoft.bfm.agent.setup.Network;
import com.bisoft.bfm.agent.setup.PgInstance;
import com.bisoft.bfm.agent.setup.Prompter;
import com.bisoft.bfm.agent.setup.Readiness;
import com.bisoft.bfm.agent.setup.Sudoers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** minipgagent setup: what it finds on a host, what it checks, and what it would write. */
class SetupTest {

    @TempDir
    Path root;

    private void file(String path, String text) throws Exception {
        Path p = root.resolve(path.substring(1));
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    /** No systemctl, no pg_lsclusters, no ip: an empty host as far as commands go. */
    private static FakeRunner quietRunner() {
        return new FakeRunner(argv -> FakeRunner.exit(argv, 127, "not here"));
    }

    @Test
    void findsRunningAndStoppedInstancesAndTheOnesAnAgentAlreadyRuns() throws Exception {
        // erp: running primary on 5432, already run by minipgagent@erp
        file("/var/lib/pgsql/16/data/PG_VERSION", "16\n");
        file("/var/lib/pgsql/16/data/postmaster.pid", "4242\n/var/lib/pgsql/16/data\n1700000000\n5432\n"
                + "/var/run/postgresql\n*\n  1234   5678\nready   \n");
        Files.createDirectories(root.resolve("proc/4242"));
        file("/etc/bfm/minipgagent/erp/agent.properties", "node.name=pg-a1\npg.data=/var/lib/pgsql/16/data\n");
        // crm: running replica on 5433
        file("/var/lib/pgsql/16/crm/PG_VERSION", "16\n");
        file("/var/lib/pgsql/16/crm/standby.signal", "");
        file("/var/lib/pgsql/16/crm/postmaster.pid", "4343\n/var/lib/pgsql/16/crm\n1700000000\n5433\n/tmp\n");
        Files.createDirectories(root.resolve("proc/4343"));
        // bi: stopped, port only in postgresql.conf
        file("/var/lib/postgresql/15/main/PG_VERSION", "15\n");
        file("/var/lib/postgresql/15/main/postgresql.conf", "#port = 1\nport = 5434   # bi\n");
        file("/usr/pgsql-16/bin/pg_ctl", "");

        List<PgInstance> found = new Discovery(root, quietRunner()).find(List.of());
        assertEquals(3, found.size(), found.toString());
        PgInstance erp = found.stream().filter(p -> p.port() == 5432).findFirst().orElseThrow();
        assertTrue(erp.running());
        assertEquals("primary", erp.role());
        assertEquals("minipgagent@erp", erp.managedBy());
        assertEquals(Path.of("/usr/pgsql-16/bin"), erp.binDir());
        PgInstance crm = found.stream().filter(p -> p.port() == 5433).findFirst().orElseThrow();
        assertEquals("replica", crm.role());
        assertEquals("/tmp", crm.socketDir());
        assertNull(crm.managedBy());
        PgInstance bi = found.stream().filter(p -> p.port() == 5434).findFirst().orElseThrow();
        assertFalse(bi.running());
        assertEquals("?", bi.role());
        assertEquals("15", bi.major());
        assertNull(bi.binDir(), "no PostgreSQL 15 programs on this host");
    }

    @Test
    void readsPgLsclustersAndPostmasterPid() {
        List<Discovery.LsCluster> ls = Discovery.parseLsClusters("""
                Ver Cluster Port Status          Owner    Data directory              Log file
                16  main    5432 online          postgres /var/lib/postgresql/16/main /var/log/postgresql/x.log
                16  crm     5433 online,recovery postgres /var/lib/postgresql/16/crm  /var/log/postgresql/y.log
                """);
        assertEquals(2, ls.size());
        assertEquals("/var/lib/postgresql/16/crm", ls.get(1).dataDir());
        assertEquals(5433, ls.get(1).port());
        Discovery.PidFile p = Discovery.parsePidFile("77\n/data\n1\n6543\n/run/pg\n");
        assertEquals(77L, p.pid());
        assertEquals(6543, p.port());
        assertEquals("/run/pg", p.socketDir());
        assertEquals(5434, Discovery.portFromConf("port=5433\nport = '5434'\n"));
    }

    @Test
    void knowsTheInterfacesAndTheirNetworks() {
        List<Network.Iface> ifs = Network.parse("""
                1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever
                2: eth0    inet 192.168.0.11/24 brd 192.168.0.255 scope global eth0\\       valid_lft forever
                3: eth1@if7    inet 10.1.0.11/16 brd 10.1.255.255 scope global eth1\\       valid_lft forever
                """);
        assertEquals(List.of("eth0", "eth1"), ifs.stream().map(Network.Iface::name).toList());
        assertTrue(ifs.get(0).contains("192.168.0.60"));
        assertFalse(ifs.get(0).contains("192.168.1.60"));
        assertTrue(ifs.get(1).contains("10.1.200.1"));
    }

    @Test
    void theSudoRuleNamesExactlyOneAddressOnOneInterface() {
        String r = Sudoers.rule("192.168.0.60", "24", "eth0");
        assertTrue(r.contains("/usr/sbin/ip addr add 192.168.0.60/24 dev eth0, \\"));
        assertTrue(r.contains("/usr/sbin/arping -U -q -c 3 -I eth0 192.168.0.60\n"));
        assertThrows(Prompter.SetupException.class, () -> Sudoers.rule("192.168.0.60, ALL", "24", "eth0"));
        assertThrows(Prompter.SetupException.class, () -> Sudoers.rule("192.168.0.60", "24", "eth0 ALL"));
        assertThrows(Prompter.SetupException.class, () -> Sudoers.rule("192.168.0.60", "33", "eth0"));
    }

    @Test
    void readinessSaysWhatIsMissingAndHowToFixIt() {
        Map<String, List<List<String>>> answers = Map.of(
                "pg_settings", List.of(List.of("server_version", "16.4"), List.of("server_version_num", "160004"),
                        List.of("wal_level", "replica"), List.of("wal_log_hints", "off"),
                        List.of("data_checksums", "off"), List.of("max_wal_senders", "10"),
                        List.of("max_replication_slots", "10"), List.of("hot_standby", "on"),
                        List.of("hba_file", "/var/lib/pgsql/16/data/pg_hba.conf")),
                "pg_control_system", List.of(List.of("7999")),
                "pg_roles", List.of(List.of("t", "t")),
                "pg_hba_file_rules", List.of(
                        List.of("host", "{replication}", "{replicator}", "10.0.0.12", "255.255.255.255"),
                        List.of("host", "{all}", "{all}", "10.0.0.0", "255.255.255.0")));
        Readiness.Sql sql = q -> answers.entrySet().stream().filter(e -> q.contains(e.getKey())).findFirst()
                .map(Map.Entry::getValue).orElseThrow(() -> new IllegalStateException("unexpected " + q));
        List<Readiness.Check> checks = Readiness.run(sql, "replicator", List.of(new Readiness.Peer("crm-a2",
                "10.0.0.12"), new Readiness.Peer("crm-dr1", "10.0.0.13")), "7000", null);
        Readiness.Check rewind = find(checks, "pg_rewind possible");
        assertEquals(Readiness.Level.WARN, rewind.level());
        assertEquals(List.of("ALTER SYSTEM SET wal_log_hints = on"), rewind.fixSql());
        assertTrue(rewind.needsRestart());
        Readiness.Check db = find(checks, "Database");
        assertEquals(Readiness.Level.WARN, db.level());
        assertTrue(db.detail().contains("another database"));
        Readiness.Check hba = find(checks, "pg_hba.conf");
        assertEquals(Readiness.Level.FAIL, hba.level(), "'all' does not cover replication; crm-dr1 has no entry");
        assertTrue(hba.detail().contains("crm-dr1") && !hba.detail().contains("crm-a2"), hba.detail());
        assertEquals(2, hba.hbaLines().size());
        assertTrue(hba.hbaLines().get(0).contains("replication") && hba.hbaLines().get(0).contains("10.0.0.13/32"));
        assertEquals(Readiness.Level.OK, find(checks, "Replication role").level());
        assertEquals(Readiness.Level.OK, find(checks, "Senders and slots").level());
    }

    private static Readiness.Check find(List<Readiness.Check> checks, String name) {
        return checks.stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aDryRunShowsTheWholePlanAndChangesNothing() throws Exception {
        file("/var/lib/pgsql/16/crm/PG_VERSION", "16\n");
        file("/var/lib/pgsql/16/crm/postgresql.conf", "port = 5433\n");
        file("/usr/pgsql-16/bin/pg_ctl", "");
        file("/etc/machine-id", "3f2a0000000000000000000000000000\n");
        file("/var/lib/pgsql/.pgpass", "*:*:*:replicator:secret\n");
        Path answers = root.resolve("answers.properties");
        Files.writeString(answers, """
                instance=crm
                agent.port=8009
                vip.address=192.168.0.60
                vip.interface=eth0
                vip.prefix=24
                node=crm-a1
                cluster=crm
                cluster-id=a5443743-ff8b-4a1f-b399-d9765c971c45
                peers=10.0.0.12
                """);
        Path bundle = root.resolve("bundle");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("crm-a1.p12"), "x");
        Files.writeString(bundle.resolve("crm-a1.pass"), "p");
        Files.writeString(bundle.resolve("ca.p12"), "x");
        Files.writeString(bundle.resolve("ca.pass"), "p");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        int code;
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            code = com.bisoft.bfm.agent.setup.SetupCommand.main(new String[]{"--dry-run", "--root", root.toString(),
                    "--no-systemd", "--as-current-user", "--bundle", bundle.toString(), "--answers",
                    answers.toString(), "--data", "/var/lib/pgsql/16/crm"});
        } finally {
            System.setOut(old);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out);
        assertTrue(out.contains("dry run: nothing is changed"), out);
        assertTrue(out.contains("This instance becomes node crm-a1 of cluster crm"), out);
        assertTrue(out.contains("/etc/bfm/minipgagent/crm/agent.properties"), out);
        assertTrue(out.contains("sudoers.d/minipgagent-crm"), out);
        assertTrue(out.contains("systemctl enable --now minipgagent@crm"), out);
        assertTrue(out.contains("PostgreSQL is stopped"), out);
        assertFalse(Files.exists(root.resolve("etc/bfm/minipgagent/crm")), "a dry run writes nothing");
    }

    @Test
    void theHostWritesWithModesAndKeepsABackup() throws Exception {
        Host host = new Host(root, false, false, true, quietRunner(), System.out);
        Path f = host.path("/etc/bfm/minipgagent/crm/agent.properties");
        host.write(f, "a=1\n", "600", "postgres");
        host.write(f, "a=2\n", "600", "postgres");
        assertEquals("a=2\n", Files.readString(f));
        assertEquals("a=1\n", Files.readString(f.resolveSibling("agent.properties.bak")));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(f)));
        ProcessResult r = host.systemctl("enable", "--now", "minipgagent@crm");
        assertTrue(r.ok(), "skipped without systemd");
        Properties none = new Properties();
        Prompter ask = new Prompter(none, null, System.out, false);
        assertThrows(Prompter.SetupException.class, () -> ask.ask("agent.port", "Agent port:", null),
                "no terminal and no answer: setup stops instead of waiting");
    }
}
