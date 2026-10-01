package untrusted.manager.um.tools;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * On-device C# compiler bridge for Android ARM64.
 *
 * The compiler is kept in UM private storage and is never installed as a
 * companion application. If the compiler bundle is not already packaged in
 * assets, the pinned official community Mono ARM64 archive is downloaded on
 * first use, unpacked, validated, and then reused offline for subsequent
 * compilations.
 *
 * Pinned bundle: Mono 6.12.0.90, Android API 24, ARM64.
 * The upstream termux-mono project documents this exact ARM64 bundle and its
 * mcs/mono workflow. See README/licensing information shipped with UM.
 */
public final class ManagedCompilerBackend {
    private static final String ROOT = "managed-compiler";
    private static final long TIMEOUT_MS = 120_000L;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final long MAX_ARCHIVE_BYTES = 120L * 1024L * 1024L;
    private static final long EXPECTED_ARCHIVE_SIZE = 70_889_068L;
    private static final String ARCHIVE_NAME = "mono-termux.6.12.0.90-arm64-androideabi24.tar.xz";
    private static final String DOWNLOAD_URL =
            "https://github.com/IanusInferus/termux-mono/releases/download/v20201017/"
                    + ARCHIVE_NAME;

    private ManagedCompilerBackend() {}

    public static final class Result {
        public final boolean success;
        public final File output;
        public final String log;
        Result(boolean success, File output, String log) {
            this.success = success;
            this.output = output;
            this.log = log == null ? "" : log;
        }
    }

    public static File root(Context context) {
        return new File(context.getFilesDir(), ROOT);
    }

    public static boolean isInstalled(Context context) {
        File root = root(context);
        File mono = new File(root, "bin/mono");
        File csc = new File(root, "lib/mono/msbuild/Current/bin/Roslyn/csc.exe");
        File mcs = new File(root, "lib/mono/4.5/mcs.exe");
        return mono.isFile() && mono.length() > 0 && (csc.isFile() || mcs.isFile());
    }

    /** Ensure the compiler exists. Assets are preferred; otherwise download once. */
    public static String ensureInstalled(Context context) {
        if (isInstalled(context)) return null;
        if (installFromAssets(context) && isInstalled(context)) return null;
        try {
            installPinnedBundle(context);
            if (isInstalled(context)) return null;
            return "Compiler archive was installed but the Mono runtime/compiler could not be validated";
        } catch (Throwable t) {
            String msg = t.getMessage();
            return "Unable to install the Android ARM64 C# compiler: "
                    + (msg == null ? t.getClass().getSimpleName() : msg);
        }
    }

    public static Result compile(Context context, File source, File output,
                                 String assemblyName, List<File> referenceDirectories) {
        String installError = ensureInstalled(context);
        if (installError != null) return new Result(false, output, installError);
        if (source == null || !source.isFile()) {
            return new Result(false, output, "C# source file does not exist");
        }
        try {
            File root = root(context);
            File mono = new File(root, "bin/mono");
            File csc = new File(root, "lib/mono/msbuild/Current/bin/Roslyn/csc.exe");
            File mcs = new File(root, "lib/mono/4.5/mcs.exe");
            File compiler = csc.isFile() ? csc : mcs;

            File parent = output.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("Cannot create compiler output directory: " + parent);
            }

            ArrayList<String> command = new ArrayList<>();
            command.add(mono.getAbsolutePath());
            command.add(compiler.getAbsolutePath());
            if (compiler.equals(csc)) {
                command.add("/nologo");
                command.add("/target:library");
                command.add("/optimize+");
                command.add("/utf8output");
                command.add("/out:" + output.getAbsolutePath());
                if (assemblyName != null && !assemblyName.isEmpty()) {
                    command.add("/moduleassemblyname:" + assemblyName);
                }
                addReferences(command, referenceDirectories, "/reference:");
                command.add(source.getAbsolutePath());
            } else {
                command.add("-target:library");
                command.add("-optimize+");
                command.add("-out:" + output.getAbsolutePath());
                addReferences(command, referenceDirectories, "-r:");
                command.add(source.getAbsolutePath());
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(parent == null ? source.getParentFile() : parent);
            pb.redirectErrorStream(true);
            String libMono = new File(root, "lib/mono").getAbsolutePath();
            pb.environment().put("MONO_PATH", libMono);
            pb.environment().put("MONO_CFG_DIR", new File(root, "etc").getAbsolutePath());
            pb.environment().put("MONO_ENV_OPTIONS", "--gc=sgen");

            Process process = pb.start();
            String log = read(process);
            boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new Result(false, output, "C# compiler timed out after " + TIMEOUT_MS + " ms\n" + log);
            }
            int exit = process.exitValue();
            if (exit != 0 || !output.isFile() || output.length() == 0) {
                return new Result(false, output, "C# compiler exited with code " + exit + "\n" + log);
            }
            return new Result(true, output, log);
        } catch (Throwable t) {
            return new Result(false, output, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void addReferences(List<String> command, List<File> dirs, String prefix) {
        if (dirs == null) return;
        for (File dir : dirs) {
            if (dir == null || !dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".dll"));
            if (files == null) continue;
            for (File file : files) command.add(prefix + file.getAbsolutePath());
        }
    }

    private static String read(Process process) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
        }
        return out.toString();
    }

    /** Recursively copies a compiler runtime bundle from assets/managed_compiler. */
    public static boolean installFromAssets(Context context) {
        try {
            android.content.res.AssetManager assets = context.getAssets();
            String[] roots = assets.list("managed_compiler");
            if (roots == null || roots.length == 0) return false;
            copyAssetTree(assets, "managed_compiler", root(context));
            File mono = new File(root(context), "bin/mono");
            if (mono.isFile()) mono.setExecutable(true, true);
            return isInstalled(context);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void copyAssetTree(android.content.res.AssetManager assets,
                                      String assetPath, File out) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("mkdir failed");
            }
            try (InputStream in = assets.open(assetPath); FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) fos.write(buffer, 0, n);
            }
            return;
        }
        if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
            throw new IOException("mkdir failed: " + out);
        }
        for (String child : children) copyAssetTree(assets, assetPath + "/" + child, new File(out, child));
    }

    private static void installPinnedBundle(Context context) throws IOException {
        File root = root(context);
        File parent = root.getParentFile();
        if (parent == null) throw new IOException("Compiler storage unavailable");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create compiler storage");
        }

        File archive = new File(parent, ARCHIVE_NAME + ".download");
        File staging = new File(parent, ROOT + ".staging");
        deleteTree(staging);
        if (archive.exists() && archive.length() != EXPECTED_ARCHIVE_SIZE) archive.delete();
        if (!archive.isFile()) downloadArchive(archive);
        if (archive.length() != EXPECTED_ARCHIVE_SIZE) {
            throw new IOException("Downloaded compiler archive has unexpected size: " + archive.length());
        }

        if (!staging.mkdirs() && !staging.isDirectory()) throw new IOException("Cannot create compiler staging directory");
        extractArchive(archive, staging);
        File actual = locateCompilerRoot(staging);
        if (actual == null) throw new IOException("Mono ARM64 archive does not contain bin/mono and a C# compiler");
        File finalRoot = root;
        deleteTree(finalRoot);
        if (!actual.renameTo(finalRoot)) {
            copyTree(actual, finalRoot);
            deleteTree(staging);
        } else {
            // actual was moved out of staging; remove remaining staging tree.
            deleteTree(staging);
        }
        File mono = new File(finalRoot, "bin/mono");
        mono.setExecutable(true, true);
        if (!isInstalled(context)) throw new IOException("Installed Mono bundle failed validation");
        archive.delete();
    }

    private static void downloadArchive(File destination) throws IOException {
        HttpURLConnection c = null;
        File temp = new File(destination.getParentFile(), destination.getName() + ".part");
        if (temp.exists()) temp.delete();
        try {
            c = (HttpURLConnection) new URL(DOWNLOAD_URL).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "Untrusted-Manager/1.0");
            c.connect();
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Compiler download HTTP " + code);
            long length = c.getContentLengthLong();
            if (length > MAX_ARCHIVE_BYTES) throw new IOException("Compiler archive exceeds safety limit");
            if (length > 0 && length != EXPECTED_ARCHIVE_SIZE) {
                throw new IOException("Unexpected compiler archive size: " + length);
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_ARCHIVE_BYTES) throw new IOException("Compiler archive exceeds safety limit");
                    out.write(buffer, 0, n);
                }
                if (total != EXPECTED_ARCHIVE_SIZE) throw new IOException("Incomplete compiler download: " + total);
            }
            if (!temp.renameTo(destination)) throw new IOException("Cannot finalize compiler download");
        } finally {
            if (c != null) c.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    private static void extractArchive(File archive, File staging) throws IOException {
        try (InputStream file = new BufferedInputStream(new FileInputStream(archive));
             XZCompressorInputStream xz = new XZCompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = tar.getNextTarEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                while (name.startsWith("./")) name = name.substring(2);
                if (name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IOException("Unsafe compiler archive entry: " + name);
                }
                while (name.startsWith("local/")) name = name.substring("local/".length());
                while (name.startsWith("usr/local/")) name = name.substring("usr/local/".length());
                if (name.isEmpty()) continue;
                File out = new File(staging, name);
                String canonicalRoot = staging.getCanonicalPath() + File.separator;
                String canonicalOut = out.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalRoot)) throw new IOException("Unsafe compiler path: " + name);
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) throw new IOException("mkdir failed: " + out);
                } else if (entry.isFile()) {
                    File p = out.getParentFile();
                    if (p != null && !p.isDirectory() && !p.mkdirs() && !p.isDirectory()) throw new IOException("mkdir failed");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = tar.read(buffer)) != -1) fos.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    private static File locateCompilerRoot(File staging) {
        File direct = staging;
        if (hasCompiler(direct)) return direct;
        File[] children = staging.listFiles();
        if (children != null) for (File child : children) if (child.isDirectory() && hasCompiler(child)) return child;
        return null;
    }

    private static boolean hasCompiler(File root) {
        return new File(root, "bin/mono").isFile()
                && (new File(root, "lib/mono/4.5/mcs.exe").isFile()
                || new File(root, "lib/mono/msbuild/Current/bin/Roslyn/csc.exe").isFile());
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs() && !to.isDirectory()) throw new IOException("mkdir failed: " + to);
            File[] files = from.listFiles();
            if (files != null) for (File f : files) copyTree(f, new File(to, f.getName()));
        } else {
            File p = to.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs() && !p.isDirectory()) throw new IOException("mkdir failed");
            try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
