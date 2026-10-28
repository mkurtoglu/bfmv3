package io.pgvip.agent;

import io.pgvip.agent.exec.ProcessResult;
import io.pgvip.agent.exec.ProcessRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Records commands and answers them from a script instead of running anything. */
public class FakeRunner extends ProcessRunner {

    public final List<String> calls = new ArrayList<>();
    private final Function<List<String>, ProcessResult> script;

    public FakeRunner(Function<List<String>, ProcessResult> script) {
        this.script = script;
    }

    @Override
    public ProcessResult run(List<String> argv, Duration timeout, Map<String, String> env) {
        calls.add(String.join(" ", argv));
        return script.apply(argv);
    }

    public static ProcessResult ok(List<String> argv, String stdout) {
        return new ProcessResult(argv, 0, stdout, "", false);
    }

    public static ProcessResult exit(List<String> argv, int code, String stderr) {
        return new ProcessResult(argv, code, "", stderr, false);
    }

    public long count(String fragment) {
        return calls.stream().filter(c -> c.contains(fragment)).count();
    }

    public List<String> matching(String fragment) {
        List<String> out = new ArrayList<>();
        for (String c : calls) {
            if (c.contains(fragment)) {
                out.add(c);
            }
        }
        return out;
    }
}
