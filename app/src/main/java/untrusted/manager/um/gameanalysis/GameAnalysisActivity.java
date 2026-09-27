package untrusted.manager.um.gameanalysis;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import untrusted.manager.um.ApkExtractor.APKExtractorActivity;
import untrusted.manager.um.patcher.PatcherActivity;
import untrusted.manager.um.ui.activities.HexEditorActivity;
import untrusted.manager.um.utils.AppLogs;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/** Integrated Game Analysis & Modding entry point. */
public class GameAnalysisActivity extends AppCompatActivity {
    private static final int PICK_INPUT = 100;
    private static final int PICK_COMPANION = 101;

    private String mode;
    private File inputFile;
    private File companionFile;
    private TextView status;
    private Button runButton;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "analyzer";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(titleForMode(mode));
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        body.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText(descriptionForMode(mode));
        intro.setTextSize(14);
        body.addView(intro, lp(0, 0, 0, 12));

        MaterialCardView inputCard = card();
        LinearLayout inputBox = new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.VERTICAL);
        inputBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView inputLabel = new TextView(this);
        inputLabel.setText("Primary input");
        inputLabel.setTextSize(16);
        inputBox.addView(inputLabel);
        TextView inputPath = new TextView(this);
        inputPath.setText("Nothing selected");
        inputPath.setTextIsSelectable(true);
        inputBox.addView(inputPath, lp(0, 0, 0, 8));
        Button inputButton = button("Select APK / game file");
        inputButton.setOnClickListener(v -> pick(PICK_INPUT));
        inputBox.addView(inputButton);
        inputCard.addView(inputBox);
        body.addView(inputCard, lp(0, 0, 0, 12));

        MaterialCardView companionCard = card();
        LinearLayout companionBox = new LinearLayout(this);
        companionBox.setOrientation(LinearLayout.VERTICAL);
        companionBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView companionLabel = new TextView(this);
        companionLabel.setText("Companion input (optional)");
        companionLabel.setTextSize(16);
        companionBox.addView(companionLabel);
        TextView companionPath = new TextView(this);
        companionPath.setText("Use when the APK/file does not contain the matching libil2cpp.so or global-metadata.dat");
        companionPath.setTextIsSelectable(true);
        companionBox.addView(companionPath, lp(0, 0, 0, 8));
        Button companionButton = button("Select companion file");
        companionButton.setOnClickListener(v -> pick(PICK_COMPANION));
        companionBox.addView(companionButton);
        companionCard.addView(companionBox);
        body.addView(companionCard, lp(0, 0, 0, 12));

        if ("modding".equals(mode)) {
            MaterialCardView actions = card();
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView label = new TextView(this);
            label.setText("Modding tools already integrated in UM");
            label.setTextSize(16);
            box.addView(label, lp(0,0,0,8));
            addAction(box, "APK Patcher", v -> startActivity(new Intent(this, PatcherActivity.class)));
            addAction(box, "APK Extractor", v -> startActivity(new Intent(this, APKExtractorActivity.class)));
            Button hex = new MaterialButton(this);
            hex.setText("Hex Editor — selected file");
            hex.setOnClickListener(v -> {
                if (inputFile != null && inputFile.isFile()) startActivity(new Intent(this, HexEditorActivity.class).putExtra("path", inputFile.getAbsolutePath()));
                else status.setText("Select a file first.");
            });
            box.addView(hex, lp(0,0,0,6));
            actions.addView(box);
            body.addView(actions, lp(0,0,0,12));
        }

        runButton = button("Analyze");
        runButton.setOnClickListener(v -> runAnalysis());
        body.addView(runButton, lp(0, 0, 0, 12));
        status = new TextView(this);
        status.setTextIsSelectable(true);
        status.setText("Output: /storage/emulated/0/Untrusted Manager/Dump");
        body.addView(status);

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        EdgeToEdgeUtil.applyContentInsets(this);

        String supplied = getIntent().getStringExtra("input_path");
        if (supplied != null && !supplied.isEmpty()) {
            File f = new File(supplied);
            if (f.isFile()) {
                inputFile = f;
                inputPath.setText(f.getAbsolutePath());
                status.setText("Selected: " + f.getAbsolutePath());
                runAnalysis();
            }
        }
    }

    private void pick(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(i, request);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        executor.execute(() -> {
            try {
                File f = materialize(uri);
                runOnUiThread(() -> {
                    if (requestCode == PICK_INPUT) { inputFile = f; status.setText("Selected: " + f.getAbsolutePath()); }
                    else { companionFile = f; status.setText("Companion: " + f.getAbsolutePath()); }
                });
            } catch (Exception e) {
                AppLogs.writeEvent("game_analysis", "picker materialization failed", e);
                runOnUiThread(() -> status.setText("Selection failed: " + e.getMessage()));
            }
        });
    }

    private void runAnalysis() {
        if (inputFile == null || !inputFile.isFile()) { status.setText("Select a primary input first."); return; }
        runButton.setEnabled(false);
        status.setText("Analyzing…\nLarge APKs and native libraries can take a while.");
        executor.execute(() -> {
            try {
                GameAnalysisEngine.Result result = GameAnalysisEngine.analyze(this, inputFile, companionFile);
                AppLogs.writeEvent("game_analysis", "completed: " + result.output().getAbsolutePath());
                runOnUiThread(() -> {
                    status.setText("Completed.\n" + result.report() + "\n\nDump folder:\n" + result.output().getAbsolutePath());
                    runButton.setEnabled(true);
                });
            } catch (Throwable t) {
                AppLogs.writeEvent("game_analysis", "analysis failed", t);
                runOnUiThread(() -> { status.setText("Analysis failed: " + t.getMessage()); runButton.setEnabled(true); });
            }
        });
    }

    private File materialize(Uri uri) throws IOException {
        String name = displayName(uri);
        if (name == null || name.trim().isEmpty()) name = "selected.bin";
        File dir = new File(getCacheDir(), "game-analysis");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create cache directory");
        File out = new File(dir, safeName(name));
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IOException("Unable to open selected document");
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) != -1) fos.write(b, 0, n);
        }
        return out;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    private MaterialCardView card() {
        MaterialCardView c = new MaterialCardView(this);
        c.setRadius(dp(14));
        c.setCardElevation(dp(1));
        return c;
    }

    private Button button(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        return b;
    }

    private void addAction(LinearLayout parent, String text, View.OnClickListener listener) {
        Button b = button(text); b.setOnClickListener(listener); parent.addView(b, lp(0,0,0,6));
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private static String safeName(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private static String titleForMode(String mode) {
        return switch (mode) {
            case "il2cpp" -> "IL2CPP Dumper";
            case "metadata" -> "Global Metadata";
            case "encryption" -> "Game Encryption";
            case "modding" -> "Game Modding Toolkit";
            default -> "Game Analyzer";
        };
    }

    private static String descriptionForMode(String mode) {
        return switch (mode) {
            case "il2cpp" -> "Analyze a Unity IL2CPP native library together with global-metadata.dat. UM generates metadata-backed dump.cs, native ELF information, strings and reproducible hashes. A matching runtime/memory dump can also be supplied when the on-disk representation is protected.";
            case "metadata" -> "Validate global-metadata.dat, recover standard enveloped/simple-XOR representations when safely detectable, export the metadata string table and generate a metadata-backed dump.cs skeleton.";
            case "encryption" -> "Inspect whether a selected game artifact resembles standard IL2CPP metadata, a simple transformed representation, an ELF native library or a packaged archive. This is static analysis, not a universal decryption or protection-bypass engine.";
            case "modding" -> "Use the existing UM APK Patcher, APK Extractor and Hex Editor alongside the IL2CPP/static analysis output. No separate launcher or companion application is installed.";
            default -> "Analyze APK/XAPK/APKM/AAB/ZIP files and automatically locate libil2cpp.so and global-metadata.dat, including nested APKs. Results are written to the UM Dump directory.";
        };
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
