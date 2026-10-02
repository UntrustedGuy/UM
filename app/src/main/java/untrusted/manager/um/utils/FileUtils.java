package untrusted.manager.um.utils;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import org.apache.commons.io.FilenameUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.util.Locale;

import untrusted.manager.um.UMManager.shizuku.ShizukuFile;
import untrusted.manager.um.UMManager.shizuku.ShizukuFileOps;

public class FileUtils {
    public static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".ico", ".tiff", ".tif", ".heic", ".heif"};
    public static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".avi", ".3gp", ".mov", ".ts", ".m4v", ".flv", ".wmv"};
    public static final String[] AUDIO_EXTS = {".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".wma", ".opus"};
    public static final String[] ARCHIVE_EXTS = {".zip", ".rar", ".7z", ".tar", ".gz", ".bz2"};
    public static final String[] TEXT_EXTS = {".txt", ".log", ".xml", ".json", ".html", ".htm", ".xhtml", ".css", ".js", ".java", ".kt", ".md", ".smali", ".pro", ".gradle", ".properties"};

    public static boolean areFilesDifferent(File[] files1, File[] files2) throws IOException {
        if (files1 == null || files2 == null)
            return files1 != files2;
        if (files1.length != files2.length - 1)
            return true;
        for (int i = 0; i < files1.length; i++) {
            if (!files1[i].exists() || !files2[i + 1].exists() || files1[i].length() != files2[i + 1].length()) {
                return true;
            }
        }
        return false;
    }

    public static boolean matchExt(String ext, String[] extensions) {
        for (String e : extensions) if (e.equals(ext)) return true;
        return false;
    }

    public static boolean isImageFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTS) if (lower.endsWith(ext)) return true;
        return false;
    }
    public static boolean doesNotHaveStoragePerm(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? !Environment.isExternalStorageManager() : Build.VERSION.SDK_INT > 22 && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED;
    }

    public static boolean isAxml(InputStream inputStream) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(inputStream); BufferedReader abr = new BufferedReader(isr)) {
            String firstLine = abr.readLine();
            return firstLine != null && !firstLine.startsWith("<?xml version=");
        }
    }
     public static File copyFileFromAssetsAndGetFile(String fileName, Context context) throws IOException {
        File destinationFile = new File(context.getFilesDir(), fileName);
        if(!destinationFile.exists()) try(InputStream is = context.getAssets().open(fileName)) {
            copyFile(is, destinationFile);
        }
        return destinationFile;
    }
    
    public static File getDebugKeystore(Context context) throws IOException {
        File destinationFile = new File(context.getFilesDir(), "debug.keystore");
        if (!destinationFile.exists() || !isDebugKeystoreValid(destinationFile)) {
            try (InputStream is = context.getAssets().open("debug.keystore")) {
                copyFile(is, destinationFile);
            }
        }
        return destinationFile;
    }

    public static boolean isDebugKeystoreValid(File file) {
        if (file == null || !file.isFile()) return false;
        try (InputStream is = new FileInputStream(file)) {
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(is, "android".toCharArray());
            return ks.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static File getUnusedFile(File file) {
        if (file == null || !file.exists()) return file;
        File parent = file.getParentFile();
        String fileName = file.getName();
        String extension = FilenameUtils.getExtension(fileName);
        String base = extension.isEmpty()
                ? fileName
                : fileName.substring(0, fileName.length() - extension.length() - 1);
        // Avoid producing names such as "foo_1." for extensionless files.
        base = base.replaceFirst("_\\d+$", "");
        int i = 1;
        File candidate;
        do {
            String candidateName = extension.isEmpty()
                    ? base + "_" + i
                    : base + "_" + i + "." + extension;
            candidate = new File(parent, candidateName);
            i++;
        } while (candidate.exists());
        return candidate;
    }

    public static File getUnusedFile(String file) {
        return getUnusedFile(new File(file));
    }

    public static void copyFolder(File src, File dest) throws IOException {
        if (src == null || dest == null) throw new IOException("Source and destination are required");
        Path sourcePath = src.toPath();
        Path destinationPath = dest.toPath();
        if (Files.isSymbolicLink(sourcePath)) {
            throw new IOException("Refusing to follow symbolic link: " + src);
        }
        if (Files.isSymbolicLink(destinationPath)) {
            throw new IOException("Refusing to write through symbolic-link destination: " + dest);
        }
        if (!src.isDirectory()) {
            File target = dest.isDirectory() ? new File(dest, src.getName()) : dest;
            if (Files.isSymbolicLink(target.toPath())) {
                throw new IOException("Refusing to overwrite symbolic link: " + target);
            }
            copyFile(src, target);
            return;
        }

        Path sourceReal = sourcePath.toRealPath();
        Path destinationParent = destinationPath.toAbsolutePath().normalize();
        if (destinationParent.startsWith(sourceReal)) {
            throw new IOException("Destination cannot be inside the source directory: " + dest);
        }
        if (Files.isSymbolicLink(destinationPath)) {
            throw new IOException("Refusing to write through symbolic-link destination: " + dest);
        }
        File destinationParentFile = dest.getParentFile();
        for (File current = destinationParentFile; current != null; current = current.getParentFile()) {
            if (Files.isSymbolicLink(current.toPath())) {
                throw new IOException("Refusing to write through symbolic-link directory: " + current);
            }
        }
        if (dest.exists() && !dest.isDirectory()) {
            throw new IOException("Destination is not a directory: " + dest);
        }
        if (!dest.exists() && !dest.mkdirs() && !dest.isDirectory()) {
            throw new IOException("Cannot create directory: " + dest);
        }

        File[] children = src.listFiles();
        if (children == null) throw new IOException("Cannot read directory: " + src);
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) {
                throw new IOException("Refusing to follow symbolic link: " + child);
            }
            File target = new File(dest, child.getName());
            if (Files.isSymbolicLink(target.toPath())) {
                throw new IOException("Refusing to overwrite symbolic link: " + target);
            }
            if (child.isDirectory()) copyFolder(child, target);
            else copyFile(child, target);
        }
    }

    public static OutputStream getOutputStream(String filepath) throws IOException {
        return getOutputStream(new File(filepath));
    }

    public static OutputStream getOutputStream(File file) throws IOException {
        return LegacyUtils.supportsFileChannel ?
        Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                : new FileOutputStream(file);
    }

    public static void copyFile(File sourceFile, File destinationFile) throws IOException {
        if (sourceFile == null || destinationFile == null) throw new IOException("Source and destination are required");
        if (!sourceFile.isFile() || Files.isSymbolicLink(sourceFile.toPath())) {
            throw new IOException("Refusing to copy non-regular or symbolic-link source: " + sourceFile);
        }
        if (Files.isSymbolicLink(destinationFile.toPath())) {
            throw new IOException("Refusing to overwrite symbolic-link destination: " + destinationFile);
        }
        File parent = destinationFile.getParentFile();
        for (File current = parent; current != null; current = current.getParentFile()) {
            if (Files.isSymbolicLink(current.toPath())) {
                throw new IOException("Refusing to write through symbolic-link directory: " + current);
            }
        }
        try (InputStream is = getInputStream(sourceFile);
             OutputStream os = getOutputStream(destinationFile)) {
            copyFile(is, os);
        }
        if (!destinationFile.isFile() || destinationFile.length() != sourceFile.length()) {
            throw new IOException("File copy verification failed: " + destinationFile);
        }
    }

    public static void copyFile(File in, OutputStream os) throws IOException {
        try(InputStream is = getInputStream(in)) {
            copyFile(is, os);
        }
    }

    public static void copyFile(InputStream is, File destinationFile) throws IOException {
        try (OutputStream os = getOutputStream(destinationFile)) {
            copyFile(is, os);
        }
    }

    public static void copyFile(InputStream is, OutputStream os) throws IOException {
        if(LegacyUtils.supportsWriteExternalStorage) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
        } else android.os.FileUtils.copy(is, os);
    }

    public static InputStream getInputStream(File file) throws IOException {
        if (file instanceof ShizukuFile) {
            file = ShizukuFileOps.materialize(null, file);
        }
        return LegacyUtils.supportsFileChannel ?
                Files.newInputStream(file.toPath(), StandardOpenOption.READ)
                : new FileInputStream(file);
    }

    public static InputStream getInputStream(String filePath) throws IOException {
        return getInputStream(new File(filePath));
    }

    public static File getUnusedFile(File appFolder, String name) {
        return getUnusedFile(new File(appFolder, name));
    }
}