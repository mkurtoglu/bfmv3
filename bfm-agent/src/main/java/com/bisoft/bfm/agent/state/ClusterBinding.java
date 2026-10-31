package com.bisoft.bfm.agent.state;

import com.bisoft.bfm.agent.AgentException;
import com.bisoft.bfm.common.json.JsonMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Which cluster this agent belongs to. With several clusters on one host, a watcher pointed at the wrong port would
 * otherwise steer another cluster's PostgreSQL; a bound agent refuses every command that names another cluster.
 *
 * <p>The cluster comes from {@code cluster.id} in the settings (the setup wizard writes it), or, for an agent set up
 * by hand, from the first command that carries one (trust on first use). It is kept in {@code state.dir/binding}
 * (first line the id, second line the cluster's name as the watchers last sent it, for messages only) so a restart
 * does not forget it.
 */
public class ClusterBinding {

    private static final Logger LOG = Logger.getLogger(ClusterBinding.class.getName());

    private final Path file;
    private final String node;
    private String clusterId;
    private String clusterName;

    public ClusterBinding(Path stateDir, String configured, String node) throws IOException {
        Files.createDirectories(stateDir);
        this.file = stateDir.resolve("binding");
        this.node = node;
        String storedId = null;
        String storedName = null;
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            storedId = lines.isEmpty() || lines.get(0).isBlank() ? null : lines.get(0).trim();
            storedName = lines.size() < 2 || lines.get(1).isBlank() ? null : lines.get(1).trim();
        }
        if (configured != null && !configured.isBlank()) {
            String id = configured.trim();
            if (storedId != null && !storedId.equals(id)) {
                LOG.warning("cluster.id in the settings (" + id + ") replaces the binding to cluster "
                        + describe(storedId, storedName) + " kept in " + file);
                storedName = null;
            }
            clusterId = id;
            clusterName = storedName;
            if (!id.equals(storedId)) {
                write();
            }
        } else {
            clusterId = storedId;
            clusterName = storedId == null ? null : storedName;
        }
    }

    /** The cluster this agent is bound to, or null. */
    public synchronized String clusterId() {
        return clusterId;
    }

    /** The bound cluster's name as its watchers last sent it, or null. */
    public synchronized String clusterName() {
        return clusterName;
    }

    /**
     * Checks a command against the binding, before anything else about it (its epoch included) is looked at.
     *
     * @throws AgentException 409 when the command is for another cluster, or carries no cluster id while this agent
     *                        is bound to one
     */
    public synchronized void check(JsonMap body) {
        String sent = blankToNull(body.str("clusterId"));
        String sentName = blankToNull(body.str("clusterName"));
        if (clusterId != null && sent != null && !clusterId.equals(sent)) {
            throw AgentException.conflict("this agent belongs to cluster " + describe(clusterId, clusterName)
                    + " as " + node + "; the command is for cluster " + describe(sent, sentName)
                    + " (check the agent URL and port)");
        }
        if (clusterId != null && sent == null) {
            throw AgentException.conflict("this agent belongs to cluster " + describe(clusterId, clusterName)
                    + " as " + node + "; the command names no cluster (a watcher older than this agent?)");
        }
        if (clusterId == null && sent != null) {
            clusterId = sent;
            clusterName = sentName;
            write();
            LOG.info("bound to cluster " + describe(sent, sentName) + " as " + node + " on first contact");
        } else if (sent != null && sentName != null && !sentName.equals(clusterName)) {
            clusterName = sentName;
            write();
        }
    }

    /** Forgets the cluster, when a cluster is detached from its watchers. */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        clusterId = null;
        clusterName = null;
    }

    private static String describe(String id, String name) {
        return name == null || Objects.equals(name, id) ? id : name + " (" + id + ")";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void write() {
        try {
            Path tmp = file.resolveSibling("binding.tmp");
            Files.writeString(tmp, clusterId + "\n" + (clusterName == null ? "" : clusterName + "\n"),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
