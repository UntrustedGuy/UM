package untrusted.manager.um.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.text.TextUtils;

import untrusted.manager.um.R;
import untrusted.manager.um.UMManager.MainActivity;

import java.io.File;
import io.github.codehasan.colorpicker.extensions.Extensions;

/** Creates user-pinned shortcuts that reopen a real file-manager location. */
public final class FileShortcutManager {
    private static final String ID_PREFIX = "file_location_";
    private static final int MAX_ID_LENGTH = 90;

    private FileShortcutManager() {}

    public static void requestPin(Activity activity, File target) {
        if (activity == null || target == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Extensions.showMessage(activity, R.string.file_shortcuts_android_o);
            return;
        }
        if (!target.exists()) {
            Extensions.showMessage(activity, R.string.file_shortcut_target_missing);
            return;
        }
        ShortcutManager manager = (ShortcutManager) activity.getSystemService(Context.SHORTCUT_SERVICE);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Extensions.showMessage(activity, R.string.file_shortcuts_unsupported);
            return;
        }
        String path = target.getAbsolutePath();
        String id = stableId(path);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setClass(activity, MainActivity.class)
                .putExtra("locatePath", path)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String label = TextUtils.isEmpty(target.getName()) ? path : target.getName();
        String shortLabel = label.length() > 25 ? label.substring(0, 25) : label;
        String longLabel = path.length() > 80 ? path.substring(0, 77) + "..." : path;
        ShortcutInfo info = new ShortcutInfo.Builder(activity, id)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(Icon.createWithResource(activity,
                        target.isDirectory() ? R.drawable.folder_24px : R.drawable.baseline_insert_drive_file_24))
                .setIntent(intent)
                .build();
        try {
            manager.requestPinShortcut(info, null);
        } catch (RuntimeException e) {
            Extensions.showMessage(activity, R.string.file_shortcut_failed);
        }
    }

    private static String stableId(String path) {
        String digest = Integer.toHexString(path.hashCode());
        String tail = path.replace(File.separatorChar, '_');
        int maxTail = Math.max(1, MAX_ID_LENGTH - ID_PREFIX.length() - digest.length() - 1);
        if (tail.length() > maxTail) tail = tail.substring(tail.length() - maxTail);
        return ID_PREFIX + digest + "_" + tail;
    }
}
