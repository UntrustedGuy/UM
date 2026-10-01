package untrusted.manager.um.player;

import android.content.Context;
import android.content.SharedPreferences;


import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent HTTP header rules used by network media playback.
 *
 * Rules are matched case-insensitively. A pattern ending in '*' is treated as a
 * prefix rule; '*' by itself is the global default. Exact URL rules take
 * precedence over prefix/default rules, and later rules override earlier
 * values for the same header name.
 */
public final class StreamingHeaderStore {
    private static final String PREFS = "um_streaming_headers";
    private static final String KEY_RULES = "rules";
    private static final int MAX_RULES = 256;
    private static final int MAX_HEADERS_PER_RULE = 64;
    private static final int MAX_NAME = 128;
    private static final int MAX_VALUE = 8192;

    private StreamingHeaderStore() {}

    public static final class Rule {
        public final String pattern;
        public final Map<String, String> headers;

        public Rule(String pattern, Map<String, String> headers) {
            this.pattern = pattern == null ? "" : pattern.trim();
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    public static List<Rule> getRules(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_RULES, "[]");
        List<Rule> result = new ArrayList<>();
        try {
            JSONArray rules = new JSONArray(raw);
            for (int i = 0; i < rules.length() && result.size() < MAX_RULES; i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule == null) continue;
                String pattern = sanitizePattern(rule.optString("pattern", ""));
                if (pattern.isEmpty()) continue;
                JSONObject obj = rule.optJSONObject("headers");
                if (obj == null) continue;
                Map<String, String> headers = new LinkedHashMap<>();
                JSONArray names = obj.names();
                if (names != null) {
                    for (int j = 0; j < names.length() && headers.size() < MAX_HEADERS_PER_RULE; j++) {
                        String name = names.optString(j, "").trim();
                        String value = obj.optString(name, "");
                        if (isValidHeaderName(name) && isValidHeaderValue(value)) headers.put(name, value);
                    }
                }
                if (!headers.isEmpty()) result.add(new Rule(pattern, headers));
            }
        } catch (Exception ignored) {
            // A malformed preference must never prevent local media playback.
        }
        return result;
    }

    public static Map<String, String> resolve(Context context, String url) {
        if (url == null || url.trim().isEmpty()) return Collections.emptyMap();
        String target = url.trim();
        List<Rule> rules = getRules(context);
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        int bestSpecificity = -1;
        // Merge all matching rules. Exact rules are applied last regardless of
        // their position so a global rule cannot accidentally override them.
        for (Rule rule : rules) {
            int specificity = matchSpecificity(rule.pattern, target);
            if (specificity < 0 || specificity < bestSpecificity) continue;
            if (specificity > bestSpecificity) {
                if (specificity == 0) result.clear();
                bestSpecificity = specificity;
            }
            putHeaders(result, rule.headers);
        }
        // Also merge less-specific defaults before the most specific rule.
        if (bestSpecificity > 0) {
            LinkedHashMap<String, String> merged = new LinkedHashMap<>();
            for (Rule rule : rules) {
                int specificity = matchSpecificity(rule.pattern, target);
                if (specificity >= 0 && specificity < bestSpecificity) putHeaders(merged, rule.headers);
            }
            putHeaders(merged, result);
            result = merged;
        }
        return Collections.unmodifiableMap(result);
    }

    public static void upsert(Context context, String pattern, Map<String, String> headers) {
        String cleanPattern = sanitizePattern(pattern);
        if (cleanPattern.isEmpty()) throw new IllegalArgumentException("URL pattern is required");
        Map<String, String> cleanHeaders = sanitizeHeaders(headers);
        if (cleanHeaders.isEmpty()) throw new IllegalArgumentException("At least one valid header is required");
        List<Rule> rules = getRules(context);
        List<Rule> updated = new ArrayList<>();
        boolean replaced = false;
        for (Rule rule : rules) {
            if (rule.pattern.equals(cleanPattern)) {
                if (!replaced) {
                    updated.add(new Rule(cleanPattern, cleanHeaders));
                    replaced = true;
                }
            } else updated.add(rule);
        }
        if (!replaced) updated.add(new Rule(cleanPattern, cleanHeaders));
        if (updated.size() > MAX_RULES) updated = new ArrayList<>(updated.subList(updated.size() - MAX_RULES, updated.size()));
        save(context, updated);
    }

    public static void remove(Context context, String pattern) {
        String clean = sanitizePattern(pattern);
        List<Rule> rules = getRules(context);
        List<Rule> updated = new ArrayList<>();
        for (Rule rule : rules) if (!rule.pattern.equals(clean)) updated.add(rule);
        save(context, updated);
    }

    public static void save(Context context, List<Rule> rules) {
        JSONArray array = new JSONArray();
        int count = 0;
        for (Rule rule : rules) {
            if (count++ >= MAX_RULES) break;
            try {
                JSONObject obj = new JSONObject();
                obj.put("pattern", sanitizePattern(rule.pattern));
                JSONObject headers = new JSONObject();
                for (Map.Entry<String, String> e : sanitizeHeaders(rule.headers).entrySet()) headers.put(e.getKey(), e.getValue());
                obj.put("headers", headers);
                array.put(obj);
            } catch (Exception ignored) {}
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RULES, array.toString()).apply();
    }

    public static String formatHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        return sb.toString();
    }

    public static Map<String, String> parseHeaders(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null) return result;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (isValidHeaderName(name) && isValidHeaderValue(value)) result.put(name, value);
        }
        return result;
    }

    private static void putHeaders(Map<String, String> target, Map<String, String> source) {
        for (Map.Entry<String, String> e : source.entrySet()) {
            String existing = null;
            for (String key : target.keySet()) if (key.equalsIgnoreCase(e.getKey())) { existing = key; break; }
            if (existing != null) target.remove(existing);
            target.put(e.getKey(), e.getValue());
        }
    }

    private static int matchSpecificity(String pattern, String url) {
        if ("*".equals(pattern)) return 0;
        if (pattern.endsWith("*") && url.regionMatches(true, 0, pattern.substring(0, pattern.length() - 1), 0, pattern.length() - 1)) {
            return pattern.length() - 1;
        }
        return pattern.equalsIgnoreCase(url) ? pattern.length() + 10000 : -1;
    }

    private static Map<String, String> sanitizeHeaders(Map<String, String> headers) {
        Map<String, String> clean = new LinkedHashMap<>();
        if (headers == null) return clean;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (clean.size() >= MAX_HEADERS_PER_RULE) break;
            String name = e.getKey() == null ? "" : e.getKey().trim();
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (isValidHeaderName(name) && isValidHeaderValue(value)) clean.put(name, value);
        }
        return clean;
    }

    private static boolean isValidHeaderName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean token = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) return false;
        }
        return true;
    }

    private static boolean isValidHeaderValue(String value) {
        return value != null && value.length() <= MAX_VALUE && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static String sanitizePattern(String pattern) {
        if (pattern == null) return "";
        String p = pattern.trim();
        return p.length() > 8192 ? p.substring(0, 8192) : p;
    }
}
