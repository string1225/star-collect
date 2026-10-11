package com.starcollect.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;

/** A single atomically persisted document, shared by the UI and the scheduled job. */
public final class StateStore {
    private static StateStore instance;
    private final SharedPreferences preferences;
    private StarState state;
    public static synchronized StateStore get(Context context) {
        if (instance == null) instance = new StateStore(context.getApplicationContext());
        return instance;
    }
    private StateStore(Context context) {
        preferences = context.getSharedPreferences("star-collect", Context.MODE_PRIVATE);
        String saved = preferences.getString("state", null);
        if (saved == null) { state = new StarState(); state.seedTasks(); save(); }
        else state = decode(saved); // Never silently replace damaged data with an empty wallet.
    }
    public synchronized StarState snapshot() { return decode(encode(state).toString()); }
    public synchronized void change(Consumer<StarState> mutation) {
        String previous = encode(state).toString();
        try { mutation.accept(state); save(); }
        catch (RuntimeException e) { state = decode(previous); throw e; }
    }
    public synchronized String export() {
        JSONObject json = encode(state);
        json.remove("pinHash"); json.remove("pinSalt");
        try { return json.toString(2); } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private void save() {
        if (!preferences.edit().putString("state", encode(state).toString()).commit())
            throw new IllegalStateException("本机保存失败，请检查存储空间");
    }
    private static JSONObject object(Object... fields) throws JSONException {
        JSONObject o = new JSONObject();
        for (int i = 0; i < fields.length; i += 2) o.put((String) fields[i], fields[i + 1]);
        return o;
    }
    private static JSONObject encode(StarState s) {
        try {
            JSONObject o = object("version", 1, "childName", s.childName, "pinSalt", s.pinSalt, "pinHash", s.pinHash,
                    "zone", s.zone, "cursor", s.cursor, "lastSync", s.lastSync, "trackingStarted", s.trackingStarted, "runtimeDown", s.runtimeDown, "syncNote", s.syncNote);
            JSONArray tasks = new JSONArray(), completions = new JSONArray(), rules = new JSONArray(), ledger = new JSONArray();
            for (StarState.Task t : s.tasks) tasks.put(object("id", t.id, "title", t.title, "emoji", t.emoji, "stars", t.stars, "enabled", t.enabled));
            for (StarState.Completion c : s.completions) completions.put(object("taskId", c.taskId, "day", c.day, "title", c.title, "emoji", c.emoji, "stars", c.stars, "approved", c.approved, "at", c.at));
            for (StarState.Rule r : s.rules) rules.put(object("id", r.id, "packageName", r.packageName, "label", r.label, "freeMinutes", r.freeMinutes, "unitMinutes", r.unitMinutes, "stars", r.stars, "start", r.start, "end", r.end));
            for (StarState.Entry e : s.ledger) ledger.put(object("id", e.id, "day", e.day, "title", e.title, "kind", e.kind, "delta", e.delta, "at", e.at));
            o.put("tasks", tasks).put("completions", completions).put("rules", rules).put("ledger", ledger);
            o.put("usage", new JSONObject(s.usage)).put("billed", new JSONObject(s.billed)).put("active", new JSONObject(s.active));
            return o;
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static StarState decode(String text) {
        try {
            JSONObject o = new JSONObject(text); StarState s = new StarState();
            if (o.getInt("version") != 1) throw new IllegalStateException("不支持的数据版本");
            s.childName = o.getString("childName"); s.pinSalt = o.getString("pinSalt"); s.pinHash = o.getString("pinHash");
            s.zone = o.getString("zone"); s.cursor = o.getLong("cursor"); s.lastSync = o.getLong("lastSync");
            s.trackingStarted = o.getBoolean("trackingStarted"); s.runtimeDown = o.optBoolean("runtimeDown", false); s.syncNote = o.getString("syncNote");
            JSONArray a = o.getJSONArray("tasks");
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i); StarState.Task t = new StarState.Task(v.getString("title"), v.getString("emoji"), v.getInt("stars"));
                t.id = v.getString("id"); t.enabled = v.getBoolean("enabled"); s.tasks.add(t);
            }
            a = o.getJSONArray("completions");
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i); StarState.Completion c = new StarState.Completion();
                c.taskId = v.getString("taskId"); c.day = v.getString("day"); c.title = v.getString("title"); c.emoji = v.getString("emoji");
                c.stars = v.getInt("stars"); c.approved = v.getBoolean("approved"); c.at = v.getLong("at"); s.completions.add(c);
            }
            a = o.getJSONArray("rules");
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i); StarState.Rule r = new StarState.Rule(v.getString("packageName"), v.getString("label"), v.getInt("freeMinutes"), v.getInt("unitMinutes"), v.getInt("stars"), v.getLong("start"));
                r.id = v.getString("id"); r.end = v.getLong("end"); s.rules.add(r);
            }
            a = o.getJSONArray("ledger");
            for (int i = 0; i < a.length(); i++) {
                JSONObject v = a.getJSONObject(i); StarState.Entry e = new StarState.Entry(v.getString("day"), v.getString("title"), v.getString("kind"), v.getInt("delta"), v.getLong("at"));
                e.id = v.getString("id"); s.ledger.add(e);
            }
            JSONObject m = o.getJSONObject("usage");
            for (Iterator<String> i = m.keys(); i.hasNext();) { String k = i.next(); s.usage.put(k, m.getLong(k)); }
            m = o.getJSONObject("billed");
            for (Iterator<String> i = m.keys(); i.hasNext();) { String k = i.next(); s.billed.put(k, m.getInt(k)); }
            m = o.getJSONObject("active");
            for (Iterator<String> i = m.keys(); i.hasNext();) { String k = i.next(); s.active.put(k, m.getString(k)); }
            return s;
        } catch (JSONException e) { throw new IllegalStateException("数据读取失败，请保留应用数据", e); }
    }
}
