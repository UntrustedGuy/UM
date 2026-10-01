package untrusted.manager.um.ui.dialogs;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import untrusted.manager.um.R;
import io.github.codehasan.colorpicker.extensions.Extensions;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import untrusted.manager.um.ui.activities.CompareTextActivity;
import untrusted.manager.um.utils.ComparisonDigest;

public class CompareZipDialog {
    private static final int MAX_DISPLAYED_DIFFERENCES = 10_000;
    private final Activity context;
    private final File zip1;
    private final File zip2;

    private record DiffItem(String text, String fileName, String status) {
        @Override public String toString() { return text; }
    }

    public CompareZipDialog(Activity context, File zip1, File zip2) {
        this.context = context;
        this.zip1 = zip1;
        this.zip2 = zip2;
    }

    public void show() {
        if (zip1 == null || zip2 == null || !zip1.isFile() || !zip2.isFile()) {
            Extensions.showMessage(context, context.getString(R.string.error_reading_zip_files));
            return;
        }
        ProgressDialog progress = new ProgressDialog(context);
        progress.setTitle(R.string.compare_zip);
        progress.setMessage(context.getString(R.string.comparing));
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
        new Thread(() -> {
            List<DiffItem> differences = new ArrayList<>();
            int identical = 0;
            try (ZipFile zf1 = new ZipFile(zip1); ZipFile zf2 = new ZipFile(zip2)) {
                Map<String, ZipEntry> entries1 = readEntries(zf1);
                Map<String, ZipEntry> entries2 = readEntries(zf2);
                int total = entries1.size() + entries2.size();
                int processed = 0;
                for (Map.Entry<String, ZipEntry> e : entries1.entrySet()) {
                    String name = e.getKey();
                    ZipEntry ze1 = e.getValue();
                    ZipEntry ze2 = entries2.get(name);
                    if (ze2 == null) {
                        addDifference(differences, "[" + context.getString(R.string.removed) + "] " + name + " (" + ze1.getSize() + " bytes)", name, "[Removed]");
                    } else {
                        try {
                            boolean same = ze1.getSize() == ze2.getSize() && ze1.getCrc() == ze2.getCrc();
                            if (same && !ze1.isDirectory()) {
                                // CRC/size is a fast filter; SHA-256 confirms equality and avoids CRC collisions.
                                same = ComparisonDigest.sha256(zf1, ze1).equals(ComparisonDigest.sha256(zf2, ze2));
                            }
                            if (!same) addDifference(differences, "[" + context.getString(R.string.modified) + "] " + name + " (Size: " + ze1.getSize() + " -> " + ze2.getSize() + " bytes)", name, "[Modified]");
                            else identical++;
                        } catch (Exception entryError) {
                            addDifference(differences, "[Read error] " + name + " — " + entryError.getMessage(), name, "[Error]");
                        }
                        entries2.remove(name);
                    }
                    processed++;
                    if (processed % 32 == 0) updateProgress(progress, processed, total);
                }
                for (Map.Entry<String, ZipEntry> e : entries2.entrySet()) {
                    ZipEntry ze = e.getValue();
                    addDifference(differences, "[" + context.getString(R.string.added) + "] " + e.getKey() + " (" + ze.getSize() + " bytes)", e.getKey(), "[Added]");
                }
            } catch (Exception e) {
                addDifference(differences, context.getString(R.string.error_reading_zip_files) + e.getMessage(), "", "Error");
            }
            final int sameCount = identical;
            context.runOnUiThread(() -> {
                if (progress.isShowing()) progress.dismiss();
                showResult(differences, sameCount);
            });
        }, "zip-compare").start();
    }

    private static Map<String, ZipEntry> readEntries(ZipFile zip) {
        Map<String, ZipEntry> map = new HashMap<>();
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            map.put(entry.getName(), entry);
        }
        return map;
    }

    private static void addDifference(List<DiffItem> list, String text, String name, String status) {
        if (list.size() < MAX_DISPLAYED_DIFFERENCES) list.add(new DiffItem(text, name, status));
    }

    private void updateProgress(ProgressDialog dialog, int done, int total) {
        int pct = total == 0 ? 100 : Math.min(100, done * 100 / total);
        context.runOnUiThread(() -> { if (dialog.isShowing()) dialog.setMessage("Comparing… " + pct + "%"); });
    }

    private void showResult(List<DiffItem> differences, int identical) {
        if (differences.isEmpty()) differences.add(new DiffItem(context.getString(R.string.no_differences_found) + "\nIdentical entries: " + identical, "", "Info"));
        else differences.add(0, new DiffItem("Identical entries: " + identical + "\nDifferences shown: " + Math.min(differences.size(), MAX_DISPLAYED_DIFFERENCES), "", "Info"));
        ListView listView = new ListView(context);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, differences));
        listView.setOnItemClickListener((parent, view, position, id) -> {
            DiffItem item = differences.get(position);
            if ("[Modified]".equals(item.status)) {
                String ext = FilenameUtils.getExtension(item.fileName).toLowerCase();
                boolean isZipInner = ext.equals("zip") || ext.equals("apk") || ext.equals("jar");
                boolean isArscInner = ext.equals("arsc");
                boolean isTextInner = !isZipInner && !isArscInner;
                if (isTextInner) {
                    context.startActivity(new Intent(context, CompareTextActivity.class)
                            .putExtra("file1", item.fileName).putExtra("file2", item.fileName)
                            .putExtra("isZip1", true).putExtra("isZip2", true)
                            .putExtra("zip1", zip1.getAbsolutePath()).putExtra("zip2", zip2.getAbsolutePath()));
                } else if (isZipInner) extractAndCompareZip(item.fileName);
                else extractAndCompareArsc(item.fileName);
            }
        });
        new MaterialAlertDialogBuilder(context).setTitle(R.string.zip_differences).setView(listView).setPositiveButton(android.R.string.ok, null).show();
    }

    private void extractAndCompareZip(String innerFileName) {
        try {
            File tmp1 = cacheFile("cmp1", innerFileName);
            File tmp2 = cacheFile("cmp2", innerFileName);
            extractZipEntry(zip1, innerFileName, tmp1);
            extractZipEntry(zip2, innerFileName, tmp2);
            new CompareZipDialog(context, tmp1, tmp2).show();
        } catch (Exception e) { Extensions.showMessage(context, "Error extracting inner zip: " + e.getMessage()); }
    }

    private void extractAndCompareArsc(String innerFileName) {
        try {
            File tmp1 = cacheFile("cmp1", innerFileName);
            File tmp2 = cacheFile("cmp2", innerFileName);
            extractZipEntry(zip1, innerFileName, tmp1);
            extractZipEntry(zip2, innerFileName, tmp2);
            new CompareArscDialog(context, tmp1.getAbsolutePath(), tmp2.getAbsolutePath()).show();
        } catch (Exception e) { Extensions.showMessage(context, "Error extracting inner arsc: " + e.getMessage()); }
    }

    private File cacheFile(String prefix, String name) {
        String digest;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            digest = sb.substring(0, 24);
        } catch (Exception e) { digest = Integer.toHexString(name.hashCode()); }
        return new File(context.getCacheDir(), prefix + "_" + digest);
    }

    private void extractZipEntry(File zip, String entryName, File out) throws Exception {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry ze = zf.getEntry(entryName);
            if (ze == null) throw new Exception("Entry not found: " + entryName);
            if (ze.getSize() > 256L * 1024L * 1024L) throw new Exception("Entry is too large to compare safely");
            try (InputStream is = zf.getInputStream(ze); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                int len;
                while ((len = is.read(buffer)) > 0) fos.write(buffer, 0, len);
            }
        }
    }
}
