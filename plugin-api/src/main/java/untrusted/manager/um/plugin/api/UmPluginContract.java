package untrusted.manager.um.plugin.api;

/** Stable host/plugin contract. Keep action and metadata keys backward compatible. */
public final class UmPluginContract {
    private UmPluginContract() {}

    public static final int API_VERSION = 1;
    public static final String ACTION_PLUGIN_ENTRY = "untrusted.manager.um.plugin.ACTION_ENTRY";
    public static final String META_PLUGIN_ID = "untrusted.manager.um.plugin.id";
    public static final String META_PLUGIN_NAME = "untrusted.manager.um.plugin.name";
    public static final String META_PLUGIN_DESCRIPTION = "untrusted.manager.um.plugin.description";
    public static final String META_PLUGIN_CATEGORY = "untrusted.manager.um.plugin.category";
    public static final String META_PLUGIN_ICON = "untrusted.manager.um.plugin.icon";
    public static final String META_PLUGIN_API = "untrusted.manager.um.plugin.api";
    public static final String META_PLUGIN_MIN_API = "untrusted.manager.um.plugin.min_api";
    public static final String EXTRA_PLUGIN_ID = "untrusted.manager.um.plugin.EXTRA_ID";
    public static final String EXTRA_INPUT_URI = "untrusted.manager.um.plugin.EXTRA_INPUT_URI";
    public static final String EXTRA_INPUT_PATH = "untrusted.manager.um.plugin.EXTRA_INPUT_PATH";
    public static final String EXTRA_INPUT_MIME = "untrusted.manager.um.plugin.EXTRA_INPUT_MIME";
}
