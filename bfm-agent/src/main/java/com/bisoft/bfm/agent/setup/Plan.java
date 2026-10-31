package com.bisoft.bfm.agent.setup;

import com.bisoft.bfm.agent.exec.ProcessResult;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** What setup will do, shown in full before anything happens, then done in order. */
final class Plan {

    private sealed interface Step permits Mkdir, Write, Append, Command, Note {
    }

    private record Mkdir(Path dir, String mode, String owner, String why) implements Step {
    }

    private record Write(Path file, byte[] content, String mode, String owner, String why) implements Step {
    }

    private record Append(Path file, String text, String mode, String owner, String why) implements Step {
    }

    private record Command(List<String> argv, String why, boolean skip) implements Step {
    }

    private record Note(String text) implements Step {
    }

    private final List<Step> steps = new ArrayList<>();

    void mkdir(Path dir, String mode, String owner, String why) {
        steps.add(new Mkdir(dir, mode, owner, why));
    }

    void write(Path file, String content, String mode, String owner, String why) {
        write(file, content.getBytes(StandardCharsets.UTF_8), mode, owner, why);
    }

    /**
     * Writes a file. A sudo rule is written beside its place first ({@code .new}, which sudo ignores) and moved
     * there only after {@code visudo -cf} accepted it: a broken rule would lock everyone out of sudo.
     */
    void write(Path file, byte[] content, String mode, String owner, String why) {
        steps.add(new Write(file, content, mode, owner, why));
    }

    void append(Path file, String text, String mode, String owner, String why) {
        steps.add(new Append(file, text, mode, owner, why));
    }

    void command(List<String> argv, String why, boolean skip) {
        steps.add(new Command(argv, why, skip));
    }

    void note(String text) {
        steps.add(new Note(text));
    }

    void print(PrintStream out) {
        out.println("   This will:");
        for (Step s : steps) {
            switch (s) {
                case Mkdir m -> out.println(line("create", m.dir().toString(), m.why()));
                case Write w -> {
                    if (w.why() != null) {
                        out.println(line("write", w.file().toString(), w.why()));
                    }
                }
                case Append a -> out.println(line("append", a.file().toString(), a.why()));
                case Command c -> out.println(line(c.skip() ? "(skip)" : "run", String.join(" ", c.argv()), c.why()));
                case Note n -> out.println("     " + n.text());
            }
        }
    }

    private static String line(String verb, String what, String why) {
        return String.format("     %-7s %-52s %s", verb, what, why == null ? "" : "(" + why + ")");
    }

    void apply(Host host, PrintStream out) {
        for (Step s : steps) {
            switch (s) {
                case Mkdir m -> host.mkdir(m.dir(), m.mode(), m.owner());
                case Write w -> {
                    if (w.file().getParent().endsWith("sudoers.d")) {
                        writeSudoers(host, w, out);
                    } else {
                        host.write(w.file(), w.content(), w.mode(), w.owner());
                    }
                }
                case Append a -> host.append(a.file(), a.text(), a.mode(), a.owner());
                case Command c -> {
                    if (c.argv().get(0).equals("visudo")) {
                        continue; // done with the rule it checks
                    }
                    ProcessResult r = c.argv().get(0).equals("systemctl")
                            ? host.systemctl(c.argv().subList(1, c.argv().size()).toArray(String[]::new))
                            : host.run(c.argv());
                    if (!r.ok()) {
                        throw new Prompter.SetupException(String.join(" ", c.argv()) + " failed: " + r.summary());
                    }
                }
                case Note n -> {
                }
            }
            boolean skipped = s instanceof Command c && (c.skip() || c.argv().get(0).equals("visudo"));
            if (!(s instanceof Note) && !skipped) {
                out.println("   ✔ " + describe(s));
            }
        }
    }

    private static void writeSudoers(Host host, Write w, PrintStream out) {
        Path staged = w.file().resolveSibling(w.file().getFileName() + ".new");
        host.write(staged, w.content(), w.mode(), w.owner());
        ProcessResult r = host.systemd ? host.run(List.of("visudo", "-cf", staged.toString()))
                : new ProcessResult(List.of("visudo"), 0, "", "", false);
        if (!r.ok()) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // it has a dot in its name: sudo ignores it either way
            }
            throw new Prompter.SetupException("visudo refused the sudo rule: " + r.summary());
        }
        try {
            if (!host.dryRun) {
                Files.move(staged, w.file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new Prompter.SetupException("cannot install " + w.file() + ": " + e.getMessage());
        }
    }

    private static String describe(Step s) {
        return switch (s) {
            case Mkdir m -> m.dir().toString();
            case Write w -> w.file().toString();
            case Append a -> a.file() + " (appended)";
            case Command c -> String.join(" ", c.argv());
            case Note n -> n.text();
        };
    }
}
