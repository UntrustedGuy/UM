package untrusted.manager.um.utils;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
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

        final int baseLeft = content.getPaddingLeft();
        final int baseTop = content.getPaddingTop();
        final int baseRight = content.getPaddingRight();
        final int baseBottom = content.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(
                    baseLeft + bars.left,
                    baseTop + bars.top,
                    baseRight + bars.right,
                    baseBottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}
