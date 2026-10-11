package com.starcollect.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.List;

/** Dependency-free device tests. Run only on a disposable emulator with usage access enabled. */
public final class SmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private int checks;
    private String test;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); test = arguments == null ? "ui" : arguments.getString("suite", "ui"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            if (test.equals("usage")) usageTest(); else uiTest();
            result.putString("stream", "PASS " + test + ": " + checks + " checks\n"); finish(Activity.RESULT_OK, result);
        } catch (Throwable e) { result.putString("stream", "FAIL " + test + ": " + android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result); }
    }
    private void uiTest() throws Exception {
        StateStore store = StateStore.get(getTargetContext());
        check(store.snapshot().balance() == 0, "fresh wallet is empty");
        click("我完成啦  ＋");
        check(store.snapshot().completions.size() == 1, "child submission persisted");
        check(store.snapshot().balance() == 0, "child cannot award stars");
        click("⚙  家长空间");
        runOnMainSync(() -> { List<EditText> fields = fields(); fields.get(0).setText("1357"); fields.get(1).setText("1357"); });
        click("保存");
        check(ParentPin.verify(store.snapshot(), "1357"), "new PIN works");
        check(!ParentPin.verify(store.snapshot(), "1111"), "incorrect PIN rejected");
        click("确认完成");
        check(store.snapshot().balance() == 3, "parent confirms reward");
        check(store.snapshot().completions.get(0).approved, "approval saved");
        click("＋ 新任务");
        runOnMainSync(() -> { List<EditText> fields = fields(); fields.get(0).setText("测试任务"); fields.get(2).setText("7"); });
        click("保存"); check(store.snapshot().tasks.size() == 7, "custom task saved");
        check(!store.export().contains("pinHash") && !store.export().contains("pinSalt"), "export excludes PIN credentials");
        click("退出家长模式");
        click("⚙  家长空间");
        check(fields().size() == 1, "leaving parent mode relocks settings");
        runOnMainSync(() -> fields().get(0).setText("1111")); click("保存");
        check(fields().size() == 1 && fields().get(0).getError() != null, "wrong PIN stays locked");
        click("取消");
        click("▤  成长记录"); check(findText("认真阅读 20 分钟") != null, "history includes completed task");
        click("◷  App 用量"); check(findText("＋ 添加 App") != null, "usage page renders");
        click("＋ 添加 App"); runOnMainSync(() -> fields().get(0).setText("1357")); click("保存");
        String settingsLabel = getTargetContext().getPackageManager().getApplicationLabel(getTargetContext().getPackageManager().getApplicationInfo("com.android.settings", 0)).toString();
        runOnMainSync(() -> {
            ListView list = null; for (View root : roots()) { list = findList(root); if (list != null) break; }
            if (list == null) throw new AssertionError("Missing app picker");
            int selected = -1; for (int i = 0; i < list.getAdapter().getCount(); i++) if (list.getAdapter().getItem(i).toString().equals(settingsLabel)) selected = i;
            if (selected < 0) throw new AssertionError("Settings missing from package visibility query");
            list.performItemClick(null, selected, list.getAdapter().getItemId(selected));
        });
        Thread.sleep(300); waitForIdleSync();
        runOnMainSync(() -> { List<EditText> f = fields(); f.get(0).setText("5"); f.get(1).setText("10"); f.get(2).setText("1"); }); click("保存");
        awaitRules(store, 1); StarState.Rule first = store.snapshot().rules.get(0);
        check(first.packageName.equals("com.android.settings") && first.freeMinutes == 5 && first.unitMinutes == 10, "app picker and rule form save selected app");
        click("修改规则"); runOnMainSync(() -> fields().get(1).setText("5")); click("保存"); awaitRules(store, 2);
        check(store.snapshot().rules.get(0).end > 0 && store.snapshot().rules.get(1).end == 0, "editing closes old rule and starts new version");
        click("停止统计"); click("停止统计");
        for (int i = 0; i < 20 && store.snapshot().rules.get(1).end == 0; i++) Thread.sleep(100);
        check(store.snapshot().rules.get(1).end > 0, "stopping rule preserves history");
        click("☀  今日任务");
        check(store.snapshot().balance() == 3, "navigation keeps wallet");
    }
    private void usageTest() throws Exception {
        StateStore store = StateStore.get(getTargetContext());
        check(UsageTracker.permitted(getTargetContext()), "usage permission granted by emulator harness");
        UsageTracker.sync(getTargetContext());
        long start = System.currentTimeMillis();
        store.change(s -> s.rules.add(new StarState.Rule("com.android.settings", "系统设置（测试）", 0, 1, 2, start)));
        getTargetContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // Leave a real Android app in the foreground across a complete billing unit.
        for (int i = 0; i < 32; i++) Thread.sleep(2000);
        getTargetContext().startActivity(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        Thread.sleep(1500); UsageTracker.sync(getTargetContext());
        StarState snapshot = store.snapshot(); StarState.Rule rule = snapshot.rules.get(snapshot.rules.size() - 1);
        long ms = snapshot.usageToday(rule, snapshot.day(start));
        check(ms >= 60_000 && ms < 90_000, "real Settings usage within expected range: " + ms);
        check(snapshot.billed.getOrDefault(rule.id + "|" + snapshot.day(start), 0) == 2, "real usage deducts two stars");
        int balance = snapshot.balance(); UsageTracker.sync(getTargetContext());
        check(store.snapshot().balance() == balance, "second refresh cannot double charge");
        store.change(s -> { for (StarState.Rule r : s.rules) if (r.id.equals(rule.id)) r.end = System.currentTimeMillis(); });
        check(store.snapshot().rules.get(store.snapshot().rules.size() - 1).end != 0, "stop rule preserves history");
    }
    private void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private void awaitRules(StateStore store, int size) throws Exception { for (int i = 0; i < 30 && store.snapshot().rules.size() < size; i++) Thread.sleep(100); check(store.snapshot().rules.size() == size, "rule mutation completed"); Thread.sleep(150); waitForIdleSync(); }
    private void click(String text) throws Exception {
        waitForIdleSync(); runOnMainSync(() -> { View v = findText(text); if (v == null) throw new AssertionError("Missing UI: " + text); v.performClick(); });
        Thread.sleep(250); waitForIdleSync();
    }
    private TextView findText(String text) {
        for (View root : roots()) { TextView result = search(root, text); if (result != null) return result; } return null;
    }
    private TextView search(View view, String text) {
        if (view instanceof TextView t && t.getText().toString().equals(text)) return t;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView result = search(group.getChildAt(i), text); if (result != null) return result; } return null;
    }
    private List<View> roots() {
        // WindowManagerGlobal is available to instrumentation; dialogs live in separate roots.
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal"); Object global = type.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field field = type.getDeclaredField("mViews"); field.setAccessible(true);
            @SuppressWarnings("unchecked") List<View> views = (List<View>) field.get(global); List<View> result = new ArrayList<>(views); java.util.Collections.reverse(result); return result;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private List<EditText> fields() { List<EditText> result = new ArrayList<>(); for (View root : roots()) collect(root, result); return result; }
    private ListView findList(View view) { if (view instanceof ListView l) return l; if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { ListView result = findList(g.getChildAt(i)); if (result != null) return result; } return null; }
    private void collect(View view, List<EditText> result) { if (view instanceof EditText e) result.add(e); if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), result); }
}
