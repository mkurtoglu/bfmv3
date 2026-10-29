package com.bisoft.bfm.core.monitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Highest replay lag per replica and minute, for the last hour. */
public class LagHistory {

    private static final int KEEP_MINUTES = 60;

    /** One minute: its start (epoch millis) and the highest lag seen in it. */
    public record Sample(long minuteMillis, long maxLagBytes) {
    }

    private final Map<String, Deque<Sample>> byNode = new HashMap<>();

    public synchronized void record(String node, long timeMillis, long lagBytes) {
        long minute = timeMillis - Math.floorMod(timeMillis, 60_000L);
        Deque<Sample> d = byNode.computeIfAbsent(node, k -> new ArrayDeque<>());
        Sample last = d.peekLast();
        if (last != null && last.minuteMillis() == minute) {
            d.pollLast();
            d.addLast(new Sample(minute, Math.max(last.maxLagBytes(), lagBytes)));
        } else {
            d.addLast(new Sample(minute, lagBytes));
        }
        while (d.size() > KEEP_MINUTES) {
            d.pollFirst();
        }
    }

    /** Samples of the last {@code minutes} minutes, oldest first. */
    public synchronized List<Sample> series(String node, int minutes, long nowMillis) {
        Deque<Sample> d = byNode.get(node);
        if (d == null) {
            return List.of();
        }
        long from = nowMillis - minutes * 60_000L;
        List<Sample> out = new ArrayList<>();
        for (Sample s : d) {
            if (s.minuteMillis() > from) {
                out.add(s);
            }
        }
        return out;
    }
}
