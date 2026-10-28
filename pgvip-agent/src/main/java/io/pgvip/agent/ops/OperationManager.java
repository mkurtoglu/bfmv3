package io.pgvip.agent.ops;

import io.pgvip.agent.AgentException;
import io.pgvip.common.model.OperationStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serialises everything that changes the node. Short commands run inline under the lock; long ones
 * (rewind, reinit) run in the background and report progress through {@link OperationStatus}.
 */
public class OperationManager {

    private static final Logger LOG = Logger.getLogger(OperationManager.class.getName());
    private static final int KEEP = 30;

    @FunctionalInterface
    public interface Body {
        String run(OpLog log) throws Exception;
    }

    private final ReentrantLock mutation = new ReentrantLock();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pgvip-operation");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Op> ops = new LinkedHashMap<>();
    private Op running;

    /** Runs {@code body} now if nothing else is changing the node; otherwise refuses. */
    public <T> T exclusive(String name, Callable<T> body) {
        synchronized (this) {
            if (running != null) {
                throw AgentException.conflict("busy with " + running.name);
            }
        }
        boolean locked;
        try {
            locked = mutation.tryLock(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("interrupted");
        }
        if (!locked) {
            throw AgentException.conflict("busy, try again (" + name + ")");
        }
        try {
            return body.call();
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException(name + " failed: " + e.getMessage());
        } finally {
            mutation.unlock();
        }
    }

    /** Starts a background operation and returns its id. */
    public synchronized OperationStatus start(String name, Body body) {
        if (running != null) {
            throw AgentException.conflict("busy with " + running.name);
        }
        Op op = new Op(UUID.randomUUID().toString(), name);
        running = op;
        ops.put(op.id, op);
        while (ops.size() > KEEP) {
            ops.remove(ops.keySet().iterator().next());
        }
        executor.submit(() -> execute(op, body));
        return op.status();
    }

    private void execute(Op op, Body body) {
        mutation.lock();
        try {
            String message = body.run(op::log);
            op.finish(OperationStatus.State.SUCCEEDED, message);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "operation " + op.name + " failed", e);
            op.log("error: " + e.getMessage());
            op.finish(OperationStatus.State.FAILED, e.getMessage());
        } finally {
            mutation.unlock();
            synchronized (this) {
                running = null;
            }
        }
    }

    public synchronized OperationStatus get(String id) {
        Op op = ops.get(id);
        if (op == null) {
            throw new AgentException(404, "no operation " + id);
        }
        return op.status();
    }

    /** Name of the running background operation, or null. */
    public synchronized String busy() {
        return running == null ? null : running.name;
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class Op {
        final String id;
        final String name;
        final long started = System.currentTimeMillis();
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        volatile OperationStatus.State state = OperationStatus.State.RUNNING;
        volatile String message = "running";
        volatile Long finished;

        Op(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void log(String line) {
            LOG.info(name + ": " + line);
            lines.add(line);
            if (lines.size() > 200) {
                lines.remove(0);
            }
        }

        void finish(OperationStatus.State s, String msg) {
            message = msg;
            finished = System.currentTimeMillis();
            state = s;
        }

        OperationStatus status() {
            List<String> copy;
            synchronized (lines) {
                copy = List.copyOf(lines);
            }
            return new OperationStatus(id, name, state, message, started, finished, copy);
        }
    }
}
