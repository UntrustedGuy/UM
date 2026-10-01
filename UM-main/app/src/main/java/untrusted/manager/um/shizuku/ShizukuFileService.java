package untrusted.manager.um.shizuku;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Keep;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ShizukuFileService extends IFileService.Stub {

    public static final int SERVICE_VERSION = 2;
    public static final long MAX_BYTES = 100L * 1024L * 1024L;

    private static final String[] SHELL_PREFIXES = new String[]{
            "settings put global private_dns_mode ",
            "settings put global private_dns_specifier ",
            "settings put secure enabled_accessibility_services ",
            "settings put secure accessibility_enabled ",
            "appops set ",
            "cmd appops set ",
            "pm grant ",
            "pm trim-caches"
    };

    private static final Pattern ALLOWED =
            Pattern.compile("^/storage/emulated/\\d+/Android/(data|obb|media)(/.*)?$");

    private volatile String error = "";

    public ShizukuFileService() {
    }

    @Keep
    public ShizukuFileService(Context context) {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public int version() {
        return SERVICE_VERSION;
    }

    @Override
    public String lastError() {
        return error;
    }

    private void fail(String msg) {
        error = msg;
    }

    private String norm(String path) {
        if (path == null || !path.startsWith("/")) return null;
        String[] parts = path.split("/");
        ArrayDeque<String> out = new ArrayDeque<>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!out.isEmpty()) out.removeLast();
            } else {
                out.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append('/').append(s);
        return sb.length() == 0 ? "/" : sb.toString();
    }

    private String canon(String path) {
        String n = norm(path);
        if (n == null) return null;
        if (n.equals("/sdcard") || n.startsWith("/sdcard/")) {
            n = "/storage/emulated/0" + n.substring("/sdcard".length());
        }
        return n;
    }

    private boolean allowed(String path) {
        String n = canon(path);
        return n != null && ALLOWED.matcher(n).matches();
    }

    /**
     * Resolve the actual filesystem target before authorization.  Lexically normalizing
     * a path is not sufficient because callers can pass ".." or traverse a symlink.
     * Canonicalizing the existing parent also protects newly-created destinations.
     */
    private File canonicalTarget(String path) throws IOException {
        if (path == null || !path.startsWith("/")) throw new IOException("Invalid path");
        File f = new File(path);
        String canonical = f.getCanonicalPath();
        if (!allowed(canonical)) throw new IOException("Path not accessible");
        return new File(canonical);
    }

    private boolean topLevel(String path) {
        String n = canon(path);
        return n != null && (n.equals("/storage/emulated/0/Android/data")
                || n.equals("/storage/emulated/0/Android/obb")
                || n.equals("/storage/emulated/0/Android/media"));
    }

    private File checked(String path) {
        try {
            return canonicalTarget(path);
        } catch (IOException e) {
            fail(e.getMessage() == null ? "Path not accessible" : e.getMessage());
            return null;
        }
    }

    @Override
    public boolean pathExists(String path) {
        File f = checked(path);
        return f != null && f.exists();
    }

    @Override
    public boolean pathIsDir(String path) {
        File f = checked(path);
        return f != null && f.isDirectory();
    }

    @Override
    public boolean pathIsFile(String path) {
        File f = checked(path);
        return f != null && f.isFile();
    }

    @Override
    public long pathSize(String path) {
        File f = checked(path);
        if (f == null || !f.isFile()) return -1;
        return f.length();
    }

    @Override
    public long pathMtime(String path) {
        File f = checked(path);
        if (f == null || !f.exists()) return 0;
        return f.lastModified();
    }

    @Override
    public List<String> dirStat(String dirPath) {
        List<String> out = new ArrayList<>();
        File dir = checked(dirPath);
        if (dir == null || !dir.isDirectory()) {
            fail("Not a directory");
            return out;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            fail("Cannot list directory");
            return out;
        }
        for (File k : kids) {
            String name = k.getName();
            if (name.equals(".") || name.equals("..")) continue;
            boolean symlink = isSymlink(k);
            boolean isDir = !symlink && k.isDirectory();
            out.add(name + "\037" + (isDir ? "1" : "0") + "\037"
                    + (isDir ? 0 : Math.max(0, symlink ? 0 : k.length())) + "\037"
                    + k.lastModified() + "\037");
        }
        return out;
    }

    @Override
    public long dirSize(String dirPath) {
        File dir = checked(dirPath);
        if (dir == null) return -1;
        return du(dir);
    }

    private long du(File f) {
        if (isSymlink(f)) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += du(k);
        return total;
    }

    @Override
    public ParcelFileDescriptor openRead(String path, long maxBytes) {
        File f = checked(path);
        if (f == null || !f.isFile()) {
            fail("Not a file");
            return null;
        }
        if (f.length() > maxBytes) {
            fail("File too large");
            return null;
        }
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            final File src = f;
            final long cap = maxBytes;
            new Thread(() -> {
                try (InputStream in = new FileInputStream(src);
                     OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] buf = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > cap) break;
                        out.write(buf, 0, n);
                    }
                } catch (Exception ignored) {
                }
            }).start();
            return pipe[0];
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    @Override
    public long writeFile(String path, ParcelFileDescriptor data, long maxBytes) {
        File dst = checked(path);
        if (dst == null) return -1;
        if (topLevel(dst.getAbsolutePath())) {
            fail("Refusing protected path");
            return -1;
        }
        if (data == null) {
            fail("No data");
            return -1;
        }
        File parent = dst.getParentFile();
        if (parent == null || !parent.isDirectory()) {
            fail("Destination directory missing");
            return -1;
        }
        File tmp = new File(parent, ".um-write-" + System.nanoTime() + ".tmp");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(data);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > maxBytes || total > MAX_BYTES) {
                    fail("File too large");
                    return -1;
                }
                out.write(buf, 0, n);
            }
            out.flush();
            if (!tmp.renameTo(dst)) {
                fail("Cannot replace destination");
                return -1;
            }
            return total;
        } catch (Exception e) {
            fail(e.getMessage());
            return -1;
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private boolean copyRec(File src, File dst) {
        if (isSymlink(src)) {
            fail("Symlinks are not supported for privileged copy");
            return false;
        }
        if (src.isDirectory()) {
            if (dst.exists() && !dst.isDirectory()) {
                fail("Destination is not a directory");
                return false;
            }
            boolean created = !dst.exists();
            if (created && !dst.mkdirs() && !dst.isDirectory()) {
                fail("Cannot create destination directory");
                return false;
            }
            File[] kids = src.listFiles();
            if (kids == null) {
                if (created) delRec(dst);
                fail("Cannot read source directory");
                return false;
            }
            for (File k : kids) {
                if (!copyRec(k, new File(dst, k.getName()))) {
                    if (created) delRec(dst);
                    return false;
                }
            }
            dst.setLastModified(src.lastModified());
            return true;
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            fail("Cannot create destination directory");
            return false;
        }
        File tmp = new File(parent, ".um-copy-" + System.nanoTime() + ".tmp");
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            if (dst.exists() && dst.isDirectory()) {
                fail("Destination is a directory");
                return false;
            }
            if (dst.exists() && !dst.delete()) {
                fail("Cannot replace destination");
                return false;
            }
            if (!tmp.renameTo(dst)) {
                fail("Cannot finalize copy");
                return false;
            }
        } catch (Exception e) {
            fail(e.getMessage());
            return false;
        } finally {
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        dst.setLastModified(src.lastModified());
        return true;
    }

    @Override
    public boolean copyFile(String src, String dst) {
        File s = checked(src);
        File d = checked(dst);
        if (s == null || d == null || !s.isFile()) {
            fail("Invalid copy source");
            return false;
        }
        if (topLevel(s.getAbsolutePath()) || topLevel(d.getAbsolutePath())) {
            fail("Refusing protected path");
            return false;
        }
        if (sameOrDescendant(d, s)) {
            fail("Destination is inside source");
            return false;
        }
        return copyRec(s, d);
    }

    @Override
    public boolean copyDir(String src, String dst) {
        File s = checked(src);
        File d = checked(dst);
        if (s == null || d == null || !s.isDirectory()) {
            fail("Invalid copy source");
            return false;
        }
        if (topLevel(s.getAbsolutePath()) || topLevel(d.getAbsolutePath())) {
            fail("Refusing protected path");
            return false;
        }
        File target = new File(d, s.getName());
        try {
            target = canonicalTarget(target.getAbsolutePath());
        } catch (IOException e) {
            fail(e.getMessage());
            return false;
        }
        if (sameOrDescendant(target, s)) {
            fail("Destination is inside source");
            return false;
        }
        return copyRec(s, target);
    }

    @Override
    public boolean mkdir(String path) {
        File d = checked(path);
        if (d == null) return false;
        if (topLevel(d.getAbsolutePath())) {
            fail("Refusing protected path");
            return false;
        }
        return d.isDirectory() || d.mkdirs();
    }

    @Override
    public boolean deletePath(String path) {
        File f = checked(path);
        if (f == null) return false;
        if (topLevel(f.getAbsolutePath())) {
            fail("Refusing protected path");
            return false;
        }
        return delRec(f);
    }

    private static boolean isSymlink(File file) {
        try {
            return !file.getAbsolutePath().equals(file.getCanonicalPath());
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean sameOrDescendant(File candidate, File root) {
        try {
            String c = candidate.getCanonicalPath();
            String r = root.getCanonicalPath();
            return c.equals(r) || c.startsWith(r.endsWith(File.separator) ? r : r + File.separator);
        } catch (IOException e) {
            return true;
        }
    }

    private boolean delRec(File f) {
        // Never follow a nested symlink during recursive deletion. Delete the link itself.
        if (isSymlink(f)) return !f.exists() || f.delete();
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!delRec(k)) return false;
                }
            }
        }
        return !f.exists() || f.delete();
    }

    @Override
    public boolean renamePath(String from, String to) {
        File s = checked(from);
        File d = checked(to);
        if (s == null || d == null) return false;
        if (topLevel(s.getAbsolutePath()) || topLevel(d.getAbsolutePath())) {
            fail("Refusing protected path");
            return false;
        }
        if (s.isDirectory() && sameOrDescendant(d, s)) {
            fail("Destination is inside source");
            return false;
        }
        return s.renameTo(d);
    }

    @Override
    public boolean touchPath(String path) {
        File f = checked(path);
        if (f == null) return false;
        try {
            if (!f.exists() && !f.createNewFile()) {
                fail("Cannot create file");
                return false;
            }
            return true;
        } catch (Exception e) {
            fail(e.getMessage());
            return false;
        }
    }

    @Override
    public boolean touchMtime(String path, long millis) {
        File f = checked(path);
        if (f == null || !f.exists()) {
            fail("No such file");
            return false;
        }
        return f.setLastModified(millis);
    }

    @Override
    public String shell(String command, int timeoutSeconds) {
        if (!shellAllowed(command)) {
            fail("Command not allowed");
            return "-1\nrefused";
        }
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder out = new StringBuilder();
            final Process p = process;
            Thread reader = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    InputStream in = p.getInputStream();
                    while ((n = in.read(buf)) != -1) {
                        synchronized (out) {
                            if (out.length() < 65536) out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();
            int timeout = timeoutSeconds <= 0 ? 15 : Math.min(timeoutSeconds, 60);
            boolean done = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!done) {
                try {
                    process.destroyForcibly();
                } catch (Exception ignored) {
                }
                fail("Command timed out");
                return "-1\ntimeout";
            }
            try {
                reader.join(2000);
            } catch (Exception ignored) {
            }
            int code;
            String text;
            synchronized (out) {
                code = process.exitValue();
                text = out.toString().trim();
            }
            return code + "\n" + text;
        } catch (Exception e) {
            fail(e.getMessage());
            return "-1\nerror";
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean shellAllowed(String command) {
        if (command == null || command.isEmpty() || command.length() > 2048) return false;
        boolean prefixOk = false;
        for (String prefix : SHELL_PREFIXES) {
            if (command.startsWith(prefix)) {
                prefixOk = true;
                break;
            }
        }
        if (!prefixOk) return false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == ';' || c == '&' || c == '|' || c == '`' || c == '$' || c == '(' || c == ')' || c == '<' || c == '>' || c == '\n' || c == '\r') return false;
        }
        return true;
    }
}
