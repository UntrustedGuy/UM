package untrusted.manager.um.UMManager.ftp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import untrusted.manager.um.UMManager.MainActivity;

public class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent stopIntent = new Intent(context, FtpForegroundService.class);
        context.stopService(stopIntent);
        if(MainActivity.ftpServer != null) {
            MainActivity.ftpServer.stop();
            MainActivity.ftpServer = null;
        }
        Intent uiIntent = new Intent("untrusted.manager.um.FTP_STOPPED");
        context.sendBroadcast(uiIntent);
    }
}