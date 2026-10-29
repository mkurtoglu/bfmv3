package com.bisoft.bfm.agent.pg;

import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.common.model.ReplicationPeer;
import com.bisoft.bfm.common.model.SlotInfo;

import java.util.List;

/** What one probe of the local PostgreSQL found. */
public record PgSnapshot(
        boolean running,
        PgRole role,
        Integer timeline,
        String systemIdentifier,
        Integer serverVersionNum,
        String currentLsn,
        String receiveLsn,
        String replayLsn,
        Long replayDelayMs,
        String walReceiverStatus,
        String upstreamHost,
        Integer upstreamPort,
        List<ReplicationPeer> replicas,
        List<SlotInfo> slots,
        ControlData control,
        String error) {

    public static PgSnapshot stopped(ControlData control, String error) {
        return new PgSnapshot(false, PgRole.UNKNOWN, null, null, null, null, null, null, null, null, null, null,
                List.of(), List.of(), control, error);
    }

    public boolean isPrimary() {
        return running && role == PgRole.PRIMARY;
    }
}
