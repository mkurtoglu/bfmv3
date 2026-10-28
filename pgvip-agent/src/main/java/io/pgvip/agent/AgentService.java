package io.pgvip.agent;

import io.pgvip.agent.exec.ProcessRunner;
import io.pgvip.agent.ops.OperationManager;
import io.pgvip.agent.pg.ControlData;
import io.pgvip.agent.pg.PgController;
import io.pgvip.agent.pg.PgProbe;
import io.pgvip.agent.pg.PgSnapshot;
import io.pgvip.agent.pg.Psql;
import io.pgvip.agent.state.EpochStore;
import io.pgvip.agent.state.FenceState;
import io.pgvip.agent.state.LeaseGuard;
import io.pgvip.agent.state.Watchdog;
import io.pgvip.agent.vip.VipManager;
import io.pgvip.common.json.JsonMap;
import io.pgvip.common.model.ApiResult;
import io.pgvip.common.model.NodeStatus;
import io.pgvip.common.model.OperationStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The agent's behaviour behind the HTTP API. */
public class AgentService {

    private static final Logger LOG = Logger.getLogger(AgentService.class.getName());

    private final AgentConfig cfg;
    private final PgProbe probe;
    private final PgController pg;
    private final VipManager vip;
    private final EpochStore epoch;
    private final LeaseGuard lease;
    private final FenceState fence;
    private final OperationManager ops;
    private final Watchdog watchdog;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "pgvip-agent-loop");
        t.setDaemon(true);
        return t;
    });

    private volatile PgSnapshot snapshot = PgSnapshot.stopped(ControlData.UNKNOWN, "not probed yet");
    private volatile boolean vipHeld;
    private volatile String vipError;
    private volatile long probedAtNanos;

    public AgentService(AgentConfig cfg) throws IOException {
        this(cfg, new ProcessRunner(), new LeaseGuard());
    }

    public AgentService(AgentConfig cfg, ProcessRunner runner, LeaseGuard lease) throws IOException {
        this.cfg = cfg;
        Psql psql = new Psql(cfg, runner);
        this.probe = new PgProbe(cfg, runner, psql);
        this.pg = new PgController(cfg, runner, psql, probe);
        this.vip = new VipManager(cfg, runner);
        this.epoch = new EpochStore(cfg.stateDir);
        this.lease = lease;
        this.fence = new FenceState(cfg.stateDir);
        this.ops = new OperationManager();
        this.watchdog = cfg.watchdogEnabled ? new Watchdog(cfg.watchdogDevice) : null;
    }

    public void start() {
        refresh();
        scheduler.scheduleWithFixedDelay(this::safeRefresh, cfg.probeIntervalMs, cfg.probeIntervalMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::leaseTick, 500, 500, TimeUnit.MILLISECONDS);
        if (watchdog != null) {
            scheduler.scheduleWithFixedDelay(
                    () -> watchdog.keepAlive(snapshot.isPrimary() && !fence.fenced()),
                    0, cfg.watchdogIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        scheduler.shutdownNow();
        ops.shutdown();
    }

    private void safeRefresh() {
        try {
            refresh();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "probe failed", e);
        }
    }

    /** Probes PostgreSQL and the VIP now. */
    public synchronized void refresh() {
        snapshot = probe.collect();
        if (vip.enabled()) {
            try {
                vipHeld = vip.isHeld();
                vipError = null;
            } catch (AgentException e) {
                vipError = e.getMessage();
            }
        }
        probedAtNanos = System.nanoTime();
    }

    void leaseTick() {
        try {
            PgSnapshot s = snapshot;
            if (s.isPrimary() && lease.mustFence() && !fence.fenced()) {
                fenceNow("primary lease expired (epoch " + lease.info().epoch() + ")");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "lease check failed", e);
        }
    }

    /** Releases the VIP and, unless configured otherwise, stops PostgreSQL. Never blocked by other work. */
    synchronized String fenceNow(String reason) {
        LOG.warning("FENCING: " + reason);
        StringBuilder out = new StringBuilder();
        try {
            out.append(vip.release());
        } catch (RuntimeException e) {
            out.append("VIP release failed: ").append(e.getMessage());
        }
        if ("stop".equals(cfg.fenceAction)) {
            try {
                out.append("; ").append(pg.stop(cfg.fenceStopMode));
            } catch (RuntimeException e) {
                out.append("; stop failed: ").append(e.getMessage());
            }
        }
        fence.set(reason);
        lease.revoke();
        refresh();
        return out.toString();
    }

    public NodeStatus status() {
        long ageMs = (System.nanoTime() - probedAtNanos) / 1_000_000L;
        if (ageMs > 3 * cfg.probeIntervalMs) {
            refresh();
        }
        PgSnapshot s = snapshot;
        ControlData c = s.control() == null ? ControlData.UNKNOWN : s.control();
        String error = s.error();
        if (vipError != null) {
            error = error == null ? vipError : error + "; " + vipError;
        }
        return new NodeStatus(cfg.nodeName, AgentMain.VERSION, System.currentTimeMillis(), s.running(), s.role(),
                s.timeline(), s.systemIdentifier(), s.serverVersionNum(), s.currentLsn(), s.receiveLsn(),
                s.replayLsn(), s.replayDelayMs(), s.walReceiverStatus(), s.upstreamHost(), s.upstreamPort(),
                s.replicas(), s.slots(), cfg.vipAddress, vipHeld, epoch.current(), lease.info(), fence.fenced(),
                fence.reason(), c.clusterState(), c.checkpointLsn(), c.timeline(), ops.busy(), error);
    }

    // ---- commands ----

    public ApiResult grantLease(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        long ttl = body.requireLng("ttlMs");
        boolean fenceOnExpiry = body.bool("fenceOnExpiry", false);
        if (fence.fenced()) {
            return ApiResult.fail("node is fenced: " + fence.reason());
        }
        lease.grant(body.requireLng("epoch"), ttl, fenceOnExpiry);
        return ApiResult.ok("lease granted for " + ttl + " ms");
    }

    public ApiResult vipAcquire(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        if (fence.fenced()) {
            throw AgentException.conflict("node is fenced: " + fence.reason());
        }
        String msg = ops.exclusive("vip-acquire", vip::acquire);
        refresh();
        return ApiResult.ok(msg);
    }

    public ApiResult vipRelease(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        String msg = ops.exclusive("vip-release", vip::release);
        refresh();
        return ApiResult.ok(msg);
    }

    public ApiResult vipCheck() {
        String busy = vip.checkFree();
        boolean held = vip.enabled() && vip.isHeld();
        return new ApiResult(busy == null || held, busy == null ? "VIP free" : busy, null,
                JsonMap.create().put("held", held).put("free", busy == null).put("address", vip.address()));
    }

    /**
     * Promotes this replica. With the VIP enabled the agent refuses unless the VIP is already up here: the watcher
     * brings the VIP up first and only promotes once that worked.
     */
    public ApiResult promote(JsonMap body) {
        long e = body.requireLng("epoch");
        epoch.accept(e);
        if (fence.fenced()) {
            throw AgentException.conflict("node is fenced: " + fence.reason());
        }
        if (vip.enabled() && body.bool("requireVip", true) && !vip.isHeld()) {
            throw AgentException.conflict("VIP " + vip.address() + " is not up on this node; refusing to promote");
        }
        String msg = ops.exclusive("promote", pg::promote);
        long ttl = body.lng("leaseTtlMs", 0);
        if (ttl > 0) {
            lease.grant(e, ttl, body.bool("fenceOnExpiry", false));
        }
        refresh();
        return ApiResult.ok(msg);
    }

    public ApiResult checkpoint(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        return ApiResult.ok(ops.exclusive("checkpoint", pg::checkpoint));
    }

    public ApiResult stopPg(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        String mode = body.str("mode", "fast");
        boolean releaseVip = body.bool("releaseVip", false);
        String msg = ops.exclusive("stop", () -> {
            String out = pg.stop(mode);
            if (releaseVip) {
                out += "; " + vip.release();
            }
            return out;
        });
        lease.revoke();
        refresh();
        ControlData c = probe.controlData();
        return ApiResult.ok(msg, JsonMap.create()
                .put("clusterState", c.clusterState())
                .put("checkpointLsn", c.checkpointLsn())
                .put("timeline", c.timeline()));
    }

    public ApiResult startPg(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        boolean allowPrimary = body.bool("allowPrimary", false);
        if (allowPrimary && fence.fenced()) {
            throw AgentException.conflict("node is fenced and may only start as a replica: " + fence.reason());
        }
        String msg = ops.exclusive("start", () -> pg.start(allowPrimary));
        refresh();
        return ApiResult.ok(msg);
    }

    /** Makes this node a replica of host:port, keeping data. Refuses on a running primary (use demote). */
    public ApiResult follow(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        String host = body.requireStr("host");
        int port = body.integer("port", 5432);
        String slot = body.str("slot");
        String msg = ops.exclusive("follow", () -> {
            PgSnapshot s = probe.collect();
            if (s.isPrimary()) {
                throw AgentException.conflict("running as primary; demote it instead");
            }
            if (s.running()) {
                pg.followRunning(host, port, slot);
                return "now following " + host + ":" + port;
            }
            pg.configureStandbyStopped(host, port, slot);
            return pg.start(false) + ", following " + host + ":" + port;
        });
        lease.revoke();
        fence.clear();
        refresh();
        return ApiResult.ok(msg);
    }

    /** Stops this node (releasing the VIP), optionally rewinds it, and starts it as a replica. Background. */
    public ApiResult demote(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        String host = body.requireStr("host");
        int port = body.integer("port", 5432);
        String slot = body.str("slot");
        boolean rewind = body.bool("rewind", true);
        String mode = body.str("stopMode", "fast");
        OperationStatus op = ops.start("demote", log -> {
            lease.revoke();
            log.line(vip.release());
            log.line(pg.stop(mode));
            if (rewind) {
                pg.rewind(host, port, log);
            }
            pg.configureStandbyStopped(host, port, slot);
            log.line(pg.start(false));
            fence.clear();
            refresh();
            return "demoted, following " + host + ":" + port;
        });
        return ApiResult.started(op.id(), "demote started");
    }

    /** Rebuilds this replica from host:port with pg_basebackup. Background. */
    public ApiResult reinit(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        String host = body.requireStr("host");
        int port = body.integer("port", 5432);
        String slot = body.str("slot");
        PgSnapshot s = probe.collect();
        if (s.isPrimary() && !body.bool("force", false)) {
            throw AgentException.conflict("this node is a running primary; refusing to rebuild it");
        }
        OperationStatus op = ops.start("reinit", log -> {
            lease.revoke();
            log.line(vip.release());
            pg.reinit(host, port, slot, log);
            fence.clear();
            refresh();
            return "rebuilt from " + host + ":" + port;
        });
        return ApiResult.started(op.id(), "reinit started");
    }

    public ApiResult fence(JsonMap body) {
        String reason = body.str("reason", "fenced by watcher");
        return ApiResult.ok(fenceNow(reason));
    }

    public ApiResult clearFence(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        fence.clear();
        return ApiResult.ok("fence cleared");
    }

    /** Can this node reach host:port over TCP? Used by the watcher to confirm a primary is really gone. */
    public ApiResult peerCheck(JsonMap body) {
        String host = body.requireStr("host");
        int port = body.integer("port", 5432);
        int timeout = (int) body.lng("timeoutMs", 2000);
        boolean reachable;
        String detail;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            reachable = true;
            detail = "connected";
        } catch (IOException e) {
            reachable = false;
            detail = e.getMessage();
        }
        PgSnapshot s = snapshot;
        return ApiResult.ok(detail, JsonMap.create()
                .put("reachable", reachable)
                .put("walReceiverStatus", s.walReceiverStatus())
                .put("upstreamHost", s.upstreamHost())
                .put("receiveLsn", s.receiveLsn()));
    }

    public ApiResult ensureSlots(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        ops.exclusive("ensure-slots", () -> {
            pg.ensureSlots(body.strList("names"));
            return null;
        });
        return ApiResult.ok("slots present");
    }

    public ApiResult dropSlot(JsonMap body) {
        epoch.accept(body.requireLng("epoch"));
        String name = body.requireStr("name");
        ops.exclusive("drop-slot", () -> {
            pg.dropSlot(name);
            return null;
        });
        return ApiResult.ok("slot " + name + " dropped");
    }

    public OperationStatus operation(String id) {
        return ops.get(id);
    }
}
