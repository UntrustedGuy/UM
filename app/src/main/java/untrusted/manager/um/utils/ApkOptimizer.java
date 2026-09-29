package untrusted.manager.um.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.apk.APKLogger;

import untrusted.manager.um.R;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

public class ApkOptimizer {

    public static File optimize(Context context, File apk, boolean deleteFiles, SharedPreferences settings, APKLogger logger) throws Exception {
        String fileName = apk.getName();
        String filePath = apk.getPath();
        File tempFolder = new File(context.getCacheDir(), System.currentTimeMillis() + '_' + fileName);
        File optFile = FileUtils.getUnusedFile(filePath.replaceFirst("(?i)\\.apk$", "_opt.apk"));
        File workingOutput = new File(optFile.getParentFile(), "." + optFile.getName() + "." + System.nanoTime() + ".tmp.apk");
        File parent = optFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create optimizer output directory");
        }
        try (ZipFile zf = new ZipFile(apk); ZipFile opt = new ZipFile(workingOutput)) {
            zf.extractAll(tempFolder.getPath());
            ZipParameters zp = new ZipParameters();
            zp.setCompressionLevel(CompressionLevel.NO_COMPRESSION);
            zp.setCompressionMethod(CompressionMethod.STORE);
            String amS = "AndroidManifest.xml";
            File am = new File(tempFolder, amS);
            logger.logMessage(context.getString(R.string.adding, amS));
            opt.addFile(am, zp);
            am.delete();
            String rssS = "resources.arsc";
            File rss = new File(tempFolder, rssS);
            if (rss.exists()) {
                logger.logMessage(context.getString(R.string.adding, rssS));
                opt.addFile(rss, zp);
                rss.delete();
            }
            ZipParameters zipParameters = new ZipParameters();
            zipParameters.setCompressionMethod(CompressionMethod.DEFLATE);
            zipParameters.setCompressionLevel(CompressionLevel.MAXIMUM);
            Set<String> filesToDelete;
            ZipParameters zpF = new ZipParameters();
            if (deleteFiles && (filesToDelete = settings.getStringSet("filesToDelete", null)) != null)
                zpF.setExcludeFileFilter(file2 -> {
                    String path = file2.getPath();
                    for (String fd : filesToDelete) if (path.endsWith(fd) || path.matches(fd)) return true;
                    return false;
                });
            List<File> lf = net.lingala.zip4j.util.FileUtils.getFilesInDirectoryRecursive(tempFolder, zpF);
            for (File f : lf) if (!f.isDirectory()) {
                String relativePath = f.getPath().replace(tempFolder.getPath() + File.separatorChar, "");
                if (relativePath.equals(amS) || relativePath.equals(rssS)) continue;
                logger.logMessage(context.getString(R.string.adding, relativePath));
                ZipParameters params = new ZipParameters(zipParameters);
                if ((relativePath.startsWith("res/") && !relativePath.endsWith(".xml"))
                        || (relativePath.startsWith("lib/") && relativePath.toLowerCase(java.util.Locale.US).endsWith(".so"))) {
                    params.setCompressionLevel(CompressionLevel.NO_COMPRESSION);
                    params.setCompressionMethod(CompressionMethod.STORE);
                }
                params.setFileNameInZip(relativePath);
                opt.addFile(f, params);
            }
        } finally {
            deleteRecursive(tempFolder);
        }
        // APK installation requires specific uncompressed/aligned entries.
        // Rebuild/alignment is done after compression so optimization cannot leave
        // a technically valid ZIP that Android rejects as an APK.
        try {
            ApkZipAlignUtil.ensureInstallable(workingOutput);
            if (!workingOutput.isFile() || workingOutput.length() == 0) throw new IOException("Optimizer produced an empty APK");
            String issue = ApkZipAlignUtil.installIssue(workingOutput);
            if (issue != null) throw new IOException("Optimizer produced an invalid APK: " + issue);
            if (optFile.exists() && !optFile.delete()) throw new IOException("Cannot replace optimizer output: " + optFile);
            if (!workingOutput.renameTo(optFile)) {
                try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(workingOutput));
                     java.io.OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(optFile))) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.flush();
                }
                if (!workingOutput.delete() && workingOutput.exists()) throw new IOException("Cannot remove optimizer temporary output");
            }
        } finally {
            if (workingOutput.exists()) workingOutput.delete();
        }
        return optFile;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
