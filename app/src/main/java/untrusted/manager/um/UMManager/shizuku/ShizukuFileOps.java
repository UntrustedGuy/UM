package untrusted.manager.um.UMManager.shizuku;

import android.content.Context;

import java.io.File;
import java.io.IOException;

import untrusted.manager.um.utils.RootManager;

/**
 * Cross-boundary helpers for Shizuku paths: copying/moving between Android/data and normal
 * folders, and materializing Shizuku-only files into the app cache for open/share flows.
 * Kept out of ShizukuFile so the File subclass stays minimal.
 */
public final class ShizukuFileOps {

    private static Context appContext;

    /** Initialized from MainActivity.onCreate so hooks can pass null. */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    private static Context ctx() {
        return appContext;
    }

    /** Returns the original file unless it can only be read via Shizuku; then returns a cache copy. */
    public static File materialize(Context context, File file) {
        Context c = context != null ? context : appContext;
        if (c == null || !(file instanceof ShizukuFile) || file.isDirectory()) return file;
        try {
            return ((ShizukuFile) file).materializeTo(c);
        } catch (IOException e) {
            return file;
        }
    }

    /** True when either side of the operation lives in Android/data. */
    public static boolean involvesShizukuPath(File src, File dest) {
        return ShizukuFile.isAndroidDataPath(src) || ShizukuFile.isAndroidDataPath(dest);
    }

    /**
     * Copy src into destFolder via shell (cp -r). Returns the created file, or null on failure.
     * Works in both directions (into and out of Android/data).
     */
    public static File shellCopy(File src, File destFolder, String name) {
        if (!ShizukuShell.isGranted() || src == null || destFolder == null || name == null || name.isEmpty()) return null;
        File dest = new File(destFolder, name);
        if (dest.equals(src)) return dest;
        String srcPath = canonicalPath(src);
        String dstPath = canonicalPathForCreate(dest);
        if (srcPath == null || dstPath == null || RootManager.isPathBlocked(srcPath) || RootManager.isPathBlocked(dstPath)) return null;
        if (src.isDirectory() && sameOrDescendant(dstPath, srcPath)) return null;
        String cmd = "cp -r -- " + RootManager.escapeShellArg(srcPath) + " " + RootManager.escapeShellArg(dstPath);
        ShizukuShell.Result r = ShizukuShell.exec(cmd);
        if (!r.success || !existsViaShell(dstPath)) return null;
        return new ShizukuFile(dstPath, src.isDirectory(), src.length());
    }

    /** Move src into destFolder via shell (mv). Returns true on success. */
    public static boolean shellMove(File src, File destFolder, String name) {
        if (!ShizukuShell.isGranted() || src == null || destFolder == null || name == null || name.isEmpty()) return false;
        File dest = new File(destFolder, name);
        if (dest.equals(src)) return true;
        String srcPath = canonicalPath(src);
        String dstPath = canonicalPathForCreate(dest);
        if (srcPath == null || dstPath == null || RootManager.isPathBlocked(srcPath) || RootManager.isPathBlocked(dstPath)) return false;
        if (src.isDirectory() && sameOrDescendant(dstPath, srcPath)) return false;
        String cmd = "mv -- " + RootManager.escapeShellArg(srcPath) + " " + RootManager.escapeShellArg(dstPath);
        return ShizukuShell.exec(cmd).success && existsViaShell(dstPath);
    }

    private static String canonicalPath(File file) {
        try {
            String p = file.getCanonicalPath();
            return ShizukuFile.isAndroidDataPath(p) || p.startsWith("/storage/emulated/0/") ? p : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String canonicalPathForCreate(File file) {
        try {
            File parent = file.getParentFile();
            if (parent == null) return null;
            String parentPath = parent.getCanonicalPath();
            if (!(parentPath.equals("/storage/emulated/0") || parentPath.startsWith("/storage/emulated/0/"))) return null;
            return new File(parentPath, file.getName()).getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean sameOrDescendant(String candidate, String root) {
        return candidate.equals(root) || candidate.startsWith(root.endsWith("/") ? root : root + "/");
    }

    private static boolean existsViaShell(String path) {
        return ShizukuShell.exec("[ -e " + RootManager.escapeShellArg(path) + " ]").success;
    }
}
