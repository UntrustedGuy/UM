package untrusted.manager.um.utils;

import android.app.Activity;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Since targetSdk 35+, the system forces edge-to-edge display (content draws
 * behind the status/navigation bars) with no opt-out. Any activity that
 * doesn't apply window-insets padding to its root content view will have
 * its UI rendered underneath the status bar / cutout / nav bar.
 * <p>
 * Call {@link #applyContentInsets(Activity)} once, right after
 * setContentView(...), to push the activity's whole content down/inward by
 * the system bar insets so nothing is obscured.
 */
public class EdgeToEdgeUtil {
    public static void applyContentInsets(Activity activity) {
        ViewCompat.setOnApplyWindowInsetsListener(activity.findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}
