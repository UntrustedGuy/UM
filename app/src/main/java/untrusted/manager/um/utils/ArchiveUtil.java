package untrusted.manager.um.utils;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.List;
import java.util.Locale;

public class ArchiveUtil {

    private static final int BUFFER_SIZE = 8192;

    public static boolean isSupportedArchive(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".7z") || lower.endsWith(".rar") || lower.endsWith(".tar")
                || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2")
                || lower.endsWith(".tar.xz") || lower.endsWith(".txz")
                || lower.endsWith(".gz") || lower.endsWith(".bz2") || lower.endsWith(".xz");
    }

    public static String[] getSupportedCreateExts() {
        return new String[] { ".zip", ".7z", ".tar", ".tar.gz", ".tgz", ".tar.bz2", ".tbz2", ".tar.xz", ".txz", ".gz", ".bz2", ".xz" };
    }

    public static void extract(File archive, File destDir) throws IOException {
        extract(archive, destDir, false);
    }

    public static void extract(File archive, File destDir, boolean preserveTime) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("Archive file not found: " + archive);
        if (!destDir.exists() && !destDir.mkdirs() && !destDir.isDirectory()) throw new IOException("Cannot create destination: " + destDir);
        File canonicalDest = destDir.getCanonicalFile();
        String lower = archive.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) extractZip(archive, canonicalDest, preserveTime);
        else if (lower.endsWith(".7z")) extract7z(archive, canonicalDest, preserveTime);
        else if (lower.endsWith(".rar")) extractRar(archive, destDir, preserveTime);
        else if (lower.endsWith(".tar")) extractTar(new FileInputStream(archive), destDir, preserveTime);
        else if (lower.endsWith(".tgz")) extractTar(new GzipCompressorInputStream(new FileInputStream(archive), true), destDir, preserveTime);
        else if (lower.endsWith(".tar.gz")) extractTar(new GzipCompressorInputStream(new FileInputStream(archive), true), destDir, preserveTime);
        else if (lower.endsWith(".tbz2")) extractTar(new BZip2CompressorInputStream(new FileInputStream(archive), true), destDir, preserveTime);
        else if (lower.endsWith(".tar.bz2")) extractTar(new BZip2CompressorInputStream(new FileInputStream(archive), true), destDir, preserveTime);
        else if (lower.endsWith(".txz")) extractTar(new XZCompressorInputStream(new FileInputStream(archive), true), destDir, preserveTime);
        else if (lower.endsWith(".tar.xz")) extractTar(new XZCompressorInputStream(new FileInputStream(archive), true), destDir, preserveTime);
        else if (lower.endsWith(".gz")) extractSingleCompressed(new GzipCompressorInputStream(new FileInputStream(archive), true), destDir, archive.getName(), ".gz");
        else if (lower.endsWith(".bz2")) extractSingleCompressed(new BZip2CompressorInputStream(new FileInputStream(archive), true), destDir, archive.getName(), ".bz2");
        else if (lower.endsWith(".xz")) extractSingleCompressed(new XZCompressorInputStream(new FileInputStream(archive), true), destDir, archive.getName(), ".xz");
        else throw new IOException("Unsupported archive format: " + archive.getName());
    }

    public static void create(File output, List<File> sources) throws IOException {
        if (output == null) throw new IOException("Output is required");
        if (sources == null || sources.isEmpty()) throw new IOException("At least one source is required");
        File outCanonical = output.getCanonicalFile();
        File parent = outCanonical.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create output directory");
        rejectSymlinkAncestors(parent);
        for (File source : sources) {
            if (source == null || !source.exists()) throw new IOException("Source not found: " + source);
            rejectSourceTree(source);
            if (source.getCanonicalFile().equals(outCanonical)) throw new IOException("Output cannot be one of the input sources");
        }
        String lower = output.getName().toLowerCase(Locale.ROOT);
        File temp = File.createTempFile(".um-archive-", ".tmp", parent);
        boolean committed = false;
        File backup = new File(outCanonical.getAbsolutePath() + ".bak");
        try {
            if (lower.endsWith(".zip")) createZip(temp, sources);
            else if (lower.endsWith(".7z")) create7z(temp, sources);
            else if (lower.endsWith(".tar")) createTar(new FileOutputStream(temp), sources);
            else if (lower.endsWith(".tgz")) createTar(new GzipCompressorOutputStream(new FileOutputStream(temp)), sources);
            else if (lower.endsWith(".tar.gz")) createTar(new GzipCompressorOutputStream(new FileOutputStream(temp)), sources);
            else if (lower.endsWith(".tbz2")) createTar(new BZip2CompressorOutputStream(new FileOutputStream(temp)), sources);
            else if (lower.endsWith(".tar.bz2")) createTar(new BZip2CompressorOutputStream(new FileOutputStream(temp)), sources);
            else if (lower.endsWith(".txz")) createTar(new XZCompressorOutputStream(new FileOutputStream(temp)), sources);
            else if (lower.endsWith(".tar.xz")) createTar(new XZCompressorOutputStream(new FileOutputStream(temp)), sources);
            else if (lower.endsWith(".gz") && sources.size() == 1) compressSingle(new GzipCompressorOutputStream(new FileOutputStream(temp)), sources.get(0));
            else if (lower.endsWith(".bz2") && sources.size() == 1) compressSingle(new BZip2CompressorOutputStream(new FileOutputStream(temp)), sources.get(0));
            else if (lower.endsWith(".xz") && sources.size() == 1) compressSingle(new XZCompressorOutputStream(new FileOutputStream(temp)), sources.get(0));
            else throw new IOException("Unsupported archive format: " + output.getName());
            if (!temp.isFile() || temp.length() <= 0) throw new IOException("Archive creation produced no output");
            if (outCanonical.exists()) {
                if (backup.exists() && !backup.delete()) throw new IOException("Cannot replace previous archive backup: " + backup);
                if (!outCanonical.renameTo(backup)) throw new IOException("Cannot back up existing archive: " + outCanonical);
            }
            if (!temp.renameTo(outCanonical)) {
                if (backup.exists()) backup.renameTo(outCanonical);
                throw new IOException("Cannot commit archive: " + outCanonical);
            }
            committed = true;
            if (backup.exists()) backup.delete();
        } finally {
            if (!committed && temp.exists()) temp.delete();
            if (!committed && backup.exists() && !outCanonical.exists()) backup.renameTo(outCanonical);
        }
    }

    private static void extractZip(File archive, File destDir, boolean preserveTime) throws IOException {
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = sanitizeEntryName(entry.getName());
                if (!seen.add(name)) throw new IOException("Duplicate archive entry: " + name);
                File out = safeDestination(destDir, name);
                ensureExtractionTarget(out, entry.isDirectory());
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) throw new IOException("Cannot create directory: " + out);
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create parent: " + parent);
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) { copy(zin, os); }
                }
                if (preserveTime && entry.getTime() > 0) out.setLastModified(entry.getTime());
            }
        }
    }

    private static void createZip(File output, List<File> sources) throws IOException {
        try (ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            Set<String> names = new HashSet<>();
            for (File source : sources) addToZip(zout, source, source.getName() + (source.isDirectory() ? "/" : ""), names);
        }
    }

    private static void addToZip(ZipOutputStream zout, File file, String entryName, Set<String> names) throws IOException {
        rejectSourceSymlink(file);
        String name = sanitizeEntryName(entryName);
        if (file.isDirectory()) name += "/";
        if (!names.add(name)) throw new IOException("Duplicate archive entry: " + name);
        ZipEntry entry = new ZipEntry(name);
        if (file.lastModified() > 0) entry.setTime(file.lastModified());
        zout.putNextEntry(entry);
        if (!file.isDirectory()) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { copy(in, zout); }
        }
        zout.closeEntry();
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) addToZip(zout, child, name + child.getName() + (child.isDirectory() ? "/" : ""), names);
        }
    }

    private static void extract7z(File archive, File destDir, boolean preserveTime) throws IOException {
        try (SevenZFile sevenZFile = new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                String name = sanitizeEntryName(entry.getName());
                File out = safeDestination(destDir, name);
                ensureExtractionTarget(out, entry.isDirectory());
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) throw new IOException("Cannot create directory: " + out);
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create parent: " + parent);
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        copy(sevenZFile.getInputStream(entry), os);
                    }
                    if (preserveTime && entry.getLastModifiedDate() != null) {
                        //noinspection ResultOfMethodCallIgnored
                        out.setLastModified(entry.getLastModifiedDate().getTime());
                    }
                }
            }
        }
    }

    private static void extractRar(File archive, File destDir, boolean preserveTime) throws IOException {
        try (Archive rar = new Archive(archive)) {
            FileHeader fh;
            while ((fh = rar.nextFileHeader()) != null) {
                String name = sanitizeEntryName(fh.getFileName());
                File out = safeDestination(destDir, name);
                ensureExtractionTarget(out, fh.isDirectory());
                if (fh.isDirectory()) { if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) throw new IOException("Cannot create directory: " + out); }
                else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create parent: " + parent);
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        rar.extractFile(fh, os);
                    }
                    if (preserveTime && fh.getMTime() != null) {
                        //noinspection ResultOfMethodCallIgnored
                        out.setLastModified(fh.getMTime().getTime());
                    }
                }
            }
        } catch (RarException e) {
            throw new IOException("Failed to extract RAR archive", e);
        }
    }

    private static void extractTar(InputStream tarInput, File destDir, boolean preserveTime) throws IOException {
        try (TarArchiveInputStream tais = new TarArchiveInputStream(tarInput)) {
            TarArchiveEntry entry;
            while ((entry = tais.getNextTarEntry()) != null) {
                if (entry.isSymbolicLink() || entry.isLink()) throw new IOException("Symbolic/hard links are not extracted for safety: " + entry.getName());
                String name = sanitizeEntryName(entry.getName());
                File out = safeDestination(destDir, name);
                ensureExtractionTarget(out, entry.isDirectory());
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) throw new IOException("Cannot create directory: " + out);
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create parent: " + parent);
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        copy(tais, os);
                    }
                    if (preserveTime && entry.getLastModifiedDate() != null) {
                        //noinspection ResultOfMethodCallIgnored
                        out.setLastModified(entry.getLastModifiedDate().getTime());
                    }
                }
            }
        }
    }

    private static void extractSingleCompressed(InputStream compressedInput, File destDir, String archiveName, String ext) throws IOException {
        try (InputStream is = compressedInput) {
            String base = archiveName.substring(0, archiveName.length() - ext.length());
            File out = safeDestination(destDir, sanitizeEntryName(base));
            ensureExtractionTarget(out, false);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("Cannot create parent: " + parent);
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                copy(is, os);
            }
        }
    }

    private static void compressSingle(OutputStream compressedOutput, File source) throws IOException {
        try (OutputStream os = new BufferedOutputStream(compressedOutput)) {
            try (InputStream is = new BufferedInputStream(new FileInputStream(source))) {
                copy(is, os);
            }
        }
    }

    private static void create7z(File output, List<File> sources) throws IOException {
        try (SevenZOutputFile sevenZOutput = new SevenZOutputFile(output)) {
            sevenZOutput.setContentCompression(SevenZMethod.LZMA2);
            for (File source : sources) addToSevenZ(sevenZOutput, source, source.isDirectory() ? source.getName() + "/" : source.getName());
        }
    }

    private static void addToSevenZ(SevenZOutputFile sevenZOutput, File file, String entryName) throws IOException {
        rejectSourceSymlink(file);
        SevenZArchiveEntry entry = sevenZOutput.createArchiveEntry(file, entryName);
        sevenZOutput.putArchiveEntry(entry);
        if (file.isDirectory()) {
            sevenZOutput.closeArchiveEntry();
            File[] children = file.listFiles();
            if (children != null) for (File child : children) addToSevenZ(sevenZOutput, child, entryName + child.getName() + (child.isDirectory() ? "/" : ""));
        } else {
            try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                copySevenZ(is, sevenZOutput);
            }
            sevenZOutput.closeArchiveEntry();
        }
    }

    private static void copySevenZ(InputStream is, SevenZOutputFile os) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int length;
        while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
    }

    private static void createTar(OutputStream tarOutput, List<File> sources) throws IOException {
        try (TarArchiveOutputStream taos = new TarArchiveOutputStream(tarOutput)) {
            taos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
            taos.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            taos.setAddPaxHeadersForNonAsciiNames(true);
            for (File source : sources) addToTar(taos, source, source.isDirectory() ? source.getName() + "/" : source.getName());
        }
    }

    private static void addToTar(TarArchiveOutputStream taos, File file, String entryName) throws IOException {
        rejectSourceSymlink(file);
        TarArchiveEntry entry = new TarArchiveEntry(file, entryName);
        taos.putArchiveEntry(entry);
        if (file.isDirectory()) {
            taos.closeArchiveEntry();
            File[] children = file.listFiles();
            if (children != null) for (File child : children) addToTar(taos, child, entryName + child.getName() + (child.isDirectory() ? "/" : ""));
        } else {
            try (InputStream is = new BufferedInputStream(new FileInputStream(file))) {
                copy(is, taos);
            }
            taos.closeArchiveEntry();
        }
    }

    private static void ensureExtractionTarget(File out, boolean directory) throws IOException {
        if (out.exists() || java.nio.file.Files.isSymbolicLink(out.toPath())) {
            if (directory && out.isDirectory() && !java.nio.file.Files.isSymbolicLink(out.toPath())) return;
            throw new IOException("Extraction target already exists: " + out);
        }
    }

    private static File safeDestination(File destDir, String name) throws IOException {
        File base = destDir.getCanonicalFile();
        rejectSymlinkAncestors(base);
        File raw = new File(base, name);
        File parent = raw.getParentFile();
        if (parent != null) rejectSymlinkAncestors(parent);
        File out = raw.getCanonicalFile();
        String bp = base.getPath();
        if (!out.getPath().equals(bp) && !out.getPath().startsWith(bp + File.separator)) {
            throw new IOException("Archive entry escapes destination: " + name);
        }
        return out;
    }

    private static void rejectSymlinkAncestors(File path) throws IOException {
        File current = path;
        while (current != null) {
            if (java.nio.file.Files.isSymbolicLink(current.toPath())) {
                throw new IOException("Archive destination contains a symbolic-link component: " + current);
            }
            current = current.getParentFile();
        }
    }

    private static void rejectSourceSymlink(File file) throws IOException {
        if (file == null) throw new IOException("Null archive source");
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            throw new IOException("Symbolic-link sources are not archived: " + file);
        }
    }

    private static void rejectSourceTree(File file) throws IOException {
        rejectSourceSymlink(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot read archive source directory: " + file);
            for (File child : children) rejectSourceTree(child);
        }
    }

    private static String sanitizeEntryName(String entryName) throws IOException {
        if (entryName == null || entryName.isEmpty()) throw new IOException("Archive contains an empty entry name");
        String cleaned = entryName.replace('\\', '/');
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        String[] parts = cleaned.split("/");
        java.util.ArrayDeque<String> safe = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (safe.isEmpty()) throw new IOException("Unsafe archive entry: " + entryName);
                safe.removeLast();
            } else if (part.indexOf('\0') >= 0) {
                throw new IOException("Unsafe archive entry: " + entryName);
            } else {
                safe.addLast(part);
            }
        }
        if (safe.isEmpty()) throw new IOException("Unsafe archive entry: " + entryName);
        return String.join("/", safe);
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int length;
        while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
    }
}
