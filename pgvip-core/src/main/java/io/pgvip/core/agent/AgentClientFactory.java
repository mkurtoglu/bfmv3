package io.pgvip.core.agent;

import io.pgvip.core.config.NodeConfig;

/** Creates the client the watcher uses to talk to one node's agent. */
@FunctionalInterface
public interface AgentClientFactory {

    AgentClient create(NodeConfig node);
}
