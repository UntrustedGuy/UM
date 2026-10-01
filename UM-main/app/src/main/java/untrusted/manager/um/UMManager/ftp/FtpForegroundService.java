package untrusted.manager.um.UMManager.ftp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.lilincpp.github.libezftp.EZFtpServer;
import com.lilincpp.github.libezftp.IEZFtpServer;
import com.lilincpp.github.libezftp.user.EZFtpUser;
import com.lilincpp.github.libezftp.user.EZFtpUserPermission;

import untrusted.manager.um.R;
import untrusted.manager.um.UMManager.ftp.FtpsCertificateUtil;
import untrusted.manager.um.network.SecureNetworkPrefs;

/** Owns the actual FTP/FTPS listener and its persistent foreground lifecycle. */
public class FtpForegroundService extends Service {
    public static final String ACTION_START = "untrusted.manager.um.ftp.START";
    public static final String ACTION_STOP = "untrusted.manager.um.ftp.STOP";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_SECURITY = "security";
    public static final String EXTRA_IP = "ip";

    private static final String PREFS = "ftp_service";
    private static final String KEY_PORT = "port";
    private static final String KEY_USER = "user";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SECURITY = "security";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_IP = "ip";
    private static final String CHANNEL_ID = "TaskChannel";
    private static final int NOTIFICATION_ID = 1;

    private static volatile IEZFtpServer server;

    public static boolean isRunning(Context context) {
        return server != null || context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("running", false);
    }

    public static boolean isAutoStartEnabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_START, false);
    }

    public static void setAutoStartEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO_START, enabled).apply();
    }

    public static int getPort(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, 2121);
    }

    private static String getString(Context context, String key, String fallback) {
        return SecureNetworkPrefs.get(context, context.getSharedPreferences(PREFS, MODE_PRIVATE), key, fallback);
    }

    public static void stopRunning(Context context) {
        Intent stop = new Intent(context, FtpForegroundService.class).setAction(ACTION_STOP);
        context.stopService(stop);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        final android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String ip = value(intent, EXTRA_IP, prefs.getString(KEY_IP, "127.0.0.1"));
        int port = intValue(intent, EXTRA_PORT, prefs.getInt(KEY_PORT, 2121));
        String user = value(intent, EXTRA_USER, getString(this, KEY_USER, "untrusted"));
        String password = value(intent, EXTRA_PASSWORD, getString(this, KEY_PASSWORD, "untrusted"));
        int security = intValue(intent, EXTRA_SECURITY, prefs.getInt(KEY_SECURITY, 0));

        // Promote before creating the listener so boot restoration and Android 15+ foreground
        // service startup rules cannot be defeated by listener initialization time.
        startForeground(NOTIFICATION_ID, buildNotification(ip, port));
        try {
            startServer(port, user, password, security);
            prefs.edit().putInt(KEY_PORT, port).putInt(KEY_SECURITY, security).putString(KEY_IP, ip).putBoolean("running", true).apply();
            android.content.SharedPreferences.Editor secure = prefs.edit();
            SecureNetworkPrefs.put(secure, KEY_USER, user);
            SecureNetworkPrefs.put(secure, KEY_PASSWORD, password);
            secure.apply();
            updateNotification(ip, port);
            return START_STICKY;
        } catch (Exception e) {
            stopServer();
            prefs.edit().putBoolean("running", false).apply();
            stopForeground(true);
            stopSelfResult(startId);
            android.util.Log.e("UntrustedManager", "FTP service failed to start", e);
            return START_NOT_STICKY;
        }
    }

    private void startServer(int port, String user, String password, int security) throws Exception {
        stopServer();
        EZFtpServer.Builder builder = new EZFtpServer.Builder()
                .setListenPort(port)
                .addUser(new EZFtpUser(
                        user, password, Environment.getExternalStorageDirectory().getPath(),
                        EZFtpUserPermission.WRITE));
        if (security > 0) {
            boolean implicit = security == 2;
            java.io.File keystore = FtpsCertificateUtil.ensureKeystore(new java.io.File(getCacheDir(), "ftps-keystore.jks"));
            builder.setFtps(keystore, FtpsCertificateUtil.getPasswordString(), implicit);
        }
        server = builder.create();
        server.start();
    }

    private void stopServer() {
        IEZFtpServer current = server;
        server = null;
        if (current != null) {
            try { current.stop(); } catch (Exception ignored) {}
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("running", false).apply();
    }

    private Notification buildNotification(String ip, int port) {
        Intent stopIntent = new Intent(this, StopReceiver.class);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent copyIntent = new Intent(this, CopyReceiver.class).putExtra(EXTRA_IP, ip);
        PendingIntent copyPendingIntent = PendingIntent.getBroadcast(this, 1, copyIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.ftp_server))
                .setContentText(getString(R.string.ftp_running_at, ip + ":" + port))
                .setSmallIcon(R.drawable.cloud_upload_24px)
                .setOngoing(true)
                .addAction(R.drawable.stop_circle_24px, getString(R.string.ftp_stop), stopPendingIntent)
                .addAction(R.drawable.ic_copy_mt, getString(R.string.ftp_copy_ip), copyPendingIntent)
                .build();
    }

    private void updateNotification(String ip, int port) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(ip, port));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_background), NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private static String value(Intent intent, String key, String fallback) {
        String value = intent == null ? null : intent.getStringExtra(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static int intValue(Intent intent, String key, int fallback) {
        if (intent == null || !intent.hasExtra(key)) return fallback;
        try { return intent.getIntExtra(key, fallback); } catch (Exception e) { return fallback; }
    }

    @Override public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
