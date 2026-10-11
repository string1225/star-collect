package com.starcollect.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;
import android.os.UserManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class UsageTracker {
    private UsageTracker() {}
    public static boolean permitted(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }
    public static synchronized void sync(Context context) {
        StateStore store = StateStore.get(context);
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) return;
        if (!permitted(context)) {
            store.change(s -> { s.active.clear(); s.syncNote = "需要开启使用情况访问权限；未授权期间不扣星"; s.trackingStarted = false; });
            return;
        }
        long now = System.currentTimeMillis();
        store.change(s -> {
            if (!s.trackingStarted) {
                s.cursor = now; s.active.clear(); s.runtimeDown = false; s.trackingStarted = true; s.lastSync = now;
                s.syncNote = "已开始统计 · 授权之前的用量不扣星"; return;
            }
            if (now < s.cursor) { s.active.clear(); s.syncNote = "设备时间早于上次结算，请校准时间"; return; }
            long retention = 48 * 60 * 60_000L;
            boolean gap = now - s.cursor > retention;
            if (gap) { s.cursor = now - retention; s.active.clear(); s.runtimeDown = false; }
            UsageEvents events = context.getSystemService(UsageStatsManager.class).queryEvents(s.cursor, now);
            if (events == null) { s.syncNote = "系统暂未提供用量，稍后重试"; return; }
            List<UsageTimeline.Event> timeline = new ArrayList<>(); UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                timeline.add(new UsageTimeline.Event(event.getTimeStamp(), event.getEventType(), event.getPackageName(), event.getClassName()));
            }
            timeline.sort(Comparator.comparingLong(e -> e.at));
            UsageTimeline.consume(s, timeline, now); s.lastSync = now;
            if (gap) s.syncNote = "存在统计缺口：仅补算最近 48 小时可用事件";
            else if (!s.syncNote.startsWith("存在统计缺口")) s.syncNote = "统计正常 · 按前台时长结算";
        });
    }
}
