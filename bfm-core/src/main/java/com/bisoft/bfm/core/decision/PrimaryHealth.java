package com.bisoft.bfm.core.decision;

import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.core.monitor.NodeView;

/** How the node named primary looked in one poll. */
public enum PrimaryHealth {
    /** Agent answered, PostgreSQL runs as primary, not fenced. */
    OK,
    /** The agent did not answer; PostgreSQL may or may not be running. */
    AGENT_UNREACHABLE,
    /** The agent answered and PostgreSQL is not running (or does not answer SQL). */
    PG_DOWN,
    /** The agent fenced the node (lease lost or fenced by a watcher). */
    FENCED,
    /** PostgreSQL runs, but in recovery. */
    NOT_PRIMARY;

    public static PrimaryHealth of(NodeView v) {
        if (v == null || !v.reachable()) {
            return AGENT_UNREACHABLE;
        }
        if (v.status().fenced()) {
            return FENCED;
        }
        if (!v.status().pgRunning() || v.status().role() == PgRole.UNKNOWN) {
            return PG_DOWN;
        }
        if (v.status().role() == PgRole.REPLICA) {
            return NOT_PRIMARY;
        }
        return OK;
    }

    /** The agent answered, so the node's own state is known. */
    public boolean agentAnswered() {
        return this != AGENT_UNREACHABLE;
    }

    public String describe(String node) {
        return switch (this) {
            case OK -> node + " is running as primary";
            case AGENT_UNREACHABLE -> "agent on " + node + " does not answer";
            case PG_DOWN -> "PostgreSQL on " + node + " is not running";
            case FENCED -> node + " is fenced";
            case NOT_PRIMARY -> node + " is running in recovery, not as primary";
        };
    }
}
