package untrusted.manager.um.plugin;

import android.content.ComponentName;

import java.util.Objects;

public final class PluginDescriptor {
    public final String packageName;
    public final String id;
    public final String name;
    public final String description;
    public final String category;
    public final String activityClass;
    public final int iconRes;
    public final int apiVersion;
    public final int minApiVersion;

    public PluginDescriptor(String packageName, String id, String name, String description,
                            String category, String activityClass, int iconRes,
                            int apiVersion, int minApiVersion) {
        this.packageName = packageName;
        this.id = id;
        this.name = name;
        this.description = description;
        this.category = category;
        this.activityClass = activityClass;
        this.iconRes = iconRes;
        this.apiVersion = apiVersion;
        this.minApiVersion = minApiVersion;
    }

    public ComponentName component() { return new ComponentName(packageName, activityClass); }
    public String stableId() { return packageName + ":" + id; }
    @Override public boolean equals(Object o) {
        return o instanceof PluginDescriptor && stableId().equals(((PluginDescriptor)o).stableId());
    }
    @Override public int hashCode() { return Objects.hash(packageName, id); }
}
