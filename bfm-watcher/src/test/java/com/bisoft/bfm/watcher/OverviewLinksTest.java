package com.bisoft.bfm.watcher;

import com.bisoft.bfm.core.state.ClusterState;
import com.bisoft.bfm.watcher.service.ConsoleService.Overview;
import com.bisoft.bfm.watcher.service.ConsoleService.WatcherRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A running operation is followed where it runs: on this watcher, or on the peer whose console is known. */
class OverviewLinksTest {

    private static Overview overview(String self, ClusterState.OperationRef op, List<WatcherRow> watchers) {
        return new Overview("prod-main", self, "RAFT", false, "w1", "https://10.0.9.1:8443", 3, 7, "pg-a1",
                "10.0.0.11:5432", "DC1", 1, "Healthy", true, false, true, false, "DC_LOCAL", true,
                "192.168.0.50", "pg-a1", 0L, null, null, null, op, List.of(), List.of(), watchers, 0);
    }

    private static WatcherRow row(String id, String url) {
        return new WatcherRow(id, id + ":9870", false, "w1".equals(id), 0L, true, 0L, url);
    }

    private static final ClusterState.OperationRef OP =
            new ClusterState.OperationRef("op-1", "SWITCHOVER", "pg-a2", "w1", "admin", 0);

    @Test
    void ownOperationHasNoRemoteLink() {
        assertNull(overview("w1", OP, List.of(row("w1", "https://10.0.9.1:8443"))).operationConsoleUrl());
    }

    @Test
    void aFollowerLinksToTheConsoleOfTheWatcherRunningIt() {
        Overview o = overview("w2", OP, List.of(row("w1", "https://10.0.9.1:8443/"), row("w2", "https://10.0.9.2:8443")));
        assertEquals("https://10.0.9.1:8443/operations", o.operationConsoleUrl());
    }

    @Test
    void noLinkWithoutAConfiguredConsoleAddress() {
        assertNull(overview("w2", OP, List.of(row("w1", null), row("w2", null))).operationConsoleUrl());
        assertNull(overview("w2", null, List.of(row("w1", "https://10.0.9.1:8443"))).operationConsoleUrl());
    }
}
