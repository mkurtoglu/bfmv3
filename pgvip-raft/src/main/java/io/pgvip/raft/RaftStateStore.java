package io.pgvip.raft;

import io.pgvip.common.json.Json;
import io.pgvip.common.json.JsonMap;
import io.pgvip.core.state.StateStore;
import io.pgvip.core.state.StateUnavailableException;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.Parameters;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.grpc.GrpcTlsConfig;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.server.DivisionInfo;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.util.TimeDuration;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link StateStore} replicated across three (or five) watchers with Apache Ratis.
 *
 * <p>Writes go through the Raft log, so a compare-and-set is decided once for the whole group and a watcher without
 * a majority cannot change anything. Reads are linearizable (ReadIndex through the leader) and fall back to this
 * watcher's own copy when there is no leader, so the UI keeps showing the last known state. The acting watcher is
 * the Raft leader; before each lease renewal it confirms its leadership with a linearizable read sent to itself.
 */
public final class RaftStateStore implements StateStore {

    private static final Logger LOG = Logger.getLogger(RaftStateStore.class.getName());

    /** A watcher of the group: its id and Raft address. */
    public record Peer(String id, String host, int port) {
        public String address() {
            return host + ":" + port;
        }
    }

    /**
     * @param selfId         this watcher's id (one of {@code peers})
     * @param peers          every watcher of the group, this one included
     * @param storageDir     Raft log and snapshots
     * @param groupName      names the Raft group (use the cluster name); all watchers must agree
     * @param requestTimeout timeout of one read or write
     * @param keyManagers    mutual TLS between watchers; null for plain gRPC (lab only)
     * @param trustManagers  CA for mutual TLS
     */
    public record Settings(String selfId, List<Peer> peers, Path storageDir, String groupName, Duration requestTimeout,
                           KeyManager[] keyManagers, TrustManager[] trustManagers) {
        public Settings {
            peers = List.copyOf(peers);
            if (peers.stream().noneMatch(p -> p.id().equals(selfId))) {
                throw new IllegalArgumentException("raft peers must include this watcher (" + selfId + ")");
            }
            if (peers.size() % 2 == 0) {
                throw new IllegalArgumentException("use an odd number of watchers (3 or 5), not " + peers.size());
            }
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
        }
    }

    private final Settings settings;
    private final RaftPeerId selfPeerId;
    private final RaftGroup group;
    private final WatcherStateMachine stateMachine = new WatcherStateMachine();
    private final RaftServer server;
    private final RaftClient client;
    private final Map<String, String> addresses = new HashMap<>();

    public RaftStateStore(Settings settings) throws IOException {
        this.settings = settings;
        this.selfPeerId = RaftPeerId.valueOf(settings.selfId());
        List<RaftPeer> peers = new ArrayList<>();
        for (Peer p : settings.peers()) {
            peers.add(RaftPeer.newBuilder().setId(p.id()).setAddress(p.address()).build());
            addresses.put(p.id(), p.address());
        }
        UUID uuid = UUID.nameUUIDFromBytes(("pgvip:" + settings.groupName()).getBytes(StandardCharsets.UTF_8));
        this.group = RaftGroup.valueOf(RaftGroupId.valueOf(uuid), peers);

        Peer self = settings.peers().stream().filter(p -> p.id().equals(settings.selfId())).findFirst().orElseThrow();
        RaftProperties props = new RaftProperties();
        Files.createDirectories(settings.storageDir());
        RaftServerConfigKeys.setStorageDir(props, List.of(settings.storageDir().toFile()));
        GrpcConfigKeys.Server.setPort(props, self.port());
        RaftServerConfigKeys.Read.setOption(props, RaftServerConfigKeys.Read.Option.LINEARIZABLE);
        RaftServerConfigKeys.Rpc.setTimeoutMin(props, TimeDuration.valueOf(1000, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setTimeoutMax(props, TimeDuration.valueOf(2000, TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Rpc.setRequestTimeout(props, TimeDuration.valueOf(settings.requestTimeout().toMillis(),
                TimeUnit.MILLISECONDS));
        RaftServerConfigKeys.Snapshot.setAutoTriggerEnabled(props, true);
        RaftServerConfigKeys.Snapshot.setAutoTriggerThreshold(props, 5_000);
        RaftServerConfigKeys.Snapshot.setRetentionFileNum(props, 2);
        RaftServerConfigKeys.Log.setPurgeUptoSnapshotIndex(props, true);
        RaftClientConfigKeys.Rpc.setRequestTimeout(props, TimeDuration.valueOf(settings.requestTimeout().toMillis(),
                TimeUnit.MILLISECONDS));

        Parameters params = new Parameters();
        if (settings.keyManagers() != null) {
            GrpcTlsConfig tls = new GrpcTlsConfig(settings.keyManagers()[0], settings.trustManagers()[0], true);
            GrpcConfigKeys.TLS.setConf(params, tls);
        }

        File[] existing = settings.storageDir().toFile().listFiles();
        RaftStorage.StartupOption option = existing != null && existing.length > 0
                ? RaftStorage.StartupOption.RECOVER : RaftStorage.StartupOption.FORMAT;
        this.server = RaftServer.newBuilder()
                .setServerId(selfPeerId)
                .setGroup(group)
                .setStateMachine(stateMachine)
                .setProperties(props)
                .setParameters(params)
                .setOption(option)
                .build();
        this.client = RaftClient.newBuilder()
                .setProperties(props)
                .setParameters(params)
                .setRaftGroup(group)
                .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(3,
                        TimeDuration.valueOf(200, TimeUnit.MILLISECONDS)))
                .build();
    }

    public void start() throws IOException {
        server.start();
        LOG.info("raft member " + settings.selfId() + " started; group " + group.getGroupId() + " with "
                + addresses);
    }

    @Override
    public String selfId() {
        return settings.selfId();
    }

    @Override
    public Mode mode() {
        return Mode.RAFT;
    }

    // ---- reads ----

    private String query(String json, boolean fallbackToLocal) {
        try {
            RaftClientReply r = client.io().sendReadOnly(Message.valueOf(json));
            if (r.isSuccess()) {
                return r.getMessage().getContent().toString(StandardCharsets.UTF_8);
            }
            if (!fallbackToLocal) {
                throw new StateUnavailableException("raft read failed: " + r.getException());
            }
        } catch (IOException | RuntimeException e) {
            if (!fallbackToLocal) {
                throw new StateUnavailableException("raft read failed: " + e.getMessage(), e);
            }
            LOG.log(Level.FINE, "linearizable read failed, using local state", e);
        }
        return stateMachine.read(json);
    }

    @Override
    public Versioned get(String key) {
        JsonMap r = Json.parseObject(query(Json.write(JsonMap.create().put("op", "get").put("key", key)), true));
        if (!r.bool("found", false)) {
            return null;
        }
        return new Versioned(r.str("value"), r.lng("version", 1));
    }

    @Override
    public Map<String, Versioned> getPrefix(String prefix) {
        JsonMap r = Json.parseObject(query(Json.write(JsonMap.create().put("op", "prefix").put("prefix", prefix)),
                true));
        Map<String, Versioned> out = new LinkedHashMap<>();
        JsonMap entries = r.obj("entries");
        if (entries != null) {
            for (String k : entries.raw().keySet()) {
                JsonMap e = entries.obj(k);
                out.put(k, new Versioned(e.str("value"), e.lng("version", 1)));
            }
        }
        return out;
    }

    @Override
    public List<String> events(int limit) {
        JsonMap r = Json.parseObject(stateMachine.read(Json.write(JsonMap.create().put("op", "events")
                .put("limit", limit))));
        return r.strList("events");
    }

    // ---- writes ----

    private JsonMap write(JsonMap command) {
        try {
            RaftClientReply r = client.io().send(Message.valueOf(command.toJson()));
            if (!r.isSuccess()) {
                throw new StateUnavailableException("raft write failed: " + r.getException());
            }
            return Json.parseObject(r.getMessage().getContent().toString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new StateUnavailableException("raft write failed (no majority?): " + e.getMessage(), e);
        }
    }

    @Override
    public boolean compareAndSet(String key, long expectedVersion, String value) {
        return write(JsonMap.create().put("op", "cas").put("key", key).put("expected", expectedVersion)
                .put("value", value)).bool("ok", false);
    }

    @Override
    public void put(String key, String value) {
        write(JsonMap.create().put("op", "put").put("key", key).put("value", value));
    }

    @Override
    public void appendEvent(String json) {
        write(JsonMap.create().put("op", "event").put("json", json));
    }

    // ---- leadership ----

    private DivisionInfo info() {
        try {
            return server.getDivision(group.getGroupId()).getInfo();
        } catch (IOException e) {
            throw new StateUnavailableException("raft member not ready: " + e.getMessage(), e);
        }
    }

    @Override
    public Leadership leadership() {
        try {
            DivisionInfo i = info();
            RaftPeerId leader = i.getLeaderId();
            boolean leads = i.isLeader() && i.isLeaderReady();
            return new Leadership(leads, leader == null ? null : leader.toString(), i.getCurrentTerm());
        } catch (RuntimeException e) {
            return new Leadership(false, null, 0);
        }
    }

    @Override
    public boolean confirmLeadership() {
        if (!leadership().leader()) {
            return false;
        }
        try {
            RaftClientReply r = client.io().sendReadOnly(Message.valueOf("{\"op\":\"ping\"}"), selfPeerId);
            return r.isSuccess() && leadership().leader();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "leadership not confirmed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public List<WatcherPeer> peers() {
        Map<String, Long> contact = new HashMap<>();
        String leader = null;
        try {
            DivisionInfo i = info();
            leader = i.getLeaderId() == null ? null : i.getLeaderId().toString();
            RaftProtos.RoleInfoProto role = i.getRoleInfoProto();
            if (role.hasLeaderInfo()) {
                for (RaftProtos.ServerRpcProto f : role.getLeaderInfo().getFollowerInfoList()) {
                    contact.put(f.getId().getId().toStringUtf8(), f.getLastRpcElapsedTimeMs());
                }
            } else if (role.hasFollowerInfo() && role.getFollowerInfo().hasLeaderInfo()) {
                RaftProtos.ServerRpcProto l = role.getFollowerInfo().getLeaderInfo();
                contact.put(l.getId().getId().toStringUtf8(), l.getLastRpcElapsedTimeMs());
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "cannot read raft role", e);
        }
        List<WatcherPeer> out = new ArrayList<>();
        for (Peer p : settings.peers()) {
            boolean self = p.id().equals(settings.selfId());
            out.add(new WatcherPeer(p.id(), p.address(), self, p.id().equals(leader),
                    self ? Long.valueOf(0) : contact.get(p.id())));
        }
        return out;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "closing raft client", e);
        }
        try {
            server.close();
        } catch (IOException e) {
            LOG.log(Level.FINE, "closing raft server", e);
        }
    }
}
