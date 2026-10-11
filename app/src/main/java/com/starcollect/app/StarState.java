package com.starcollect.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure domain model. Milliseconds are retained; only complete billing units cost stars. */
public final class StarState {
    public String childName = "小朋友";
    public String pinSalt = "", pinHash = "";
    public String zone = ZoneId.systemDefault().getId();
    public long cursor, lastSync;
    public boolean trackingStarted, runtimeDown;
    public String syncNote = "尚未开启用量统计";
    public final List<Task> tasks = new ArrayList<>();
    public final List<Completion> completions = new ArrayList<>();
    public final List<Rule> rules = new ArrayList<>();
    public final List<Entry> ledger = new ArrayList<>();
    public final Map<String, Long> usage = new LinkedHashMap<>();
    public final Map<String, Integer> billed = new HashMap<>();
    public final Map<String, String> active = new HashMap<>();

    public static final class Task {
        public String id = UUID.randomUUID().toString(), title, emoji;
        public int stars;
        public boolean enabled = true;
        public Task(String title, String emoji, int stars) { this.title = title; this.emoji = emoji; this.stars = stars; }
    }
    public static final class Completion {
        public String taskId, day, title, emoji;
        public int stars;
        public boolean approved;
        public long at;
    }
    public static final class Rule {
        public String id = UUID.randomUUID().toString(), packageName, label;
        public int freeMinutes, unitMinutes, stars;
        public long start, end;
        public Rule(String packageName, String label, int freeMinutes, int unitMinutes, int stars, long start) {
            if (freeMinutes < 0 || unitMinutes <= 0 || stars <= 0) throw new IllegalArgumentException("Invalid billing rule");
            this.packageName = packageName; this.label = label; this.freeMinutes = freeMinutes;
            this.unitMinutes = unitMinutes; this.stars = stars; this.start = start;
        }
    }
    public static final class Entry {
        public String id = UUID.randomUUID().toString(), day, title, kind;
        public int delta;
        public long at;
        public Entry(String day, String title, String kind, int delta, long at) {
            this.day = day; this.title = title; this.kind = kind; this.delta = delta; this.at = at;
        }
    }
    public String day(long millis) { return Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)).toLocalDate().toString(); }
    public int balance() { int total = 0; for (Entry e : ledger) total += e.delta; return total; }
    public Completion completion(String taskId, String day) {
        for (Completion c : completions) if (c.taskId.equals(taskId) && c.day.equals(day)) return c;
        return null;
    }
    public boolean submit(Task task, long now) {
        if (!task.enabled || completion(task.id, day(now)) != null) return false;
        Completion c = new Completion(); c.taskId = task.id; c.day = day(now); c.title = task.title;
        c.emoji = task.emoji; c.stars = task.stars; c.at = now; completions.add(c); return true;
    }
    public boolean approve(Completion c, long now) {
        if (!completions.contains(c) || c.approved) return false;
        c.approved = true; ledger.add(new Entry(c.day, c.title, "task", c.stars, now)); return true;
    }
    public boolean undo(Completion c, long now) {
        if (!completions.remove(c)) return false;
        if (c.approved) ledger.add(new Entry(c.day, "撤销：" + c.title, "undo", -c.stars, now));
        return true;
    }
    public static int charge(long millis, Rule r) {
        long payable = Math.max(0, millis - r.freeMinutes * 60_000L);
        return Math.toIntExact(payable / (r.unitMinutes * 60_000L) * r.stars);
    }
    public void addInterval(String pkg, long from, long to) {
        if (to <= from) return;
        for (Rule r : rules) {
            if (!r.packageName.equals(pkg)) continue;
            long begin = Math.max(from, r.start), end = Math.min(to, r.end == 0 ? to : r.end);
            while (begin < end) {
                String day = day(begin);
                long midnight = LocalDate.parse(day).plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli();
                long until = Math.min(end, midnight);
                String key = r.id + "|" + day;
                usage.put(key, usage.getOrDefault(key, 0L) + until - begin);
                begin = until;
            }
        }
    }
    public void settle(long now) {
        for (Map.Entry<String, Long> u : usage.entrySet()) {
            String[] parts = u.getKey().split("\\|");
            for (Rule r : rules) if (r.id.equals(parts[0])) {
                int due = charge(u.getValue(), r), previous = billed.getOrDefault(u.getKey(), 0);
                if (due > previous) {
                    ledger.add(new Entry(parts[1], r.label + " · 用量扣星", "usage", -(due - previous), now));
                    billed.put(u.getKey(), due);
                }
                break;
            }
        }
    }
    public long usageToday(Rule rule, String day) { return usage.getOrDefault(rule.id + "|" + day, 0L); }
    public void seedTasks() {
        tasks.add(new Task("认真阅读 20 分钟", "📚", 3));
        tasks.add(new Task("完成今天的作业", "✏️", 5));
        tasks.add(new Task("运动，让身体充充电", "🏃", 3));
        tasks.add(new Task("自己收拾玩具", "🧸", 2));
        tasks.add(new Task("刷牙洗脸，早点睡觉", "🪥", 2));
        tasks.add(new Task("帮家人做一件小事", "🌱", 2));
    }
}
