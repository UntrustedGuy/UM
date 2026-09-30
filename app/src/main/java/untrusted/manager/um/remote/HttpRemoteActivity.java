package untrusted.manager.um.remote;

import untrusted.manager.um.utils.EdgeToEdgeUtil;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.os.Bundle;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.security.SecureRandom;
import java.util.Locale;

import untrusted.manager.um.ui.UiFields;

/** UI for the local HTTP remote-management service. */
public class HttpRemoteActivity extends AppCompatActivity {
    private HttpRemoteServer server;
    private TextView status, urlView, mcpView;
    private TextInputEditText portInput;
    private TextInputEditText rootInput;
    private TextInputEditText tokenInput;
    private MaterialButton toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("HTTP Remote Management");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16); body.setPadding(p, p, p, p);

        TextView description = new TextView(this);
        description.setText("Browse and manage UM files from a browser on the same network. Access is confined to the selected root and protected by a Bearer token.");
        body.addView(description, lp());

        TextInputLayout rootBox = UiFields.box(this, "Server root");
        rootInput = new TextInputEditText(this);
        rootInput.setSingleLine(true);
        rootInput.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        rootBox.addView(rootInput, lp()); body.addView(rootBox, lp());

        TextInputLayout portBox = UiFields.box(this, "Port (0 = automatic)");
        portInput = new TextInputEditText(this);
        portInput.setSingleLine(true); portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); portInput.setText("8080");
        portBox.addView(portInput, lp()); body.addView(portBox, lp());

        TextInputLayout tokenBox = UiFields.box(this, "Bearer token");
        tokenInput = new TextInputEditText(this);
        tokenInput.setSingleLine(true);
        String savedToken = HttpRemoteService.getToken(this);
        tokenInput.setText(savedToken.isEmpty() ? generateToken() : savedToken);
        tokenBox.addView(tokenInput, lp()); body.addView(tokenBox, lp());

        toggle = new MaterialButton(this);
        toggle.setText("Start HTTP server");
        toggle.setOnClickListener(v -> toggleServer());
        body.addView(toggle, lp());

        status = new TextView(this); status.setText("Stopped"); body.addView(status, lp());
        urlView = new TextView(this); urlView.setTextIsSelectable(true); body.addView(urlView, lp());
        mcpView = new TextView(this); mcpView.setTextIsSelectable(true); body.addView(mcpView, lp());

        MaterialButton copyMcp = new MaterialButton(this);
        copyMcp.setText("Copy MCP URL");
        copyMcp.setOnClickListener(v -> {
            String url = mcpView.getText().toString();
            if (!url.isEmpty()) ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("UM MCP URL", url));
        });
        body.addView(copyMcp, lp());

        MaterialButton copy = new MaterialButton(this);
        copy.setText("Copy URL");
        copy.setOnClickListener(v -> {
            String url = urlView.getText().toString();
            if (!url.isEmpty()) ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("UM HTTP URL", url));
        });
        body.addView(copy, lp());
        root.addView(body, new LinearLayout.LayoutParams(-1, -1));
        setContentView(root); EdgeToEdgeUtil.applyContentInsets(this);
    }

    private void toggleServer() {
        if (HttpRemoteService.isRunning(this)) {
            stopService(new Intent(this, HttpRemoteService.class).setAction(HttpRemoteService.ACTION_STOP));
            updateStopped();
            return;
        }
        try {
            String base = rootInput.getText() == null ? "" : rootInput.getText().toString().trim();
            int port = Integer.parseInt(portInput.getText() == null ? "0" : portInput.getText().toString().trim());
            String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
            if (token.length() < 16) throw new IllegalArgumentException("Use a token with at least 16 characters");
            Intent intent = new Intent(this, HttpRemoteService.class)
                    .setAction(HttpRemoteService.ACTION_START)
                    .putExtra(HttpRemoteService.EXTRA_ROOT, base)
                    .putExtra(HttpRemoteService.EXTRA_PORT, port)
                    .putExtra(HttpRemoteService.EXTRA_TOKEN, token);
            ContextCompat.startForegroundService(this, intent);
            // The foreground service starts asynchronously; refresh after it has had time to bind.
            new android.os.Handler(getMainLooper()).postDelayed(this::refreshState, 350);
        } catch (Exception e) {
            status.setText("Cannot start: " + e.getMessage());
        }
    }

    private void updateRunning(int port) {
        toggle.setText("Stop HTTP server"); status.setText("Running on port " + port);
        String ip = localIp();
        // The credential is in the URL fragment, never in the HTTP request/URL query.
        String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString();
        urlView.setText("HTTP: http://" + ip + ":" + port + "/#token=" + android.net.Uri.encode(token));
        mcpView.setText("MCP: http://" + ip + ":" + HttpRemoteService.getMcpPort(this) + "/mcp");
    }

    private void updateStopped() { toggle.setText("Start HTTP server"); status.setText("Stopped"); urlView.setText(""); if (mcpView != null) mcpView.setText(""); }

    private void refreshState() {
        if (!HttpRemoteService.isRunning(this)) { updateStopped(); return; }
        int p = HttpRemoteService.getPort(this);
        updateRunning(p);
    }

    @Override protected void onResume() { super.onResume(); refreshState(); }

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }

    private static String generateToken() {
        byte[] b = new byte[24]; new SecureRandom().nextBytes(b);
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private LinearLayout.LayoutParams lp() { return new LinearLayout.LayoutParams(-1, -2); }

    @Override protected void onDestroy() { super.onDestroy(); }
}
