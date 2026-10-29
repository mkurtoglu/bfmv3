package com.bisoft.bfm.common.model;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;

import java.util.List;

/**
 * Everything an agent reports about its node in one poll.
 *
 * <p>Replica fields ({@code receiveLsn}, {@code replayLsn}, {@code walReceiverStatus}, upstream)
 * are null on a primary; primary fields ({@code currentLsn}, {@code replicas}, {@code slots})
 * are null or empty on a replica. {@code vipAddress} is null when the agent does not manage a VIP.
 */
public record NodeStatus(
        String node,
        String agentVersion,
        long agentTimeMillis,
        boolean pgRunning,
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
        String vipAddress,
        boolean vipHeld,
        long epoch,
        LeaseInfo lease,
        boolean fenced,
        String fencedReason,
        String controlClusterState,
        String controlCheckpointLsn,
        Integer controlTimeline,
        String busyOperation,
        String error) implements JsonWritable {

    public boolean isPrimary() {
        return pgRunning && role == PgRole.PRIMARY;
    }

    public boolean isReplica() {
        return pgRunning && role == PgRole.REPLICA;
    }

    /** The furthest WAL position this node has: current for a primary, received (or replayed) for a replica. */
    public String bestLsn() {
        if (role == PgRole.PRIMARY) {
            return currentLsn;
        }
        if (receiveLsn != null) {
            return receiveLsn;
        }
        if (replayLsn != null) {
            return replayLsn;
        }
        return controlCheckpointLsn;
    }

    public boolean streaming() {
        return "streaming".equalsIgnoreCase(walReceiverStatus);
    }

    @Override
    public Object toJsonValue() {
        return JsonMap.create()
                .put("node", node)
                .put("agentVersion", agentVersion)
                .put("agentTimeMillis", agentTimeMillis)
                .put("pgRunning", pgRunning)
                .put("role", role)
                .put("timeline", timeline)
                .put("systemIdentifier", systemIdentifier)
                .put("serverVersionNum", serverVersionNum)
                .put("currentLsn", currentLsn)
                .put("receiveLsn", receiveLsn)
                .put("replayLsn", replayLsn)
                .put("replayDelayMs", replayDelayMs)
                .put("walReceiverStatus", walReceiverStatus)
                .put("upstreamHost", upstreamHost)
                .put("upstreamPort", upstreamPort)
                .put("replicas", replicas == null ? List.of() : replicas)
                .put("slots", slots == null ? List.of() : slots)
                .put("vipAddress", vipAddress)
                .put("vipHeld", vipHeld)
                .put("epoch", epoch)
                .put("lease", lease == null ? LeaseInfo.NONE : lease)
                .put("fenced", fenced)
                .put("fencedReason", fencedReason)
                .put("controlClusterState", controlClusterState)
                .put("controlCheckpointLsn", controlCheckpointLsn)
                .put("controlTimeline", controlTimeline)
                .put("busyOperation", busyOperation)
                .put("error", error)
                .raw();
    }

    public static NodeStatus fromJson(String json) {
        return from(Json.parseObject(json));
    }

    public static NodeStatus from(JsonMap m) {
        return new NodeStatus(
                m.str("node"),
                m.str("agentVersion"),
                m.lng("agentTimeMillis", 0),
                m.bool("pgRunning", false),
                m.enumValue("role", PgRole.class, PgRole.UNKNOWN),
                m.intOrNull("timeline"),
                m.str("systemIdentifier"),
                m.intOrNull("serverVersionNum"),
                m.str("currentLsn"),
                m.str("receiveLsn"),
                m.str("replayLsn"),
                m.lngOrNull("replayDelayMs"),
                m.str("walReceiverStatus"),
                m.str("upstreamHost"),
                m.intOrNull("upstreamPort"),
                m.objList("replicas", ReplicationPeer::from),
                m.objList("slots", SlotInfo::from),
                m.str("vipAddress"),
                m.bool("vipHeld", false),
                m.lng("epoch", 0),
                LeaseInfo.from(m.obj("lease")),
                m.bool("fenced", false),
                m.str("fencedReason"),
                m.str("controlClusterState"),
                m.str("controlCheckpointLsn"),
                m.intOrNull("controlTimeline"),
                m.str("busyOperation"),
                m.str("error"));
    }
}
