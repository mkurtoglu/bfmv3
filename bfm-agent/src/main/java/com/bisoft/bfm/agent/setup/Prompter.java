package com.bisoft.bfm.agent.setup;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Properties;

/**
 * Asks the operator, or reads the answer from an answers file ({@code --answers}, for Ansible and the like). With
 * an answers file every question must be answered there or have a default: setup never waits for a terminal that
 * is not there.
 */
public class Prompter {

    private final Properties answers;
    private final BufferedReader in;
    private final PrintStream out;
    private final boolean interactive;
    private final boolean yes;

    /**
     * @param in  the terminal, or null when there is none
     * @param yes {@code --yes}: every confirmation is answered yes
     */
    public Prompter(Properties answers, BufferedReader in, PrintStream out, boolean yes) {
        this.answers = answers;
        this.in = in;
        this.out = out;
        this.interactive = in != null;
        this.yes = yes;
    }

    public String ask(String key, String question, String dflt) {
        String a = answers.getProperty(key);
        if (a != null) {
            out.println(" " + question + " " + a + "   (" + key + " from the answers)");
            return a.trim();
        }
        if (!interactive) {
            if (dflt != null) {
                out.println(" " + question + " " + dflt);
                return dflt;
            }
            throw new SetupException("no answer for " + key + " (" + question + ")");
        }
        out.print(" " + question + (dflt == null || dflt.isEmpty() ? "" : " [" + dflt + "]") + " ");
        out.flush();
        String line = readLine();
        return line == null || line.isBlank() ? dflt : line.trim();
    }

    /** A password: never echoed, never printed back. */
    public String secret(String key, String question) {
        String a = answers.getProperty(key);
        if (a != null) {
            return a.isEmpty() ? null : a;
        }
        if (!interactive) {
            return null;
        }
        Console c = System.console();
        if (c != null) {
            char[] p = c.readPassword(" " + question + " ");
            return p == null || p.length == 0 ? null : new String(p);
        }
        out.print(" " + question + " ");
        out.flush();
        String line = readLine();
        return line == null || line.isEmpty() ? null : line;
    }

    public boolean confirm(String key, String question, boolean dflt) {
        String a = answers.getProperty(key);
        if (a != null) {
            boolean v = a.trim().equalsIgnoreCase("yes") || a.trim().equalsIgnoreCase("true") || a.trim().equals("y");
            out.println(" " + question + " " + (v ? "yes" : "no") + "   (" + key + " from the answers)");
            return v;
        }
        if (yes) {
            out.println(" " + question + " yes   (--yes)");
            return true;
        }
        if (!interactive) {
            out.println(" " + question + " " + (dflt ? "yes" : "no"));
            return dflt;
        }
        out.print(" " + question + (dflt ? " [Y/n] " : " [y/N] "));
        out.flush();
        String line = readLine();
        if (line == null || line.isBlank()) {
            return dflt;
        }
        return line.trim().toLowerCase().startsWith("y");
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /** Setup cannot go on; the message says why and what to do. */
    public static final class SetupException extends RuntimeException {
        public SetupException(String message) {
            super(message);
        }
    }
}
