package untrusted.manager.um.ui.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.view.ViewGroup;
import android.webkit.WebView;
import io.github.codehasan.colorpicker.extensions.Extensions;

import androidx.appcompat.app.AppCompatActivity;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import untrusted.manager.um.utils.ErrorUtil;

public class CompareTextActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(false);
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        setContentView(webView);
        untrusted.manager.um.utils.EdgeToEdgeUtil.applyContentInsets(this);

        Intent intent = getIntent();
        String path1 = intent.getStringExtra("file1");
        String path2 = intent.getStringExtra("file2");
        String text1 = intent.getStringExtra("text1");
        String text2 = intent.getStringExtra("text2");
        boolean isZip1 = intent.getBooleanExtra("isZip1", false);
        boolean isZip2 = intent.getBooleanExtra("isZip2", false);
        String zip1 = intent.getStringExtra("zip1");
        String zip2 = intent.getStringExtra("zip2");
        String title1 = intent.getStringExtra("title1");
        String title2 = intent.getStringExtra("title2");
        if (title1 == null || title1.isEmpty()) title1 = "File 1";
        if (title2 == null || title2.isEmpty()) title2 = "File 2";

        final String finalTitle1 = title1;
        final String finalTitle2 = title2;
        final WebView finalWebView = webView;
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        addContentView(progress, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new Thread(() -> {
            try {
                List<String> lines1 = text1 != null ? splitLines(text1) : readLines(path1, isZip1, zip1);
                List<String> lines2 = text2 != null ? splitLines(text2) : readLines(path2, isZip2, zip2);
                final int maxLines = 100_000;
                if (lines1.size() > maxLines || lines2.size() > maxLines) {
                    throw new IOException("Files contain too many lines to compare safely");
                }

                DiffRowGenerator generator = DiffRowGenerator.create()
                        .showInlineDiffs(true)
                        .inlineDiffByWord(true)
                        .oldTag(f -> f ? "<span style=\"background-color:#ffcccc;text-decoration:line-through;\">" : "</span>")
                        .newTag(f -> f ? "<span style=\"background-color:#ccffcc;\">" : "</span>")
                        .build();

                List<DiffRow> rows = generator.generateDiffRows(lines1, lines2);
                final int maxRows = 100_000;
                if (rows.size() > maxRows) throw new IOException("Comparison result is too large to render safely");

                StringBuilder html = new StringBuilder(Math.min(8 * 1024 * 1024, Math.max(4096, rows.size() * 80)));
                html.append("<html><head><style>")
                    .append("body { font-family: monospace; font-size: 14px; white-space: pre-wrap; word-wrap: break-word; } ")
                    .append("table { width: 100%; border-collapse: collapse; table-layout: fixed; } ")
                    .append("th, td { border: 1px solid #ddd; padding: 4px; vertical-align: top; overflow: hidden; } ")
                    .append("th { background-color: #f2f2f2; }")
                    .append("</style></head><body>");

                html.append("<table><tr><th style=\"width:50%\">").append(escapeDiffHtml(finalTitle1)).append("</th><th style=\"width:50%\">").append(escapeDiffHtml(finalTitle2)).append("</th></tr>");
                for (DiffRow row : rows) {
                    html.append("<tr>");
                    String oldLine = row.getOldLine();
                    String newLine = row.getNewLine();
                    String oldBg = row.getTag() == DiffRow.Tag.DELETE ? "background-color:#ffe6e6;" : "";
                    String newBg = row.getTag() == DiffRow.Tag.INSERT ? "background-color:#e6ffe6;" : "";
                    html.append("<td style=\"").append(oldBg).append("\">").append(escapeDiffHtml(oldLine)).append("</td>");
                    html.append("<td style=\"").append(newBg).append("\">").append(escapeDiffHtml(newLine)).append("</td>");
                    html.append("</tr>");
                }
                html.append("</table></body></html>");

                mainHandler.post(() -> {
                    try {
                        finalWebView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null);
                    } finally {
                        if (progress.getParent() instanceof ViewGroup parent) parent.removeView(progress);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (progress.getParent() instanceof ViewGroup parent) parent.removeView(progress);
                    Extensions.showMessage(this, "Error comparing text: " + e.getMessage());
                    new ErrorUtil(this).showError(e);
                });
            }
        }, "text-compare").start();

    }

    private static final long MAX_COMPARE_BYTES = 16L * 1024L * 1024L;

    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        String[] parts = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        java.util.Collections.addAll(lines, parts);
        return lines;
    }

    private String escapeDiffHtml(String value) {
        if (value == null) return "";
        final String openOld = "<span style=\"background-color:#ffcccc;text-decoration:line-through;\">";
        final String openNew = "<span style=\"background-color:#ccffcc;\">";
        final String close = "</span>";
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
        // DiffRowGenerator inserts only these known tags. Restore them after escaping file content
        // so arbitrary source text cannot inject HTML while inline highlighting still works.
        return escaped
                .replace("&lt;span style=&quot;background-color:#ffcccc;text-decoration:line-through;&quot;&gt;", openOld)
                .replace("&lt;span style=&quot;background-color:#ccffcc;&quot;&gt;", openNew)
                .replace("&lt;/span&gt;", close);
    }

    private List<String> readLines(String path, boolean isZip, String zipPath) throws Exception {
        List<String> lines = new ArrayList<>();
        if (isZip) {
            try (ZipFile zf = new ZipFile(zipPath)) {
                ZipEntry ze = zf.getEntry(path);
                if (ze != null) {
                    if (ze.getSize() > MAX_COMPARE_BYTES) throw new IOException("File is too large to compare safely");
                    long total = 0;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(zf.getInputStream(ze), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            total += line.length() * 2L + 1L;
                            if (total > MAX_COMPARE_BYTES) throw new IOException("File expands beyond the safe comparison limit");
                            lines.add(line);
                        }
                    }
                }
            }
        } else {
            File file = new File(path);
            if (file.length() > MAX_COMPARE_BYTES) throw new IOException("File is too large to compare safely");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
            }
        }
        return lines;
    }
}
