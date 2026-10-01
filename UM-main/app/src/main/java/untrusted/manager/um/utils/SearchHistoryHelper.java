package untrusted.manager.um.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Robust, bounded search-history storage shared by the file, Dex and ARSC search UIs. */
public final class SearchHistoryHelper {
    public static final String KEY_MAIN = "search_history";
    public static final String KEY_DEX = "dex_search_history";
    public static final String KEY_ARSC_PLUS = "arsc_plus_search_history";
    public static final String KEY_LIMIT = "search_history_limit";
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 500;
    private static final Object LOCK = new Object();

    private SearchHistoryHelper() {}

    public static class Item {
        public String query;
        public boolean pinned;
        public Item(String query, boolean pinned) { this.query = query; this.pinned = pinned; }
    }

    public static int getLimit(Context context) {
        try {
            int value = PreferenceManager.getDefaultSharedPreferences(context)
                    .getInt(KEY_LIMIT, DEFAULT_LIMIT);
            return value <= 0 ? DEFAULT_LIMIT : Math.min(value, MAX_LIMIT);
        } catch (Exception e) { return DEFAULT_LIMIT; }
    }

    public static List<Item> load(Context context, String key) {
        synchronized (LOCK) {
            List<Item> parsed = new ArrayList<>();
            try {
                String raw = PreferenceManager.getDefaultSharedPreferences(context).getString(key, "");
                if (raw == null || raw.trim().isEmpty()) return parsed;
                raw = raw.trim();
                if (raw.startsWith("[")) {
                    JSONArray array = new JSONArray(raw);
                    for (int i = 0; i < array.length(); i++) {
                        Object value = array.opt(i);
                        String query = null;
                        boolean pinned = false;
                        if (value instanceof JSONObject) {
                            JSONObject object = (JSONObject) value;
                            query = object.optString("q", null);
                            if (query == null) query = object.optString("query", null);
                            pinned = object.optBoolean("p", object.optBoolean("pinned", false));
                        } else if (value instanceof String) {
                            query = (String) value;
                        }
                        addUnique(parsed, query, pinned);
                    }
                } else {
                    for (String line : raw.split("\\R", -1)) addUnique(parsed, line, false);
                }
            } catch (Exception ignored) {
                // Corrupt history must never prevent the search dialog from opening.
                // Preserve no partially parsed state from a malformed JSON document.
                parsed.clear();
            }
            normalize(parsed);
            trim(parsed, getLimit(context));
            return parsed;
        }
    }

    public static void save(Context context, String key, List<Item> items) {
        synchronized (LOCK) {
            List<Item> normalized = new ArrayList<>();
            if (items != null) {
                for (Item item : items) if (item != null) addUnique(normalized, item.query, item.pinned);
            }
            normalize(normalized);
            trim(normalized, getLimit(context));
            JSONArray array = new JSONArray();
            for (Item item : normalized) {
                try {
                    JSONObject object = new JSONObject();
                    object.put("q", item.query);
                    object.put("p", item.pinned);
                    array.put(object);
                } catch (Exception ignored) {}
            }
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(key, array.toString()).commit();
        }
    }

    public static void push(Context context, String key, String query) {
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty()) return;
        synchronized (LOCK) {
            List<Item> items = load(context, key);
            boolean pinned = false;
            for (Item item : items) if (item.query.equals(normalized)) { pinned = item.pinned; break; }
            for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).query.equals(normalized)) items.remove(i);
            items.add(0, new Item(normalized, pinned));
            save(context, key, items);
        }
    }

    public static void remove(Context context, String key, String query) {
        String normalized = normalizeQuery(query);
        synchronized (LOCK) {
            List<Item> items = load(context, key);
            for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).query.equals(normalized)) items.remove(i);
            save(context, key, items);
        }
    }

    public static void setPinned(Context context, String key, String query, boolean pinned) {
        String normalized = normalizeQuery(query);
        synchronized (LOCK) {
            List<Item> items = load(context, key);
            for (Item item : items) if (item.query.equals(normalized)) { item.pinned = pinned; break; }
            save(context, key, items);
        }
    }

    private static void addUnique(List<Item> target, String query, boolean pinned) {
        String normalized = normalizeQuery(query);
        if (normalized.isEmpty()) return;
        for (Item existing : target) {
            if (existing.query.equals(normalized)) {
                existing.pinned |= pinned;
                return;
            }
        }
        target.add(new Item(normalized, pinned));
    }

    private static void normalize(List<Item> items) {
        List<Item> unique = new ArrayList<>();
        for (Item item : items) if (item != null) addUnique(unique, item.query, item.pinned);
        items.clear();
        for (Item item : unique) if (item.pinned) items.add(item);
        for (Item item : unique) if (!item.pinned) items.add(item);
    }

    private static void trim(List<Item> items, int limit) {
        int pinned = 0;
        for (Item item : items) if (item.pinned) pinned++;
        int allowed = Math.max(limit, pinned);
        while (items.size() > allowed) {
            int last = -1;
            for (int i = items.size() - 1; i >= 0; i--) if (!items.get(i).pinned) { last = i; break; }
            if (last < 0) break;
            items.remove(last);
        }
    }

    private static String normalizeQuery(String query) {
        if (query == null) return "";
        String value = query.trim();
        if (value.length() > 4096) value = value.substring(0, 4096);
        return value;
    }
}
