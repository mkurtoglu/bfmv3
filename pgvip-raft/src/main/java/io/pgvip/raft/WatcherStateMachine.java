package io.pgvip.raft;

import io.pgvip.common.json.Json;
import io.pgvip.common.json.JsonMap;
import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.proto.RaftProtos;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.StateMachineStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.util.MD5FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * The replicated watcher state: a versioned key-value map and a bounded event list. Commands are JSON documents in
 * the Raft log; a compare-and-set is decided when the entry is applied, so every watcher reaches the same answer.
 *
 * <p>Commands: {@code {"op":"cas","key":k,"expected":n,"value":v}}, {@code {"op":"put","key":k,"value":v}},
 * {@code {"op":"event","json":e}}. Queries: {@code get}, {@code prefix}, {@code events}, {@code ping}.
 */
public class WatcherStateMachine extends BaseStateMachine {

    static final int KEEP_EVENTS = 1000;

    private record Entry(String value, long version) {
    }

    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();
    private final Map<String, Entry> data = new TreeMap<>();
    private final Deque<String> events = new ArrayDeque<>();

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        storage.init(raftStorage);
        reinitialize();
    }

    @Override
    public void reinitialize() throws IOException {
        load(storage.loadLatestSnapshot());
    }

    @Override
    public StateMachineStorage getStateMachineStorage() {
        return storage;
    }

    private synchronized void load(SingleFileSnapshotInfo snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        File f = snapshot.getFile().getPath().toFile();
        if (!f.exists()) {
            return;
        }
        JsonMap root = Json.parseObject(Files.readString(f.toPath(), StandardCharsets.UTF_8));
        data.clear();
        events.clear();
        JsonMap keys = root.obj("keys");
        if (keys != null) {
            for (String k : keys.raw().keySet()) {
                JsonMap e = keys.obj(k);
                data.put(k, new Entry(e.str("value"), e.lng("version", 1)));
            }
        }
        events.addAll(root.strList("events"));
        setLastAppliedTermIndex(snapshot.getTermIndex());
    }

    @Override
    public long takeSnapshot() throws IOException {
        TermIndex last;
        String json;
        synchronized (this) {
            last = getLastAppliedTermIndex();
            Map<String, Object> keys = new LinkedHashMap<>();
            data.forEach((k, v) -> keys.put(k, JsonMap.create().put("value", v.value()).put("version", v.version()).raw()));
            json = Json.write(JsonMap.create().put("keys", keys).put("events", new ArrayList<>(events)));
        }
        if (last == null) {
            return -1; // nothing applied yet
        }
        File f = storage.getSnapshotFile(last.getTerm(), last.getIndex());
        Files.writeString(f.toPath(), json, StandardCharsets.UTF_8);
        MD5Hash md5 = MD5FileUtil.computeAndSaveMd5ForFile(f);
        storage.updateLatestSnapshot(new SingleFileSnapshotInfo(new FileInfo(f.toPath(), md5), last));
        return last.getIndex();
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        RaftProtos.LogEntryProto entry = trx.getLogEntry();
        String text = entry.getStateMachineLogEntry().getLogData().toString(StandardCharsets.UTF_8);
        String reply;
        synchronized (this) {
            reply = apply(text);
            updateLastAppliedTermIndex(entry.getTerm(), entry.getIndex());
        }
        return CompletableFuture.completedFuture(Message.valueOf(reply));
    }

    /** Applies one command; returns the reply. Callers hold the lock. */
    String apply(String text) {
        JsonMap c;
        try {
            c = Json.parseObject(text);
        } catch (RuntimeException e) {
            return reply(false, "bad command");
        }
        String op = c.str("op", "");
        switch (op) {
            case "cas" -> {
                String key = c.str("key");
                long expected = c.lng("expected", -1);
                Entry cur = data.get(key);
                long version = cur == null ? 0 : cur.version();
                if (version != expected) {
                    return reply(false, "version " + version);
                }
                data.put(key, new Entry(c.str("value"), version + 1));
                return reply(true, null);
            }
            case "put" -> {
                String key = c.str("key");
                Entry cur = data.get(key);
                data.put(key, new Entry(c.str("value"), cur == null ? 1 : cur.version() + 1));
                return reply(true, null);
            }
            case "event" -> {
                events.addLast(c.str("json"));
                while (events.size() > KEEP_EVENTS) {
                    events.removeFirst();
                }
                return reply(true, null);
            }
            default -> {
                return reply(false, "unknown op " + op);
            }
        }
    }

    private static String reply(boolean ok, String detail) {
        return Json.write(JsonMap.create().put("ok", ok).put("detail", detail));
    }

    @Override
    public CompletableFuture<Message> query(Message request) {
        return CompletableFuture.completedFuture(Message.valueOf(read(request.getContent()
                .toString(StandardCharsets.UTF_8))));
    }

    /** Answers a query from local state. */
    synchronized String read(String text) {
        JsonMap q = Json.parseObject(text);
        switch (q.str("op", "")) {
            case "get" -> {
                Entry e = data.get(q.str("key"));
                return Json.write(e == null ? JsonMap.create().put("found", false)
                        : JsonMap.create().put("found", true).put("value", e.value()).put("version", e.version()));
            }
            case "prefix" -> {
                String prefix = q.str("prefix", "");
                Map<String, Object> out = new LinkedHashMap<>();
                data.forEach((k, v) -> {
                    if (k.startsWith(prefix)) {
                        out.put(k, JsonMap.create().put("value", v.value()).put("version", v.version()).raw());
                    }
                });
                return Json.write(JsonMap.create().put("entries", out));
            }
            case "events" -> {
                int limit = q.integer("limit", 100);
                List<String> out = new ArrayList<>();
                Iterator<String> it = events.descendingIterator();
                while (it.hasNext() && out.size() < limit) {
                    out.add(it.next());
                }
                return Json.write(JsonMap.create().put("events", out));
            }
            default -> {
                return Json.write(JsonMap.create().put("ok", true));
            }
        }
    }
}
