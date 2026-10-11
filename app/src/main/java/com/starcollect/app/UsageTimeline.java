package com.starcollect.app;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Counts a union of resumed activities per package, including sessions across midnight. */
public final class UsageTimeline {
    private UsageTimeline() {}
    public static final int RESUME = 1, PAUSE = 2, SCREEN_OFF = 16, LOCK = 17, STOP = 23, SHUTDOWN = 26, STARTUP = 27;
    public static final class Event {
        public final long at;
        public final int type;
        public final String pkg, activity;
        public Event(long at, int type, String pkg, String activity) {
            this.at = at; this.type = type; this.pkg = pkg == null ? "" : pkg; this.activity = activity == null ? "" : activity;
        }
    }
    public static void consume(StarState state, List<Event> events, long until) {
        long position = state.cursor;
        Map<String, List<long[]>> pending = new HashMap<>();
        for (Event e : events) {
            if (e.at < position || e.at >= until) continue;
            count(state, pending, position, e.at);
            String key = e.pkg + "|" + e.activity;
            if (e.type == RESUME && !state.runtimeDown) state.active.put(key, e.pkg);
            else if (e.type == PAUSE || e.type == STOP) {
                state.active.remove(key);
                if (!state.active.containsValue(e.pkg)) flush(state, pending, e.pkg);
            } else if (e.type == STARTUP) {
                // An unclosed session before startup has an unknown end, not the startup time.
                pending.clear(); state.active.clear(); state.runtimeDown = false;
            } else if (e.type == SCREEN_OFF || e.type == LOCK || e.type == SHUTDOWN) {
                flushAll(state, pending); state.active.clear();
                if (e.type == SHUTDOWN) state.runtimeDown = true;
            }
            position = e.at;
        }
        count(state, pending, position, until); flushAll(state, pending);
        state.cursor = until;
        state.settle(until);
    }
    private static void count(StarState state, Map<String, List<long[]>> pending, long from, long to) {
        if (to <= from) return;
        for (String pkg : new HashSet<>(state.active.values())) pending.computeIfAbsent(pkg, k -> new ArrayList<>()).add(new long[]{from, to});
    }
    private static void flush(StarState state, Map<String, List<long[]>> pending, String pkg) {
        List<long[]> intervals = pending.remove(pkg);
        if (intervals != null) for (long[] interval : intervals) state.addInterval(pkg, interval[0], interval[1]);
    }
    private static void flushAll(StarState state, Map<String, List<long[]>> pending) {
        for (String pkg : new ArrayList<>(pending.keySet())) flush(state, pending, pkg);
    }
}
