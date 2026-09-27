package untrusted.manager.um.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.Choreographer;

/** Lightweight app-wide diagnostics for lag and network state. */
public final class AppDiagnostics {
    private static volatile boolean installed;
    private static ConnectivityManager.NetworkCallback networkCallback;

    private AppDiagnostics() {}

    public static void install(Context context) {
        if (installed) return;
        synchronized (AppDiagnostics.class) {
            if (installed) return;
            installed = true;
        }
        Context app = context.getApplicationContext();
        DiagnosticWriter.start();
        AppLogs.writeEvent("app", "startup");

        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) {
                        AppLogs.writeEvent("network", "available " + network);
                    }
                    @Override public void onLost(Network network) {
                        AppLogs.writeEvent("network", "lost " + network);
                    }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        AppLogs.writeEvent("network", "capabilities " + caps);
                    }
                };
                cm.registerDefaultNetworkCallback(networkCallback);
                Network active = cm.getActiveNetwork();
                NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
                AppLogs.writeEvent("network", "initial active=" + active + " capabilities=" + caps);
            }
        } catch (Throwable t) {
            AppLogs.writeEvent("network", "callback registration failed", t);
        }

        try {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                long last = 0;
                @Override public void doFrame(long frameTimeNanos) {
                    if (last != 0) {
                        long deltaMs = (frameTimeNanos - last) / 1_000_000L;
                        if (deltaMs >= 100) AppLogs.writeEvent("lag", "main-thread frame gap=" + deltaMs + "ms");
                    }
                    last = frameTimeNanos;
                    Choreographer.getInstance().postFrameCallback(this);
                }
            });
        } catch (Throwable t) {
            AppLogs.writeEvent("lag", "frame monitor unavailable", t);
        }
    }
}
