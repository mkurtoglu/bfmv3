package com.bisoft.bfm.core.state;

import com.bisoft.bfm.common.json.Json;
import com.bisoft.bfm.common.json.JsonMap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * State for a single watcher, kept in {@code state.json} (rewritten atomically) and {@code events.jsonl}
 * (appended; {@code events-<log>.jsonl} for a named log) under the data directory. The watcher is always the leader.
 */
public class LocalStateStore implements StateStore {

    static final int KEEP_EVENTS = 2000;

    private final String selfId;
    private final Path stateFile;
    private final Path dataDir;
    private final Map<String, Versioned> data = new TreeMap<>();
    private final Map<String, Deque<String>> logs = new java.util.HashMap<>();

    public LocalStateStore(String selfId, Path dataDir) {
        this.selfId = selfId;
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.stateFile = dataDir.resolve("state.json");
        this.dataDir = dataDir;
        load();
    }

    /** In-memory store for tests. */
    public static LocalStateStore inMemory(String selfId) {
        return new LocalStateStore(selfId);
    }

    private LocalStateStore(String selfId) {
        this.selfId = selfId;
        this.stateFile = null;
        this.dataDir = null;
    }

    private void load() {
        try {
            if (Files.exists(stateFile)) {
                JsonMap root = Json.parseObject(Files.readString(stateFile, StandardCharsets.UTF_8));
                JsonMap keys = root.obj("keys");
                if (keys != null) {
                    for (String k : keys.raw().keySet()) {
                        JsonMap e = keys.obj(k);
                        data.put(k, new Versioned(e.str("value"), e.lng("version", 1)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read watcher state from " + stateFile.getParent(), e);
        }
    }

    @Override
    public String selfId() {
        return selfId;
    }

    @Override
    public Mode mode() {
        return Mode.STANDALONE;
    }

    @Override
    public synchronized Versioned get(String key) {
        return data.get(key);
    }

    @Override
    public synchronized Map<String, Versioned> getPrefix(String prefix) {
        Map<String, Versioned> out = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k, v);
            }
        });
        return out;
    }

    @Override
    public synchronized boolean compareAndSet(String key, long expectedVersion, String value) {
        Versioned cur = data.get(key);
        long version = cur == null ? 0 : cur.version();
        if (version != expectedVersion) {
            return false;
        }
        data.put(key, new Versioned(value, version + 1));
        persist();
        return true;
    }

    @Override
    public synchronized void put(String key, String value) {
        Versioned cur = data.get(key);
        data.put(key, new Versioned(value, cur == null ? 1 : cur.version() + 1));
        if (!ephemeral(key)) {
            persist();
        }
    }

    /** {@code events.jsonl} for the default log, {@code events-<log>.jsonl} for a named one. */
    private Path eventsFile(String log) {
        if (dataDir == null) {
            return null;
        }
        if (!log.matches("[A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("bad event log name " + log);
        }
        return dataDir.resolve(log.isEmpty() ? "events.jsonl" : "events-" + log + ".jsonl");
    }

    /** The log, read from its file the first time it is used. */
    private Deque<String> log(String log) {
        Deque<String> events = logs.get(log);
        if (events != null) {
            return events;
        }
        events = new ArrayDeque<>();
        Path file = eventsFile(log);
        if (file != null && Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        events.addLast(line);
                        if (events.size() > KEEP_EVENTS) {
                            events.removeFirst();
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + file, e);
            }
        }
        logs.put(log, events);
        return events;
    }

    @Override
    public synchronized void appendEvent(String log, String json) {
        Deque<String> events = log(log);
        events.addLast(json);
        while (events.size() > KEEP_EVENTS) {
            events.removeFirst();
        }
        Path file = eventsFile(log);
        if (file != null) {
            try {
                Files.writeString(file, json.replace('\n', ' ') + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (events.size() == KEEP_EVENTS && Files.size(file) > 8L * 1024 * 1024) {
                    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                    Files.write(tmp, events, StandardCharsets.UTF_8);
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public synchronized List<String> events(String log, int limit) {
        Deque<String> events = log(log);
        List<String> out = new ArrayList<>(Math.min(limit, events.size()));
        Iterator<String> it = events.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    @Override
    public Leadership leadership() {
        return new Leadership(true, selfId, 0);
    }

    @Override
    public boolean confirmLeadership() {
        return true;
    }

    @Override
    public List<WatcherPeer> peers() {
        return List.of(new WatcherPeer(selfId, "local", true, true, 0L));
    }

    @Override
    public void close() {
        // nothing to release
    }

    /** Observations are rewritten every poll and mean nothing after a restart; they are kept in memory only. */
    static boolean ephemeral(String key) {
        return key.startsWith("observation/") || key.contains("/observation/");
    }

    private void persist() {
        if (stateFile == null) {
            return;
        }
        Map<String, Object> keys = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (!ephemeral(k)) {
                keys.put(k, JsonMap.create().put("value", v.value()).put("version", v.version()).raw());
            }
        });
        String json = Json.write(JsonMap.create().put("keys", keys));
        Path tmp = stateFile.resolveSibling("state.json.tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(StandardCharsets.UTF_8.encode(json));
                ch.force(true);
            }
            Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new StateUnavailableException("cannot write " + stateFile + ": " + e.getMessage(), e);
        }
    }
}
