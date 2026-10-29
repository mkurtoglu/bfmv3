package com.bisoft.bfm.core;

import com.bisoft.bfm.common.Lsn;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.LeaseInfo;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.OperationStatus;
import com.bisoft.bfm.common.model.PgRole;
import com.bisoft.bfm.common.model.ReplicationPeer;
import com.bisoft.bfm.common.model.SlotInfo;
import com.bisoft.bfm.core.agent.AgentCallException;
import com.bisoft.bfm.core.agent.AgentClient;
import com.bisoft.bfm.core.agent.AgentClientFactory;
import com.bisoft.bfm.core.config.NodeConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory cluster whose agents follow the real agent's rules: epochs, "no VIP, no promote", no start as
 * primary without permission, lease self-fencing, clean shutdown checkpoints and timeline forks.
 */
public final class FakeCluster implements AgentClientFactory {

    public static final String VIP = "192.168.0.50";

    public final ManualTime time;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, String> vipHolder = new HashMap<>();
    /** Every command any agent accepted, in order: "node: command". */
    public final List<String> log = new CopyOnWriteArrayList<>();

    public FakeCluster(ManualTime time) {
        this.time = time;
    }

    public final class Node implements AgentClient {
        public final String name;
        public final String host;
        public final String dc;
        public String vip = VIP;
        public boolean agentUp = true;
        public boolean hostUp = true;
        public boolean pgRunning;
        public PgRole role = PgRole.REPLICA;
        public int timeline = 1;
        public long lsn = 0x3000000L;
        public long forkLsn = -1;
        public long lag;
        public String upstreamHost;
        public boolean standbySignal = true;
        public long epoch;
        public boolean fenced;
        public String fencedReason;
        public boolean leaseGranted;
        public long leaseDeadline;
        public boolean fenceOnExpiry;
        public boolean vipAddFails;
        public boolean dadBusy;
        public boolean promoteFails;
        /** Promotes, but the answer never reaches the watcher (timeout). */
        public boolean promoteTimesOut;
        /** fence.action=vip-only: fencing releases the VIP and leaves PostgreSQL running. */
        public boolean fenceKeepsRunning;
        /** Called right after a successful promotion, before the answer goes back. */
        public Runnable onPromote;
        public boolean rewindFails;
        public String clusterState = "in production";
        public long checkpointLsn;
        public final Set<String> slots = new TreeSet<>();
        private final Map<String, OperationStatus> ops = new HashMap<>();

        Node(String name, String host, String dc) {
            this.name = name;
            this.host = host;
            this.dc = dc;
        }

        @Override
        public String node() {
            return name;
        }

        public boolean vipHeld() {
            return name.equals(vipHolder.get(vip));
        }

        private void reachable() {
            if (!agentUp) {
                throw AgentCallException.unreachable(name, "unreachable: connection refused");
            }
        }

        private void epoch(long e) {
            if (e < epoch) {
                throw new AgentCallException(name, 409, "stale epoch " + e + " (agent is at " + epoch + ")");
            }
            epoch = e;
        }

        private AgentCallException conflict(String msg) {
            return new AgentCallException(name, 409, msg);
        }

        private void did(String what) {
            log.add(name + ": " + what);
        }

        @Override
        public NodeStatus status() {
            synchronized (FakeCluster.this) {
                reachable();
                sync();
                Node up = upstream();
                boolean streaming = isStreaming();
                List<ReplicationPeer> peers = new ArrayList<>();
                List<SlotInfo> slotInfos = new ArrayList<>();
                if (pgRunning && role == PgRole.PRIMARY) {
                    for (Node r : nodes.values()) {
                        if (r != this && r.isStreaming() && r.upstream() == this) {
                            peers.add(new ReplicationPeer(r.name, r.host, "streaming", "async", Lsn.format(r.lsn),
                                    Lsn.format(r.lsn), Lsn.format(r.lsn), Lsn.format(r.lsn), 0L));
                        }
                    }
                    for (String s : slots) {
                        boolean active = nodes.values().stream().anyMatch(r -> r != this && r.isStreaming()
                                && r.upstream() == this && NodeConfig.defaultSlot(r.name).equals(s));
                        slotInfos.add(new SlotInfo(s, "physical", active, Lsn.format(lsn), 0L));
                    }
                }
                boolean primary = pgRunning && role == PgRole.PRIMARY;
                return new NodeStatus(name, "test", time.millis(), pgRunning, pgRunning ? role : PgRole.UNKNOWN,
                        pgRunning ? timeline : null, "7000000000000000001", 160004,
                        primary ? Lsn.format(lsn) : null,
                        !primary && pgRunning ? Lsn.format(lsn) : null,
                        !primary && pgRunning ? Lsn.format(lsn) : null, 0L,
                        !primary && pgRunning ? (streaming ? "streaming" : null) : null,
                        !primary && pgRunning ? upstreamHost : null,
                        !primary && pgRunning && upstreamHost != null ? 5432 : null,
                        peers, slotInfos, vip, vipHeld(), epoch, leaseInfo(), fenced, fencedReason,
                        pgRunning ? null : clusterState, Lsn.format(checkpointLsn), timeline,
                        null, up == null && !primary && pgRunning ? "no upstream" : null);
            }
        }

        private LeaseInfo leaseInfo() {
            if (!leaseGranted) {
                return LeaseInfo.NONE;
            }
            return new LeaseInfo(true, Math.max(0, (leaseDeadline - time.nanos()) / 1_000_000L), epoch, fenceOnExpiry);
        }

        /** Lease expiry and replication progress, applied lazily. */
        void sync() {
            if (leaseGranted && fenceOnExpiry && pgRunning && role == PgRole.PRIMARY && !fenced
                    && time.nanos() - leaseDeadline >= 0) {
                selfFence("primary lease expired (epoch " + epoch + ")");
            }
            if (isStreaming()) {
                Node up = upstream();
                timeline = up.timeline;
                lsn = Math.max(lsn, up.lsn - lag);
            }
        }

        Node upstream() {
            if (upstreamHost == null) {
                return null;
            }
            return nodes.values().stream().filter(n -> n.host.equals(upstreamHost)).findFirst().orElse(null);
        }

        boolean isStreaming() {
            if (!pgRunning || role != PgRole.REPLICA) {
                return false;
            }
            Node up = upstream();
            if (up == null || !up.pgRunning || up.role != PgRole.PRIMARY || !up.hostUp || !hostUp) {
                return false;
            }
            return timeline == up.timeline || (timeline < up.timeline && lsn <= up.forkLsn);
        }

        private void selfFence(String reason) {
            vipHolder.remove(vip, name);
            pgRunning = fenceKeepsRunning;
            clusterState = "in production";
            fenced = true;
            fencedReason = reason;
            leaseGranted = false;
            did("self-fence: " + reason);
        }

        @Override
        public ApiResult grantLease(long e, long ttlMs, boolean foe) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                sync();
                if (fenced) {
                    throw conflict("node is fenced: " + fencedReason);
                }
                leaseGranted = true;
                leaseDeadline = time.nanos() + ttlMs * 1_000_000L;
                fenceOnExpiry = foe;
                return ApiResult.ok("lease granted for " + ttlMs + " ms");
            }
        }

        @Override
        public ApiResult vipAcquire(long e) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                sync();
                if (fenced) {
                    throw conflict("node is fenced: " + fencedReason);
                }
                if (vipHeld()) {
                    return ApiResult.ok("VIP " + vip + " already up");
                }
                String holder = vipHolder.get(vip);
                if (dadBusy || (holder != null && nodes.get(holder).hostUp)) {
                    throw conflict("VIP " + vip + " answers from another host (arping -D)");
                }
                if (vipAddFails) {
                    throw new AgentCallException(name, 500, "ip addr add failed: exit 2: RTNETLINK answers: Operation"
                            + " not permitted");
                }
                vipHolder.put(vip, name);
                did("vip acquire");
                return ApiResult.ok("VIP " + vip + " up on eth0, gratuitous ARP sent");
            }
        }

        @Override
        public ApiResult vipRelease(long e) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                if (vipHolder.remove(vip, name)) {
                    did("vip release");
                    return ApiResult.ok("VIP " + vip + " removed from eth0");
                }
                return ApiResult.ok("VIP not held");
            }
        }

        @Override
        public ApiResult vipCheck() {
            synchronized (FakeCluster.this) {
                reachable();
                String holder = vipHolder.get(vip);
                boolean held = vipHeld();
                boolean busy = dadBusy || (holder != null && !held && nodes.get(holder).hostUp);
                return new ApiResult(!busy || held, busy ? "VIP " + vip + " answers from another host (arping -D)"
                        : "VIP free", null, JsonMap.create().put("held", held).put("free", !busy).put("address", vip));
            }
        }

        @Override
        public ApiResult promote(long e, long leaseTtlMs, boolean foe) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                sync();
                if (fenced) {
                    throw conflict("node is fenced: " + fencedReason);
                }
                if (!vipHeld()) {
                    throw conflict("VIP " + vip + " is not up on this node; refusing to promote");
                }
                if (promoteFails) {
                    throw new AgentCallException(name, 500, "promote failed: pg_promote() did not finish");
                }
                if (!pgRunning) {
                    throw new AgentCallException(name, 500, "promote failed: server not running");
                }
                if (role == PgRole.PRIMARY) {
                    return ApiResult.ok("already primary");
                }
                role = PgRole.PRIMARY;
                timeline++;
                forkLsn = lsn;
                standbySignal = false;
                upstreamHost = null;
                if (leaseTtlMs > 0) {
                    leaseGranted = true;
                    leaseDeadline = time.nanos() + leaseTtlMs * 1_000_000L;
                    fenceOnExpiry = foe;
                }
                did("promote (timeline " + timeline + ")");
                if (onPromote != null) {
                    onPromote.run();
                }
                if (promoteTimesOut) {
                    throw AgentCallException.unreachable(name, "unreachable: HttpTimeoutException: request timed out");
                }
                return ApiResult.ok("promoted");
            }
        }

        @Override
        public ApiResult checkpoint(long e) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                did("checkpoint");
                return ApiResult.ok("checkpoint done");
            }
        }

        @Override
        public ApiResult stop(long e, String mode, boolean releaseVip) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                sync();
                String msg = "already stopped";
                if (pgRunning) {
                    if (!"immediate".equals(mode) && role == PgRole.PRIMARY) {
                        // Shutdown checkpoint: connected replicas receive everything up to and past it.
                        for (Node r : nodes.values()) {
                            if (r != this) {
                                r.sync();
                            }
                        }
                        checkpointLsn = lsn;
                        lsn += 0x68;
                        for (Node r : nodes.values()) {
                            if (r != this && r.isStreaming() && r.upstream() == this) {
                                r.lsn = lsn;
                            }
                        }
                        clusterState = "shut down";
                    } else {
                        clusterState = "immediate".equals(mode) ? "in production"
                                : role == PgRole.REPLICA ? "shut down in recovery" : "shut down";
                        checkpointLsn = lsn;
                    }
                    pgRunning = false;
                    msg = "stopped (" + mode + ")";
                    did("stop " + mode);
                }
                if (releaseVip && vipHolder.remove(vip, name)) {
                    did("vip release");
                }
                leaseGranted = false;
                return ApiResult.ok(msg, JsonMap.create().put("clusterState", clusterState)
                        .put("checkpointLsn", Lsn.format(checkpointLsn)).put("timeline", timeline));
            }
        }

        @Override
        public ApiResult start(long e, boolean allowPrimary) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                return ApiResult.ok(startNow(allowPrimary));
            }
        }

        private String startNow(boolean allowPrimary) {
            if (pgRunning) {
                return "already running";
            }
            if (allowPrimary && fenced) {
                throw conflict("node is fenced and may only start as a replica: " + fencedReason);
            }
            if (!allowPrimary && !standbySignal) {
                throw conflict("refusing to start without standby.signal: this would start a primary");
            }
            pgRunning = true;
            role = standbySignal ? PgRole.REPLICA : PgRole.PRIMARY;
            did("start as " + role);
            return "started";
        }

        @Override
        public ApiResult follow(long e, String h, int port, String slot) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                sync();
                if (pgRunning && role == PgRole.PRIMARY) {
                    throw conflict("running as primary; demote it instead");
                }
                upstreamHost = h;
                standbySignal = true;
                String out = pgRunning ? "now following " + h : startNow(false) + ", following " + h;
                leaseGranted = false;
                fenced = false;
                fencedReason = null;
                did("follow " + h);
                return ApiResult.ok(out);
            }
        }

        @Override
        public ApiResult demote(long e, String h, int port, String slot, boolean rewind, String stopMode) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                sync();
                String id = UUID.randomUUID().toString();
                List<String> lines = new ArrayList<>();
                OperationStatus.State st = OperationStatus.State.SUCCEEDED;
                String msg = "demoted, following " + h;
                vipHolder.remove(vip, name);
                leaseGranted = false;
                pgRunning = false;
                Node up = nodes.values().stream().filter(n -> n.host.equals(h)).findFirst().orElseThrow();
                if (rewind) {
                    if (rewindFails) {
                        st = OperationStatus.State.FAILED;
                        msg = "pg_rewind failed: exit 1";
                    } else if (timeline < up.timeline && lsn > up.forkLsn) {
                        lines.add("pg_rewind: servers diverged at WAL location " + Lsn.format(up.forkLsn));
                        lsn = up.forkLsn;
                    } else {
                        lines.add("pg_rewind: no rewind required");
                    }
                }
                if (st == OperationStatus.State.SUCCEEDED) {
                    upstreamHost = h;
                    standbySignal = true;
                    role = PgRole.REPLICA;
                    pgRunning = true;
                    fenced = false;
                    fencedReason = null;
                    did("demote rewind=" + rewind + " -> follow " + h);
                }
                ops.put(id, new OperationStatus(id, "demote", st, msg, time.millis(), time.millis(), lines));
                return ApiResult.started(id, "demote started");
            }
        }

        @Override
        public ApiResult reinit(long e, String h, int port, String slot) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                sync();
                if (pgRunning && role == PgRole.PRIMARY) {
                    throw conflict("this node is a running primary; refusing to rebuild it");
                }
                Node up = nodes.values().stream().filter(n -> n.host.equals(h)).findFirst().orElseThrow();
                String id = UUID.randomUUID().toString();
                vipHolder.remove(vip, name);
                lsn = up.lsn;
                timeline = up.timeline;
                upstreamHost = h;
                standbySignal = true;
                role = PgRole.REPLICA;
                pgRunning = true;
                fenced = false;
                fencedReason = null;
                did("reinit from " + h);
                ops.put(id, new OperationStatus(id, "reinit", OperationStatus.State.SUCCEEDED, "rebuilt from " + h,
                        time.millis(), time.millis(), List.of("pg_basebackup done")));
                return ApiResult.started(id, "reinit started");
            }
        }

        @Override
        public OperationStatus operation(String id) {
            synchronized (FakeCluster.this) {
                reachable();
                OperationStatus s = ops.get(id);
                if (s == null) {
                    throw new AgentCallException(name, 404, "no such operation");
                }
                return s;
            }
        }

        @Override
        public ApiResult fence(String reason) {
            synchronized (FakeCluster.this) {
                reachable();
                vipHolder.remove(vip, name);
                if (pgRunning && !fenceKeepsRunning) {
                    pgRunning = false;
                    clusterState = "in production";
                }
                fenced = true;
                fencedReason = reason;
                leaseGranted = false;
                did("fence: " + reason);
                return ApiResult.ok("VIP released; stopped (immediate)");
            }
        }

        @Override
        public ApiResult clearFence(long e) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                fenced = false;
                fencedReason = null;
                did("clear fence");
                return ApiResult.ok("fence cleared");
            }
        }

        @Override
        public ApiResult peerCheck(String h, int port, long timeoutMs) {
            synchronized (FakeCluster.this) {
                reachable();
                sync();
                Node t = nodes.values().stream().filter(n -> n.host.equals(h)).findFirst().orElse(null);
                boolean ok = t != null && t.hostUp && t.pgRunning && hostUp;
                return ApiResult.ok(ok ? "connected" : "Connection refused", JsonMap.create()
                        .put("reachable", ok)
                        .put("walReceiverStatus", isStreaming() ? "streaming" : null)
                        .put("upstreamHost", upstreamHost)
                        .put("upstreamPort", upstreamHost == null ? null : 5432));
            }
        }

        @Override
        public ApiResult ensureSlots(long e, List<String> names) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                if (!names.isEmpty()) {
                    if (!pgRunning) {
                        throw new AgentCallException(name, 500, "cannot create slot: server not running");
                    }
                    slots.addAll(names);
                }
                return ApiResult.ok("slots present");
            }
        }

        @Override
        public ApiResult dropSlot(long e, String slot) {
            synchronized (FakeCluster.this) {
                reachable();
                epoch(e);
                slots.remove(slot);
                return ApiResult.ok("slot " + slot + " dropped");
            }
        }
    }

    // ---- building and driving the cluster ----

    public synchronized Node primary(String name, String host, String dc) {
        Node n = new Node(name, host, dc);
        n.pgRunning = true;
        n.role = PgRole.PRIMARY;
        n.standbySignal = false;
        vipHolder.put(n.vip, name);
        nodes.put(name, n);
        return n;
    }

    public synchronized Node replica(String name, String host, String dc, String upstreamHost) {
        Node n = new Node(name, host, dc);
        n.pgRunning = true;
        n.role = PgRole.REPLICA;
        n.upstreamHost = upstreamHost;
        nodes.put(name, n);
        return n;
    }

    public synchronized Node node(String name) {
        return nodes.get(name);
    }

    /** Writes on the running primary; streaming replicas follow on their next poll. */
    public synchronized void write(long bytes) {
        for (Node n : nodes.values()) {
            if (n.pgRunning && n.role == PgRole.PRIMARY) {
                n.lsn += bytes;
            }
        }
    }

    /** The host loses power: agent and PostgreSQL gone, VIP gone with it. */
    public synchronized void crash(String name) {
        Node n = nodes.get(name);
        n.agentUp = false;
        n.hostUp = false;
        n.pgRunning = false;
        n.clusterState = "in production";
        vipHolder.remove(n.vip, name);
    }

    public synchronized void giveVip(String node) {
        vipHolder.put(VIP, node);
    }

    public synchronized String vipHolder() {
        return vipHolder.get(VIP);
    }

    public synchronized void sync() {
        for (Node n : nodes.values()) {
            n.sync();
        }
    }

    public List<NodeConfig> configs() {
        List<NodeConfig> out = new ArrayList<>();
        for (Node n : nodes.values()) {
            out.add(new NodeConfig(n.name, "https://" + n.host + ":8008", n.host, 5432, n.dc, 100, false, null));
        }
        return out;
    }

    @Override
    public AgentClient create(NodeConfig node) {
        return nodes.get(node.name());
    }
}
