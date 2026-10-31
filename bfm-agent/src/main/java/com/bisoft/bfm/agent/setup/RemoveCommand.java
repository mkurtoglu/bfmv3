package com.bisoft.bfm.agent.setup;

import com.bisoft.bfm.agent.exec.ProcessRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * {@code minipgagent remove <instance>}: stops and disables the agent instance, sets its settings and sudo rule
 * aside, and offers to hand PostgreSQL back to its own service. PostgreSQL keeps running and its data is not
 * touched; the state directory stays for the record.
 */
public final class RemoveCommand {

    private RemoveCommand() {
    }

    public static int main(String[] args) {
        PrintStream out = Glyphs.wrap(System.out);
        Properties opt = new Properties();
        String instance = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--yes", "--no-systemd", "--dry-run" -> opt.setProperty(args[i].substring(2), "true");
                case "--root" -> opt.setProperty("root", args[++i]);
                default -> {
                    if (args[i].startsWith("--") || instance != null) {
                        System.err.println("usage: minipgagent remove INSTANCE [--yes] [--dry-run]");
                        return 64;
                    }
                    instance = args[i];
                }
            }
        }
        if (instance == null || !instance.matches("[a-z0-9-]{1,32}")) {
            System.err.println("usage: minipgagent remove INSTANCE [--yes] [--dry-run]");
            return 64;
        }
        Host host = new Host(Path.of(opt.getProperty("root", "/")), opt.containsKey("dry-run"),
                !opt.containsKey("no-systemd"), false, new ProcessRunner(), out);
        Prompter ask = new Prompter(new Properties(), new BufferedReader(new InputStreamReader(System.in,
                StandardCharsets.UTF_8)), out, opt.containsKey("yes"));
        try {
            return run(host, ask, out, instance);
        } catch (Prompter.SetupException e) {
            out.println(" ✖ " + e.getMessage());
            return 1;
        }
    }

    static int run(Host host, Prompter ask, PrintStream out, String instance) {
        Path dir = host.path("/etc/bfm/minipgagent/" + instance);
        if (!Files.isDirectory(dir)) {
            throw new Prompter.SetupException("no agent instance " + instance + " (" + dir + ")");
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path sudoers = host.path("/etc/sudoers.d/minipgagent-" + instance);
        String unit = read(dir.resolve("pg.unit")).trim();
        out.println(" This will:");
        out.println("   stop and disable minipgagent@" + instance + " (PostgreSQL keeps running)");
        out.println("   move " + dir + " to " + dir + ".removed-" + stamp);
        if (Files.exists(sudoers)) {
            out.println("   move " + sudoers + " to " + sudoers + ".removed-" + stamp + " (sudo ignores it there)");
        }
        out.println("   keep " + host.path("/var/lib/minipgagent/" + instance) + " (its state, for the record)");
        if (!unit.isEmpty()) {
            out.println("   then offer to enable " + unit + " again");
        }
        out.println(" The watchers see the node as unreachable: take it out of the cluster (or detach the cluster)"
                + " first, or expect alerts.");
        if (host.dryRun) {
            out.println(" Dry run: nothing was changed.");
            return 0;
        }
        if (!ask.confirm("proceed", "Proceed?", false)) {
            out.println(" Nothing was changed.");
            return 2;
        }
        host.systemctl("disable", "--now", "minipgagent@" + instance);
        try {
            Files.move(dir, dir.resolveSibling(dir.getFileName() + ".removed-" + stamp),
                    StandardCopyOption.ATOMIC_MOVE);
            if (Files.exists(sudoers)) {
                Files.move(sudoers, sudoers.resolveSibling(sudoers.getFileName() + ".removed-" + stamp),
                        StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new Prompter.SetupException("cannot move the settings aside: " + e.getMessage());
        }
        out.println(" ✔ minipgagent@" + instance + " removed");
        if (!unit.isEmpty() && ask.confirm("enable-pg-unit", "Let " + unit + " start PostgreSQL again at boot?",
                true)) {
            host.systemctl("enable", unit);
            out.println(" ✔ " + unit + " enabled (PostgreSQL itself was never stopped)");
        }
        return 0;
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
