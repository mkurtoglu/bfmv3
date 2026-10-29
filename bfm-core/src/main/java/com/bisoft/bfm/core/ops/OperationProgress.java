package com.bisoft.bfm.core.ops;

import com.bisoft.bfm.core.TimeSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Live progress of one watcher operation (failover, switchover, reinit, rejoin, start), shown step by step in the
 * UI. Thread-safe; readers take {@link #snapshot()}.
 */
public final class OperationProgress {

    public enum State { RUNNING, SUCCEEDED, FAILED }

    public enum StepState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

    /**
     * @param startedAtMillis  when the step began running, null if it never ran
     * @param finishedAtMillis when it ended (done, failed or skipped), null while pending or running
     */
    public record Step(String key, String title, StepState state, String detail, Long startedAtMillis,
                       Long finishedAtMillis) {

        /** How long the step ran, or null when it did not run to an end. */
        public Long durationMs() {
            return startedAtMillis == null || finishedAtMillis == null ? null : finishedAtMillis - startedAtMillis;
        }
    }

    public record LogLine(long timeMillis, String source, String text) {
    }

    public record Snapshot(String id, String type, String target, String startedBy, long startedAtMillis,
                           Long finishedAtMillis, State state, String result, long epochFrom, long epochTo,
                           List<Step> steps, List<LogLine> log) {
        public boolean running() {
            return state == State.RUNNING;
        }

        /** Total run time, or null while running. */
        public Long durationMs() {
            return finishedAtMillis == null ? null : finishedAtMillis - startedAtMillis;
        }
    }

    private static final int MAX_LOG = 500;

    private final String id = UUID.randomUUID().toString();
    private final String type;
    private final String target;
    private final String startedBy;
    private final long startedAtMillis;
    private final TimeSource time;
    private final List<Step> steps = new ArrayList<>();
    private final List<LogLine> log = new ArrayList<>();
    private State state = State.RUNNING;
    private String result;
    private Long finishedAtMillis;
    private long epochFrom;
    private long epochTo;

    public OperationProgress(String type, String target, String startedBy, TimeSource time, List<String[]> plan) {
        this.type = type;
        this.target = target;
        this.startedBy = startedBy;
        this.time = time;
        this.startedAtMillis = time.millis();
        for (String[] s : plan) {
            steps.add(new Step(s[0], s[1], StepState.PENDING, null, null, null));
        }
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String target() {
        return target;
    }

    public String startedBy() {
        return startedBy;
    }

    public synchronized void epochs(long from, long to) {
        this.epochFrom = from;
        this.epochTo = to;
    }

    public synchronized void running(String key) {
        set(key, StepState.RUNNING, null);
    }

    public synchronized void done(String key, String detail) {
        set(key, StepState.DONE, detail);
    }

    public synchronized void failed(String key, String detail) {
        set(key, StepState.FAILED, detail);
    }

    public synchronized void skipped(String key, String detail) {
        set(key, StepState.SKIPPED, detail);
    }

    /** Renames a step, for example once the VIP address of the target is known. */
    public synchronized void retitle(String key, String title) {
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.key().equals(key)) {
                steps.set(i, new Step(key, title, s.state(), s.detail(), s.startedAtMillis(), s.finishedAtMillis()));
                return;
            }
        }
    }

    private void set(String key, StepState st, String detail) {
        long now = time.millis();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.key().equals(key)) {
                Long started = st == StepState.RUNNING ? Long.valueOf(now) : s.startedAtMillis();
                Long finished = st == StepState.RUNNING || st == StepState.PENDING ? null : Long.valueOf(now);
                steps.set(i, new Step(key, s.title(), st, detail == null ? s.detail() : detail, started, finished));
                return;
            }
        }
        steps.add(new Step(key, key, st, detail, st == StepState.RUNNING ? now : null,
                st == StepState.RUNNING || st == StepState.PENDING ? null : now));
    }

    public synchronized void log(String source, String text) {
        log.add(new LogLine(time.millis(), source, text));
        if (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    public synchronized void succeed(String message) {
        finish(State.SUCCEEDED, message);
    }

    public synchronized void fail(String message) {
        finish(State.FAILED, message);
    }

    private void finish(State s, String message) {
        if (state != State.RUNNING) {
            return;
        }
        state = s;
        result = message;
        finishedAtMillis = time.millis();
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (step.state() == StepState.PENDING || step.state() == StepState.RUNNING) {
                steps.set(i, new Step(step.key(), step.title(),
                        step.state() == StepState.RUNNING && s == State.FAILED ? StepState.FAILED : StepState.SKIPPED,
                        step.detail(), step.startedAtMillis(),
                        step.state() == StepState.RUNNING ? finishedAtMillis : null));
            }
        }
    }

    public synchronized boolean finished() {
        return state != State.RUNNING;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(id, type, target, startedBy, startedAtMillis, finishedAtMillis, state, result, epochFrom,
                epochTo, List.copyOf(steps), List.copyOf(log));
    }
}
