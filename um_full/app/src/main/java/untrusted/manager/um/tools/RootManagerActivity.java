package untrusted.manager.um.tools;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;
import untrusted.manager.um.UMManager.shizuku.ShizukuShell;
import untrusted.manager.um.utils.EdgeToEdgeUtil;
import untrusted.manager.um.utils.RootManager;

/** UI for the existing RootManager and Shizuku backends. */
public class RootManagerActivity extends AppCompatActivity {
    private static final int SHIZUKU_REQUEST_CODE = 1001;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private RootManager rootManager;
    private RadioButton rootMode, shizukuMode, nonRootMode;
    private TextView status, output;
    private EditText command;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_REQUEST_CODE) return;
        runOnUiThread(() -> {
            output.setText(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ? "Shizuku permission granted."
                    : "Shizuku permission denied.");
            refreshStatus();
        });
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        rootManager = RootManager.getInstance(this);
        try { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) {}
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle("Root Manager");
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> finish());
        root.addView(bar, lp(0,0,0,0));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = box();
        body.setPadding(dp(16),dp(16),dp(16),dp(16));

        MaterialCardView accessCard = card();
        LinearLayout access = box();
        TextView heading = new TextView(this); heading.setText("Access mode"); heading.setTextSize(18);
        access.addView(heading, lp(0,0,0,8));
        nonRootMode = new RadioButton(this); nonRootMode.setText("Non-root");
        rootMode = new RadioButton(this); rootMode.setText("Root");
        shizukuMode = new RadioButton(this); shizukuMode.setText("Shizuku");
        access.addView(nonRootMode); access.addView(rootMode); access.addView(shizukuMode);
        accessCard.addView(access); body.addView(accessCard, lp(0,0,0,12));

        View.OnClickListener choose = v -> {
            if (v == rootMode && !rootMode.isEnabled()) return;
            if (v == shizukuMode && !shizukuMode.isEnabled()) return;
            if (v == rootMode) rootManager.setWorkingMode(RootManager.WorkingMode.ROOT);
            else if (v == shizukuMode) rootManager.setWorkingMode(RootManager.WorkingMode.SHIZUKU);
            else rootManager.setWorkingMode(RootManager.WorkingMode.NON_ROOT);
            refreshStatus();
        };
        nonRootMode.setOnClickListener(choose); rootMode.setOnClickListener(choose); shizukuMode.setOnClickListener(choose);

        status = new TextView(this); status.setTextIsSelectable(true); body.addView(status, lp(0,0,0,10));
        body.addView(button("Refresh access status", v -> refreshStatus()), lp(0,0,0,8));
        body.addView(button("Request Shizuku permission", v -> requestShizuku()), lp(0,0,0,12));

        MaterialCardView shellCard = card(); LinearLayout shell = box();
        TextView shellTitle = new TextView(this); shellTitle.setText("Elevated shell test"); shellTitle.setTextSize(18);
        shell.addView(shellTitle, lp(0,0,0,8));
        TextView shellInfo = new TextView(this); shellInfo.setText("Runs through the selected Root or Shizuku backend. Non-root mode never runs an elevated command.");
        shell.addView(shellInfo, lp(0,0,0,8));
        command = new EditText(this); command.setSingleLine(true); command.setHint("id"); shell.addView(command, lp(0,0,0,8));
        shell.addView(button("Run command", v -> runCommand()), lp(0,0,0,8));
        shellCard.addView(shell); body.addView(shellCard, lp(0,0,0,12));
        output = new TextView(this); output.setTextIsSelectable(true); body.addView(output);

        scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root); EdgeToEdgeUtil.applyContentInsets(this);
        refreshStatus();
    }

    private void refreshStatus() {
        executor.execute(() -> {
            boolean rootAvailable = false;
            try { rootManager.refreshRootCache(); rootAvailable = rootManager.isRootAvailable(); } catch (Throwable ignored) {}
            boolean shizukuAvailable = false, granted = false;
            try { shizukuAvailable = ShizukuShell.isAvailable(); granted = ShizukuShell.isGranted(); } catch (Throwable ignored) {}
            boolean ra = rootAvailable, sa = shizukuAvailable, sg = granted;
            runOnUiThread(() -> {
                rootMode.setEnabled(ra);
                shizukuMode.setEnabled(sa);
                RootManager.WorkingMode current = rootManager.getWorkingMode();
                if (current == RootManager.WorkingMode.ROOT && !ra) current = RootManager.WorkingMode.NON_ROOT;
                if (current == RootManager.WorkingMode.SHIZUKU && !sa) current = RootManager.WorkingMode.NON_ROOT;
                if (current != rootManager.getWorkingMode()) rootManager.setWorkingMode(current);
                nonRootMode.setChecked(current == RootManager.WorkingMode.NON_ROOT);
                rootMode.setChecked(current == RootManager.WorkingMode.ROOT);
                shizukuMode.setChecked(current == RootManager.WorkingMode.SHIZUKU);
                status.setText("Selected mode: " + current.label +
                        "\nRoot available: " + ra +
                        "\nShizuku service: " + sa +
                        "\nShizuku permission: " + sg);
            });
        });
    }

    private void requestShizuku() {
        try {
            if (!ShizukuShell.isAvailable()) { output.setText("Shizuku service is not running."); return; }
            if (!ShizukuShell.isGranted()) { Shizuku.requestPermission(SHIZUKU_REQUEST_CODE); output.setText("Shizuku permission request sent."); }
            else output.setText("Shizuku permission is already granted.");
        } catch (Throwable t) { output.setText("Shizuku request failed: " + message(t)); }
    }

    private void runCommand() {
        String cmd = command.getText() == null ? "" : command.getText().toString().trim();
        if (cmd.isEmpty()) { output.setText("Enter a command."); return; }
        output.setText("Running…");
        executor.execute(() -> {
            String result;
            try {
                if (rootManager.getWorkingMode() == RootManager.WorkingMode.ROOT) {
                    RootManager.ShellResult r = rootManager.executeFs(cmd, 30);
                    result = "exit=" + r.exitCode() + "\n" + r.output() + (r.error() == null || r.error().isEmpty() ? "" : "\nERROR: " + r.error());
                } else if (rootManager.getWorkingMode() == RootManager.WorkingMode.SHIZUKU) {
                    ShizukuShell.Result r = ShizukuShell.exec(cmd, 30);
                    result = "exit=" + r.exitCode + "\n" + r.stdout + (r.stderr == null || r.stderr.isEmpty() ? "" : "\nERROR: " + r.stderr);
                } else {
                    result = "Non-root mode is selected; elevated execution is disabled.";
                }
            } catch (Throwable t) { result = "Command failed: " + message(t); }
            String finalResult = result; runOnUiThread(() -> output.setText(finalResult));
        });
    }

    private static String message(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
    private MaterialCardView card() { MaterialCardView c=new MaterialCardView(this); c.setRadius(dp(14)); c.setCardElevation(dp(1)); return c; }
    private LinearLayout box() { LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); return b; }
    private Button button(String text, View.OnClickListener l) { MaterialButton b=new MaterialButton(this); b.setText(text); b.setOnClickListener(l); return b; }
    private LinearLayout.LayoutParams lp(int l,int t,int r,int b) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+.5f); }
    @Override protected void onDestroy() {
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener); } catch (Throwable ignored) {}
        executor.shutdownNow();
        super.onDestroy();
    }
}
