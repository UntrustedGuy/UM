package untrusted.manager.um.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import untrusted.manager.um.R;
import untrusted.manager.um.network.SecureNetworkPrefs;

import java.io.File;
import java.security.SecureRandom;

/** Persistent foreground owner for UM's HTTP remote-management server. */
public final class HttpRemoteService extends Service {
    public static final String ACTION_START = "untrusted.manager.um.remote.START";
    public static final String ACTION_STOP = "untrusted.manager.um.remote.STOP";
    public static final String EXTRA_ROOT = "root";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_AUTH_REQUIRED = "auth_required";
    private static final String PREFS = "http_remote";
    private static final String KEY_ROOT = "root";
    private static final String KEY_PORT = "port";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_AUTH_REQUIRED = "auth_required";
    private static final String KEY_MCP_PORT = "mcp_port";
    public static final String KEY_AUTO_START = "auto_start";
    private static final int NOTIFICATION_ID = 2307;
    private static final String CHANNEL = "http_remote";
    private HttpRemoteServer server;
    private McpRemoteServer mcpServer;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            SecureNetworkPrefs.migrate(this, p, KEY_TOKEN);
            String root = value(intent, EXTRA_ROOT, p.getString(KEY_ROOT, "/storage/emulated/0"));
            int port = intValue(intent, EXTRA_PORT, p.getInt(KEY_PORT, 0));
            boolean authRequired = intent != null && intent.hasExtra(EXTRA_AUTH_REQUIRED)
                    ? intent.getBooleanExtra(EXTRA_AUTH_REQUIRED, false)
                    : p.getBoolean(KEY_AUTH_REQUIRED, false);
            String token = value(intent, EXTRA_TOKEN, SecureNetworkPrefs.get(this, p, KEY_TOKEN, ""));
            if (authRequired && token.length() < 16) token = generateToken();
            if (!authRequired) token = "";
            SharedPreferences.Editor pe = p.edit().putString(KEY_ROOT, root).putInt(KEY_PORT, port).putBoolean(KEY_AUTH_REQUIRED, authRequired);
            SecureNetworkPrefs.put(pe, KEY_TOKEN, token);
            pe.apply();
            if (authRequired) McpTokenStore.ensureLegacyToken(this, token);
            createChannel();
            startForeground(NOTIFICATION_ID, notification(port));
            if (server != null && server.isRunning()) server.stop();
            if (mcpServer != null && mcpServer.isRunning()) mcpServer.stop();
            server = new HttpRemoteServer(new File(root), port, token, null);
            int mcpPort = p.getInt(KEY_MCP_PORT, 8787);
            if (mcpPort == server.getPort()) mcpPort = 0;
            mcpServer = new McpRemoteServer(this, new File(root), mcpPort, token);
            p.edit().putInt(KEY_PORT, server.getPort()).putInt(KEY_MCP_PORT, mcpServer.getPort()).apply();
            server.start();
            mcpServer.start();
            sRunning = true;
            sPort = server.getPort();
            sMcpPort = mcpServer.getPort();
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification(server.getPort()));
            return START_STICKY;
        } catch (Exception e) {
            if (server != null) { try { server.stop(); } catch (Exception ignored) {} }
            if (mcpServer != null) { try { mcpServer.stop(); } catch (Exception ignored) {} }
            sRunning = false; sPort = 0; sMcpPort = 0;
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    public static String getToken(android.content.Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        SecureNetworkPrefs.migrate(c, p, KEY_TOKEN);
        return SecureNetworkPrefs.get(c, p, KEY_TOKEN, "");
    }
    public static String getRoot(android.content.Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROOT, "/storage/emulated/0");
    }
    public static int getMcpPort(android.content.Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_MCP_PORT, 8787);
    }
    public static int getPort(android.content.Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PORT, 0);
    }
    public static boolean isRunning(android.content.Context c) {
        return sRunning && sPort > 0;
    }
    public static boolean isAuthRequired(android.content.Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTH_REQUIRED, false);
    }

    public static boolean isAutoStartEnabled(android.content.Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_START, false);
    }
    public static void setAutoStartEnabled(android.content.Context c, boolean enabled) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUTO_START, enabled).apply();
    }
    private static volatile boolean sRunning;
    private static volatile int sPort;
    private static volatile int sMcpPort;

    private Notification notification(int port) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.cloud_upload_24px)
                .setContentTitle("Untrusted Manager HTTP")
                            .setContentText("HTTP " + port + " • MCP " + sMcpPort)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 2308,
                        new Intent(this, HttpRemoteActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
    }
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager n = getSystemService(NotificationManager.class);
            if (n != null) n.createNotificationChannel(new NotificationChannel(CHANNEL, "HTTP Remote Management", NotificationManager.IMPORTANCE_LOW));
        }
    }
    private static String value(Intent i, String k, String fallback) { String v=i==null?null:i.getStringExtra(k); return v==null||v.trim().isEmpty()?fallback:v.trim(); }
    private static int intValue(Intent i, String k, int fallback) { try { return i==null?fallback:i.getIntExtra(k,fallback); } catch(Exception e){return fallback;} }
    private static String generateToken() { byte[] b=new byte[24]; new SecureRandom().nextBytes(b); return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING); }
    @Override public void onDestroy() { if(server!=null)server.stop(); if(mcpServer!=null)mcpServer.stop(); sRunning=false;sPort=0;sMcpPort=0;super.onDestroy(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
