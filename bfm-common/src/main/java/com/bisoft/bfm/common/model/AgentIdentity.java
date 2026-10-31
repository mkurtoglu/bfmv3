package com.bisoft.bfm.common.model;

import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

/**
 * Who an agent is and where it runs, so a watcher can tell it is talking to the right node and can see which
 * instances share a host.
 *
 * @param clusterId   the cluster this agent is bound to, null while it is not bound
 * @param clusterName that cluster's name as its watchers last sent it, null when unknown
 * @param instance    the agent instance name on its host ({@code minipgagent@<instance>}), null for the default one
 * @param hostId      the host's {@code /etc/machine-id}, null when unreadable
 * @param hostname    the host name
 */
public record AgentIdentity(String clusterId, String clusterName, String instance, String hostId, String hostname)
        implements JsonWritable {

    public static final AgentIdentity UNKNOWN = new AgentIdentity(null, null, null, null, null);

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("clusterId", clusterId)
                .put("clusterName", clusterName)
                .put("instance", instance)
                .put("hostId", hostId)
                .put("hostname", hostname)
                .raw();
    }

    public static AgentIdentity from(JsonMap m) {
        if (m == null) {
            return UNKNOWN;
        }
        return new AgentIdentity(m.str("clusterId"), m.str("clusterName"), m.str("instance"), m.str("hostId"),
                m.str("hostname"));
    }

    /** The bound cluster for messages: its name and id, or only the id; null while unbound. */
    public String cluster() {
        if (clusterId == null) {
            return null;
        }
        return clusterName == null || clusterName.equals(clusterId) ? clusterId : clusterName + " (" + clusterId + ")";
    }
}
