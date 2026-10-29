package com.bisoft.bfm.core.agent;

import com.bisoft.bfm.core.config.NodeConfig;

/** Creates the client the watcher uses to talk to one node's agent. */
@FunctionalInterface
public interface AgentClientFactory {

    AgentClient create(NodeConfig node);
}
