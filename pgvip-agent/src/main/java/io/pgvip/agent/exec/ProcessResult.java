package io.pgvip.agent.exec;

import java.util.List;

public record ProcessResult(List<String> argv, int exitCode, String stdout, String stderr, boolean timedOut) {

    public boolean ok() {
        return !timedOut && exitCode == 0;
    }

    /** One-line description for logs and API messages. */
    public String summary() {
        String cmd = argv.isEmpty() ? "" : argv.get(0);
        if (timedOut) {
            return cmd + " timed out";
        }
        String detail = stderr == null || stderr.isBlank() ? stdout : stderr;
        detail = detail == null ? "" : detail.strip();
        if (detail.length() > 400) {
            detail = detail.substring(0, 400) + "...";
        }
        return cmd + " exit " + exitCode + (detail.isEmpty() ? "" : ": " + detail.replace('\n', ' '));
    }
}
