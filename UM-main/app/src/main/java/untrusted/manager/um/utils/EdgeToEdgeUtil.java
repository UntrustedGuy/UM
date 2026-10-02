package untrusted.manager.um.utils;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Applies the Android 15+ system-bar/display-cutout insets to an Activity's
 * content root. Android 15 enforces edge-to-edge for target SDK 35+, and
 * Android 16 removes the opt-out for target SDK 36+; screens therefore need
 * explicit inset handling rather than relying on status/navigation bar colors.
 */
public final class EdgeToEdgeUtil {
    private EdgeToEdgeUtil() {}

    public static void applyContentInsets(Activity activity) {
        if (activity == null) return;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        // Android 15+ edge-to-edge means the decor no longer reserves the status/navigation
        // bar areas for us. Apply the insets to the actual setContentView() root rather than
        // android.R.id.content itself. Padding the content wrapper can leave the child root
        // drawing underneath the status bar on some Android 15/16 configurations.
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        final View target;
        if (content instanceof ViewGroup group && group.getChildCount() == 1) {
            target = group.getChildAt(0);
        } else {
            target = content;
        }

        final int baseLeft = target.getPaddingLeft();
        final int baseTop = target.getPaddingTop();
        final int baseRight = target.getPaddingRight();
        final int baseBottom = target.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(target, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            v.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(target);
    }
}
