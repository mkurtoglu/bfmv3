package com.bisoft.bfm.common.model;

public enum PgRole {
    /** Running and accepting writes (not in recovery). */
    PRIMARY,
    /** Running in recovery (streaming or archive replica). */
    REPLICA,
    /** Not running or not reachable through SQL. */
    UNKNOWN
}
