package com.bisoft.bfm.agent.setup;

import com.bisoft.bfm.agent.AgentMain;
import com.bisoft.bfm.agent.exec.ProcessResult;
import com.bisoft.bfm.agent.exec.ProcessRunner;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.tls.Keytool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * {@code minipgagent setup}: turns one PostgreSQL instance on this host into a node of a cluster, in six steps:
 * discover the instance, join a cluster (one-time token from the console, or a certificate bundle), network (agent
 * port, VIP), PostgreSQL readiness with fixes the operator confirms one by one, review, apply and verify.
 *
 * <p>It never restarts PostgreSQL: a setting that needs a restart is written and reported. It is safe to run again.
 * {@code --dry-run} shows every change and makes none; {@code --answers FILE} answers every question from a
 * properties file.
 */
public final class SetupCommand {

    private static final String USAGE = """
            minipgagent setup [options]
              --enroll URL --token TOKEN --fingerprint SHA256:...   join with a one-time token from the console
              --bundle DIR [--node NAME] [--cluster-id ID]            or with a bfm-make-certs bundle (offline CA)
              --data DIR        the data directory of the instance (otherwise chosen from what is found)
              --instance NAME   the agent instance name (default: the cluster name)
              --answers FILE    answers for every question (keys: see docs/install.md)
              --dry-run         show every change, make none
              --yes             answer yes to every confirmation
              lab only: --insecure-http --root DIR --no-systemd --as-current-user""";

    private final Host host;
    private final Prompter ask;
    private final PrintStream out;
    private final Properties opt;

    SetupCommand(Host host, Prompter ask, PrintStream out, Properties opt) {
        this.host = host;
        this.ask = ask;
        this.out = out;
        this.opt = opt;
    }

    public static int main(String[] args) {
        PrintStream out = Glyphs.wrap(System.out);
        Properties opt = new Properties();
        try {
            parse(args, opt);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            return 64;
        }
        if (opt.containsKey("help")) {
            out.println(USAGE);
            return 0;
        }
        Properties answers = new Properties();
        if (opt.getProperty("answers") != null) {
            try (InputStream in = Files.newInputStream(Path.of(opt.getProperty("answers")))) {
                answers.load(in);
            } catch (IOException e) {
                System.err.println("cannot read " + opt.getProperty("answers") + ": " + e.getMessage());
                return 66;
            }
        }
        // Command-line options answer the same questions as the answers file, and win.
        for (String k : List.of("data", "instance", "node", "cluster-id")) {
            if (opt.getProperty(k) != null) {
                answers.setProperty(k, opt.getProperty(k));
            }
        }
        BufferedReader in = answers.isEmpty() || opt.getProperty("answers") == null
                ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
        Host host = new Host(Path.of(opt.getProperty("root", "/")), opt.containsKey("dry-run"),
                !opt.containsKey("no-systemd"), opt.containsKey("as-current-user"), new ProcessRunner(), out);
        SetupCommand cmd = new SetupCommand(host, new Prompter(answers, in, out, opt.containsKey("yes")), out, opt);
        try {
            return cmd.run();
        } catch (Prompter.SetupException | Enroller.EnrollException | Keytool.KeytoolException e) {
            out.println();
            out.println(" ✖ " + e.getMessage());
            return 1;
        } catch (Exception e) {
            out.println();
            out.println(" ✖ setup stopped: " + e);
            return 1;
        }
    }

    static void parse(String[] args, Properties opt) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + a);
            }
            String key = a.substring(2);
            switch (key) {
                case "dry-run", "yes", "insecure-http", "no-systemd", "as-current-user", "help" -> opt.setProperty(key,
                        "true");
                case "enroll", "token", "fingerprint", "bundle", "node", "cluster-id", "data", "instance", "answers",
                     "root" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(a + " needs a value");
                    }
                    opt.setProperty(key, args[++i]);
                }
                default -> throw new IllegalArgumentException("unknown option " + a);
            }
        }
    }

    // ---------------------------------------------------------------- the steps

    /** What the cluster told us at enrollment, or what the bundle and the answers say. */
    /** @param agentPort where the watcher reaches the agent, or null when unknown (a bundle) */
    record Join(String clusterId, String cluster, String environment, String node, String dc, Integer agentPort,
                List<Readiness.Peer> peers, String systemIdentifier, Path nodeStore, String nodePass,
                Path caStore, String caPass) {
    }

    int run() throws Exception {
        boolean dry = host.dryRun;
        String hostname = hostname();
        String machineId = read(host.path("/etc/machine-id")).trim();
        out.println();
        out.println(" MiniPG agent setup · " + AgentMain.VERSION + "   host " + hostname
                + (machineId.isEmpty() ? "" : " · machine-id " + machineId.substring(0, Math.min(8, machineId.length()))
                + "…") + (dry ? "   (dry run: nothing is changed)" : ""));
        if (!dry && host.root.equals(Path.of("/")) && !host.isRoot()) {
            throw new Prompter.SetupException("run setup as root (sudo): it writes /etc/bfm, a sudo rule and a"
                    + " service. --dry-run works as any user.");
        }

        // 1. Discover
        section("1 · PostgreSQL on this host");
        List<Path> extra = new ArrayList<>();
        if (opt.getProperty("data") != null) {
            extra.add(Path.of(opt.getProperty("data")));
        }
        List<PgInstance> found = new Discovery(host.root, host.runner).find(extra);
        if (found.isEmpty()) {
            throw new Prompter.SetupException("no PostgreSQL data directory found; give it with --data DIR");
        }
        out.println(String.format("   %-2s %-8s %-34s %-6s %-8s %-8s %s", "#", "version", "data directory", "port",
                "state", "role", "managed by"));
        for (int i = 0; i < found.size(); i++) {
            PgInstance p = found.get(i);
            out.println(String.format("   %-2d %-8s %-34s %-6d %-8s %-8s %s", i + 1, p.version(), p.dataDir(),
                    p.port(), p.running() ? "running" : "stopped", p.role(), p.managedBy() == null ? "—"
                            : p.managedBy()));
        }
        PgInstance pg = choose(found);
        if (pg.managedBy() != null) {
            throw new Prompter.SetupException(pg.dataDir() + " is already managed by " + pg.managedBy() + "; run"
                    + " 'minipgagent remove' for it first, or choose another instance");
        }
        if (pg.binDir() == null) {
            String bin = ask.ask("pg.bin", "PostgreSQL " + pg.major() + " programs (bin directory):", null);
            pg = new PgInstance(pg.dataDir(), pg.version(), pg.port(), pg.running(), pg.role(), Path.of(bin),
                    pg.socketDir(), null, pg.systemdUnit());
        }

        // 2. Join a cluster
        section("2 · Join a cluster");
        Path work = Files.createTempDirectory("minipgagent-setup", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        try {
            Join join = join(work, hostname, machineId, dry);
            out.println("   This instance becomes node " + join.node() + " of cluster " + join.cluster()
                    + (join.environment() == null ? "" : " (" + join.environment().toUpperCase(Locale.ROOT) + ")")
                    + (join.dc() == null ? "" : ", data center " + join.dc()) + ".");

            // 3. Network
            section("3 · Network");
            Map<String, String> net = network(join, pg);

            // 4. Readiness
            section("4 · PostgreSQL readiness");
            String replUser = ask.ask("replication.user", "Replication role:", "replicator");
            List<String> notes = readiness(pg, join, replUser);

            // 5. Review and apply
            section("5 · Review");
            String instance = instanceName(join);
            Plan plan = plan(pg, join, net, instance, replUser, work);
            plan.print(out);
            notes.forEach(n -> out.println("   ! " + n));
            if (dry) {
                out.println();
                out.println(" Dry run: nothing was changed.");
                return 0;
            }
            if (!ask.confirm("proceed", "Proceed?", false)) {
                out.println(" Nothing was changed.");
                return 2;
            }
            plan.apply(host, out);

            // 6. Verify
            section("6 · Verify");
            verify(join, net, instance);
            return 0;
        } finally {
            deleteTree(work);
        }
    }

    private PgInstance choose(List<PgInstance> found) {
        String data = opt.getProperty("data");
        if (data != null) {
            for (PgInstance p : found) {
                if (p.dataDir().equals(Path.of(data))) {
                    return p;
                }
            }
            throw new Prompter.SetupException("no PostgreSQL data directory at " + data);
        }
        int dflt = 0;
        for (int i = 0; i < found.size(); i++) {
            if (found.get(i).managedBy() == null) {
                dflt = i;
                break;
            }
        }
        String a = ask.ask("instance.number", "Choose the instance to set up:", Integer.toString(dflt + 1));
        try {
            return found.get(Integer.parseInt(a) - 1);
        } catch (RuntimeException e) {
            throw new Prompter.SetupException("no instance number " + a);
        }
    }

    private Join join(Path work, String hostname, String machineId, boolean dry) throws IOException {
        String nodePass = Keytool.randomPassword();
        String caPass = Keytool.randomPassword();
        Path nodeStore = work.resolve("node.p12");
        Path caStore = work.resolve("ca.p12");
        String url = opt.getProperty("enroll");
        if (url != null) {
            String token = opt.getProperty("token");
            if (token == null) {
                throw new Prompter.SetupException("--enroll needs --token (the console prints both)");
            }
            if (dry) {
                out.println("   (dry run: the token is not spent; the console's settings for the node are not known"
                        + " yet)");
                return new Join(null, "(from the token)", null, "(from the token)", null, 8008, List.of(), null,
                        nodeStore, nodePass, caStore, caPass);
            }
            out.println("   Creating this node's key (RSA 3072) and asking " + url + " to certify it…");
            Keytool.createKeyPair(nodeStore, nodePass, "node", "minipgagent");
            String csr = Keytool.certificateRequest(nodeStore, nodePass, "node");
            JsonMap r = Enroller.enroll(url, opt.getProperty("fingerprint"), opt.containsKey("insecure-http"),
                    JsonMap.create().put("token", token.trim()).put("csr", csr).put("hostname", hostname)
                            .put("hostId", machineId.isEmpty() ? null : machineId));
            out.println("   ✔ watcher " + url + (opt.getProperty("fingerprint") == null ? ""
                    : " · fingerprint matches"));
            Keytool.importTrusted(caStore, caPass, "bfm-ca", r.requireStr("caCertificate"), work);
            Keytool.importTrusted(nodeStore, nodePass, "bfm-ca", r.requireStr("caCertificate"), work);
            Keytool.importChain(nodeStore, nodePass, "node", r.requireStr("certificate"), work);
            List<Readiness.Peer> peers = new ArrayList<>();
            for (JsonMap p : r.objList("peers", m -> m)) {
                peers.add(new Readiness.Peer(p.requireStr("name"), p.requireStr("pgHost")));
            }
            return new Join(r.requireStr("clusterId"), r.requireStr("cluster"), r.str("environment"),
                    r.requireStr("node"), r.str("dc"), r.integer("agentPort", 8008), peers,
                    r.str("systemIdentifier"), nodeStore, nodePass, caStore, caPass);
        }
        String bundle = opt.getProperty("bundle");
        if (bundle == null) {
            throw new Prompter.SetupException("join a cluster: --enroll URL --token TOKEN --fingerprint FP (from the"
                    + " console's Agent certificates page), or --bundle DIR with a bfm-make-certs bundle");
        }
        Path dir = Path.of(bundle);
        String node = ask.ask("node", "Node name (as in watcher.yml):", null);
        Path p12 = dir.resolve(node + ".p12");
        if (!Files.exists(p12) || !Files.exists(dir.resolve("ca.p12"))) {
            throw new Prompter.SetupException(dir + " needs " + node + ".p12, " + node + ".pass, ca.p12 and ca.pass");
        }
        Files.copy(p12, nodeStore);
        Files.copy(dir.resolve("ca.p12"), caStore);
        String clusterName = ask.ask("cluster", "Cluster name:", "cluster");
        String clusterId = ask.ask("cluster-id", "Cluster id (from the console; empty: bound on first contact):", "");
        String peers = ask.ask("peers", "Addresses of the other nodes (comma separated, for pg_hba.conf):", "");
        List<Readiness.Peer> list = new ArrayList<>();
        for (String a : peers.split(",")) {
            if (!a.isBlank()) {
                list.add(new Readiness.Peer(a.trim(), a.trim()));
            }
        }
        return new Join(clusterId.isBlank() ? null : clusterId, clusterName, null, node, null, null, list, null,
                nodeStore, read(dir.resolve(node + ".pass")).trim(), caStore, read(dir.resolve("ca.pass")).trim());
    }

    private Map<String, String> network(Join join, PgInstance pg) {
        Map<String, String> net = new LinkedHashMap<>();
        int dflt = join.agentPort() == null ? 8008 : join.agentPort();
        String port = ask.ask("agent.port", "Agent port:", Integer.toString(dflt));
        int p = Integer.parseInt(port);
        if (!host.dryRun && !Network.portFree(p)) {
            throw new Prompter.SetupException("port " + p + " is in use on this host (another agent instance?);"
                    + " the watcher must reach this agent at its agent-url");
        }
        if (join.agentPort() != null && p != dflt) {
            out.println("   ! the watcher reaches " + join.node() + " at port " + dflt + "; change its agent-url too");
        }
        net.put("port", port);
        String vip = ask.ask("vip.address", "VIP for " + join.cluster() + (join.dc() == null ? "" : " in "
                + join.dc()) + " (none: no VIP):", "none");
        if (vip == null || vip.equalsIgnoreCase("none") || vip.isBlank()) {
            net.put("vip", null);
            return net;
        }
        ProcessResult ip = host.runner.run(List.of("ip", "-o", "-4", "addr", "show"), Duration.ofSeconds(10));
        List<Network.Iface> ifaces = Network.parse(ip.stdout());
        String guess = ifaces.stream().filter(i -> i.contains(vip)).map(Network.Iface::name).findFirst()
                .orElse(ifaces.isEmpty() ? "eth0" : ifaces.get(0).name());
        StringBuilder list = new StringBuilder();
        for (Network.Iface i : ifaces) {
            list.append("  ").append(i.name()).append(' ').append(i.address()).append('/').append(i.prefix());
        }
        out.println("   Interfaces:" + list);
        String iface = ask.ask("vip.interface", "Interface:", guess);
        String prefix = ask.ask("vip.prefix", "Prefix length:", ifaces.stream().filter(i -> i.name().equals(iface))
                .map(i -> Integer.toString(i.prefix())).findFirst().orElse("24"));
        Network.Iface chosen = ifaces.stream().filter(i -> i.name().equals(iface)).findFirst().orElse(null);
        if (chosen != null && chosen.contains(vip)) {
            out.println("   ✔ " + vip + " is in " + iface + "'s subnet");
        } else {
            out.println("   ! " + vip + " is not in " + iface + "'s subnet: clients may not reach it");
        }
        ProcessResult dad = host.runner.run(List.of("arping", "-D", "-q", "-c", "2", "-w", "3", "-I", iface, vip),
                Duration.ofSeconds(10));
        if (dad.exitCode() == 127) {
            out.println("   ! arping is not installed: the agent needs it for the duplicate address check");
        } else if (dad.ok()) {
            out.println("   ✔ nobody answers for " + vip + " (arping -D)");
        } else {
            out.println("   ! " + vip + " answers already (the current primary holds it?)");
        }
        net.put("vip", vip);
        net.put("iface", iface);
        net.put("prefix", prefix);
        return net;
    }

    /** Runs the checks, offers each fix; returns notes for the review (restarts needed). */
    private List<String> readiness(PgInstance pg, Join join, String replUser) {
        List<String> notes = new ArrayList<>();
        if (!pg.running()) {
            out.println("   ! PostgreSQL is stopped: its settings cannot be checked. Start it and run setup again,"
                    + " or let the agent start it later.");
            return notes;
        }
        Path data = Files.exists(host.path(pg.dataDir().toString())) ? host.path(pg.dataDir().toString())
                : pg.dataDir();
        List<Readiness.Check> checks = Readiness.run(sql -> host.psql(pg, sql), replUser, join.peers(),
                join.systemIdentifier(), data.toString());
        for (Readiness.Check c : checks) {
            String mark = switch (c.level()) {
                case OK -> "✔";
                case WARN -> "!";
                case FAIL -> "✖";
            };
            out.println("   " + mark + " " + c.name() + ": " + c.detail());
            if (c.fix() != null && c.level() != Readiness.Level.OK) {
                out.println("       fix: " + c.fix());
            }
        }
        boolean restart = false;
        for (Readiness.Check c : checks) {
            if (c.level() == Readiness.Level.OK || (c.fixSql().isEmpty() && c.hbaLines().isEmpty())) {
                continue;
            }
            String what = c.fixSql().isEmpty() ? "add to pg_hba.conf:\n         " + String.join("\n         ",
                    c.hbaLines()) + "\n       and reload" : String.join("; ", c.fixSql());
            if (!ask.confirm("fix." + c.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"),
                    "Apply now — " + what + "?", false)) {
                continue;
            }
            if (host.dryRun) {
                out.println("   (dry run: not applied)");
                continue;
            }
            try {
                for (String s : c.fixSql()) {
                    host.psql(pg, s);
                }
                if (!c.hbaLines().isEmpty()) {
                    String hba = host.psql(pg, "SHOW hba_file").get(0).get(0);
                    host.append(host.path(hba), "\n# added by minipgagent setup " + Instant.now() + "\n"
                            + String.join("\n", c.hbaLines()) + "\n", "600", "postgres");
                    host.psql(pg, "SELECT pg_reload_conf()");
                }
                out.println("   ✔ applied" + (c.needsRestart() ? " (takes effect after a restart)" : ""));
                restart |= c.needsRestart();
            } catch (Exception e) {
                out.println("   ✖ could not apply: " + e.getMessage());
            }
        }
        if (restart) {
            notes.add("restart PostgreSQL (" + pg.dataDir() + ") in a maintenance window for the changed settings;"
                    + " setup does not restart it");
        }
        return notes;
    }

    private String instanceName(Join join) {
        String dflt = join.cluster() == null ? "pg" : join.cluster().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        if (dflt.isEmpty() || dflt.startsWith("(")) {
            dflt = "pg";
        }
        dflt = dflt.length() > 32 ? dflt.substring(0, 32) : dflt;
        String name = ask.ask("instance", "Agent instance name (minipgagent@<name>):", dflt);
        if (!name.matches("[a-z0-9-]{1,32}")) {
            throw new Prompter.SetupException("instance names are 1 to 32 of a-z, 0-9 and '-'");
        }
        if (Files.exists(host.path("/etc/bfm/minipgagent/" + name + "/agent.properties"))) {
            throw new Prompter.SetupException("minipgagent@" + name + " exists already; run 'minipgagent remove "
                    + name + "' first or choose another name");
        }
        return name;
    }

    private Plan plan(PgInstance pg, Join join, Map<String, String> net, String instance, String replUser,
                      Path work) throws IOException {
        Plan plan = new Plan();
        String etc = "/etc/bfm/minipgagent/" + instance;
        Path dir = host.path(etc);
        String home = host.postgresHome();
        String passfile = home + "/.pgpass";
        Map<String, String> props = new LinkedHashMap<>();
        props.put("node.name", join.node());
        if (join.clusterId() != null) {
            props.put("cluster.id", join.clusterId());
        }
        props.put("listen.address", "0.0.0.0");
        props.put("listen.port", net.get("port"));
        props.put("tls.enabled", "true");
        props.put("tls.keystore", dir.resolve("node.p12").toString());
        props.put("tls.keystore.password.file", dir.resolve("node.pass").toString());
        props.put("tls.truststore", dir.resolve("ca.p12").toString());
        props.put("tls.truststore.password.file", dir.resolve("ca.pass").toString());
        props.put("state.dir", host.path("/var/lib/minipgagent/" + instance).toString());
        props.put("pg.bin", pg.binDir().toString());
        props.put("pg.data", pg.dataDir().toString());
        props.put("pg.port", Integer.toString(pg.port()));
        props.put("pg.socket.dir", pg.socketDir());
        props.put("replication.user", replUser);
        props.put("replication.passfile", passfile);
        if (net.get("vip") == null) {
            props.put("vip.enabled", "false");
        } else {
            props.put("vip.enabled", "true");
            props.put("vip.address", net.get("vip"));
            props.put("vip.prefix", net.get("prefix"));
            props.put("vip.interface", net.get("iface"));
        }
        // A watchdog reset takes every instance on the host down; setup never turns it on.
        props.put("watchdog.enabled", "false");
        StringBuilder text = new StringBuilder("# Written by minipgagent setup on " + Instant.now() + " for node "
                + join.node() + " of cluster " + join.cluster() + ".\n# Every setting: docs/agent-config.md\n");
        props.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));

        plan.mkdir(dir, "750", "postgres", "the instance's settings and certificates");
        plan.write(dir.resolve("agent.properties"), text.toString(), "600", "postgres",
                "node " + join.node() + ", cluster " + join.cluster() + ", port " + net.get("port"));
        if (!host.dryRun) {
            plan.write(dir.resolve("node.p12"), Files.readAllBytes(join.nodeStore()), "600", "postgres",
                    "this node's key and certificate");
            plan.write(dir.resolve("ca.p12"), Files.readAllBytes(join.caStore()), "600", "postgres", "the CA it trusts");
        } else {
            plan.note(String.format("%-7s %-52s %s", "write", dir.resolve("node.p12") + ", ca.p12",
                    "(this node's certificate and the CA)"));
        }
        plan.write(dir.resolve("node.pass"), join.nodePass() + "\n", "600", "postgres", null);
        plan.write(dir.resolve("ca.pass"), join.caPass() + "\n", "600", "postgres", null);
        if (pg.systemdUnit() != null) {
            plan.write(dir.resolve("pg.unit"), pg.systemdUnit() + "\n", "600", "postgres",
                    "remembers the PostgreSQL service, for 'minipgagent remove'");
        }
        plan.mkdir(host.path("/var/lib/minipgagent/" + instance), "700", "postgres", "the agent's state");
        if (join.clusterId() != null) {
            // The same file the agent keeps; with the cluster's name, messages name the cluster from the start.
            plan.write(host.path("/var/lib/minipgagent/" + instance + "/binding"), join.clusterId() + "\n"
                    + (join.cluster() == null ? "" : join.cluster() + "\n"), "600", "postgres", "bound to cluster "
                    + join.cluster());
        }

        if (net.get("vip") != null) {
            String rule = Sudoers.rule(net.get("vip"), net.get("prefix"), net.get("iface"));
            Path sudoers = host.path("/etc/sudoers.d/minipgagent-" + instance);
            plan.write(sudoers, rule, "440", "root", net.get("vip") + "/" + net.get("prefix") + " on "
                    + net.get("iface") + " only");
            plan.command(List.of("visudo", "-cf", sudoers.toString()), "check the sudo rule", !host.systemd);
        }
        String password = null;
        if (!pgpassHas(host.path(passfile), replUser)) {
            password = ask.secret("replication.password", "Password of " + replUser + " for .pgpass (empty: skip):");
            if (password != null) {
                plan.append(host.path(passfile), "*:*:*:" + replUser + ":" + password.replace(":", "\\:") + "\n", "600",
                        "postgres", "1 line for " + replUser);
            } else {
                plan.note(passfile + " has no line for " + replUser + ": replicas and rewinds will ask for a"
                        + " password");
            }
        }
        if (pg.systemdUnit() != null) {
            plan.command(List.of("systemctl", "disable", pg.systemdUnit()), "the agent starts PostgreSQL from now"
                    + " on (PostgreSQL keeps running)", !host.systemd);
        }
        plan.command(List.of("systemctl", "enable", "--now", "minipgagent@" + instance), "start the agent",
                !host.systemd);
        plan.note("watchdog: off");
        return plan;
    }

    private static boolean pgpassHas(Path file, String user) {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("(?<!\\\\):");
                if (f.length >= 5 && f[3].equals(user)) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private void verify(Join join, Map<String, String> net, String instance) {
        if (!host.systemd) {
            out.println("   Without systemd, start it yourself: minipgagent " + host.path("/etc/bfm/minipgagent/"
                    + instance + "/agent.properties"));
            return;
        }
        out.println("   Waiting for minipgagent@" + instance + " on port " + net.get("port") + "…");
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (!Network.portFree(Integer.parseInt(net.get("port")))) {
                out.println("   ✔ the agent listens. In the console, " + join.cluster() + " shows " + join.node()
                        + " within a few seconds.");
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        out.println("   ✖ the agent does not listen yet: journalctl -u minipgagent@" + instance);
    }

    private void section(String title) {
        out.println();
        out.println(" " + title);
    }

    private String hostname() {
        ProcessResult r = host.runner.run(List.of("hostname"), Duration.ofSeconds(5));
        return r.ok() ? r.stdout().trim() : "localhost";
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
