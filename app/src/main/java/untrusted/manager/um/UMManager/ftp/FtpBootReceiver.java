package untrusted.manager.um.UMManager.ftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Restores the user-enabled FTP/FTPS server after boot or app replacement. */
public final class FtpBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!FtpForegroundService.isAutoStartEnabled(context)) return;
        Intent start = new Intent(context, FtpForegroundService.class).setAction(FtpForegroundService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, start);
            else context.startService(start);
        } catch (RuntimeException ignored) {
            // Do not crash the system broadcast process if the OS rejects the background start.
        }
    }
}
