package io.pgvip.core.monitor;

import io.pgvip.common.model.NodeStatus;
import io.pgvip.core.TimeSource;
import io.pgvip.core.agent.AgentClient;
import io.pgvip.core.config.ClusterConfig;
import io.pgvip.core.config.NodeConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Polls every agent in parallel and keeps a per-node count of consecutive failures. */
public class Poller implements AutoCloseable {

    private final ClusterConfig cfg;
    private final Map<String, AgentClient> agents;
    private final TimeSource time;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Integer> failures = new HashMap<>();

    public Poller(ClusterConfig cfg, Map<String, AgentClient> agents, TimeSource time) {
        this.cfg = cfg;
        this.agents = agents;
        this.time = time;
    }

    public ClusterView poll() {
        Map<String, Future<NodeStatus>> pending = new LinkedHashMap<>();
        for (NodeConfig n : cfg.nodes()) {
            AgentClient c = agents.get(n.name());
            pending.put(n.name(), executor.submit(c::status));
        }
        long deadline = time.nanos() + TimeUnit.MILLISECONDS.toNanos(cfg.timing().agentTimeoutMs() + 1_000);
        long now = time.millis();
        List<NodeView> views = new ArrayList<>();
        for (NodeConfig n : cfg.nodes()) {
            Future<NodeStatus> f = pending.get(n.name());
            NodeStatus status = null;
            String error = null;
            try {
                long waitNanos = Math.max(0, deadline - time.nanos());
                status = f.get(waitNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                error = "agent did not answer in time";
            } catch (ExecutionException e) {
                error = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error = "interrupted";
            }
            int fails;
            synchronized (failures) {
                fails = status == null ? failures.merge(n.name(), 1, Integer::sum) : 0;
                if (status != null) {
                    failures.put(n.name(), 0);
                }
            }
            views.add(new NodeView(n, status, error, now, fails));
        }
        return new ClusterView(views, now);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
