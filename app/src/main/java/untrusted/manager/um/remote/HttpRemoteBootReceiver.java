package untrusted.manager.um.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Restores the user-enabled HTTP/MCP remote service after device boot or app replacement. */
public final class HttpRemoteBootReceiver extends BroadcastReceiver {
    public static final String ACTION_RESTORE = "untrusted.manager.um.remote.RESTORE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !ACTION_RESTORE.equals(action)) {
            return;
        }
        if (!HttpRemoteService.isAutoStartEnabled(context)) return;
        Intent service = new Intent(context, HttpRemoteService.class)
                .setAction(HttpRemoteService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, service);
            } else {
                context.startService(service);
            }
        } catch (RuntimeException ignored) {
            // The service performs its own cleanup/error handling. A boot receiver must not
            // crash the system broadcast process when the OS rejects a background start.
        }
    }
}
