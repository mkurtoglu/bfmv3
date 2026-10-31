package com.bisoft.bfm.agent;

import com.bisoft.bfm.agent.exec.ProcessRunner;
import com.bisoft.bfm.agent.ops.OperationManager;
import com.bisoft.bfm.agent.pg.ControlData;
import com.bisoft.bfm.agent.pg.PgController;
import com.bisoft.bfm.agent.pg.PgProbe;
import com.bisoft.bfm.agent.pg.PgSnapshot;
import com.bisoft.bfm.agent.pg.Psql;
import com.bisoft.bfm.agent.state.ClusterBinding;
import com.bisoft.bfm.agent.state.EpochStore;
import com.bisoft.bfm.agent.state.FenceState;
import com.bisoft.bfm.agent.state.InterruptedOperation;
import com.bisoft.bfm.agent.state.LeaseGuard;
import com.bisoft.bfm.agent.state.Watchdog;
import com.bisoft.bfm.agent.vip.VipManager;
import com.bisoft.bfm.common.json.JsonMap;
import com.bisoft.bfm.common.json.JsonWritable;
import com.bisoft.bfm.common.model.AgentIdentity;
import com.bisoft.bfm.common.model.ApiResult;
import com.bisoft.bfm.common.model.NodeStatus;
import com.bisoft.bfm.common.model.OperationStatus;
import com.bisoft.bfm.common.model.PgRole;

import java.io.IOException;
import java.util.List;
import java.net.InetSocketAddress;
import java.nio.file.Files;
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
    private final ClusterBinding binding;
    private final String hostId;
    private final String hostname;
    private final InterruptedOperation interrupted;
    private final OperationManager ops;
    private final Watchdog watchdog;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "minipgagent-loop");
        t.setDaemon(true);
        return t;
    });

    private volatile PgSnapshot snapshot = PgSnapshot.stopped(ControlData.UNKNOWN, "not probed yet");
    private volatile boolean vipHeld;
    private final Psql psql;
    private volatile String vipError;
    private volatile long probedAtNanos;

    public AgentService(AgentConfig cfg) throws IOException {
        this(cfg, new ProcessRunner(), LeaseGuard.persistent(cfg.stateDir));
    }

    public AgentService(AgentConfig cfg, ProcessRunner runner, LeaseGuard lease) throws IOException {
        this.cfg = cfg;
        Psql psql = new Psql(cfg, runner);
        this.psql = psql;
        this.probe = new PgProbe(cfg, runner, psql);
        this.interrupted = new InterruptedOperation(cfg.stateDir);
        this.pg = new PgController(cfg, runner, psql, probe, interrupted);
        this.vip = new VipManager(cfg, runner);
        this.epoch = new EpochStore(cfg.stateDir);
        this.lease = lease;
        this.fence = new FenceState(cfg.stateDir);
        this.binding = new ClusterBinding(cfg.stateDir, cfg.clusterId, cfg.nodeName);
        this.hostId = readHostId();
        this.hostname = readHostname();
        this.ops = new OperationManager();
        this.watchdog = cfg.watchdogEnabled ? new Watchdog(cfg.watchdogDevice) : null;
    }

    public void start() {
        refresh();
        if (interrupted.present()) {
            LOG.warning(interrupted.refusal());
        }
        if (cfg.pgAutostartReplica) {
            scheduler.schedule(this::autostartReplica, 0, TimeUnit.MILLISECONDS);
        }
        scheduler.scheduleWithFixedDelay(this::safeRefresh, cfg.probeIntervalMs, cfg.probeIntervalMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::leaseTick, 500, 500, TimeUnit.MILLISECONDS);
        if (watchdog != null) {
            scheduler.scheduleWithFixedDelay(
                    () -> watchdog.keepAlive(snapshot.isPrimary() && !fence.fenced()),
                    0, cfg.watchdogIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Optional: starts PostgreSQL as a replica when the agent comes up and finds it stopped, so a rebooted replica
     * is back before any watcher tells it to be. Never a primary: without standby.signal nothing is started, and a
     * fenced node or an interrupted rewind/rebuild is left to the watcher.
     */
    void autostartReplica() {
        try {
            if (snapshot.running()) {
                return;
            }
            String why = fence.fenced() ? "node is fenced" : interrupted.refusal() != null ? interrupted.refusal()
                    : !Files.exists(pg.standbySignal()) ? "no standby.signal (it would start a primary)"
                    : null;
            if (why != null) {
                LOG.info("autostart: not starting PostgreSQL: " + why);
                return;
            }
            String msg = ops.exclusive("autostart", () -> pg.start(false));
            refresh();
            LOG.info("autostart: " + msg + " as a replica");
        } catch (RuntimeException e) {
            LOG.warning("autostart: PostgreSQL not started: " + e.getMessage());
        }
    }

    /** The watchdog, or null when disabled. */
    Watchdog watchdog() {
        return watchdog;
    }

    public void stop() {
        scheduler.shutdownNow();
        ops.shutdown();
        if (watchdog != null) {
            // A clean stop is not a hang: leave the timer off, or the machine resets once it runs out.
            try {
                watchdog.disarm();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "watchdog not disarmed: " + e.getMessage());
            }
        }
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
            // Fence unless PostgreSQL is stopped or confirmed to be a replica: a primary whose probe fails (no free
            // connection, psql timeout) must not outlive its lease either.
            if (s.running() && s.role() != PgRole.REPLICA && lease.mustFence() && !fence.fenced()) {
                fenceNow("primary lease expired (epoch " + lease.info().epoch() + ")");
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "lease check failed", e);
        }
    }

    /**
     * Releases the VIP and, unless configured otherwise, stops PostgreSQL. Never blocked by other work. The node is
     * marked fenced even when a step fails (so it is never started as primary again on its own), but the result then
     * says the fence is incomplete.
     */
    synchronized ApiResult fenceNow(String reason) {
        LOG.warning("FENCING: " + reason);
        StringBuilder out = new StringBuilder();
        boolean complete = true;
        try {
            out.append(vip.release());
        } catch (RuntimeException e) {
            complete = false;
            out.append("VIP release failed: ").append(e.getMessage());
        }
        if ("stop".equals(cfg.fenceAction)) {
            try {
                out.append("; ").append(pg.stop(cfg.fenceStopMode));
            } catch (RuntimeException e) {
                complete = false;
                out.append("; stop failed: ").append(e.getMessage());
            }
        }
        fence.set(reason);
        lease.revoke();
        refresh();
        return complete ? ApiResult.ok(out.toString()) : new ApiResult(false, "fence incomplete: " + out, null,
                null);
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
        if (interrupted.present()) {
            error = error == null ? interrupted.refusal() : error + "; " + interrupted.refusal();
        }
        return new NodeStatus(cfg.nodeName, AgentMain.VERSION, System.currentTimeMillis(), s.running(), s.role(),
                s.timeline(), s.systemIdentifier() != null ? s.systemIdentifier() : c.systemIdentifier(),
                s.serverVersionNum(), s.currentLsn(), s.receiveLsn(),
                s.replayLsn(), s.replayDelayMs(), s.walReceiverStatus(), s.upstreamHost(), s.upstreamPort(),
                s.replicas(), s.slots(), cfg.vipEnabled ? cfg.vipAddress : null, vipHeld, epoch.current(),
                lease.info(), fence.fenced(), fence.reason(), c.clusterState(), c.checkpointLsn(), c.timeline(),
                ops.busy(), error, new AgentIdentity(binding.clusterId(), binding.clusterName(), cfg.instanceName,
                hostId, hostname));
    }

    /**
     * Every command that changes the node goes through here: first the cluster it is meant for (a command for
     * another cluster must not even move this agent's epoch), then its epoch.
     */
    private void accept(JsonMap body) {
        binding.check(body);
        epoch.accept(body.requireLng("epoch"));
    }

    /** The cluster this agent belongs to, or null while unbound. */
    public String boundClusterId() {
        return binding.clusterId();
    }

    private static String readHostId() {
        try {
            String id = Files.readString(java.nio.file.Path.of("/etc/machine-id")).trim();
            return id.isEmpty() ? null : id;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readHostname() {
        String h = System.getenv("HOSTNAME");
        if (h != null && !h.isBlank()) {
            return h.trim();
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return null;
        }
    }

    // ---- commands ----

    public ApiResult grantLease(JsonMap body) {
        accept(body);
        long ttl = body.requireLng("ttlMs");
        boolean fenceOnExpiry = body.bool("fenceOnExpiry", false);
        if (fence.fenced()) {
            return ApiResult.fail("node is fenced: " + fence.reason());
        }
        lease.grant(body.requireLng("epoch"), ttl, fenceOnExpiry);
        return ApiResult.ok("lease granted for " + ttl + " ms");
    }

    public ApiResult vipAcquire(JsonMap body) {
        accept(body);
        if (fence.fenced()) {
            throw AgentException.conflict("node is fenced: " + fence.reason());
        }
        String msg = ops.exclusive("vip-acquire", vip::acquire);
        refresh();
        return ApiResult.ok(msg);
    }

    public ApiResult vipRelease(JsonMap body) {
        accept(body);
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
     * brings the VIP up first and only promotes once that worked. There is no flag to skip the check: a caller
     * that wants to promote without the address runs the node with vip.enabled=false.
     */
    public ApiResult promote(JsonMap body) {
        long e = body.requireLng("epoch");
        accept(body);
        if (fence.fenced()) {
            throw AgentException.conflict("node is fenced: " + fence.reason());
        }
        if (vip.enabled() && !vip.isHeld()) {
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
        accept(body);
        return ApiResult.ok(ops.exclusive("checkpoint", pg::checkpoint));
    }

    public ApiResult stopPg(JsonMap body) {
        accept(body);
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
        accept(body);
        boolean allowPrimary = body.bool("allowPrimary", false);
        if (allowPrimary && fence.fenced()) {
            throw AgentException.conflict("node is fenced and may only start as a replica: " + fence.reason());
        }
        if (allowPrimary) {
            // A lease left from an earlier term must not fence the primary the watcher is starting now; the
            // watcher grants a fresh one right after the start.
            lease.revoke();
        }
        String msg = ops.exclusive("start", () -> pg.start(allowPrimary));
        refresh();
        return ApiResult.ok(msg);
    }

    /** Makes this node a replica of host:port, keeping data. Refuses on a running primary (use demote). */
    public ApiResult follow(JsonMap body) {
        accept(body);
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
        accept(body);
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
        accept(body);
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

    /** Fences this node. Carries the epoch like every other command, so a superseded watcher cannot stop a node. */
    public ApiResult fence(JsonMap body) {
        accept(body);
        String reason = body.str("reason", "fenced by watcher");
        ApiResult r = fenceNow(reason);
        if (!r.ok()) {
            throw new AgentException(r.message());
        }
        return r;
    }

    public ApiResult clearFence(JsonMap body) {
        accept(body);
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
                .put("upstreamPort", s.upstreamPort())
                .put("receiveLsn", s.receiveLsn()));
    }

    public ApiResult ensureSlots(JsonMap body) {
        accept(body);
        ops.exclusive("ensure-slots", () -> {
            pg.ensureSlots(body.strList("names"));
            return null;
        });
        return ApiResult.ok("slots present");
    }

    public ApiResult dropSlot(JsonMap body) {
        accept(body);
        String name = body.requireStr("name");
        ops.exclusive("drop-slot", () -> {
            pg.dropSlot(name);
            return null;
        });
        return ApiResult.ok("slot " + name + " dropped");
    }

    /**
     * The readiness checks of setup, live: {@code peers} ([{name, address}]) are the other nodes that must be able
     * to replicate from this one; {@code systemIdentifier} the cluster's. Read only.
     */
    public JsonWritable checks(JsonMap body) {
        List<com.bisoft.bfm.agent.setup.Readiness.Peer> peers = new java.util.ArrayList<>();
        for (JsonMap p : body.objList("peers", m -> m)) {
            peers.add(new com.bisoft.bfm.agent.setup.Readiness.Peer(p.str("name", p.str("address")),
                    p.requireStr("address")));
        }
        List<com.bisoft.bfm.agent.setup.Readiness.Check> list = com.bisoft.bfm.agent.setup.Readiness.run(
                sql -> psql.query(sql, 10), body.str("replicationUser", cfg.replicationUser), peers,
                body.str("systemIdentifier"), cfg.pgData.toString());
        return () -> java.util.Map.of("checks", list.stream().map(c -> c.toJsonValue()).toList());
    }

    public OperationStatus operation(String id) {
        return ops.get(id);
    }
}
