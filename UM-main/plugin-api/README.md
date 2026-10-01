# Untrusted Manager Plugin SDK

`plugin-api` is the stable Android-side contract for third-party feature plugins.

A plugin is an independently installed APK. It registers one or more exported activities with the `untrusted.manager.um.plugin.ACTION_ENTRY` action and supplies the metadata defined by `UmPluginContract`.

Required metadata:

- `untrusted.manager.um.plugin.id` — stable plugin ID (`A-Z`, `a-z`, digits, `.`, `_`, `-`; maximum 64 characters).
- `untrusted.manager.um.plugin.name` — user-facing feature name.
- `untrusted.manager.um.plugin.api` — API version implemented by the plugin.
- `untrusted.manager.um.plugin.min_api` — oldest host API required by the plugin.

Optional metadata:

- `untrusted.manager.um.plugin.description`
- `untrusted.manager.um.plugin.category`
- `untrusted.manager.um.plugin.icon`

Example entry declaration:

```xml
<activity
    android:name=".MyPluginActivity"
    android:exported="true"
    android:label="My Plugin">
    <intent-filter>
        <action android:name="untrusted.manager.um.plugin.ACTION_ENTRY" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
    <meta-data android:name="untrusted.manager.um.plugin.id" android:value="example.tool" />
    <meta-data android:name="untrusted.manager.um.plugin.name" android:value="Example Tool" />
    <meta-data android:name="untrusted.manager.um.plugin.description" android:value="Example feature" />
    <meta-data android:name="untrusted.manager.um.plugin.category" android:value="Plugins" />
    <meta-data android:name="untrusted.manager.um.plugin.api" android:value="1" />
    <meta-data android:name="untrusted.manager.um.plugin.min_api" android:value="1" />
</activity>
```

Subclass `UmPluginActivity` to receive the host-provided plugin ID and optional file-context extras. The host automatically discovers compatible installed plugins, filters disabled/incompatible entries, adds enabled plugins to Tools Kit, loads the plugin application's icon, and launches the declared entry activity.
