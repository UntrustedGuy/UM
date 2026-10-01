package untrusted.manager.um.plugin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import untrusted.manager.um.plugin.api.UmPluginContract;

/** Discovers installed UM plugins and exposes only validated, enabled plugin entries. */
public final class PluginManager {
    private static final String PREF_ENABLED = "um.plugins.enabled";
    private static final String ID_PATTERN = "[A-Za-z0-9._-]{1,64}";
    private PluginManager() {}

    public static List<PluginDescriptor> discover(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(UmPluginContract.ACTION_PLUGIN_ENTRY);
        List<ResolveInfo> infos = queryActivities(pm, query);
        List<PluginDescriptor> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : infos) {
            ActivityInfo ai = info.activityInfo;
            if (ai == null || TextUtils.isEmpty(ai.packageName) || TextUtils.isEmpty(ai.name)) continue;
            if (!ai.exported) continue;
            Bundle md = null;
            try { md = ai.metaData; } catch (RuntimeException ignored) {}
            if (md == null) continue;
            String id = md.getString(UmPluginContract.META_PLUGIN_ID);
            String name = md.getString(UmPluginContract.META_PLUGIN_NAME);
            String desc = md.getString(UmPluginContract.META_PLUGIN_DESCRIPTION, "");
            String category = md.getString(UmPluginContract.META_PLUGIN_CATEGORY, "Plugins");
            int api = md.getInt(UmPluginContract.META_PLUGIN_API, UmPluginContract.API_VERSION);
            int minApi = md.getInt(UmPluginContract.META_PLUGIN_MIN_API, 1);
            if (TextUtils.isEmpty(id) || !id.matches(ID_PATTERN) || TextUtils.isEmpty(name)) continue;
            if (api < UmPluginContract.API_VERSION || minApi > UmPluginContract.API_VERSION) continue;
            String key = ai.packageName + ":" + id;
            if (!seen.add(key)) continue;
            int icon = md.getInt(UmPluginContract.META_PLUGIN_ICON, 0);
            result.add(new PluginDescriptor(ai.packageName, id, name, desc, category, ai.name, icon, api, minApi));
        }
        Collections.sort(result, Comparator.comparing((PluginDescriptor p) -> p.category, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static List<ResolveInfo> queryActivities(PackageManager pm, Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));
        }
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
    }

    public static boolean isEnabled(Context context, PluginDescriptor plugin) {
        return prefs(context).getBoolean(plugin.stableId(), true);
    }

    public static void setEnabled(Context context, PluginDescriptor plugin, boolean enabled) {
        prefs(context).edit().putBoolean(plugin.stableId(), enabled).apply();
    }

    public static List<PluginDescriptor> enabledPlugins(Context context) {
        List<PluginDescriptor> out = new ArrayList<>();
        for (PluginDescriptor p : discover(context)) if (isEnabled(context, p)) out.add(p);
        return out;
    }

    public static PluginDescriptor findByStableId(Context context, String stableId) {
        if (stableId == null) return null;
        for (PluginDescriptor p : discover(context)) if (stableId.equals(p.stableId())) return p;
        return null;
    }

    private static SharedPreferences prefs(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }
}
