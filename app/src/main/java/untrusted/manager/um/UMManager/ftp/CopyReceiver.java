package untrusted.manager.um.UMManager.ftp;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import untrusted.manager.um.R;

public class CopyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String ip = intent == null ? "" : intent.getStringExtra(FtpForegroundService.EXTRA_IP);
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("FTP address", ip == null ? "" : ip));
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }
}
