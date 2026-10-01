package untrusted.manager.um.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Persistent path permissions for the local MCP file tools. */
public final class McpAccessRuleStore {
    public static final int DENY = 0;
    public static final int READ_ONLY = 1;
    public static final int READ_WRITE = 2;
    private static final String PREFS = "mcp_access";
    private static final String KEY_RULES = "rules";

    public record Rule(String path, int mode) {}

    private McpAccessRuleStore() {}

    public static List<Rule> getRules(Context context) {
        List<Rule> result = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RULES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                String path = canonical(o.optString("path", ""));
                int mode = o.optInt("mode", DENY);
                if (!path.isEmpty() && mode >= DENY && mode <= READ_WRITE) result.add(new Rule(path, mode));
            }
        } catch (Exception ignored) {}
        result.sort(Comparator.comparingInt((Rule r) -> r.path().length()).reversed());
        return result;
    }

    public static void put(Context context, String path, int mode) throws Exception {
        String canonical = canonical(path);
        if (canonical.isEmpty()) throw new IllegalArgumentException("Path is required");
        if (mode < DENY || mode > READ_WRITE) throw new IllegalArgumentException("Invalid permission");
        List<Rule> rules = getRules(context);
        rules.removeIf(r -> r.path().equals(canonical));
        rules.add(new Rule(canonical, mode));
        save(context, rules);
    }

    public static void remove(Context context, String path) throws Exception {
        String canonical = canonical(path);
        List<Rule> rules = getRules(context);
        rules.removeIf(r -> r.path().equals(canonical));
        save(context, rules);
    }

    public static int permission(Context context, File file, boolean write, File defaultRoot) throws Exception {
        String path = file.getCanonicalPath();
        for (Rule rule : getRules(context)) {
            String base = rule.path();
            if (path.equals(base) || path.startsWith(base + File.separator)) {
                if (rule.mode() == DENY) return DENY;
                if (write && rule.mode() != READ_WRITE) return DENY;
                return rule.mode();
            }
        }
        if (defaultRoot != null) {
            String base = defaultRoot.getCanonicalPath();
            if (path.equals(base) || path.startsWith(base + File.separator)) return READ_WRITE;
        }
        return DENY;
    }


    public static boolean hasExplicitRule(Context context, File file) throws Exception {
        String path = file.getCanonicalPath();
        for (Rule rule : getRules(context)) {
            if (path.equals(rule.path()) || path.startsWith(rule.path() + File.separator)) return true;
        }
        return false;
    }

    private static void save(Context context, List<Rule> rules) throws Exception {
        JSONArray array = new JSONArray();
        for (Rule rule : rules) {
            JSONObject o = new JSONObject();
            o.put("path", rule.path());
            o.put("mode", rule.mode());
            array.put(o);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RULES, array.toString()).apply();
    }

    private static String canonical(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        try { return new File(path.trim()).getCanonicalPath(); }
        catch (Exception e) { return ""; }
    }
}
