package com.starcollect.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(39, 56, 46), MUTED = Color.rgb(115, 128, 116), GREEN = Color.rgb(53, 121, 104);
    private static final int BG = Color.rgb(247, 248, 242), LINE = Color.rgb(230, 235, 223), PALE = Color.rgb(235, 242, 227);
    private static final int GOLD = Color.rgb(173, 121, 35), RED = Color.rgb(180, 89, 70);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private StateStore store;
    private StarState state;
    private LinearLayout root, content;
    private int page;
    private long parentUntil;
    private boolean wide, syncing, resumed, dialogOpen;
    private String historyDay;
    private AlertDialog updateDialog;
    private ReleasePolicy.Release updateRelease;
    private String updateMessage = "点击检查更新，查看是否有可安装的新版本。";
    private boolean updateBusy;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { if (resumed) { syncUsage(); handler.postDelayed(this, 60_000); } }
    };
    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        try { store = StateStore.get(this); }
        catch (RuntimeException e) { new AlertDialog.Builder(this).setTitle("暂时无法读取记录").setMessage(e.getMessage()).setPositiveButton("关闭", (d, w) -> finish()).show(); return; }
        historyDay = store.snapshot().day(System.currentTimeMillis());
        if (bundle != null) { page = bundle.getInt("page", 0); if (page == 3) page = 0; historyDay = bundle.getString("historyDay", historyDay); }
        UsageJobService.schedule(this); render();
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (store != null) { render(); handler.removeCallbacks(refresh); handler.post(refresh); }
        refreshUpdateDialog();
    }
    @Override protected void onStop() { super.onStop(); resumed = false; parentUntil = 0; handler.removeCallbacks(refresh); if (page == 3) page = 0; }
    @Override protected void onDestroy() { handler.removeCallbacks(refresh); executor.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle b) { b.putInt("page", page); b.putString("historyDay", historyDay); super.onSaveInstanceState(b); }
    private boolean parent() { return SystemClock.elapsedRealtime() < parentUntil; }
    private void syncUsage() {
        if (syncing) return; syncing = true;
        executor.submit(() -> {
            String error = null;
            try { UsageTracker.sync(this); } catch (RuntimeException e) { error = "统计暂未更新：" + e.getMessage(); }
            String result = error;
            handler.post(() -> { syncing = false; if (resumed && !isFinishing()) { if (result != null) toast(result); if (!dialogOpen) render(); } });
        });
    }
    private void mutate(java.util.function.Consumer<StarState> action) {
        try { store.change(action); render(); } catch (RuntimeException e) { toast(e.getMessage()); }
    }
    private void render() {
        state = store.snapshot();
        wide = getResources().getConfiguration().screenWidthDp >= 840;
        if (page == 3 && !parent()) page = 0;
        root = column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout body = row(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (wide) body.addView(sidebar(), new LinearLayout.LayoutParams(dp(190), -1));
        LinearLayout main = column(); body.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
        main.addView(header());
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column(); pad(content, wide ? 30 : 18); scroll.addView(content);
        if (page == 0) todayPage(); else if (page == 1) usagePage(); else if (page == 2) historyPage(); else parentPage();
        if (!wide) root.addView(bottomNav());
    }
    private LinearLayout sidebar() {
        LinearLayout rail = column(); pad(rail, 18); rail.setBackgroundColor(Color.WHITE);
        TextView logo = text("✦  攒星星", 25, GREEN, true); logo.setPadding(dp(10), dp(20), 0, dp(4)); rail.addView(logo);
        TextView subtitle = text("把每一天的小努力\n变成闪闪发光的星星", 12, MUTED, false); subtitle.setPadding(dp(10), dp(8), 0, dp(34)); rail.addView(subtitle);
        String[] labels = {"☀  今日任务", "◷  App 用量", "▤  成长记录", "⚙  家长空间"};
        for (int i = 0; i < labels.length; i++) {
            final int target = i;
            Button b = button(labels[i], () -> navigate(target), page == i); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52)); lp.bottomMargin = dp(10); rail.addView(b, lp);
        }
        rail.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        LinearLayout profile = card(PALE); profile.addView(text("🌿  " + state.childName, 16, INK, true));
        profile.addView(text(parent() ? "家长模式 · 5 分钟后锁定" : "每天进步一点点", 11, MUTED, false));
        if (parent()) profile.addView(button("退出家长模式", () -> { parentUntil = 0; page = 0; render(); }, false));
        rail.addView(profile); return rail;
    }
    private LinearLayout header() {
        LinearLayout h = row(); h.setPadding(dp(wide ? 30 : 18), dp(22), dp(wide ? 30 : 18), dp(14));
        LinearLayout titles = column();
        String[] names = {"今天，也要闪闪发光", "玩得开心，也有约定", "每一颗星星，都有故事", "一起定下成长的小约定"};
        titles.addView(text(names[page], wide ? 25 : 20, INK, true));
        titles.addView(text(LocalDate.parse(today()).format(DateTimeFormatter.ofPattern("M 月 d 日  EEEE", Locale.CHINA)) + (parent() ? "  ·  家长模式" : ""), 12, MUTED, false));
        h.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView wallet = text("★  " + state.balance() + " 星", 17, GOLD, true); wallet.setTag("wallet"); pad(wallet, 12); wallet.setBackground(shape(Color.rgb(255, 244, 214), 18));
        h.addView(wallet); return h;
    }
    private LinearLayout bottomNav() {
        LinearLayout nav = row(); nav.setBackgroundColor(Color.WHITE); nav.setPadding(dp(8), dp(6), dp(8), dp(6));
        String[] labels = {"今日", "用量", "记录", "家长"};
        for (int i = 0; i < 4; i++) { final int target = i; nav.addView(button(labels[i], () -> navigate(target), page == i), new LinearLayout.LayoutParams(0, dp(50), 1)); }
        return nav;
    }
    private void navigate(int target) {
        if (target == 3) gate(() -> { page = 3; render(); });
        else { page = target; render(); }
    }
    private void todayPage() {
        LinearLayout hero = row(); hero.setPadding(dp(26), dp(16), dp(20), dp(16)); hero.setBackground(shape(GREEN, 24));
        LinearLayout words = column(); words.setGravity(Gravity.CENTER_VERTICAL); words.setMinimumHeight(dp(wide ? 200 : 175));
        words.addView(text("MY LITTLE STAR GARDEN", 10, Color.rgb(197, 222, 191), true));
        TextView welcome = text(state.childName + "，\n今天想种下几颗星星？", wide ? 29 : 23, Color.WHITE, true); welcome.setPadding(0, dp(14), 0, dp(10)); words.addView(welcome);
        words.addView(text("完成一件小事，让星星花园长大一点。", 13, Color.rgb(225, 239, 212), false));
        hero.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (getResources().getConfiguration().screenWidthDp >= 600) hero.addView(new GardenView(this), new LinearLayout.LayoutParams(dp(260), dp(200)));
        content.addView(hero); gap(content, 18);
        int finished = 0, earned = 0, spent = 0, total = 0;
        for (StarState.Task t : state.tasks) if (t.enabled) { total++; StarState.Completion c = state.completion(t.id, today()); if (c != null && c.approved) finished++; }
        for (StarState.Entry e : state.ledger) if (e.day.equals(today())) { if (e.delta > 0) earned += e.delta; else spent -= e.delta; }
        LinearLayout stats = row(); stats.addView(stat("今日完成", finished + " / " + total, "小事也值得被看见", GREEN), weighted(1));
        stats.addView(stat("今日获得", "+" + earned + " ★", "每一分努力都有回报", GOLD), weighted(1));
        stats.addView(stat("今日扣除", "−" + spent + " ★", "娱乐用量与其他扣星", RED), weighted(1)); content.addView(stats); gap(content, 22);
        LinearLayout split = wide ? row() : column(), tasks = column(); if (wide) split.setGravity(Gravity.TOP);
        if (wide) split.addView(tasks, new LinearLayout.LayoutParams(0, -2, 1)); else split.addView(tasks);
        tasks.addView(section("今天的小任务", "完成后请家长确认，就能收获星星"));
        List<StarState.Task> enabled = new ArrayList<>(); for (StarState.Task t : state.tasks) if (t.enabled) enabled.add(t);
        boolean two = getResources().getConfiguration().screenWidthDp >= (wide ? 1100 : 700);
        for (int i = 0; i < enabled.size(); i += two ? 2 : 1) {
            LinearLayout line = row(); line.addView(taskCard(enabled.get(i)), weighted(1));
            if (two) { if (i + 1 < enabled.size()) line.addView(taskCard(enabled.get(i + 1)), weighted(1)); else line.addView(new View(this), weighted(1)); }
            tasks.addView(line); gap(tasks, 10);
        }
        if (enabled.isEmpty()) tasks.addView(note("还没有任务，去家长空间添加第一件小事吧。"));
        LinearLayout aside = column(); if (wide) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(260), -2); lp.leftMargin = dp(20); split.addView(aside, lp); } else { gap(split, 16); split.addView(aside); }
        LinearLayout motivation = card(Color.rgb(255, 246, 223)); motivation.addView(text("✧  星星的秘密", 17, GOLD, true));
        motivation.addView(text("不用一天做到所有事。\n今天比昨天多一点努力，\n就是很棒的进步。", 14, INK, false)); gap(motivation, 14);
        motivation.addView(text("当前余额  " + state.balance() + " ★", 21, GOLD, true));
        if (state.balance() < 0) motivation.addView(text("星星可以暂时欠一点，完成任务慢慢补回来。", 12, MUTED, false));
        aside.addView(motivation); gap(aside, 16);
        LinearLayout screen = card(Color.WHITE); screen.addView(text("◷  娱乐小约定", 17, INK, true));
        int count = 0; long used = 0; for (StarState.Rule r : state.rules) { if (r.end == 0) count++; used += state.usageToday(r, today()); }
        screen.addView(text(count == 0 ? "让家长挑选要统计的 App，\n一起约定用量与星星。" : "已设置 " + count + " 个 App\n今日累计 " + duration(used), 13, MUTED, false));
        screen.addView(button("查看 App 用量  →", () -> navigate(1), false)); aside.addView(screen); content.addView(split);
        if (state.pinHash.isEmpty()) { gap(content, 18); content.addView(button("首次使用：请家长设置 PIN 和规则", () -> navigate(3), true)); }
    }
    private LinearLayout taskCard(StarState.Task task) {
        StarState.Completion c = state.completion(task.id, today());
        LinearLayout tile = card(c != null && c.approved ? PALE : Color.WHITE); tile.setMinimumHeight(dp(180));
        LinearLayout top = row(); top.addView(text(task.emoji, 27, INK, false), weighted(1)); top.addView(text("+" + task.stars + " ★", 16, GOLD, true)); tile.addView(top);
        TextView title = text(task.title, 17, INK, true); title.setPadding(0, dp(9), 0, dp(6)); tile.addView(title);
        if (c == null) tile.addView(button("我完成啦  ＋", () -> {
            mutate(s -> { for (StarState.Task t : s.tasks) if (t.id.equals(task.id)) s.submit(t, System.currentTimeMillis()); }); toast("已经记下啦，等家长确认后收获星星");
        }, false));
        else if (c.approved) tile.addView(text("✓  已收获星星，真棒！", 13, GREEN, true));
        else if (parent()) tile.addView(button("确认完成  +" + c.stars + " ★", () -> approve(c), true));
        else tile.addView(text("◷  已提交，等待家长确认", 13, MUTED, false));
        return tile;
    }
    private void usagePage() {
        LinearLayout access = card(UsageTracker.permitted(this) ? PALE : Color.rgb(255, 246, 223));
        access.addView(text(UsageTracker.permitted(this) ? "✓  用量统计已授权" : "先开启使用情况访问权限", 19, INK, true));
        access.addView(text("只读取本机 App 的前台使用时长，记录保存在这台平板。每累计一个完整计费单位扣星，剩余秒数在当天继续累计。", 13, MUTED, false));
        access.addView(text(state.syncNote, 12, UsageTracker.permitted(this) ? GREEN : GOLD, false));
        if (state.lastSync > 0) access.addView(text("上次结算 " + java.time.Instant.ofEpochMilli(state.lastSync).atZone(java.time.ZoneId.of(state.zone)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), 12, MUTED, false));
        if (!UsageTracker.permitted(this)) access.addView(button("家长授权统计", () -> gate(() -> {
            try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException e) { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        }), true));
        else access.addView(button(syncing ? "正在更新…" : "刷新用量", this::syncUsage, false));
        content.addView(access); gap(content, 20);
        LinearLayout heading = row(); heading.addView(section("今日 App 用量", "家长可以为每个 App 分别设置免费额度和扣星规则"), weighted(1));
        heading.addView(button("＋ 添加 App", () -> gate(this::chooseApp), true), new LinearLayout.LayoutParams(-2, dp(48))); content.addView(heading);
        boolean any = false;
        for (StarState.Rule r : state.rules) if (r.end == 0) { any = true; content.addView(ruleCard(r)); gap(content, 12); }
        if (!any) content.addView(note("还没有选择 App\n点击「添加 App」，选择这台平板上的游戏或视频应用。"));
        gap(content, 14); content.addView(note("后台约每 15 分钟尝试结算，系统省电策略可能延后；打开应用时也会补算。强制停止会暂停后台任务。当前只补算最近 48 小时可用事件，建议每天打开一次。"));
    }
    private LinearLayout ruleCard(StarState.Rule r) {
        LinearLayout tile = card(Color.WHITE), line = row(), labels = column();
        labels.addView(text("◷  " + r.label, 19, INK, true));
        labels.addView(text("每天免费 " + r.freeMinutes + " 分钟 · 每 " + r.unitMinutes + " 分钟扣 " + r.stars + " ★", 13, MUTED, false));
        labels.addView(text(r.packageName, 10, MUTED, false)); line.addView(labels, weighted(1));
        LinearLayout result = column(); long ms = 0; int billed = 0, versions = 0;
        for (StarState.Rule version : state.rules) if (version.packageName.equals(r.packageName)) {
            ms += state.usageToday(version, today()); billed += state.billed.getOrDefault(version.id + "|" + today(), 0);
            if (state.usageToday(version, today()) > 0) versions++;
        }
        result.addView(text(duration(ms), 24, INK, true));
        result.addView(text("已扣 " + billed + " ★", 13, RED, true)); line.addView(result); tile.addView(line);
        if (versions > 1) tile.addView(text("今日合计含修改前的规则；当前规则从保存时重新累计。", 12, MUTED, false));
        if (parent()) { LinearLayout controls = row(); controls.addView(button("修改规则", () -> gate(() -> ruleEditor(r.packageName, r.label, r)), false), weighted(1)); controls.addView(button("停止统计", () -> gate(() -> retireRule(r)), false), weighted(1)); tile.addView(controls); }
        return tile;
    }
    private void historyPage() {
        LinearLayout week = card(Color.WHITE); week.addView(text("最近 7 天的努力", 18, INK, true));
        LinearLayout chart = row(); LocalDate current = LocalDate.parse(today());
        for (int i = 6; i >= 0; i--) {
            String day = current.minusDays(i).toString(); int earned = 0;
            for (StarState.Entry e : state.ledger) if (e.day.equals(day) && e.delta > 0) earned += e.delta;
            LinearLayout bar = column(); bar.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); bar.setMinimumHeight(dp(105));
            bar.addView(text(earned + " ★", 12, day.equals(historyDay) ? GREEN : MUTED, true));
            View fill = new View(this); fill.setBackground(shape(day.equals(historyDay) ? GREEN : Color.rgb(201, 218, 190), 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(8 + Math.min(earned, 25) * 2)); lp.topMargin = dp(8); lp.bottomMargin = dp(8); bar.addView(fill, lp);
            bar.addView(text(current.minusDays(i).format(DateTimeFormatter.ofPattern("M/d")), 11, MUTED, false));
            bar.setContentDescription(day + " 获得 " + earned + " 星"); bar.setOnClickListener(v -> { historyDay = day; render(); }); chart.addView(bar, weighted(1));
        }
        week.addView(chart); content.addView(week); gap(content, 18);
        LinearLayout picker = row(); picker.addView(button("‹ 前一天", () -> { historyDay = LocalDate.parse(historyDay).minusDays(1).toString(); render(); }, false), new LinearLayout.LayoutParams(-2, dp(48)));
        Button date = button(historyDay + "  ▾", this::pickDate, false); picker.addView(date, weighted(1));
        Button next = button("后一天 ›", () -> { historyDay = LocalDate.parse(historyDay).plusDays(1).toString(); render(); }, false); next.setEnabled(historyDay.compareTo(today()) < 0); picker.addView(next, new LinearLayout.LayoutParams(-2, dp(48))); content.addView(picker); gap(content, 14);
        content.addView(section("这一天做了什么", "任务记录与星星收支可以分别核对"));
        int n = 0;
        for (StarState.Completion c : state.completions) if (c.day.equals(historyDay)) {
            n++; LinearLayout item = card(Color.WHITE); item.addView(text(c.emoji + "  " + c.title, 16, INK, true));
            item.addView(text(c.approved ? "✓ 家长已确认 · +" + c.stars + " ★" : "◷ 等待家长确认", 12, c.approved ? GREEN : MUTED, false));
            if (parent()) item.addView(button("撤销这条记录", () -> gate(() -> undo(c)), false)); content.addView(item); gap(content, 8);
        }
        if (n == 0) content.addView(note("这一天还没有任务记录。"));
        gap(content, 18); content.addView(section("星星账本", "奖励、扣星和撤销都会留下记录"));
        int entries = 0;
        for (int i = state.ledger.size() - 1; i >= 0; i--) {
            StarState.Entry e = state.ledger.get(i); if (!e.day.equals(historyDay)) continue; entries++;
            LinearLayout item = row(); item.setPadding(dp(16), dp(14), dp(16), dp(14)); item.setBackground(shape(Color.WHITE, 14));
            LinearLayout title = column(); title.addView(text(e.title, 15, INK, false));
            title.addView(text(java.time.Instant.ofEpochMilli(e.at).atZone(java.time.ZoneId.of(state.zone)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), 11, MUTED, false)); item.addView(title, weighted(1));
            item.addView(text((e.delta > 0 ? "+" : "") + e.delta + " ★", 19, e.delta > 0 ? GOLD : RED, true)); content.addView(item); gap(content, 6);
        }
        if (entries == 0) content.addView(note("暂无星星收支。"));
        gap(content, 18); content.addView(section("这一天的 App 用量", "停用或修改前的规则也会保留历史"));
        boolean used = false;
        for (StarState.Rule r : state.rules) if (state.usageToday(r, historyDay) > 0) { used = true; content.addView(note(r.label + "  ·  " + duration(state.usageToday(r, historyDay)) + "  ·  扣 " + state.billed.getOrDefault(r.id + "|" + historyDay, 0) + " ★")); gap(content, 6); }
        if (!used) content.addView(note("暂无已统计的 App 用量。"));
    }
    private void pickDate() {
        LocalDate d = LocalDate.parse(historyDay); DatePickerDialog picker = new DatePickerDialog(this, (view, y, m, day) -> { historyDay = LocalDate.of(y, m + 1, day).toString(); render(); }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis()); picker.show();
    }
    private void parentPage() {
        LinearLayout top = card(PALE); top.addView(text("🔒  家长空间", 22, GREEN, true));
        top.addView(text("奖励先确认，规则一起定。离开应用或 5 分钟后会自动锁定。", 13, MUTED, false));
        LinearLayout controls = row(); controls.addView(button("修改昵称", () -> gate(this::editName), false), weighted(1)); controls.addView(button("修改 PIN", () -> gate(this::changePin), false), weighted(1));
        controls.addView(button("退出家长模式", () -> { parentUntil = 0; page = 0; render(); }, false), weighted(1)); top.addView(controls); content.addView(top); gap(content, 18);
        content.addView(section("等待确认的小努力", "包括之前日期尚未确认的任务")); int pending = 0;
        for (StarState.Completion c : state.completions) if (!c.approved) {
            pending++; LinearLayout item = card(Color.WHITE); item.addView(text(c.emoji + "  " + c.title + "  ·  +" + c.stars + " ★", 17, INK, true)); item.addView(text(c.day, 12, MUTED, false));
            LinearLayout buttons = row(); buttons.addView(button("确认完成", () -> approve(c), true), weighted(1)); buttons.addView(button("退回重做", () -> undo(c), false), weighted(1)); item.addView(buttons); content.addView(item); gap(content, 8);
        }
        if (pending == 0) content.addView(note("已经全部确认啦，等待下一件小事。"));
        gap(content, 18); LinearLayout head = row(); head.addView(section("每日任务与奖励", "任务修改只影响之后的提交，历史奖励保持原样"), weighted(1)); head.addView(button("＋ 新任务", () -> gate(() -> taskEditor(null)), true), new LinearLayout.LayoutParams(-2, dp(48))); content.addView(head);
        for (StarState.Task task : state.tasks) {
            LinearLayout item = card(Color.WHITE), line = row(); line.addView(text(task.emoji + "  " + task.title + (task.enabled ? "" : "（已停用）"), 16, INK, true), weighted(1)); line.addView(text("+" + task.stars + " ★", 17, GOLD, true)); item.addView(line);
            LinearLayout actions = row(); actions.addView(button("编辑", () -> gate(() -> taskEditor(task)), false), weighted(1)); actions.addView(button(task.enabled ? "停用" : "启用", () -> gate(() -> mutate(s -> { for (StarState.Task t : s.tasks) if (t.id.equals(task.id)) t.enabled = !t.enabled; })), false), weighted(1)); item.addView(actions); content.addView(item); gap(content, 8);
        }
        gap(content, 18); content.addView(section("补记与数据", "补奖、扣星都需要填写原因"));
        content.addView(button("手动补奖 / 扣星", () -> gate(this::adjustment), true)); gap(content, 8);
        content.addView(button("导出本机记录（JSON）", () -> gate(() -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "star-collect-" + today() + ".json"); startActivityForResult(intent, 100);
        }), false));
        gap(content, 18); content.addView(section("应用更新", "当前版本 " + BuildConfig.VERSION_NAME + " · 由家长手动检查和安装"));
        content.addView(button("检查应用更新", () -> gate(this::showUpdateDialog), false));
        gap(content, 12); content.addView(note("记录只保存在本机。覆盖更新会保留记录；卸载或清除应用数据会删除记录。导出文件用于留档，首版暂不支持导入。请家长记住 PIN。"));
    }
    private void showUpdateDialog() {
        updateDialog = new AlertDialog.Builder(this).setTitle("应用更新 · 当前 " + BuildConfig.VERSION_NAME)
                .setMessage(updateMessage).setPositiveButton("检查更新", null).setNegativeButton("关闭", null).create();
        dialogOpen = true; updateDialog.setOnDismissListener(d -> { updateDialog = null; dialogOpen = false; }); updateDialog.show();
        updateDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> gate(() -> {
            if (readyVersion() != null) installUpdate();
            else if (updateRelease != null && updateRelease.version.compareTo(ReleasePolicy.Version.parse(BuildConfig.VERSION_NAME)) > 0) downloadUpdate();
            else checkUpdate();
        })); refreshUpdateDialog();
    }
    private String readyVersion() {
        String version = getSharedPreferences("app-update", MODE_PRIVATE).getString("readyVersion", null);
        if (version == null || !new java.io.File(getFilesDir(), "updates/update.apk").isFile()) return null;
        try { if (ReleasePolicy.Version.parse(version).compareTo(ReleasePolicy.Version.parse(BuildConfig.VERSION_NAME)) > 0) return version; } catch (IllegalArgumentException ignored) { }
        getSharedPreferences("app-update", MODE_PRIVATE).edit().remove("readyVersion").apply(); return null;
    }
    private void refreshUpdateDialog() {
        if (updateDialog == null || !updateDialog.isShowing()) return;
        String ready = readyVersion(); updateDialog.setMessage(ready != null && !updateBusy ? "新版 " + ready + " 已下载并校验，可以覆盖安装。\n\n安装需要安卓系统确认，星星、任务和家长 PIN 会保留。" : updateMessage);
        Button action = updateDialog.getButton(AlertDialog.BUTTON_POSITIVE); if (action == null) return;
        action.setEnabled(!updateBusy);
        action.setText(updateBusy ? "请稍候…" : ready != null ? "安装新版" : updateRelease != null && updateRelease.version.compareTo(ReleasePolicy.Version.parse(BuildConfig.VERSION_NAME)) > 0 ? "下载新版" : "检查更新");
    }
    private void checkUpdate() {
        if (updateBusy) return; updateBusy = true; updateMessage = "正在检查新版本…"; refreshUpdateDialog();
        executor.submit(() -> {
            try {
                ReleasePolicy.Release release = new ManualUpdater(this).check();
                handler.post(() -> {
                    updateBusy = false; updateRelease = release;
                    updateMessage = release == null ? "暂时没有已发布的更新。" : release.version.compareTo(ReleasePolicy.Version.parse(BuildConfig.VERSION_NAME)) <= 0 ? "当前已经是最新版本。" : "发现新版 " + release.version + "（" + String.format(Locale.CHINA, "%.1f", release.asset.size / 1048576.0) + " MB）\n\n" + release.notes + "\n\n点击下载后，再由您确认安装。";
                    refreshUpdateDialog();
                });
            } catch (Exception e) { updateFailure(e); }
        });
    }
    private void downloadUpdate() {
        if (updateBusy || updateRelease == null) return; ReleasePolicy.Release release = updateRelease;
        updateBusy = true; updateMessage = "正在下载新版… 0%"; refreshUpdateDialog();
        executor.submit(() -> {
            try {
                new ManualUpdater(this).download(release, percent -> handler.post(() -> { updateMessage = "正在下载新版… " + percent + "%"; refreshUpdateDialog(); }));
                getSharedPreferences("app-update", MODE_PRIVATE).edit().putString("readyVersion", release.version.toString()).commit();
                handler.post(() -> { updateBusy = false; refreshUpdateDialog(); if (resumed && !isFinishing()) toast("新版已下载，请点击安装新版"); });
            } catch (Exception e) { updateFailure(e); }
        });
    }
    private void installUpdate() {
        String version = readyVersion(); if (version == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            updateMessage = "请开启「允许来自此来源的应用」，返回后再次点击安装新版。";
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException e) { toast("请在系统设置中允许攒星星安装应用"); }
            return;
        }
        updateBusy = true; refreshUpdateDialog();
        executor.submit(() -> {
            try {
                new ManualUpdater(this).validate(new java.io.File(getFilesDir(), "updates/update.apk"), version);
                handler.post(() -> { updateBusy = false; refreshUpdateDialog(); if (!resumed || isFinishing()) return;
                    gate(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(UpdateProvider.URI, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
                        catch (RuntimeException e) { toast("无法打开系统安装界面：" + e.getMessage()); } });
                });
            } catch (Exception e) { getSharedPreferences("app-update", MODE_PRIVATE).edit().remove("readyVersion").commit(); updateFailure(e); }
        });
    }
    private void updateFailure(Exception e) { handler.post(() -> { updateBusy = false; updateMessage = e.getMessage() == null ? "更新失败，请重试" : e.getMessage(); refreshUpdateDialog(); }); }
    private void approve(StarState.Completion target) {
        gate(() -> { mutate(s -> { StarState.Completion c = s.completion(target.taskId, target.day); if (c != null) s.approve(c, System.currentTimeMillis()); }); toast("奖励已到账  +" + target.stars + " ★"); });
    }
    private void undo(StarState.Completion target) {
        gate(() -> new AlertDialog.Builder(this).setTitle(target.approved ? "撤销完成并扣回奖励？" : "退回这条完成记录？")
                .setMessage(target.title + (target.approved ? "\n将扣回 " + target.stars + " 颗星，账本保留撤销记录。" : "\n孩子可以重新提交。"))
                .setNegativeButton("取消", null).setPositiveButton("确认", (d, w) -> gate(() -> mutate(s -> { StarState.Completion c = s.completion(target.taskId, target.day); if (c != null) s.undo(c, System.currentTimeMillis()); }))).show());
    }
    private void gate(Runnable action) {
        if (parent()) { action.run(); return; }
        StarState current = store.snapshot(); boolean first = current.pinHash.isEmpty();
        LinearLayout form = form();
        form.addView(text(first ? "首次使用请由家长设置 4–8 位数字 PIN。" : "请输入家长 PIN。", 14, INK, false));
        EditText pin = input(form, "家长 PIN", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText confirm = first ? input(form, "再次输入 PIN", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD) : null;
        showForm(first ? "设置家长 PIN" : "进入家长空间", form, () -> {
            String value = pin.getText().toString();
            if (!value.matches("\\d{4,8}")) { pin.setError("请输入 4–8 位数字"); return false; }
            if (first) {
                if (!value.equals(confirm.getText().toString())) { confirm.setError("两次 PIN 不一致"); return false; }
                store.change(s -> ParentPin.set(s, value));
            } else {
                android.content.SharedPreferences p = getSharedPreferences("pin-attempts", MODE_PRIVATE);
                if (p.getLong("blocked", 0) > System.currentTimeMillis()) { pin.setError("尝试过多，请 30 秒后再试"); return false; }
                if (!ParentPin.verify(store.snapshot(), value)) {
                    int count = p.getInt("count", 0) + 1;
                    p.edit().putInt("count", count >= 5 ? 0 : count).putLong("blocked", count >= 5 ? System.currentTimeMillis() + 30_000 : 0).apply();
                    pin.setError("PIN 不正确"); return false;
                }
                p.edit().clear().apply();
            }
            parentUntil = SystemClock.elapsedRealtime() + 5 * 60_000;
            handler.post(action); return true;
        });
    }
    private void editName() {
        LinearLayout f = form(); EditText name = input(f, "孩子的昵称", state.childName, InputType.TYPE_CLASS_TEXT);
        showForm("修改昵称", f, () -> { String value = name.getText().toString().trim(); if (value.isEmpty() || value.length() > 12) { name.setError("请填写 1–12 个字符"); return false; } if (!parent()) return expired(); mutate(s -> s.childName = value); return true; });
    }
    private void changePin() {
        LinearLayout f = form(); EditText pin = input(f, "新 PIN", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD), repeat = input(f, "再次输入", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        showForm("修改家长 PIN", f, () -> { String v = pin.getText().toString(); if (!v.matches("\\d{4,8}") || !v.equals(repeat.getText().toString())) { pin.setError("需 4–8 位数字，且两次输入一致"); return false; } if (!parent()) return expired(); mutate(s -> ParentPin.set(s, v)); return true; });
    }
    private void taskEditor(StarState.Task task) {
        LinearLayout f = form(); EditText title = input(f, "任务名称", task == null ? "" : task.title, InputType.TYPE_CLASS_TEXT);
        EditText emoji = input(f, "图标（可填一个表情）", task == null ? "🌱" : task.emoji, InputType.TYPE_CLASS_TEXT);
        EditText stars = input(f, "奖励星星（1–100）", task == null ? "3" : String.valueOf(task.stars), InputType.TYPE_CLASS_NUMBER);
        showForm(task == null ? "添加每日任务" : "修改每日任务", f, () -> {
            String name = title.getText().toString().trim(), icon = emoji.getText().toString().trim(); int reward = number(stars, 1, 100);
            if (name.isEmpty() || name.length() > 40) { title.setError("请填写 1–40 个字符"); return false; } if (reward < 0) return false;
            if (icon.length() > 12) { emoji.setError("请用一个短图标"); return false; } if (!parent()) return expired();
            mutate(s -> { if (task == null) s.tasks.add(new StarState.Task(name, icon.isEmpty() ? "🌱" : icon, reward)); else for (StarState.Task t : s.tasks) if (t.id.equals(task.id)) { t.title = name; t.emoji = icon.isEmpty() ? "🌱" : icon; t.stars = reward; } }); return true;
        });
    }
    private void chooseApp() {
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        apps.sort(Comparator.comparing(a -> a.loadLabel(getPackageManager()).toString(), java.text.Collator.getInstance(Locale.CHINA)));
        List<String> packages = new ArrayList<>(), labels = new ArrayList<>();
        for (ResolveInfo app : apps) { String pkg = app.activityInfo.packageName; if (pkg.equals(getPackageName()) || packages.contains(pkg)) continue; packages.add(pkg); labels.add(app.loadLabel(getPackageManager()).toString()); }
        if (packages.isEmpty()) { toast("未找到可以选择的 App"); return; }
        dialogOpen = true;
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择要统计的 App").setItems(labels.toArray(new String[0]), (d, index) -> {
            StarState.Rule existing = null; for (StarState.Rule r : store.snapshot().rules) if (r.end == 0 && r.packageName.equals(packages.get(index))) existing = r;
            StarState.Rule selected = existing; handler.post(() -> gate(() -> ruleEditor(packages.get(index), labels.get(index), selected)));
        }).setNegativeButton("取消", null).create(); dialog.setOnDismissListener(d -> dialogOpen = false); dialog.show();
    }
    private void ruleEditor(String pkg, String label, StarState.Rule old) {
        LinearLayout f = form(); f.addView(text(label + "\n" + pkg, 14, INK, true));
        EditText free = input(f, "每日免费分钟（0–1440）", old == null ? "0" : String.valueOf(old.freeMinutes), InputType.TYPE_CLASS_NUMBER);
        EditText unit = input(f, "每多少分钟扣一次（1–1440）", old == null ? "10" : String.valueOf(old.unitMinutes), InputType.TYPE_CLASS_NUMBER);
        EditText stars = input(f, "每次扣几颗星（1–100）", old == null ? "1" : String.valueOf(old.stars), InputType.TYPE_CLASS_NUMBER);
        f.addView(text(old == null ? "从保存时开始累计，不追扣之前的用量。" : "保存前先结算旧规则；新规则从保存时重新累计，免费额度重新计算。", 12, MUTED, false));
        showForm("设置 App 扣星规则", f, () -> {
            int a = number(free, 0, 1440), b = number(unit, 1, 1440), c = number(stars, 1, 100); if (a < 0 || b < 0 || c < 0) return false; if (!parent()) return expired();
            queueRuleChange(() -> store.change(s -> { long now = System.currentTimeMillis(); for (StarState.Rule r : s.rules) if (r.packageName.equals(pkg) && r.end == 0) r.end = now; s.rules.add(new StarState.Rule(pkg, label, a, b, c, now)); })); return true;
        });
    }
    private void retireRule(StarState.Rule target) {
        new AlertDialog.Builder(this).setTitle("停止统计 " + target.label + "？").setMessage("先结算已用时长，然后停用规则。历史用量和扣星记录会保留。")
                .setNegativeButton("取消", null).setPositiveButton("停止统计", (d, w) -> gate(() -> queueRuleChange(() -> store.change(s -> { for (StarState.Rule r : s.rules) if (r.id.equals(target.id) && r.end == 0) r.end = System.currentTimeMillis(); })))).show();
    }
    private void queueRuleChange(Runnable change) {
        toast("正在保存规则…");
        executor.submit(() -> { String error = null; try { synchronized (UsageTracker.class) { UsageTracker.sync(this); change.run(); } } catch (RuntimeException e) { error = e.getMessage(); }
            String result = error; handler.post(() -> { if (resumed && !isFinishing()) { toast(result == null ? "规则已保存" : result); render(); } }); });
    }
    private void adjustment() {
        LinearLayout f = form(); EditText amount = input(f, "星星数量（正数补奖，负数扣星）", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED), reason = input(f, "原因", "", InputType.TYPE_CLASS_TEXT);
        showForm("手动补记星星", f, () -> { int n = number(amount, -1000, 1000); String r = reason.getText().toString().trim(); if (n == Integer.MIN_VALUE || n == 0) { amount.setError("填写非零整数，范围 -1000 到 1000"); return false; } if (r.isEmpty() || r.length() > 60) { reason.setError("请填写 1–60 个字符的原因"); return false; } if (!parent()) return expired(); mutate(s -> { long now = System.currentTimeMillis(); s.ledger.add(new StarState.Entry(s.day(now), r, "manual", n, now)); }); return true; });
    }
    private boolean expired() { toast("家长模式已锁定，请重新进入"); handler.post(this::render); return true; }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 100 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData(); executor.submit(() -> { try (OutputStream stream = getContentResolver().openOutputStream(uri)) { if (stream == null) throw new IllegalStateException("无法打开文件"); stream.write(store.export().getBytes(StandardCharsets.UTF_8)); handler.post(() -> toast("记录已导出")); } catch (Exception e) { handler.post(() -> toast("导出失败：" + e.getMessage())); } });
        }
    }
    private void showForm(String title, LinearLayout form, java.util.function.BooleanSupplier save) {
        ScrollView scroller = new ScrollView(this); scroller.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroller).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialogOpen = true; dialog.setOnDismissListener(d -> dialogOpen = false); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { if (save.getAsBoolean()) dialog.dismiss(); } catch (RuntimeException e) { toast(e.getMessage()); } });
    }
    private int number(EditText field, int min, int max) { try { int n = Integer.parseInt(field.getText().toString().trim()); if (n >= min && n <= max) return n; } catch (NumberFormatException ignored) { } field.setError("请输入 " + min + " 到 " + max + " 的整数"); return Integer.MIN_VALUE; }
    private EditText input(LinearLayout form, String label, String value, int type) {
        TextView heading = text(label, 13, MUTED, false); heading.setPadding(0, dp(10), 0, 0); form.addView(heading);
        EditText field = new EditText(this); field.setInputType(type); field.setText(value); field.setTextSize(17); field.setSingleLine(true); field.setSelectAllOnFocus(true); field.setContentDescription(label); form.addView(field, new LinearLayout.LayoutParams(-1, dp(52))); return field;
    }
    private LinearLayout form() { LinearLayout f = column(); f.setPadding(dp(24), dp(12), dp(24), dp(16)); return f; }
    private String today() { return state.day(System.currentTimeMillis()); }
    private static String duration(long ms) { long seconds = ms / 1000; return seconds >= 60 ? (seconds / 60) + " 分 " + (seconds % 60) + " 秒" : seconds + " 秒"; }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void pad(View v, int n) { v.setPadding(dp(n), dp(n), dp(n), dp(n)); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams weighted(float weight) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight); lp.setMargins(0, 0, dp(8), 0); return lp; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (color == Color.WHITE) d.setStroke(dp(1), LINE); return d; }
    private TextView text(String value, int size, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(4), 1); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private Button button(String value, Runnable action, boolean primary) {
        Button b = new Button(this); b.setText(value); b.setTextSize(13); b.setAllCaps(false); b.setTextColor(primary ? Color.WHITE : GREEN); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(shape(primary ? GREEN : PALE, 12)); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(8); b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private LinearLayout card(int color) { LinearLayout c = column(); pad(c, 18); c.setBackground(shape(color, 18)); return c; }
    private LinearLayout stat(String label, String value, String detail, int accent) {
        LinearLayout tile = card(Color.WHITE); tile.addView(text(label, 12, MUTED, false)); tile.addView(text(value, wide ? 26 : 21, accent, true)); if (wide) tile.addView(text(detail, 11, MUTED, false)); return tile;
    }
    private LinearLayout section(String title, String subtitle) { LinearLayout l = column(); l.setPadding(0, 0, 0, dp(14)); l.addView(text(title, 20, INK, true)); l.addView(text(subtitle, 12, MUTED, false)); return l; }
    private LinearLayout note(String value) { LinearLayout tile = card(Color.WHITE); tile.addView(text(value, 13, MUTED, false)); return tile; }
    private void gap(LinearLayout target, int size) { View space = new View(this); target.addView(space, new LinearLayout.LayoutParams(1, dp(size))); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
