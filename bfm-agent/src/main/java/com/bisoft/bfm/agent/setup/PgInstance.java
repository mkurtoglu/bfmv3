package com.bisoft.bfm.agent.setup;

import java.nio.file.Path;

/**
 * A PostgreSQL instance found on this host.
 *
 * @param role        "primary", "replica" or "?" (stopped without standby.signal)
 * @param managedBy   the agent instance that already runs it ({@code minipgagent@erp}), or null
 * @param systemdUnit the PostgreSQL service that starts it today, or null
 */
public record PgInstance(Path dataDir, String version, int port, boolean running, String role, Path binDir,
                         String socketDir, String managedBy, String systemdUnit) {

    public String major() {
        int dot = version == null ? -1 : version.indexOf('.');
        return version == null ? "?" : dot < 0 ? version : version.substring(0, dot);
    }
}
