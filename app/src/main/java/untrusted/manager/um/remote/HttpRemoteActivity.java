package untrusted.manager.um.remote;

import untrusted.manager.um.R;

import untrusted.manager.um.utils.EdgeToEdgeUtil;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.os.Bundle;
import android.os.Environment;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.security.SecureRandom;
import java.util.Locale;

import untrusted.manager.um.ui.UiFields;
import untrusted.manager.um.ui.dialogs.FilePickerDialog;

/** UI for the local HTTP remote-management service. */
public class HttpRemoteActivity extends AppCompatActivity {
    private HttpRemoteServer server;
    private TextView status, urlView, mcpView;
    private TextInputEditText portInput;
    private TextInputEditText rootInput;
    private TextInputEditText tokenInput;
    private MaterialButton toggle;
    private MaterialSwitch autoStart;
    private MaterialSwitch authRequired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        boolean mcpMode = "mcp".equals(getIntent().getStringExtra("mode"));
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(mcpMode ? "MCP Service" : "HTTP Remote Management");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16); body.setPadding(p, p, p, p);

        TextView description = new TextView(this);
        description.setText(mcpMode
                ? "Connect an AI client to UM through Streamable HTTP. The service exposes local file and APK tooling within the configured root."
                : "Browse and manage UM files from a browser on the same network. Access is confined to the selected root and protected by a Bearer token.");
        body.addView(description, lp());

        TextInputLayout rootBox = UiFields.box(this, "Server root");
        rootInput = new TextInputEditText(this);
        rootInput.setSingleLine(true);
        rootInput.setText(Environment.getExternalStorageDirectory().getAbsolutePath());
        rootBox.addView(rootInput, lp()); body.addView(rootBox, lp());

        MaterialButton permissions = new MaterialButton(this);
        permissions.setText("Manage MCP file permissions");
        permissions.setOnClickListener(v -> showAccessRulesDialog());
        body.addView(permissions, lp());

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

        authRequired = new MaterialSwitch(this);
        authRequired.setText("Require Bearer authentication");
        authRequired.setChecked(HttpRemoteService.isAuthRequired(this));
        authRequired.setOnCheckedChangeListener((button, checked) -> tokenInput.setEnabled(checked));
        body.addView(authRequired, lp());
        tokenInput.setEnabled(authRequired.isChecked());

        MaterialButton tokenManager = new MaterialButton(this);
        tokenManager.setText("Manage named MCP tokens");
        tokenManager.setOnClickListener(v -> showTokenManager());
        body.addView(tokenManager, lp());

        toggle = new MaterialButton(this);
        toggle.setText("Start HTTP server");
        toggle.setOnClickListener(v -> toggleServer());
        body.addView(toggle, lp());

        autoStart = new MaterialSwitch(this);
        autoStart.setText("Start remote server after device boot");
        autoStart.setChecked(HttpRemoteService.isAutoStartEnabled(this));
        autoStart.setOnCheckedChangeListener((button, checked) ->
                HttpRemoteService.setAutoStartEnabled(this, checked));
        body.addView(autoStart, lp());

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
            boolean auth = authRequired != null && authRequired.isChecked();
            String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
            if (auth && token.length() < 16) throw new IllegalArgumentException("Use a token with at least 16 characters");
            Intent intent = new Intent(this, HttpRemoteService.class)
                    .setAction(HttpRemoteService.ACTION_START)
                    .putExtra(HttpRemoteService.EXTRA_ROOT, base)
                    .putExtra(HttpRemoteService.EXTRA_PORT, port)
                    .putExtra(HttpRemoteService.EXTRA_TOKEN, token)
                    .putExtra(HttpRemoteService.EXTRA_AUTH_REQUIRED, auth);
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
        urlView.setText(authRequired != null && authRequired.isChecked()
                ? "HTTP: http://" + ip + ":" + port + "/#token=" + android.net.Uri.encode(token)
                : "HTTP: http://" + ip + ":" + port + "/");
        mcpView.setText("MCP: http://" + ip + ":" + HttpRemoteService.getMcpPort(this) + "/mcp");
    }

    private void updateStopped() { toggle.setText("Start HTTP server"); status.setText("Stopped"); urlView.setText(""); if (mcpView != null) mcpView.setText(""); }

    private void refreshState() {
        if (!HttpRemoteService.isRunning(this)) { updateStopped(); return; }
        int p = HttpRemoteService.getPort(this);
        updateRunning(p);
    }

    @Override protected void onResume() { super.onResume(); refreshState(); }

    private void showAccessRulesDialog() {
        java.util.List<McpAccessRuleStore.Rule> rules = McpAccessRuleStore.getRules(this);
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (McpAccessRuleStore.Rule rule : rules) {
            String mode = rule.mode() == McpAccessRuleStore.READ_WRITE ? "Read & write"
                    : rule.mode() == McpAccessRuleStore.READ_ONLY ? "Read only" : "Denied";
            labels.add(mode + " — " + rule.path());
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16); box.setPadding(pad, 0, pad, 0);
        TextView info = new TextView(this);
        info.setText("Home remains available. Rules can grant read-only, read/write, or deny access to additional files and folders.");
        box.addView(info, lp());
        android.widget.ListView list = new android.widget.ListView(this);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        box.addView(list, new LinearLayout.LayoutParams(-1, dp(260)));
        MaterialButton add = new MaterialButton(this);
        add.setText("Add file or folder rule");
        box.addView(add, lp());
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("MCP file permissions")
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        add.setOnClickListener(v -> {
            FilePickerDialog.Properties props = new FilePickerDialog.Properties();
            props.selection_mode = FilePickerDialog.SINGLE_MODE;
            props.selection_type = FilePickerDialog.FILE_AND_DIR_SELECT;
            props.root = Environment.getExternalStorageDirectory();
            FilePickerDialog picker = new FilePickerDialog(this, props);
            picker.setTitle("Select folder");
            picker.setDialogSelectionListener(files -> {
                if (files == null || files.length == 0 || files[0] == null) return;
                final String path = files[0];
                String[] modes = {"Denied", "Read only", "Read & write"};
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Permission for " + path)
                        .setSingleChoiceItems(modes, McpAccessRuleStore.READ_WRITE, (d, which) -> {
                            try {
                                McpAccessRuleStore.put(this, path, which);
                                d.dismiss(); dialog.dismiss(); showAccessRulesDialog();
                            } catch (Exception e) { status.setText("Cannot save rule: " + e.getMessage()); }
                        }).show();
            });
            picker.show();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= rules.size()) return true;
            String path = rules.get(position).path();
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Remove rule?")
                    .setMessage(path)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.delete, (d,w) -> {
                        try { McpAccessRuleStore.remove(this, path); dialog.dismiss(); showAccessRulesDialog(); }
                        catch (Exception e) { status.setText("Cannot remove rule: " + e.getMessage()); }
                    }).show();
            return true;
        });
        dialog.show();
    }

    private void showTokenManager() {
        java.util.List<McpTokenStore.Token> tokens = McpTokenStore.get(this);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); int pad=dp(16); box.setPadding(pad,0,pad,0);
        TextView info=new TextView(this); info.setText("Named tokens are accepted in addition to the active service token. Long-press a token to revoke it."); box.addView(info,lp());
        android.widget.ListView list=new android.widget.ListView(this); java.util.List<String> labels=new java.util.ArrayList<>(); for(McpTokenStore.Token t:tokens) labels.add(t.name()+"  •  "+t.value());
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels)); box.addView(list,new LinearLayout.LayoutParams(-1,dp(260)));
        MaterialButton add=new MaterialButton(this); add.setText("Create named token"); box.addView(add,lp());
        AlertDialog dialog=new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("MCP bearer tokens").setView(box).setPositiveButton(android.R.string.ok,null).create();
        add.setOnClickListener(v->{ TextInputEditText name=new TextInputEditText(this); name.setSingleLine(true); name.setHint("Token name"); new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Create token").setView(name).setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{try{McpTokenStore.Token t=McpTokenStore.create(this,name.getText()==null?"":name.getText().toString()); ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("MCP token",t.value())); dialog.dismiss(); showTokenManager();}catch(Exception e){status.setText("Cannot create token: "+e.getMessage());}}).show(); });
        list.setOnItemLongClickListener((p,v,pos,id)->{if(pos<tokens.size())new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Revoke token?").setMessage(tokens.get(pos).name()).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.delete,(d,w)->{try{McpTokenStore.revoke(this,tokens.get(pos).name());dialog.dismiss();showTokenManager();}catch(Exception e){status.setText("Cannot revoke token: "+e.getMessage());}}).show();return true;});
        dialog.show();
    }

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
