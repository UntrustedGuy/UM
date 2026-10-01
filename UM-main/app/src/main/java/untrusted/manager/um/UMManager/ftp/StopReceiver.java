package untrusted.manager.um.UMManager.ftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Stops the service-owned FTP listener from its notification action. */
public class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        FtpForegroundService.stopRunning(context);
        context.sendBroadcast(new Intent("untrusted.manager.um.FTP_STOPPED").setPackage(context.getPackageName()));
    }
}
