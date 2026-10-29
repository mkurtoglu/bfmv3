package com.bisoft.bfm.agent.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs an external program without a shell, with a hard timeout and captured output. */
public class ProcessRunner {

    public ProcessResult run(List<String> argv, Duration timeout) {
        return run(argv, timeout, Map.of());
    }

    public ProcessResult run(List<String> argv, Duration timeout, Map<String, String> env) {
        if (argv.isEmpty()) {
            return new ProcessResult(argv, -1, "", "empty command", false);
        }
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().putAll(env);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new ProcessResult(argv, 127, "", "cannot start " + argv.get(0) + ": " + e.getMessage(), false);
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // nothing to write
        }
        StreamCollector out = new StreamCollector(process.getInputStream());
        StreamCollector err = new StreamCollector(process.getErrorStream());
        out.start();
        err.start();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        out.await();
        err.await();
        int exit = finished ? process.exitValue() : -1;
        return new ProcessResult(argv, exit, out.text(), err.text(), !finished);
    }

    private static final class StreamCollector extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        StreamCollector(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try {
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    synchronized (buf) {
                        if (buf.size() < 4 * 1024 * 1024) {
                            buf.write(chunk, 0, n);
                        }
                    }
                }
            } catch (IOException ignored) {
                // stream closed when the process ends
            }
        }

        void await() {
            try {
                join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        String text() {
            synchronized (buf) {
                return buf.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
