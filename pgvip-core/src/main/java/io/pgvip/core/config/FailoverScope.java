package io.pgvip.core.config;

public enum FailoverScope {
    /** Automatic failover only promotes a replica in the old primary's data center. */
    DC_LOCAL,
    /** Automatic failover may promote any eligible replica. */
    ANY
}
