package untrusted.manager.um.plugin;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Opens the platform installer for a plugin APK; installation remains user-authorized by Android. */
public final class PluginInstaller {
    private PluginInstaller() {}
    public static Intent installIntent(Context context, Uri apk) {
        if (apk == null) throw new IllegalArgumentException("APK URI is required");
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
    public static Intent unknownSourcesSettings(Context context) {
        if (Build.VERSION.SDK_INT >= 26) return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName()));
        return new Intent(Settings.ACTION_SECURITY_SETTINGS);
    }
}
