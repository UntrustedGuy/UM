package untrusted.manager.um.gameanalysis;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.FileWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline Unity IL2CPP analysis engine.
 *
 * The implementation deliberately follows the public IL2CPP metadata layout used by
 * open-source dumpers, while keeping UM self-contained. It does not inject into a
 * process, defeat anti-cheat/anti-debugging, or claim to decrypt arbitrary schemes.
 */
public final class GameAnalysisEngine {
    private static final int METADATA_MAGIC = 0xFAB11BAF;
    private static final long MAX_ARCHIVE_ENTRIES = 100_000;
    private static final long MAX_EXTRACT_BYTES = 1024L * 1024L * 1024L;
    private static final int MAX_STRINGS = 250_000;
    private static final long MAX_TABLE_READ_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_TYPES = 1_000_000;
    private static final int MAX_METHODS = 2_000_000;
    private static final int MAX_FIELDS = 2_000_000;

    private GameAnalysisEngine() {}

    public record Result(String report, File output) {}

    public static File dumpRoot() {
        return new File(new File(android.os.Environment.getExternalStorageDirectory(), "Untrusted Manager"), "Dump");
    }

    public static Result analyze(Context context, File input, File companion) throws IOException {
        if (input == null || !input.isFile()) throw new IOException("Input file does not exist");
        File root = newDumpDirectory();
        File work = new File(context.getCacheDir(), "game-analysis-" + System.nanoTime());
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Cannot create analysis workspace");
        try {
            AnalysisState state = new AnalysisState(root);
            state.source = input;
            state.companion = companion;

            List<Artifact> artifacts = new ArrayList<>();
            artifacts.addAll(scanInput(input, work, state));
            if (companion != null && companion.isFile()) artifacts.addAll(scanCompanion(companion, work, state));

            List<File> metadataFiles = new ArrayList<>();
            List<File> nativeFiles = new ArrayList<>();
            for (Artifact a : artifacts) {
                if (a.kind == Kind.METADATA) metadataFiles.add(a.file);
                if (a.kind == Kind.IL2CPP) nativeFiles.add(a.file);
            }

            File bestMetadata = firstValidMetadata(metadataFiles);
            if (bestMetadata != null) {
                state.metadataResult = parseMetadata(bestMetadata, root);
            } else if (!metadataFiles.isEmpty()) {
                File recovered = recoverSimpleMetadataRepresentation(metadataFiles.get(0), new File(work, "metadata-recovery"));
                if (recovered != null) {
                    MetadataResult mr = parseMetadata(recovered, root);
                    state.metadataResult = new MetadataResult(mr.valid, false, mr.version, mr.header, mr.stats, recovered,
                            "Recovered a standard metadata representation from an envelope/simple transform; original input was preserved");
                } else {
                    state.metadataResult = inspectProtectedMetadata(metadataFiles.get(0), root);
                }
            }

            if (!nativeFiles.isEmpty()) {
                state.nativeResults = new ArrayList<>();
                for (int ni = 0; ni < nativeFiles.size(); ni++) {
                    File nativeFile = nativeFiles.get(ni);
                    int methodCount = state.metadataResult != null && state.metadataResult.stats != null ? state.metadataResult.stats.methods : 0;
                    int typeCount = state.metadataResult != null && state.metadataResult.stats != null ? state.metadataResult.stats.types : 0;
                    int imageCount = state.metadataResult != null && state.metadataResult.stats != null ? state.metadataResult.stats.images : 0;
                    int metadataVersion = state.metadataResult != null ? state.metadataResult.version : -1;
                    File nativeWork = new File(work, "native_" + ni);
                    ElfResult er = analyzeElf(nativeFile, nativeWork, methodCount, typeCount, imageCount, metadataVersion,
                            state.metadataResult != null && state.metadataResult.header != null ? state.metadataResult.header.layoutVersion() : metadataVersion,
                            state.metadataResult != null ? state.metadataResult.file : null);
                    flattenNativeArtifacts(nativeWork, root, "native_" + ni + "_");
                    state.nativeResults.add(er);
                }
            }

            writePairReports(root, state, metadataFiles, nativeFiles);
            writeEncryptionReport(root, state, artifacts);
            String report = writeAnalysisReport(root, state, artifacts);
            return new Result(report, root);
        } finally {
            deleteRecursive(work);
        }
    }

    /** IL2CPP Dumper entry point: libil2cpp.so and global-metadata.dat are mandatory; APK is optional context. */
    public static Result analyzeIl2CppPair(Context context, File lib, File metadata, File apkOptional) throws IOException {
        if (lib == null || !lib.isFile()) throw new IOException("libil2cpp.so is required");
        if (metadata == null || !metadata.isFile()) throw new IOException("global-metadata.dat is required");
        File root = newDumpDirectory();
        File work = new File(context.getCacheDir(), "game-analysis-pair-" + System.nanoTime());
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Cannot create analysis workspace");
        try {
            File libCopy = copyIfNeeded(lib, work, "libil2cpp.so");
            File metaCopy = copyIfNeeded(metadata, work, "global-metadata.dat");
            AnalysisState state = new AnalysisState(root); state.source = libCopy; state.companion = metaCopy;
            List<File> metadataFiles = List.of(metaCopy), nativeFiles = List.of(libCopy);
            state.metadataResult = parseMetadata(metaCopy, root);
            int methodCount = state.metadataResult.stats != null ? state.metadataResult.stats.methods : 0;
            int typeCount = state.metadataResult.stats != null ? state.metadataResult.stats.types : 0;
            int imageCount = state.metadataResult.stats != null ? state.metadataResult.stats.images : 0;
            int metadataVersion = state.metadataResult.version;
            state.nativeResults = new ArrayList<>();
            File nativeWork = new File(work, "native_libil2cpp");
            ElfResult er = analyzeElf(libCopy, nativeWork, methodCount, typeCount, imageCount, metadataVersion,
                    state.metadataResult.header != null ? state.metadataResult.header.layoutVersion() : metadataVersion, metaCopy);
            flattenNativeArtifacts(nativeWork, root, "libil2cpp_");
            state.nativeResults.add(er);
            if (apkOptional != null && apkOptional.isFile()) {
                writeText(new File(root, "apk_context.txt"), "Optional APK/game file: " + apkOptional.getAbsolutePath() + "\nSize: " + apkOptional.length() + " bytes\nSHA-256: " + sha256(apkOptional) + "\n");
            }
            writePairReports(root, state, metadataFiles, nativeFiles);
            String report = writeAnalysisReport(root, state, List.of(
                    new Artifact(libCopy, Kind.IL2CPP, "required:libil2cpp.so"),
                    new Artifact(metaCopy, Kind.METADATA, "required:global-metadata.dat")));
            return new Result(report, root);
        } finally {
            deleteRecursive(work);
        }
    }

    private static void flattenNativeArtifacts(File dir, File root, String prefix) throws IOException {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) continue;
            File target = new File(root, prefix + f.getName());
            if (!target.equals(f)) {
                if (!f.renameTo(target)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(target)) {
                        byte[] b = new byte[64 * 1024]; int n; while ((n = in.read(b)) != -1) out.write(b,0,n);
                    }
                    f.delete();
                }
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static List<Artifact> scanInput(File input, File work, AnalysisState state) throws IOException {
        String n = input.getName().toLowerCase(Locale.ENGLISH);
        if (isMetadataName(n) || looksLikeMetadataCandidate(input)) return List.of(new Artifact(copyIfNeeded(input, work, "global-metadata.dat"), Kind.METADATA, "direct"));
        if (isIl2CppName(n) || looksLikeIl2CppNative(input)) return List.of(new Artifact(copyIfNeeded(input, work, "libil2cpp.so"), Kind.IL2CPP, "direct"));
        if (isArchiveName(n)) return scanArchive(input, work, state);
        return List.of(new Artifact(copyIfNeeded(input, work, safeName(input.getName())), Kind.OTHER, "direct"));
    }

    private static List<Artifact> scanCompanion(File input, File work, AnalysisState state) throws IOException {
        String n = input.getName().toLowerCase(Locale.ENGLISH);
        if (isMetadataName(n) || looksLikeMetadataCandidate(input)) return List.of(new Artifact(copyIfNeeded(input, work, "companion_global-metadata.dat"), Kind.METADATA, "companion"));
        if (isIl2CppName(n) || looksLikeIl2CppNative(input)) return List.of(new Artifact(copyIfNeeded(input, work, "companion_libil2cpp.so"), Kind.IL2CPP, "companion"));
        if (isArchiveName(n)) return scanArchive(input, work, state);
        return List.of(new Artifact(copyIfNeeded(input, work, safeName(input.getName())), Kind.OTHER, "companion"));
    }

    private static boolean looksLikeMetadataCandidate(File f) {
        try {
            byte[] b = readAtMost(f, 16);
            if (b.length >= 8 && u32(b, 0) == METADATA_MAGIC) {
                int v = u32(b, 4);
                return v >= 16 && v <= 31;
            }
            return detectSimpleXor(f) != null;
        } catch (Exception ignored) { return false; }
    }

    private static boolean looksLikeElf(File f) {
        try {
            byte[] b = readAtMost(f, 4);
            return b.length == 4 && b[0] == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
        } catch (Exception ignored) { return false; }
    }

    private static List<Artifact> scanArchive(File archive, File work, AnalysisState state) throws IOException {
        List<Artifact> result = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            int count = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (++count > MAX_ARCHIVE_ENTRIES) throw new IOException("Archive contains too many entries");
                if (e.isDirectory()) continue;
                String path = e.getName();
                String lower = path.toLowerCase(Locale.ENGLISH);
                boolean metadataEntry = isMetadataName(lower) || entryLooksLikeMetadata(zip, e);
                boolean il2cppEntry = entryLooksLikeIl2CppNative(zip, e);
                if (metadataEntry || il2cppEntry) {
                    String outName = (metadataEntry ? "metadata_" : "il2cpp_") + (++state.sequence) + "_" + safeName(new File(path).getName());
                    File out = safeChild(work, outName);
                    long bytes = extractEntry(zip, e, out, MAX_EXTRACT_BYTES - state.extractedBytes);
                    state.extractedBytes += bytes;
                    result.add(new Artifact(out, metadataEntry ? Kind.METADATA : Kind.IL2CPP, path));
                } else if (isArchiveName(lower) && lower.endsWith(".apk") && state.nestedApkCount < 16) {
                    state.nestedApkCount++;
                    File nested = safeChild(work, "nested_" + (++state.sequence) + ".apk");
                    long bytes = extractEntry(zip, e, nested, MAX_EXTRACT_BYTES - state.extractedBytes);
                    state.extractedBytes += bytes;
                    result.addAll(scanArchive(nested, work, state));
                }
            }
        }
        return result;
    }

    private static boolean looksLikeIl2CppNative(File file) {
        if (file == null || !file.isFile()) return false;
        String n = file.getName().toLowerCase(Locale.ENGLISH);
        if (isIl2CppName(n)) return true;
        if (!looksLikeElf(file)) return false;
        try {
            byte[] sample = readAtMost(file, (int)Math.min(file.length(), 1024L * 1024L));
            int hits = 0;
            if (containsAscii(sample, "il2cpp")) hits++;
            if (containsAscii(sample, "g_CodeRegistration")) hits++;
            if (containsAscii(sample, "g_MetadataRegistration")) hits++;
            if (containsAscii(sample, "il2cpp_init")) hits++;
            return hits >= 2;
        } catch (Exception ignored) { return false; }
    }

    private static boolean entryLooksLikeIl2CppNative(ZipFile zip, ZipEntry e) {
        String lower = e.getName().toLowerCase(Locale.ENGLISH);
        if (isIl2CppName(lower)) return true;
        if (!lower.endsWith(".so") && !lower.endsWith(".dll")) return false;
        try (InputStream in = zip.getInputStream(e)) {
            long size = e.getSize();
            int want = (int)Math.min(size > 0 ? size : 1024L * 1024L, 1024L * 1024L);
            byte[] sample = new byte[Math.max(4096, want)];
            int pos = 0, n;
            while (pos < sample.length && (n = in.read(sample, pos, sample.length - pos)) > 0) pos += n;
            if (pos < 4) return false;
            boolean elf = sample[0] == 0x7f && sample[1] == 'E' && sample[2] == 'L' && sample[3] == 'F';
            int hits = 0;
            if (containsAscii(sample, "il2cpp")) hits++;
            if (containsAscii(sample, "g_CodeRegistration")) hits++;
            if (containsAscii(sample, "g_MetadataRegistration")) hits++;
            if (containsAscii(sample, "il2cpp_init")) hits++;
            return elf && hits >= 2;
        } catch (Exception ignored) { return false; }
    }

    private static boolean containsAscii(byte[] data, String needle) {
        if (data == null || needle == null || needle.isEmpty()) return false;
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        outer: for (int i = 0; i + n.length <= data.length; i++) {
            for (int j = 0; j < n.length; j++) if (data[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }

    private static boolean entryLooksLikeMetadata(ZipFile zip, ZipEntry e) {
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = new byte[32]; int n = in.read(b);
            if (n >= 8 && u32(b, 0) == METADATA_MAGIC) { int v = u32(b, 4); return v >= 16 && v <= 31; }
            if (n >= 8) {
                for (int key=1; key<=255; key++) {
                    if (((b[0]&255)^key)!=(METADATA_MAGIC&255)) continue;
                    int v=((b[4]&255)^key)|(((b[5]&255)^key)<<8)|(((b[6]&255)^key)<<16)|(((b[7]&255)^key)<<24);
                    if(v>=16&&v<=31)return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean entryLooksLikeElf(ZipFile zip, ZipEntry e) {
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = new byte[4]; int n=in.read(b);
            return n==4 && b[0]==0x7f && b[1]=='E' && b[2]=='L' && b[3]=='F';
        } catch (Exception ignored) { return false; }
    }

    private static long extractEntry(ZipFile zip, ZipEntry e, File out, long remaining) throws IOException {
        long declared = e.getSize();
        if (declared > remaining) throw new IOException("Archive extraction limit exceeded");
        long total = 0;
        try (InputStream in = new BufferedInputStream(zip.getInputStream(e)); FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > remaining) throw new IOException("Archive extraction limit exceeded");
                fos.write(buf, 0, n);
            }
        }
        return total;
    }

    private static File firstValidMetadata(List<File> files) {
        for (File f : files) {
            try {
                if (readMetadataHeader(f) != null) return f;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static MetadataHeader readMetadataHeader(File file) throws IOException {
        if (file.length() < 8) return null;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            int magic = readIntLE(raf);
            int version = readIntLE(raf);
            if (magic != METADATA_MAGIC || version < 16 || version > 31) return null;
            MetadataHeader h = new MetadataHeader(magic, version);
            try {
                h.read(raf, file.length());
            } catch (EOFException malformed) {
                return null;
            }
            return h.isStructurallyValid(file.length()) ? h : null;
        }
    }

    private static MetadataResult parseMetadata(File file, File outDir) throws IOException {
        mkdir(outDir);
        MetadataHeader h;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            h = readMetadataHeader(file);
            if (h == null) throw new IOException("Invalid IL2CPP metadata");
            writeHeader(outDir, h, file.length());
            writeLayout(outDir, h);

            StringTable strings = readStringTable(raf, h.stringOffset, h.stringSize);
            writeStrings(outDir, strings);
            writeStringLiteralJson(outDir, raf, h);

            List<ImageDef> images = readImages(raf, h);
            List<TypeDef> types = readTypes(raf, h);
            List<MethodDef> methods = readMethods(raf, h);
            List<FieldDef> fields = readFields(raf, h);
            List<PropertyDef> properties = readProperties(raf, h);
            List<EventDef> events = readEvents(raf, h);

            DumpStats stats = new DumpStats(images.size(), types.size(), methods.size(), fields.size(), properties.size(), events.size());
            writeDumpCs(outDir, h, strings, images, types, methods, fields, properties, events);
            try { DummyDllWriter.writeAll(outDir, images, types, methods, fields, strings); } catch (IOException e) { writeText(new File(outDir, "dummy_dll_report.txt"), "DummyDll generation was not emitted: " + e.getMessage() + "\n"); }
            writeTablesSummary(outDir, h, stats);
            return new MetadataResult(true, false, h.version, h, stats, file, "Standard IL2CPP metadata parsed");
        }
    }

    private static File recoverSimpleMetadataRepresentation(File file, File outDir) throws IOException {
        mkdir(outDir);
        // 1) Common enveloped representation: a standard metadata header begins later in the file.
        int offset = findMagic(file, 16 * 1024 * 1024);
        if (offset > 0) {
            File out = new File(outDir, "metadata_from_envelope.dat");
            try (RandomAccessFile in = new RandomAccessFile(file, "r"); FileOutputStream fos = new FileOutputStream(out)) {
                in.seek(offset);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            if (readMetadataHeader(out) != null) return out;
            out.delete();
        }

        byte[] head = readAtMost(file, 64);
        if (head.length < 8) return null;

        // 2) Simple single-byte XOR, a transformation seen in lightweight protectors.
        for (int key = 1; key <= 255; key++) {
            if (((head[0] & 0xff) ^ key) != (METADATA_MAGIC & 0xff)) continue;
            byte[] decodedHead = xorSingle(head, key);
            int version = u32(decodedHead, 4);
            if (version < 16 || version > 31) continue;
            File out = new File(outDir, String.format(Locale.US, "metadata_xor8_%02X.dat", key));
            transformXorSingle(file, out, key);
            if (readMetadataHeader(out) != null) return out;
            out.delete();
        }

        // 3) Repeating 32-bit XOR, inferred directly from the known metadata magic.
        int key = u32(head, 0) ^ METADATA_MAGIC;
        byte[] decoded = new byte[Math.min(head.length, 64)];
        for (int i = 0; i + 4 <= decoded.length; i += 4) putU32(decoded, i, u32(head, i) ^ key);
        int version = decoded.length >= 8 ? u32(decoded, 4) : -1;
        if (version >= 16 && version <= 31) {
            File out = new File(outDir, String.format(Locale.US, "metadata_xor32_%08X.dat", key));
            transformXor32(file, out, key);
            if (readMetadataHeader(out) != null) return out;
            out.delete();
        }
        return null;
    }

    private static byte[] xorSingle(byte[] data, int key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) ((data[i] & 0xff) ^ key);
        return out;
    }

    private static void transformXorSingle(File inFile, File outFile, int key) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inFile)); FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] b = new byte[64 * 1024]; int n;
            while ((n = in.read(b)) != -1) { for (int i=0;i<n;i++) b[i]=(byte)((b[i]&0xff)^key); out.write(b,0,n); }
        }
    }

    private static void transformXor32(File inFile, File outFile, int key) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inFile)); FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] b = new byte[64 * 1024]; int n, carry=0;
            while ((n = in.read(b, carry, b.length-carry)) != -1) {
                int total=carry+n, whole=total-total%4;
                for(int i=0;i<whole;i+=4) putU32(b,i,u32(b,i)^key);
                out.write(b,0,whole);
                carry=total-whole;
                if(carry>0) System.arraycopy(b,whole,b,0,carry);
            }
            if(carry>0) out.write(b,0,carry);
        }
    }

    private static MetadataResult inspectProtectedMetadata(File file, File outDir) throws IOException {
        mkdir(outDir);
        long length = file.length();
        byte[] head = readAtMost(file, (int) Math.min(4096, length));
        int directMagic = head.length >= 4 ? u32(head, 0) : 0;
        int magicOffset = findMagic(file, 16 * 1024 * 1024);
        String xor = detectSimpleXor(file);
        double entropy = shannonEntropy(readAtMost(file, (int) Math.min(1024 * 1024, length)));
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(outDir, "metadata_header.txt")))) {
            w.write("Standard magic at offset 0: " + (directMagic == METADATA_MAGIC)); w.newLine();
            w.write("First standard magic offset: " + magicOffset); w.newLine();
            w.write("Simple XOR transform candidate: " + (xor == null ? "none" : xor)); w.newLine();
            w.write(String.format(Locale.US, "Sample entropy: %.4f bits/byte", entropy)); w.newLine();
            w.write("Status: non-standard, encrypted, transformed or packed representation; no fabricated dump was generated."); w.newLine();
        }
        writeRawStrings(outDir, head);
        return new MetadataResult(false, true, -1, null, new DumpStats(0,0,0,0,0,0), file,
                "Metadata is not a standard v16-v31 file at offset 0");
    }

    private static void writeRawStrings(File outDir, byte[] data) throws IOException {
        File f = new File(outDir, "metadata_raw_strings.txt");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(f))) {
            int start = -1;
            for (int i = 0; i <= data.length; i++) {
                int b = i < data.length ? data[i] & 0xff : 0;
                boolean printable = b >= 0x20 && b <= 0x7e;
                if (printable && start < 0) start = i;
                if ((!printable || i == data.length) && start >= 0) {
                    int len = i - start;
                    if (len >= 4) w.write(new String(data, start, len, StandardCharsets.UTF_8).replaceAll("[\\r\\n]", " "));
                    w.newLine();
                    start = -1;
                }
            }
        }
    }

    private static int findMagic(File file, int maxScan) throws IOException {
        long limit = Math.min(file.length(), maxScan);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] b = new byte[64 * 1024];
            long base = 0;
            int carry = 0;
            byte[] scan = new byte[b.length + 3];
            while (base < limit) {
                int n = raf.read(b);
                if (n <= 0) break;
                System.arraycopy(scan, Math.max(0, scan.length - carry), scan, 0, carry);
                System.arraycopy(b, 0, scan, carry, n);
                int total = carry + n;
                for (int i = 0; i + 4 <= total; i++) {
                    if (u32(scan, i) == METADATA_MAGIC) return (int) (base - carry + i);
                }
                carry = Math.min(3, total);
                System.arraycopy(scan, total - carry, scan, 0, carry);
                base += n;
                if (base >= limit) break;
                long remaining = limit - base;
                if (remaining < b.length) b = new byte[(int) remaining];
            }
        }
        return -1;
    }

    private static String detectSimpleXor(File file) throws IOException {
        if (file.length() < 16) return null;
        byte[] b = readAtMost(file, 32);
        for (int key = 1; key <= 255; key++) {
            if (((b[0] & 0xff) ^ key) != (METADATA_MAGIC & 0xff)) continue;
            byte[] d = new byte[Math.min(32, b.length)];
            for (int i = 0; i < d.length; i++) d[i] = (byte) ((b[i] & 0xff) ^ key);
            int version = u32(d, 4);
            if (version >= 16 && version <= 31) return String.format(Locale.US, "single-byte XOR 0x%02X", key);
        }
        if (b.length >= 8) {
            int key = u32(b, 0) ^ METADATA_MAGIC;
            if (key == 0) return null;
            byte[] d = new byte[Math.min(64, b.length)];
            for (int i = 0; i + 4 <= d.length; i += 4) {
                int x = u32(b, i) ^ key;
                putU32(d, i, x);
            }
            int version = u32(d, 4);
            if (version >= 16 && version <= 31) return String.format(Locale.US, "repeating 32-bit XOR key 0x%08X", key);
        }
        return null;
    }

    private static StringTable readStringTable(RandomAccessFile raf, long offset, long size) throws IOException {
        if (size <= 0) return new StringTable();
        if (size > MAX_TABLE_READ_BYTES || size > Integer.MAX_VALUE) throw new IOException("String table is too large for safe in-memory analysis");
        if (offset < 0 || offset > raf.length() || size > raf.length() - offset) throw new IOException("String table is outside the metadata file");
        raf.seek(offset);
        byte[] data = new byte[(int) size];
        raf.readFully(data);
        StringTable result = new StringTable();
        int start = 0;
        for (int pos = 0; pos <= data.length && result.size() < MAX_STRINGS; pos++) {
            if (pos == data.length || data[pos] == 0) {
                if (pos > start) {
                    String value = new String(data, start, pos - start, StandardCharsets.UTF_8);
                    result.put(start, value);
                }
                start = pos + 1;
            }
        }
        return result;
    }

    private static List<ImageDef> readImages(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = h.version >= 24 ? 40 : (h.version >= 19 ? 24 : 20);
        return readRecords(raf, h.imagesOffset, h.imagesSize, size, (buf, p) -> new ImageDef(
                u32(buf,p), i32(buf,p+4), i32(buf,p+8), u32(buf,p+12),
                i32(buf,p+16), h.version >= 19 ? u32(buf,p+20) : 0,
                h.version >= 24 ? i32(buf,p+24) : 0, h.version >= 24 ? u32(buf,p+28) : 0,
                h.version >= 24 ? i32(buf,p+32) : 0, h.version >= 24 ? u32(buf,p+36) : 0));
    }

    private static List<TypeDef> readTypes(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = typeDefSize(h.layoutVersion());
        return readRecords(raf, h.typeDefinitionsOffset, h.typeDefinitionsSize, size, (buf,p) -> {
            TypeDef t = new TypeDef();
            t.nameIndex = u32(buf,p); t.namespaceIndex = u32(buf,p+4);
            int o = 8;
            if (h.layoutVersion() == 24 || h.layoutVersion() == 241) { t.customAttributeIndex=i32(buf,p+o); o+=4; }
            t.byvalTypeIndex=i32(buf,p+o); o+=4;
            if (h.layoutVersion() == 24 || h.layoutVersion() == 241 || h.layoutVersion() == 242 || h.layoutVersion() == 243 || h.layoutVersion() == 244) { t.byrefTypeIndex=i32(buf,p+o); o+=4; }
            t.declaringTypeIndex=i32(buf,p+o); o+=4; t.parentIndex=i32(buf,p+o); o+=4; t.elementTypeIndex=i32(buf,p+o); o+=4;
            if (h.layoutVersion() == 24 || h.layoutVersion() == 241) { t.rgctxStart=i32(buf,p+o); o+=4; t.rgctxCount=i32(buf,p+o); o+=4; }
            t.genericContainerIndex=i32(buf,p+o); o+=4;
            if (h.version <= 22) { o += 4; o += 4; if (h.version >= 21) { o += 4; o += 4; } }
            t.flags=u32(buf,p+o); o+=4;
            t.fieldStart=i32(buf,p+o); o+=4; t.methodStart=i32(buf,p+o); o+=4; t.eventStart=i32(buf,p+o); o+=4; t.propertyStart=i32(buf,p+o); o+=4;
            t.nestedTypesStart=i32(buf,p+o); o+=4; t.interfacesStart=i32(buf,p+o); o+=4; t.vtableStart=i32(buf,p+o); o+=4; t.interfaceOffsetsStart=i32(buf,p+o); o+=4;
            t.methodCount=u16(buf,p+o); o+=2; t.propertyCount=u16(buf,p+o); o+=2; t.fieldCount=u16(buf,p+o); o+=2; t.eventCount=u16(buf,p+o); o+=2;
            t.nestedTypeCount=u16(buf,p+o); o+=2; t.vtableCount=u16(buf,p+o); o+=2; t.interfacesCount=u16(buf,p+o); o+=2; t.interfaceOffsetsCount=u16(buf,p+o); o+=2;
            t.bitfield=u32(buf,p+o); o+=4;
            if (h.version >= 19) t.token=u32(buf,p+o);
            return t;
        });
    }

    private static List<MethodDef> readMethods(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = methodDefSize(h.layoutVersion());
        return readRecords(raf, h.methodsOffset, h.methodsSize, size, (buf,p) -> {
            MethodDef m = new MethodDef(); int o=0;
            m.nameIndex=u32(buf,p+o);o+=4;m.declaringType=i32(buf,p+o);o+=4;m.returnType=i32(buf,p+o);o+=4;
            if(h.version>=31){m.returnParameterToken=i32(buf,p+o);o+=4;}
            m.parameterStart=i32(buf,p+o);o+=4;
            if(h.layoutVersion()<=24 || h.layoutVersion()==241){o+=4; m.genericContainerIndex=i32(buf,p+o);o+=4; m.methodIndex=i32(buf,p+o);o+=4; o+=16;}
            else { m.genericContainerIndex=i32(buf,p+o);o+=4; }
            m.token=u32(buf,p+o);o+=4;m.flags=u16(buf,p+o);o+=2;m.iflags=u16(buf,p+o);o+=2;m.slot=u16(buf,p+o);o+=2;m.parameterCount=u16(buf,p+o);
            return m;
        });
    }

    private static List<ParameterDef> readParameters(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = h.version <= 24 ? 16 : 12;
        return readRecords(raf, h.parametersOffset, h.parametersSize, size, (buf,p) -> { ParameterDef x=new ParameterDef(); x.nameIndex=u32(buf,p); x.token=u32(buf,p+4); x.typeIndex=i32(buf,p+(h.version<=24?12:8)); return x; });
    }

    private static List<FieldDef> readFields(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = h.version <= 24 ? 16 : 12;
        return readRecords(raf, h.fieldsOffset, h.fieldsSize, size, (buf,p) -> {
            FieldDef f = new FieldDef(); f.nameIndex=u32(buf,p); f.typeIndex=i32(buf,p+4); f.token=h.version<=24?u32(buf,p+12):(h.version>=19?u32(buf,p+8):0); return f;
        });
    }

    private static List<PropertyDef> readProperties(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = h.version <= 24 ? 24 : 20;
        return readRecords(raf, h.propertiesOffset, h.propertiesSize, size, (buf,p) -> {
            PropertyDef x=new PropertyDef();x.nameIndex=u32(buf,p);x.get=i32(buf,p+4);x.set=i32(buf,p+8);x.attrs=u32(buf,p+12);x.token=h.version<=24?u32(buf,p+20):(h.version>=19?u32(buf,p+16):0);return x;
        });
    }

    private static List<EventDef> readEvents(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = h.version <= 24 ? 28 : 24;
        return readRecords(raf, h.eventsOffset, h.eventsSize, size, (buf,p) -> {
            EventDef x=new EventDef();x.nameIndex=u32(buf,p);x.typeIndex=i32(buf,p+4);x.add=i32(buf,p+8);x.remove=i32(buf,p+12);x.raise=i32(buf,p+16);x.token=h.version<=24?u32(buf,p+24):(h.version>=19?u32(buf,p+20):0);return x;
        });
    }

    private interface Decoder<T> { T decode(byte[] buf, int p); }
    private static <T> List<T> readRecords(RandomAccessFile raf, long offset, long bytes, int recordSize, Decoder<T> decoder) throws IOException {
        if (recordSize <= 0 || bytes < recordSize || bytes % recordSize != 0) return Collections.emptyList();
        long countL = bytes / recordSize;
        if (countL > MAX_TYPES) throw new IOException("Metadata table is unreasonably large");
        if (bytes > MAX_TABLE_READ_BYTES) throw new IOException("Metadata table is too large for safe in-memory analysis");
        int count = (int) countL;
        byte[] all = new byte[(int) bytes];
        raf.seek(offset); raf.readFully(all);
        List<T> out = new ArrayList<>(count);
        for (int i=0;i<count;i++) out.add(decoder.decode(all,i*recordSize));
        return out;
    }

    private static int imageDefSize(int raw,int layout){double v=normalizedIl2CppVersion(raw,layout);if(v>=24.1)return 40;if(v>=24)return 32;if(v>=19)return 24;return 20;}
    private static int typeDefSize(int v) {
        if (v == 241) return 100;
        if (v == 242 || v == 243 || v == 244) return 88;
        if (v <= 18) return 108;
        if (v <= 20) return 112;
        if (v <= 22) return 120;
        if (v <= 24) return 100;
        return 88;
    }
    private static int methodDefSize(int v) {
        if (v == 242 || v == 243 || v == 244) return 32;
        if (v == 241) return 52;
        if (v >= 31) return 36;
        if (v >= 25) return 32;
        return 52;
    }

    private static void writeDumpCs(File outDir, MetadataHeader h, StringTable strings, List<ImageDef> images,
                                    List<TypeDef> types, List<MethodDef> methods, List<FieldDef> fields,
                                    List<PropertyDef> properties, List<EventDef> events) throws IOException {
        File out = new File(outDir, "dump.cs");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write("// Untrusted Manager IL2CPP metadata dump"); w.newLine();
            w.write("// Metadata version: " + h.version); w.newLine();
            w.write("// Native method RVAs/field offsets require the matching native registration data; they are not fabricated here."); w.newLine(); w.newLine();
            for (int imageIndex=0; imageIndex<images.size(); imageIndex++) {
                ImageDef image=images.get(imageIndex);
                String imageName=stringAt(strings,image.nameIndex,"Image_"+imageIndex);
                w.write("// Image: "+imageName);w.newLine();
                int start=Math.max(0,image.typeStart), end=Math.min(types.size(), start + (int) safeCount(image.typeCount));
                for(int ti=start;ti<end;ti++) {
                    TypeDef t=types.get(ti);
                    String ns=stringAt(strings,t.namespaceIndex,"");
                    String name=sanitizeCSharpIdentifier(stringAt(strings,t.nameIndex,"Type_"+ti));
                    if(name.isEmpty()) name="Type_"+ti;
                    w.write("namespace "+(ns.isEmpty()?"Global":sanitizeCSharpNamespace(ns))+" {");w.newLine();
                    String kind=((t.bitfield&0x2)!=0)?"enum":((t.bitfield&0x1)!=0?"struct":"class");
                    w.write("    "+kind+" "+name+" {");w.newLine();
                    int fs=Math.max(0,t.fieldStart), fe=Math.min(fields.size(),fs+t.fieldCount);
                    for(int fi=fs;fi<fe;fi++) {
                        FieldDef f=fields.get(fi);w.write("        // token: 0x"+Long.toHexString(f.token));w.newLine();
                        w.write("        public object "+sanitizeCSharpIdentifier(stringAt(strings,f.nameIndex,"field_"+fi))+";");w.newLine();
                    }
                    int ms=Math.max(0,t.methodStart), me=Math.min(methods.size(),ms+t.methodCount);
                    for(int mi=ms;mi<me;mi++) {
                        MethodDef m=methods.get(mi);String mn=sanitizeCSharpIdentifier(stringAt(strings,m.nameIndex,"Method_"+mi));
                        w.write("        // token: 0x"+Long.toHexString(m.token)+" parameters: "+m.parameterCount);w.newLine();
                        w.write("        public void "+mn+"() { }");w.newLine();
                    }
                    int ps=Math.max(0,t.propertyStart), pe=Math.min(properties.size(),ps+t.propertyCount);
                    for(int pi=ps;pi<pe;pi++) w.write("        // property "+sanitizeCSharpIdentifier(stringAt(strings,properties.get(pi).nameIndex,"property_"+pi))+"\n");
                    int es=Math.max(0,t.eventStart), ee=Math.min(events.size(),es+t.eventCount);
                    for(int ei=es;ei<ee;ei++) w.write("        // event "+sanitizeCSharpIdentifier(stringAt(strings,events.get(ei).nameIndex,"event_"+ei))+"\n");
                    w.write("    }");w.newLine();w.write("}");w.newLine();w.newLine();
                }
            }
        }
    }

    private static void writeHeader(File outDir, MetadataHeader h, long fileLength) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(outDir,"metadata_header.txt")))) {
            w.write("Magic: 0x"+Integer.toHexString(h.magic));w.newLine();w.write("Version: "+h.version);w.newLine();w.write("File size: "+fileLength);w.newLine();
            w.write("Header bytes used: "+h.headerBytes);w.newLine();
        }
    }
    private static void writeLayout(File outDir, MetadataHeader h) throws IOException {
        try (BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"metadata_layout.txt")))) {
            w.write("field\toffset\tsize\tcount");w.newLine();
            for(Map.Entry<String,long[]> e:h.layout.entrySet()) {
                long[] x=e.getValue(); long count=x[1]>0?x[1]/x[0]:0;
                w.write(e.getKey()+"\t"+x[2]+"\t"+x[1]+"\t"+count);w.newLine();
            }
        }
    }
    private static void writeStrings(File outDir, StringTable strings) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(new File(outDir, "metadata_strings.txt")))) {
            for (Map.Entry<Long, String> e : strings.byOffset.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue());
                w.newLine();
            }
        }
    }
    private static void writeTablesSummary(File outDir,MetadataHeader h,DumpStats s)throws IOException{
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"metadata_tables.txt")))){
            w.write("Metadata version: "+h.version);w.newLine();
            w.write("Images: "+s.images);w.newLine();w.write("Types: "+s.types);w.newLine();w.write("Methods: "+s.methods);w.newLine();w.write("Fields: "+s.fields);w.newLine();w.write("Properties: "+s.properties);w.newLine();w.write("Events: "+s.events);w.newLine();
        }
    }

    private static ElfResult analyzeElf(File file, File outDir, int methodCount, int typeCount, int imageCount, int metadataVersion, int metadataLayoutVersion, File metadataFile) throws IOException {
        mkdir(outDir);
        byte[] head=readAtMost(file,64);
        if(head.length<4 || head[0]!=0x7f || head[1]!='E' || head[2]!='L' || head[3]!='F'){
            writeText(new File(outDir,"elf_info.txt"),"Not an ELF file. The file may be packed, encrypted, truncated or a different native format.");
            writeBinaryStrings(file,new File(outDir,"binary_strings.txt"));
            return new ElfResult(false,0,"not-elf",0,0,0);
        }
        int clazz=head[4]&0xff, data=head[5]&0xff;
        boolean le=data==1; if(!le && data!=2) throw new IOException("Unsupported ELF endianness");
        try(RandomAccessFile raf=new RandomAccessFile(file,"r")){
            ElfResult r=readElf(raf,clazz,le,file.length());
            writeText(new File(outDir,"elf_info.txt"),r.toText());
            writeBinaryStrings(file,new File(outDir,"binary_strings.txt"));
            writeElfSections(raf,r,outDir);
            writeElfSymbols(raf,r,outDir);
            writeIl2CppRegistration(raf, r, outDir, methodCount, typeCount, imageCount, metadataVersion, metadataLayoutVersion, metadataFile);
            return r;
        }
    }

    private static ElfResult readElf(RandomAccessFile raf,int clazz,boolean le,long fileLength)throws IOException{
        raf.seek(0); byte[] h=new byte[64];raf.readFully(h);
        int machine=u16(h,18,le); long entry=clazz==2?u64(h,24,le):u32l(h,24,le); long shoff=clazz==2?u64(h,40,le):u32l(h,32,le);
        int shentsize=u16(h,clazz==2?58:46,le), shnum=u16(h,clazz==2?60:48,le), shstr=u16(h,clazz==2?62:50,le);
        long tableEnd = shoff >= 0 && shentsize > 0 ? shoff + (long) shentsize * shnum : Long.MAX_VALUE;
        boolean sectionsValid = shentsize > 0 && shoff >= 0 && shoff < fileLength && tableEnd >= shoff && tableEnd <= fileLength && shnum <= 100_000;
        int actualShnum = sectionsValid ? shnum : 0;
        int actualShstr = sectionsValid && shstr < shnum ? shstr : -1;
        ElfResult result = new ElfResult(true,clazz,machineName(machine),entry,shoff,actualShnum,le,shentsize,actualShstr);
        if (clazz == 2) loadElf64Segments(raf, result, fileLength);
        else loadElf32Segments(raf, result, fileLength);
        return result;
    }

    private static void loadElf64Segments(RandomAccessFile raf, ElfResult r, long fileLength) throws IOException {
        byte[] h=readRange(raf,0,64); long phoff=u64(h,32,r.le); int phentsize=u16(h,54,r.le), phnum=u16(h,56,r.le);
        if(phentsize<56||phnum>1000||phoff<0||phoff+(long)phentsize*phnum>fileLength)return;
        for(int i=0;i<phnum;i++){byte[]b=readRange(raf,phoff+(long)i*phentsize,phentsize);long type=u32l(b,0,r.le);long flags=u32l(b,4,r.le);long off=u64(b,8,r.le),va=u64(b,16,r.le),fs=u64(b,32,r.le),ms=u64(b,40,r.le);if(type!=1||fs==0)continue;LoadSegment seg=new LoadSegment(off,Math.min(fs,fileLength-off),va,ms,flags);r.loadSegments.add(seg);if((flags&1)!=0)r.execSegments.add(seg);else if((flags&2)!=0)r.dataSegments.add(seg);}
    }
    private static void loadElf32Segments(RandomAccessFile raf, ElfResult r, long fileLength) throws IOException {
        byte[] h=readRange(raf,0,52); long phoff=u32l(h,28,r.le); int phentsize=u16(h,42,r.le), phnum=u16(h,44,r.le);
        if(phentsize<32||phnum>1000||phoff<0||phoff+(long)phentsize*phnum>fileLength)return;
        for(int i=0;i<phnum;i++){byte[]b=readRange(raf,phoff+(long)i*phentsize,phentsize);long type=u32l(b,0,r.le);long off=u32l(b,4,r.le),va=u32l(b,8,r.le),fs=u32l(b,16,r.le),ms=u32l(b,20,r.le),flags=u32l(b,24,r.le);if(type!=1||fs==0)continue;LoadSegment seg=new LoadSegment(off,Math.min(fs,fileLength-off),va,ms,flags);r.loadSegments.add(seg);if((flags&1)!=0)r.execSegments.add(seg);else if((flags&2)!=0)r.dataSegments.add(seg);}
    }

    private static void writeElfSections(RandomAccessFile raf,ElfResult r,File outDir)throws IOException{
        if(r.sectionCount<=0)return; List<Section> sections=readSections(raf,r); try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"elf_sections.txt")))){w.write("index\tname\toffset\tsize\ttype\tflags");w.newLine();for(Section s:sections)w.write(s.index+"\t"+s.name+"\t"+s.offset+"\t"+s.size+"\t"+s.type+"\t0x"+Long.toHexString(s.flags)+"\n");}
    }
    private static List<Section> readSections(RandomAccessFile raf,ElfResult r)throws IOException{
        List<Section> s=new ArrayList<>(); Section[] raw=new Section[r.sectionCount];
        for(int i=0;i<r.sectionCount;i++){raf.seek(r.sectionOffset+(long)i*r.sectionEntrySize);byte[] b=new byte[r.sectionEntrySize];raf.readFully(b);Section x=new Section();x.index=i;x.nameOffset=u32l(b,0,r.le);x.type=u32l(b,4,r.le);if(r.clazz==2){x.flags=u64(b,8,r.le);x.offset=u64(b,24,r.le);x.size=u64(b,32,r.le);x.link=u32l(b,40,r.le);x.info=u32l(b,44,r.le);x.entsize=u64(b,56,r.le);}else{x.flags=u32l(b,8,r.le);x.offset=u32l(b,16,r.le);x.size=u32l(b,20,r.le);x.link=u32l(b,24,r.le);x.info=u32l(b,28,r.le);x.entsize=u32l(b,36,r.le);}raw[i]=x;}
        if(r.sectionStringIndex>=0&&r.sectionStringIndex<raw.length){Section str=raw[r.sectionStringIndex];byte[] names=readRange(raf,str.offset,Math.min(str.size,8*1024*1024));for(Section x:raw)x.name=cString(names,(int)x.nameOffset);}
        Collections.addAll(s,raw);return s;
    }
    private static void writeElfSymbols(RandomAccessFile raf, ElfResult r, File outDir) throws IOException {
        List<Section> sections = readSections(raf, r);
        File out = new File(outDir, "elf_symbols.txt");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write("section\tname\tvalue\tsize\tbind\ttype\n");
            for (Section sec : sections) {
                if (sec.type != 2 && sec.type != 11) continue;
                if (sec.entsize <= 0 || sec.link < 0 || sec.link >= sections.size()) continue;
                Section str = sections.get((int) sec.link);
                byte[] names = readRange(raf, str.offset, Math.min(str.size, 32L * 1024L * 1024L));
                long count = sec.size / sec.entsize;
                for (long i = 0; i < count && i < 2_000_000; i++) {
                    byte[] b = readRange(raf, sec.offset + i * sec.entsize, sec.entsize);
                    long nameOffset = u32l(b, 0, r.le);
                    long value = r.clazz == 2 ? u64(b, 8, r.le) : u32l(b, 4, r.le);
                    long size = r.clazz == 2 ? u64(b, 16, r.le) : u32l(b, 8, r.le);
                    int info = r.clazz == 2 ? b[4] & 0xff : b[12] & 0xff;
                    String name = cString(names, (int) nameOffset);
                    if (!name.isEmpty()) {
                        w.write(sec.name + "\t" + name + "\t0x" + Long.toHexString(value) + "\t" + size + "\t" + (info >> 4) + "\t" + (info & 15) + "\n");
                    }
                }
            }
        }
    }

    private static void writeBinaryStrings(File file, File out) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file));
             BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            byte[] buf = new byte[64 * 1024];
            StringBuilder s = new StringBuilder();
            long base = 0;
            while (true) {
                int n = in.read(buf);
                if (n < 0) break;
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 255;
                    if (b >= 32 && b <= 126) {
                        s.append((char) b);
                    } else {
                        if (s.length() >= 4) {
                            w.write(Long.toHexString(base + i - s.length()));
                            w.write("\t");
                            w.write(s.toString());
                            w.newLine();
                        }
                        s.setLength(0);
                    }
                }
                base += n;
            }
            if (s.length() >= 4) {
                w.write(Long.toHexString(Math.max(0, base - s.length())));
                w.write("\t");
                w.write(s.toString());
                w.newLine();
            }
        }
    }

    /**
     * Parses the standard IL2CPP registration structures when they can be located with
     * sufficient confidence.  This is deliberately version-gated: a wrong registration
     * address is worse than an incomplete dump because it can turn arbitrary data into
     * apparently valid method/field addresses.
     */
    private static void writeIl2CppRegistration(RandomAccessFile raf, ElfResult r, File outDir,
                                                 int methodCount, int typeCount, int imageCount,
                                                 int metadataVersion, int metadataLayoutVersion, File metadataFile) throws IOException {
        File report = new File(outDir, "il2cpp_registration.txt");
        List<SymbolHint> symbols = findRegistrationSymbols(raf, r);
        long codeReg = 0, metadataReg = 0;
        for (SymbolHint s : symbols) {
            if ("g_CodeRegistration".equals(s.name)) codeReg = s.value;
            if ("g_MetadataRegistration".equals(s.name)) metadataReg = s.value;
        }
        RegistrationData reg = null;
        if (codeReg != 0 && metadataReg != 0) {
            reg = parseRegistration(raf, r, codeReg, metadataReg, metadataVersion, metadataLayoutVersion, methodCount, typeCount);
        }
        try (BufferedWriter w = new BufferedWriter(new FileWriter(report))) {
            w.write("IL2CPP registration analysis"); w.newLine();
            w.write("Metadata counts: methods=" + methodCount + " types=" + typeCount + " images=" + imageCount); w.newLine();
            w.write("ELF: " + r.machine + " / " + (r.clazz == 2 ? "ELF64" : "ELF32")); w.newLine();
            for (SymbolHint sh : symbols) {
                w.write("SYMBOL\t" + sh.name + "\tVA=0x" + Long.toHexString(sh.value) + "\tfile=0x" + Long.toHexString(sh.fileOffset)); w.newLine();
            }
            if (reg == null) {
                w.write("Status: registration structures were not safely resolved."); w.newLine();
                w.write("Automatic symbol discovery requires usable registration symbols; otherwise use the manual CodeRegistration/MetadataRegistration fields in the IL2CPP tool."); w.newLine();
                if (r.clazz == 2 && r.le) {
                    w.write("AArch64/ELF64 heuristic discovery remains available as a candidate report, but candidates are never treated as confirmed data."); w.newLine();
                    writeRegistrationCandidates(w, raf, r, methodCount, typeCount, imageCount, metadataVersion);
                }
                return;
            }
            writeRegistrationFiles(raf, r, outDir, reg, metadataVersion);
            w.write("Status: confirmed structural parse from named registration symbols."); w.newLine();
            w.write("CodeRegistration: 0x" + Long.toHexString(reg.codeAddress)); w.newLine();
            w.write("MetadataRegistration: 0x" + Long.toHexString(reg.metadataAddress)); w.newLine();
            w.write("Method pointer entries: " + reg.methods.size()); w.newLine();
            w.write("Field offset entries: " + reg.fieldOffsets.size()); w.newLine();
        }
        if (reg != null && metadataVersion >= 16 && metadataFile != null && metadataFile.isFile()) {
            // Rebuild the useful source-level artifacts using the verified native registration data.
            writeNativeBackedArtifacts(raf, r, outDir, reg, metadataFile, metadataVersion, metadataLayoutVersion);
        }
    }

    private static void writeRegistrationCandidates(BufferedWriter w, RandomAccessFile raf, ElfResult r,
                                                    int methodCount, int typeCount, int imageCount,
                                                    int metadataVersion) throws IOException {
        byte[] all = readRange(raf, 0, Math.min(raf.length(), 32L * 1024L * 1024L));
        long mscorlibFile = findAscii(all, "mscorlib.dll\0");
        if (mscorlibFile < 0) { w.write("No mscorlib.dll anchor found in first 32 MiB."); w.newLine(); return; }
        long mscorlibVa = mapFileToVa(r.loadSegments, mscorlibFile);
        if (mscorlibVa == 0) return;
        List<Long> refs = findPointerReferences(raf, r.loadSegments, mscorlibVa, 10000);
        w.write("Candidate anchors: " + refs.size()); w.newLine();
        int emitted = 0;
        for (long ref : refs) {
            List<Long> second = findPointerReferences(raf, r.loadSegments, ref, 1000);
            for (long x : second) {
                long maybeCount = readVaPointer(raf, r.loadSegments, x - 8);
                if (maybeCount == imageCount || maybeCount == typeCount || maybeCount == methodCount) {
                    w.write("CANDIDATE\tVA=0x" + Long.toHexString(x - 8 * 4) + "\tanchor=0x" + Long.toHexString(x));
                    w.newLine();
                    if (++emitted >= 32) return;
                }
            }
        }
    }

    private static double normalizedIl2CppVersion(int raw,int layout){if(raw==24&&layout==242)return 24.2;if(raw==24&&layout==243)return 24.3;if(raw==24&&layout==244)return 24.4;if(raw==24&&layout==241)return 24.1;return raw;}

    private static RegistrationData parseRegistration(RandomAccessFile raf, ElfResult r, long codeAddress,
                                                      long metadataAddress, int version, int layoutVersion,
                                                      int methodCount, int typeCount) throws IOException {
        if (r.clazz != 2 || !r.le) return null;
        long codeFile = mapVaToFile(r.loadSegments, codeAddress);
        long metaFile = mapVaToFile(r.loadSegments, metadataAddress);
        if (codeFile < 0 || metaFile < 0) return null;
        double il2cppVersion = normalizedIl2CppVersion(version, layoutVersion);
        long[] c = readCodeRegistration(raf, r, codeFile, il2cppVersion);
        long[] m = readMetadataRegistration(raf, metaFile, il2cppVersion);
        if (c == null || m == null) return null;
        RegistrationData out = new RegistrationData(codeAddress, metadataAddress);
        // MetadataRegistration: genericClasses, genericInsts, genericMethodTable, types, methodSpecs,
        // optional methodReferences, fieldOffsets, typeDefinitionsSizes, optional metadataUsages.
        out.typesCount=m[6]; out.typesAddress=m[7];
        int fieldPair = il2cppVersion <= 16 ? 12 : 10;
        out.fieldOffsetsCount = m[fieldPair]; out.fieldOffsetsAddress = m[fieldPair+1];
        if (!validCount(out.fieldOffsetsCount, 16_000_000)) return null;
        if (out.fieldOffsetsCount > 0 && out.fieldOffsetsAddress > 0 && mapVaToFile(r.loadSegments, out.fieldOffsetsAddress) >= 0) {
            if (il2cppVersion > 21) {
                for (long i = 0; i < out.fieldOffsetsCount; i++) {
                    long fo = mapVaToFile(r.loadSegments, out.fieldOffsetsAddress + i * 8);
                    if (fo < 0) break;
                    out.fieldOffsets.add(readLongAt(raf, fo, r.le));
                }
            } else {
                for (long i = 0; i < out.fieldOffsetsCount; i++) {
                    long fo = mapVaToFile(r.loadSegments, out.fieldOffsetsAddress + i * 4);
                    if (fo < 0) break;
                    out.fieldOffsets.add(Integer.toUnsignedLong(readIntAt(raf, fo, r.le)));
                }
            }
        }
        // CodeRegistration method pointers. <=24.1 stores a direct methodPointers array;
        // >=24.2 stores per-codegen-module method arrays.
        if (il2cppVersion < 24.2) {
            long count = c[0], addr = c[1];
            if (!validCount(count, 20_000_000) || (count > 0 && mapVaToFile(r.loadSegments, addr) < 0)) return null;
            for (long i=0;i<count;i++) {
                long fo=mapVaToFile(r.loadSegments,addr+i*8); if(fo<0)break;
                out.methods.add(readLongAt(raf,fo,r.le));
            }
        } else {
            long moduleCount = c[c.length-2], modulesAddr = c[c.length-1];
            if (!validCount(moduleCount, 1_000_000) || (moduleCount > 0 && mapVaToFile(r.loadSegments, modulesAddr) < 0)) return null;
            for (long i=0;i<moduleCount;i++) {
                long mo=mapVaToFile(r.loadSegments,modulesAddr+i*8); if(mo<0)break;
                long moduleVa=readLongAt(raf,mo,r.le); long moduleFo=mapVaToFile(r.loadSegments,moduleVa); if(moduleFo<0)continue;
                CodeGenModule gm=readCodeGenModule(raf,r,moduleFo,version);
                if(gm==null)continue;
                out.modules.add(gm);
                if (gm.methodPointersAddress > 0 && validCount(gm.methodPointerCount, 10_000_000)) {
                    for(long j=0;j<gm.methodPointerCount;j++) {
                        long fo=mapVaToFile(r.loadSegments,gm.methodPointersAddress+j*8); if(fo<0)break;
                        out.moduleMethods.add(new MethodPointerEntry(gm.name,j,readLongAt(raf,fo,r.le)));
                    }
                }
            }
        }
        if (out.methods.isEmpty() && out.moduleMethods.isEmpty()) return null;
        return out;
    }

    private static long[] readCodeRegistration(RandomAccessFile raf,ElfResult r,long fileOffset,double version)throws IOException{
        boolean[] adjustors=version>=24.2?new boolean[]{false,true}:new boolean[]{false};
        boolean[] extras=version>=29.0?new boolean[]{false,true}:new boolean[]{false};
        for(boolean a:adjustors) for(boolean e:extras){
            long[] x=readCodeRegistrationLayout(raf,fileOffset,version,a,e);
            if(x.length<2)continue;
            long count=x[x.length-2],addr=x[x.length-1];
            if(count<0||count>1_000_000||(count>0&&mapVaToFile(r.loadSegments,addr)<0))continue;
            if(count==0)continue;
            long mo=mapVaToFile(r.loadSegments,addr);if(mo<0)continue;
            long moduleVa=readLongAt(raf,mo,r.le);long moduleFo=mapVaToFile(r.loadSegments,moduleVa);if(moduleFo<0)continue;
            CodeGenModule gm=readCodeGenModule(raf,r,moduleFo,(int)version);
            if(gm!=null)return x;
        }
        return null;
    }

    private static long[] readCodeRegistrationLayout(RandomAccessFile raf,long fileOffset,double version,boolean adjustor,boolean unresolvedExtras)throws IOException{
        ArrayList<Long> v=new ArrayList<>(); long p=fileOffset;
        // All fields are pointer-sized on the supported 64-bit Android path.
        if(version<=21){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;}
        if(version>=22){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;}
        if(version<=22){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;}
        v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16; // genericMethodPointers
        if(adjustor){v.add(readLongAt(raf,p,true));p+=8;} // genericAdjustorThunks (count/pointer representation differs; preserve alignment)
        v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16; // invoker
        if(version<=24.5){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;} // custom attributes
        if(version>=21&&version<=22){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;}
        if(version>=22){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;} // unresolved virtual
        if(unresolvedExtras){v.add(readLongAt(raf,p,true));p+=8;v.add(readLongAt(raf,p,true));p+=8;}
        if(version>=23){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;}
        if(version>=24.3){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;}
        if(version>=24.2){v.add(readLongAt(raf,p,true));v.add(readLongAt(raf,p+8,true));p+=16;}
        long[] a=new long[v.size()];for(int i=0;i<a.length;i++)a[i]=v.get(i);return a;
    }
    private static long[] readMetadataRegistration(RandomAccessFile raf,long fileOffset,double version)throws IOException{
        int pairs=version<=16?9:8; // methodReferences replaces metadataUsages in v16; field offsets remain the seventh pair
        if(version>16)pairs=8;
        long[] a=new long[pairs*2];for(int i=0;i<a.length;i++)a[i]=readLongAt(raf,fileOffset+i*8,true);return a;
    }

    private static CodeGenModule readCodeGenModule(RandomAccessFile raf,ElfResult r,long fileOffset,int version)throws IOException{
        long[] v=new long[18];
        for(int i=0;i<v.length;i++) { if(fileOffset+8L*(i+1)>raf.length()) return null; v[i]=readLongAt(raf,fileOffset+i*8,r.le); }
        long moduleName=v[0], methodCount=v[1], methodPtr=v[2];
        if(!validCount(methodCount,10_000_000) || (methodCount>0&&mapVaToFile(r.loadSegments,methodPtr)<0))return null;
        String name="codegen_module";
        long nf=mapVaToFile(r.loadSegments,moduleName); if(nf>=0) name=readCStringAt(raf,nf,512);
        return new CodeGenModule(name,methodCount,methodPtr);
    }

    private static void writeRegistrationFiles(RandomAccessFile raf,ElfResult r,File outDir,RegistrationData reg,int version)throws IOException{
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"registration.json")))){
            w.write("{\n  \"codeRegistration\": \"0x"+Long.toHexString(reg.codeAddress)+"\",\n");
            w.write("  \"metadataRegistration\": \"0x"+Long.toHexString(reg.metadataAddress)+"\",\n");
            w.write("  \"fieldOffsetsCount\": "+reg.fieldOffsets.size()+",\n  \"methodPointersCount\": "+reg.methods.size()+",\n  \"codeGenModuleMethodPointersCount\": "+reg.moduleMethods.size()+"\n}\n");
        }
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"method_pointers.json")))){
            w.write("{\n  \"methods\": [\n"); boolean first=true;
            for(int i=0;i<reg.methods.size();i++){if(!first)w.write(",\n");first=false;long p=reg.methods.get(i);w.write("    {\"index\": "+i+", \"va\": \"0x"+Long.toHexString(p)+"\", \"rva\": \"0x"+Long.toHexString(Math.max(0,p-imageBase(r)))+"\"}");}
            for(int i=0;i<reg.moduleMethods.size();i++){MethodPointerEntry e=reg.moduleMethods.get(i);if(!first)w.write(",\n");first=false;w.write("    {\"module\": \""+jsonEscape(e.module)+"\", \"index\": "+e.index+", \"va\": \"0x"+Long.toHexString(e.pointer)+"\", \"rva\": \"0x"+Long.toHexString(Math.max(0,e.pointer-imageBase(r)))+"\"}");}
            w.write("\n  ]\n}\n");
        }
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"field_offsets.json")))){
            w.write("{\n  \"offsets\": [\n"); for(int i=0;i<reg.fieldOffsets.size();i++){if(i>0)w.write(",\n");w.write("    {\"typeIndex\": "+i+", \"offsetTableAddress\": \"0x"+Long.toHexString(reg.fieldOffsets.get(i))+"\"}");} w.write("\n  ]\n}\n");
        }
    }

    private static void writeNativeBackedArtifacts(RandomAccessFile raf,ElfResult r,File outDir,RegistrationData reg,File metadataFile,int version,int layoutVersion)throws IOException{
        MetadataHeader h=readMetadataHeader(metadataFile);
        if(h==null)return;
        try(RandomAccessFile mr=new RandomAccessFile(metadataFile,"r")){
            StringTable strings=readStringTable(mr,h.stringOffset,h.stringSize);
            List<ImageDef> images=readImages(mr,h);
            List<TypeDef> types=readTypes(mr,h);
            List<MethodDef> methods=readMethods(mr,h);
            List<FieldDef> fields=readFields(mr,h);
            List<ParameterDef> parameters=readParameters(mr,h);
            NativeTypeResolver resolver=new NativeTypeResolver(raf,r,reg,types,strings);
            Map<Integer,Long> methodAddresses=new HashMap<>();
            double il2cppVersion=normalizedIl2CppVersion(version,layoutVersion);
            if(il2cppVersion<24.2){
                for(int i=0;i<methods.size()&&i<reg.methods.size();i++){MethodDef md=methods.get(i);if(md.methodIndex>=0)methodAddresses.put(i,reg.methods.get(md.methodIndex));}
            }else{
                Map<String,List<Long>> modules=new HashMap<>();
                for(MethodPointerEntry e:reg.moduleMethods)modules.computeIfAbsent(e.module,k->new ArrayList<>()).add(e.pointer);
                for(int i=0;i<methods.size();i++){
                    MethodDef md=methods.get(i); if(md.token==0)continue;
                    int row=(int)(md.token&0x00ffffffL); if(row<=0)continue;
                    int imageIndex=findImageForType(images,md.declaringType);
                    String imageName=imageIndex>=0?stringAt(strings,images.get(imageIndex).nameIndex,""):"";
                    List<Long> list=modules.get(imageName); if(list==null&&modules.size()==1)list=modules.values().iterator().next();
                    int pointerIndex=row-1; if(list!=null&&pointerIndex>=0&&pointerIndex<list.size())methodAddresses.put(i,list.get(pointerIndex));
                }
            }            writeMethodMap(outDir,methods,strings,methodAddresses,r);
            writeFieldOffsets(outDir,raf,r,reg,types,fields,strings,il2cppVersion);
            writeScriptJson(outDir,methods,parameters,strings,methodAddresses,resolver,r);
            writeStringLiteralJson(outDir,mr,h);
            writeIl2CppHeader(outDir,types,fields,methods,strings,methodAddresses,reg,r);
            writeNativeDump(outDir,images,types,methods,fields,parameters,strings,methodAddresses,resolver,r,reg,version);
        }
    }

    private static long imageBase(ElfResult r){long base=Long.MAX_VALUE;for(LoadSegment s:r.loadSegments)if(s.virtualAddress<base)base=s.virtualAddress;return base==Long.MAX_VALUE?0:base;}
    private static int findImageForType(List<ImageDef> images,int typeIndex){for(int i=0;i<images.size();i++){ImageDef x=images.get(i);if(typeIndex>=x.typeStart&&typeIndex<x.typeStart+x.typeCount)return i;}return -1;}
    private static String stringAt(StringTable t,long index,String fallback){String s=t.get(index);return s==null||s.isEmpty()?fallback:s;}

    private static void writeMethodMap(File outDir,List<MethodDef> methods,StringTable strings,Map<Integer,Long> addresses,ElfResult r)throws IOException{
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"method_map.json")))){w.write("{\n  \"methods\": [\n");boolean first=true;for(int i=0;i<methods.size();i++){Long a=addresses.get(i);if(a==null)continue;if(!first)w.write(",\n");first=false;MethodDef m=methods.get(i);w.write("    {\"index\": "+i+", \"name\": \""+jsonEscape(stringAt(strings,m.nameIndex,"Method_"+i))+"\", \"token\": \"0x"+Long.toHexString(m.token)+"\", \"va\": \"0x"+Long.toHexString(a)+"\", \"rva\": \"0x"+Long.toHexString(Math.max(0,a-imageBase(r)))+"\"}");}w.write("\n  ]\n}\n");}
    }
    private static void writeFieldOffsets(File outDir,RandomAccessFile raf,ElfResult r,RegistrationData reg,List<TypeDef> types,List<FieldDef> fields,StringTable strings,double version)throws IOException{
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"field_offsets.json")))){w.write("{\n  \"types\": [\n");boolean firstType=true;for(int ti=0;ti<types.size()&&ti<reg.fieldOffsets.size();ti++){long ptr=reg.fieldOffsets.get(ti);if(ptr==0)continue;List<Integer> offs=new ArrayList<>();if(version>21){long fo=mapVaToFile(r.loadSegments,ptr);if(fo<0)continue;for(int j=0;j<types.get(ti).fieldCount;j++){try{offs.add(readIntAt(raf,fo+j*4,r.le));}catch(Exception e){break;}}}else{offs.add((int)ptr);}if(!firstType)w.write(",\n");firstType=false;w.write("    {\"typeIndex\": "+ti+", \"type\": \""+jsonEscape(stringAt(strings,types.get(ti).nameIndex,"Type_"+ti))+"\", \"offsets\": [");for(int j=0;j<offs.size();j++){if(j>0)w.write(", ");w.write("\"0x"+Integer.toHexString(offs.get(j))+"\"");}w.write("]}");}w.write("\n  ]\n}\n");}
    }
    private static void writeScriptJson(File outDir,List<MethodDef> methods,List<ParameterDef> parameters,StringTable strings,Map<Integer,Long> addresses,NativeTypeResolver resolver,ElfResult r)throws IOException{
        List<Map.Entry<Integer,Long>> entries=new ArrayList<>(addresses.entrySet());
        entries.sort(Comparator.comparingLong(Map.Entry::getValue));
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"script.json")))){
            w.write("{\n  \"ScriptMethod\": [\n");
            boolean first=true;
            for(Map.Entry<Integer,Long> e:entries){
                int i=e.getKey(); if(i<0||i>=methods.size())continue;
                MethodDef m=methods.get(i); long rva=Math.max(0,e.getValue()-imageBase(r));
                String name=stringAt(strings,m.nameIndex,"Method_"+i);
                String signature=resolver.resolve(m.returnType)+" "+sanitizeCSharpIdentifier(name)+"("+parameterSignature(m,parameters,resolver,strings)+")";
                if(!first)w.write(",\n"); first=false;
                w.write("    {\"Address\": "+rva+", \"Name\": \""+jsonEscape(name)+"\", \"Signature\": \""+jsonEscape(signature)+"\", \"TypeSignature\": \""+jsonEscape(resolver.resolve(m.returnType))+"\", \"Index\": "+i+"}");
            }
            w.write("\n  ],\n  \"Addresses\": [");
            first=true;
            for(Map.Entry<Integer,Long> e:entries){long rva=Math.max(0,e.getValue()-imageBase(r));if(!first)w.write(", ");first=false;w.write(Long.toString(rva));}
            w.write("]\n}\n");
        }
    }

    private static void writeStringLiteralJson(File outDir,RandomAccessFile raf,MetadataHeader h)throws IOException{
        File out=new File(outDir,"stringliteral.json");
        try(BufferedWriter w=new BufferedWriter(new FileWriter(out))){
            w.write("{\n  \"strings\": [\n");
            boolean first=true;
            long count=h.stringLiteralSize/8;
            if(count>MAX_STRINGS)count=MAX_STRINGS;
            for(long i=0;i<count;i++){
                long p=h.stringLiteralOffset+i*8L;
                if(p<0||p+8>raf.length())break;
                long len=readU32At(raf,p), dataIndex=readU32At(raf,p+4);
                if(len<0||len>1024*1024L||h.stringLiteralDataOffset<0||dataIndex>h.stringLiteralDataSize||len>h.stringLiteralDataSize-dataIndex)continue;
                String value;
                try{
                    byte[] b=readRange(raf,h.stringLiteralDataOffset+dataIndex,len);
                    value=new String(b,StandardCharsets.UTF_8);
                }catch(Exception ex){continue;}
                if(!first)w.write(",\n"); first=false;
                w.write("    {\"index\": "+i+", \"length\": "+len+", \"value\": \""+jsonEscape(value)+"\"}");
            }
            w.write("\n  ]\n}\n");
        }
    }

    private static long readU32At(RandomAccessFile raf,long off)throws IOException{raf.seek(off);return Integer.toUnsignedLong(readIntLE(raf));}

    private static void writeIl2CppHeader(File outDir,List<TypeDef> types,List<FieldDef> fields,List<MethodDef> methods,StringTable strings,Map<Integer,Long> addresses,RegistrationData reg,ElfResult r)throws IOException{
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(outDir,"il2cpp.h")))){w.write("#pragma once\n#include <stdint.h>\n\n");w.write("typedef struct UM_Il2CppRegistration { uintptr_t codeRegistration; uintptr_t metadataRegistration; uint64_t methodPointerCount; uint64_t fieldOffsetCount; } UM_Il2CppRegistration;\n#define UM_CODE_REGISTRATION 0x"+Long.toHexString(reg.codeAddress)+"\n#define UM_METADATA_REGISTRATION 0x"+Long.toHexString(reg.metadataAddress)+"\n\n");for(int mi=0;mi<methods.size();mi++){Long a=addresses.get(mi);if(a!=null)w.write("#define UM_METHOD_"+mi+"_RVA 0x"+Long.toHexString(Math.max(0,a-imageBase(r)))+"\n");}w.write("\n");for(int i=0;i<Math.min(types.size(),100000);i++){TypeDef t=types.get(i);w.write("// TypeIndex "+i+" " + stringAt(strings,t.nameIndex,"Type_"+i)+"\n");w.write("typedef struct {\n    uintptr_t _klass;\n");int end=Math.min(fields.size(),t.fieldStart+t.fieldCount);for(int f=t.fieldStart;f<end;f++)w.write("    uintptr_t "+sanitizeCSharpIdentifier(stringAt(strings,fields.get(f).nameIndex,"field_"+f))+";\n");w.write("} UM_Type_"+i+";\n\n");}}
    }
    private static String parameterSignature(MethodDef m,List<ParameterDef> params,NativeTypeResolver resolver,StringTable strings){StringBuilder b=new StringBuilder();int start=Math.max(0,m.parameterStart),end=Math.min(params.size(),start+Math.max(0,m.parameterCount));for(int i=start;i<end;i++){if(i>start)b.append(", ");ParameterDef p=params.get(i);String n=sanitizeCSharpIdentifier(stringAt(strings,p.nameIndex,"arg"+(i-start)));b.append(resolver.resolve(p.typeIndex)).append(' ').append(n);}return b.toString();}

    private static final class NativeTypeResolver {
        private final RandomAccessFile raf; private final ElfResult elf; private final RegistrationData reg; private final List<TypeDef> types; private final StringTable strings;
        NativeTypeResolver(RandomAccessFile f,ElfResult e,RegistrationData r,List<TypeDef> t,StringTable s){raf=f;elf=e;reg=r;types=t;strings=s;}
        String resolve(int index){if(index<0||reg.typesCount<=0||index>=reg.typesCount)return "object";try{long typePtrFile=mapVaToFile(elf.loadSegments,reg.typesAddress+index*8L);if(typePtrFile<0)return "object";long typeVa=readLongAt(raf,typePtrFile,elf.le);long fo=mapVaToFile(elf.loadSegments,typeVa);if(fo<0)return "object";long data=readLongAt(raf,fo,elf.le);int bits=readIntAt(raf,fo+8,elf.le);int kind=(bits>>>16)&255;String base=switch(kind){case 1->"void";case 2->"bool";case 3->"char";case 4->"sbyte";case 5->"byte";case 6->"short";case 7->"ushort";case 8->"int";case 9->"uint";case 10->"long";case 11->"ulong";case 12->"float";case 13->"double";case 14->"string";case 24->"nint";case 25->"nuint";case 28->"object";case 22->"System.TypedReference";case 29->resolveNestedType(data,index)+"[]";case 15->resolveNestedType(data,index)+"*";case 16->resolveNestedType(data,index)+"&";case 17,18,85->resolveClass(data);case 19->"T";case 30->"M";default->"object";};return base;}catch(Exception e){return "object";}}
        private String resolveClass(long data){if(data>=0&&data<types.size()){TypeDef t=types.get((int)data);String ns=stringAt(strings,t.namespaceIndex,"");String n=sanitizeIdentifier(stringAt(strings,t.nameIndex,"Type_"+data));return ns.isEmpty()?n:ns+"."+n;}return "object";}
        private String resolveNestedType(long data,int self){
            try{
                if(data>=0&&reg.typesCount>0&&reg.typesCount<=1_000_000){
                    for(int i=0;i<reg.typesCount;i++){long pf=mapVaToFile(elf.loadSegments,reg.typesAddress+i*8L);if(pf<0)break;long tv=readLongAt(raf,pf,elf.le);if(tv==data)return resolve(i);}
                }
            }catch(Exception ignored){}
            if(data>=0&&data<types.size())return resolveClass(data);return "object";
        }
    }

    private static void writeNativeDump(File outDir,List<ImageDef> images,List<TypeDef> types,List<MethodDef> methods,List<FieldDef> fields,List<ParameterDef> parameters,StringTable strings,Map<Integer,Long> addresses,NativeTypeResolver resolver,ElfResult r,RegistrationData reg,int version)throws IOException{
        File out=new File(outDir,"dump.cs");try(BufferedWriter w=new BufferedWriter(new FileWriter(out))){w.write("// Untrusted Manager IL2CPP dump (native registration-backed)\n// Metadata version: "+version+"\n\n");for(int ti=0;ti<types.size();ti++){TypeDef t=types.get(ti);String ns=sanitizeCSharpNamespace(stringAt(strings,t.namespaceIndex,"Global"));String name=sanitizeCSharpIdentifier(stringAt(strings,t.nameIndex,"Type_"+ti));String kind=(t.bitfield&2)!=0?"enum":((t.bitfield&1)!=0?"struct":"class");w.write("namespace "+ns+" {\n    public "+kind+" "+name+" {\n");int fe=Math.min(fields.size(),t.fieldStart+t.fieldCount);for(int f=Math.max(0,t.fieldStart);f<fe;f++){w.write("        public "+resolver.resolve(fields.get(f).typeIndex)+" "+sanitizeCSharpIdentifier(stringAt(strings,fields.get(f).nameIndex,"field_"+f))+";\n");}int me=Math.min(methods.size(),t.methodStart+t.methodCount);for(int mi=Math.max(0,t.methodStart);mi<me;mi++){MethodDef m=methods.get(mi);Long a=addresses.get(mi);w.write("        // Token: 0x"+Long.toHexString(m.token)+(a==null?"":" RVA: 0x"+Long.toHexString(Math.max(0,a-imageBase(r))))+"\n");w.write("        public "+resolver.resolve(m.returnType)+" "+sanitizeCSharpIdentifier(stringAt(strings,m.nameIndex,"Method_"+mi))+"("+parameterSignature(m,parameters,resolver,strings)+") { }\n");}w.write("    }\n}\n\n");}}
    }

    private static List<SymbolHint> findRegistrationSymbols(RandomAccessFile raf,ElfResult r)throws IOException{List<SymbolHint> out=new ArrayList<>();if(r.sectionCount<=0)return out;List<Section> sections=readSections(raf,r);for(Section sec:sections){if(sec.type!=2&&sec.type!=11)continue;if(sec.entsize<=0||sec.link<0||sec.link>=sections.size())continue;Section str=sections.get((int)sec.link);byte[] names=readRange(raf,str.offset,Math.min(str.size,32L*1024*1024));long count=sec.size/sec.entsize;for(long i=0;i<count&&i<2000000;i++){byte[] b=readRange(raf,sec.offset+i*sec.entsize,sec.entsize);long no=u32l(b,0,r.le);String name=cString(names,(int)no);if(!name.equals("g_CodeRegistration")&&!name.equals("g_MetadataRegistration"))continue;long value=r.clazz==2?u64(b,8,r.le):u32l(b,4,r.le);out.add(new SymbolHint(name,value,mapVaToFile(r.loadSegments,value)));}}return out;}
    private static long findAscii(byte[] data,String needle){byte[] n=needle.getBytes(StandardCharsets.US_ASCII);outer:for(int i=0;i+n.length<=data.length;i++){for(int j=0;j<n.length;j++)if(data[i+j]!=n[j])continue outer;return i;}return -1;}
    private static long mapFileToVa(List<LoadSegment> segs,long off){for(LoadSegment s:segs)if(off>=s.fileOffset&&off<s.fileOffset+s.fileSize)return s.virtualAddress+(off-s.fileOffset);return 0;}
    private static long mapVaToFile(List<LoadSegment> segs,long va){for(LoadSegment s:segs)if(va>=s.virtualAddress&&va<s.virtualAddress+s.fileSize)return s.fileOffset+(va-s.virtualAddress);return -1;}
    private static List<Long> findPointerReferences(RandomAccessFile raf,List<LoadSegment> segs,long target,long limit)throws IOException{List<Long> out=new ArrayList<>();byte[] buf=new byte[64*1024];for(LoadSegment s:segs){long base=s.fileOffset,end=Math.min(s.fileOffset+s.fileSize,raf.length());raf.seek(base);while(base<end){int want=(int)Math.min(buf.length,end-base),n=raf.read(buf,0,want);if(n<=0)break;for(int i=0;i+8<=n;i+=8){long x=0;for(int j=0;j<8;j++)x|=(long)(buf[i+j]&255)<<(8*j);if(x==target){out.add(s.virtualAddress+base+i-s.fileOffset);if(out.size()>=limit)return out;}}base+=n;if(n<want)break;}}return out;}
    private static long readVaPointer(RandomAccessFile raf,List<LoadSegment> segs,long va)throws IOException{long fo=mapVaToFile(segs,va);return fo<0?-1:readLongAt(raf,fo,true);}
    private static int readIntAt(RandomAccessFile raf,long fileOffset,boolean le)throws IOException{raf.seek(fileOffset);byte[] b=new byte[4];raf.readFully(b);return le?u32(b,0):((b[0]&255)<<24)|((b[1]&255)<<16)|((b[2]&255)<<8)|(b[3]&255);}
    private static long readLongAt(RandomAccessFile raf,long fileOffset,boolean le)throws IOException{
        raf.seek(fileOffset); byte[] b=new byte[8];raf.readFully(b);return u64(b,0,le);
    }
    private static String readCStringAt(RandomAccessFile raf,long fileOffset,int max)throws IOException{
        raf.seek(fileOffset);ByteArrayOutputStream b=new ByteArrayOutputStream();for(int i=0;i<max;i++){int x=raf.read();if(x<0||x==0)break;b.write(x);}return new String(b.toByteArray(),StandardCharsets.UTF_8);
    }
    private static boolean validCount(long n,long max){return n>=0&&n<=max;}
    private static String jsonEscape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}

    private static void writePairReports(File root,AnalysisState state,List<File> metadata,List<File> nativeFiles)throws IOException{
        try(BufferedWriter w=new BufferedWriter(new FileWriter(new File(root,"IL2CPP_PAIR.txt")))){
            w.write("IL2CPP analysis inputs\n");w.write("Native candidates: "+nativeFiles.size()+"\n");w.write("Metadata candidates: "+metadata.size()+"\n");
            if(!nativeFiles.isEmpty()&&!metadata.isEmpty())w.write("Pairing status: both required artifacts were found. Native registration/RVA recovery is reported only when supported by available binary data.\n");
            else w.write("Pairing status: incomplete pair. Supply both libil2cpp.so and global-metadata.dat (or a package containing both) for full static analysis.\n");
        }
    }
    private static void writeEncryptionReport(File root, AnalysisState state, List<Artifact> artifacts) throws IOException {
        File out = new File(root, "encryption_analysis.txt");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write("Game encryption / transformation analysis\n");
            w.write("This report identifies standard representations and common simple transforms; it does not claim to break arbitrary game-specific encryption.\n\n");
            for (Artifact a : artifacts) {
                w.write("Artifact: " + a.file.getName() + "\n");
                w.write("Kind: " + a.kind + "\n");
                w.write("Size: " + a.file.length() + " bytes\n");
                w.write(String.format(Locale.US, "Sample entropy: %.4f bits/byte\n", shannonEntropy(readAtMost(a.file, (int)Math.min(1024L*1024L, a.file.length())))));
                if (a.kind == Kind.METADATA) {
                    MetadataHeader h = null;
                    try { h = readMetadataHeader(a.file); } catch (Exception ignored) {}
                    w.write("Standard metadata header: " + (h != null ? "yes (v" + h.version + ")" : "no") + "\n");
                    w.write("Simple transform candidate: " + (detectSimpleXor(a.file) == null ? "none detected" : detectSimpleXor(a.file)) + "\n");
                    int off = findMagic(a.file, 16 * 1024 * 1024);
                    w.write("Embedded standard-metadata magic offset: " + off + "\n");
                } else if (a.kind == Kind.IL2CPP) {
                    w.write("ELF header: " + (looksLikeElf(a.file) ? "yes" : "no") + "\n");
                }
                w.newLine();
            }
            if (state.metadataResult != null && state.metadataResult.protectedLike) {
                w.write("Conclusion: the supplied metadata representation was not statically validated as standard IL2CPP metadata. A runtime-memory representation may be required for game-specific protection.\n");
            } else if (state.metadataResult != null) {
                w.write("Conclusion: standard metadata was successfully validated or safely recovered.\n");
            }
        }
    }

    private static String writeAnalysisReport(File root,AnalysisState state,List<Artifact> artifacts)throws IOException{
        File report=new File(root,"analysis_report.txt");try(BufferedWriter w=new BufferedWriter(new FileWriter(report))){
            w.write("Untrusted Manager Game Analysis\n");w.write("Input: "+state.source.getAbsolutePath()+"\n");
            if(state.companion!=null)w.write("Companion: "+state.companion.getAbsolutePath()+"\n");
            w.write("Output: "+root.getAbsolutePath()+"\n\n");
            w.write("Open-source compatibility references: Perfare/Il2CppDumper metadata layouts; MyDearMoon/il2cpp-Dumper archive/envelope workflow; CameroonD/Il2CppMetadataExtractor runtime-decrypted metadata workflow.\n");
            w.write("UM performs offline/static analysis here; it does not inject, bypass anti-cheat, or disable anti-debugging/integrity protections.\n\n");
            for(Artifact a:artifacts)w.write(a.kind+"\t"+a.source+"\t"+a.file.getAbsolutePath()+"\tSHA256="+sha256(a.file)+"\n");
            if(state.metadataResult!=null){w.write("\nMetadata: "+state.metadataResult.message+"\n");if(state.metadataResult.header!=null)w.write("Metadata version: "+state.metadataResult.version+"\n");}
            if(state.nativeResults!=null){w.write("\nNative libraries:\n");for(ElfResult r:state.nativeResults)w.write(r.toText()+"\n");}
            File dummyDir = new File(root, "DummyDll");
            File[] dummyAssemblies = dummyDir.listFiles((d,n) -> n.toLowerCase(Locale.ENGLISH).endsWith(".dll"));
            w.write("\nDummyDll assemblies emitted: " + (dummyAssemblies == null ? 0 : dummyAssemblies.length) + "\n");
            w.write("\nProtection/encryption note: arbitrary game-specific encryption cannot be universally decrypted from an offline file alone. If the game only exposes the plaintext metadata after runtime initialization, a runtime-memory dump can be supplied to UM for static analysis.\n");
        }return report.getAbsolutePath();
    }

    private static String sha256(File f)throws IOException{try{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[]b=new byte[64*1024];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.US,"%02x",x));return s.toString();}catch(Exception e){throw new IOException(e);}}
    private static File newDumpDirectory()throws IOException{File root=dumpRoot();if(!root.exists()&&!root.mkdirs())throw new IOException("Cannot create "+root);String base="game_"+System.currentTimeMillis();File out=new File(root,base);int i=1;while(out.exists())out=new File(root,base+"_"+(i++));if(!out.mkdirs())throw new IOException("Cannot create dump directory");return out;}
    private static File copyIfNeeded(File f,File work,String name)throws IOException{File out=safeChild(work,name);if(!f.getCanonicalFile().equals(out.getCanonicalFile())){try(InputStream in=new FileInputStream(f);OutputStreamCopy o=new OutputStreamCopy(out)){o.copy(in);}}return out;}
    private static File safeChild(File dir,String name)throws IOException{File d=dir.getCanonicalFile();File f=new File(d,name).getCanonicalFile();if(!f.getPath().startsWith(d.getPath()+File.separator))throw new IOException("Unsafe path");return f;}
    private static void mkdir(File f)throws IOException{if(!f.isDirectory()&&!f.mkdirs()&&!f.isDirectory())throw new IOException("Cannot create "+f);}
    private static void writeText(File f,String s)throws IOException{try(FileWriter w=new FileWriter(f)){w.write(s);}}
    private static byte[] readAtMost(File f,int max)throws IOException{try(InputStream in=new FileInputStream(f)){ByteArrayOutputStream b=new ByteArrayOutputStream(Math.min(max,64*1024));byte[]buf=new byte[64*1024];int left=max,n;while(left>0&&(n=in.read(buf,0,Math.min(buf.length,left)))!=-1){b.write(buf,0,n);left-=n;}return b.toByteArray();}}
    private static byte[] readRange(RandomAccessFile raf,long off,long size)throws IOException{if(off<0||size<0||size>MAX_TABLE_READ_BYTES||size>Integer.MAX_VALUE||off>raf.length()||size>raf.length()-off)throw new IOException("Range too large or outside file");raf.seek(off);byte[]b=new byte[(int)size];raf.readFully(b);return b;}
    private static String sanitizeCSharpIdentifier(String s){String x=sanitizeIdentifier(s);if(CSHARP_KEYWORDS.contains(x))return "@"+x;return x;}
    private static String sanitizeCSharpNamespace(String s){String[] p=s.split("\\.");StringBuilder b=new StringBuilder();for(String x:p){if(b.length()>0)b.append('.');b.append(sanitizeCSharpIdentifier(x));}return b.toString();}
    private static final Set<String> CSHARP_KEYWORDS=new HashSet<>(Arrays.asList("abstract","as","base","bool","break","byte","case","catch","char","checked","class","const","continue","decimal","default","delegate","do","double","else","enum","event","explicit","extern","false","finally","fixed","float","for","foreach","goto","if","implicit","in","int","interface","internal","is","lock","long","namespace","new","null","object","operator","out","override","params","private","protected","public","readonly","ref","return","sbyte","sealed","short","sizeof","stackalloc","static","string","struct","switch","this","throw","true","try","typeof","uint","ulong","unchecked","unsafe","ushort","using","virtual","void","volatile","while","record","init","required","file","global","scoped","nint","nuint"));

    private static String sanitizeIdentifier(String s){if(s==null||s.isEmpty())return "_";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if((i==0&&Character.isJavaIdentifierStart(c))||(i>0&&Character.isJavaIdentifierPart(c)))b.append(c);else b.append('_');}if(!Character.isJavaIdentifierStart(b.charAt(0)))b.insert(0,'_');return b.toString();}
    private static String sanitizeNamespace(String s){String[]p=s.split("\\.");StringBuilder b=new StringBuilder();for(String x:p){if(b.length()>0)b.append('.');b.append(sanitizeIdentifier(x));}return b.toString();}
    private static long safeCount(long n){return n<0?0:Math.min(n,MAX_TYPES);}
    private static String safeName(String s){return s.replaceAll("[^A-Za-z0-9._-]","_");}
    private static boolean isMetadataName(String s){return s.endsWith("global-metadata.dat")||s.equals("metadata.dat");}
    private static boolean isIl2CppName(String s){return s.endsWith("libil2cpp.so")||s.endsWith("gameassembly.dll");}
    private static boolean isArchiveName(String s){return s.endsWith(".apk")||s.endsWith(".xapk")||s.endsWith(".apkm")||s.endsWith(".apks")||s.endsWith(".zip")||s.endsWith(".aab");}
    private static int u32(byte[]b,int o){return (b[o]&255)|((b[o+1]&255)<<8)|((b[o+2]&255)<<16)|((b[o+3]&255)<<24);}
    private static long u32l(byte[]b,int o,boolean le){long x=u32leOrBe(b,o,le);return x&0xffffffffL;}
    private static int u16(byte[]b,int o){return (b[o]&255)|((b[o+1]&255)<<8);}
    private static int u16(byte[]b,int o,boolean le){return le?u16(b,o):((b[o+1]&255)|((b[o]&255)<<8));}
    private static long u64(byte[]b,int o,boolean le){long x=0;if(le){for(int i=7;i>=0;i--)x=(x<<8)|(b[o+i]&255L);}else{for(int i=0;i<8;i++)x=(x<<8)|(b[o+i]&255L);}return x;}
    private static long u32leOrBe(byte[]b,int o,boolean le){if(le)return u32(b,o)&0xffffffffL;return ((b[o+3]&255L))|((b[o+2]&255L)<<8)|((b[o+1]&255L)<<16)|((b[o]&255L)<<24);}
    private static int i32(byte[]b,int o){return u32(b,o);}
    private static int u16(byte[]b,int o,boolean le,int dummy){return u16(b,o,le);}
    private static void putU32(byte[]b,int o,int x){b[o]=(byte)x;b[o+1]=(byte)(x>>>8);b[o+2]=(byte)(x>>>16);b[o+3]=(byte)(x>>>24);}
    private static String cString(byte[]b,int off){if(off<0||off>=b.length)return "";int i=off;while(i<b.length&&b[i]!=0)i++;return new String(b,off,i-off,StandardCharsets.UTF_8);}
    private static String machineName(int m){return switch(m){case 3->"x86";case 40->"ARM";case 62->"x86-64";case 183->"AArch64";case 243->"RISC-V";default->"machine-"+m;};}
    private static double shannonEntropy(byte[]b){if(b.length==0)return 0;int[]c=new int[256];for(byte x:b)c[x&255]++;double e=0;for(int n:c)if(n>0){double p=(double)n/b.length;e-=p*(Math.log(p)/Math.log(2));}return e;}

    private static final class OutputStreamCopy implements AutoCloseable {private final FileOutputStream out;OutputStreamCopy(File f)throws IOException{out=new FileOutputStream(f);}void copy(InputStream in)throws IOException{byte[]b=new byte[64*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}public void close()throws IOException{out.close();}}
    private enum Kind{METADATA,IL2CPP,OTHER}
    private record Artifact(File file,Kind kind,String source){}
    static final class StringTable {
        final LinkedHashMap<Long, String> byOffset = new LinkedHashMap<>();
        void put(long offset, String value) { byOffset.put(offset, value); }
        int size() { return byOffset.size(); }
        String get(long offset) { return byOffset.get(offset); }
    }
    private static final class AnalysisState{final File root;File source,companion;int sequence;int nestedApkCount;long extractedBytes;MetadataResult metadataResult;List<ElfResult> nativeResults;AnalysisState(File r){root=r;}}
    private record DumpStats(int images,int types,int methods,int fields,int properties,int events){}
    private record MetadataResult(boolean valid,boolean protectedLike,int version,MetadataHeader header,DumpStats stats,File file,String message){}

    private static final class MetadataHeader{
        final int magic,version;int layoutVersion;int headerBytes;long stringLiteralOffset,stringLiteralSize,stringLiteralDataOffset,stringLiteralDataSize,stringOffset,stringSize,eventsOffset,eventsSize,propertiesOffset,propertiesSize,methodsOffset,methodsSize,parameterDefaultValuesOffset,parameterDefaultValuesSize,fieldDefaultValuesOffset,fieldDefaultValuesSize,fieldAndParameterDefaultValueDataOffset,fieldAndParameterDefaultValueDataSize,fieldMarshaledSizesOffset,fieldMarshaledSizesSize,parametersOffset,parametersSize,fieldsOffset,fieldsSize,genericParametersOffset,genericParametersSize,genericParameterConstraintsOffset,genericParameterConstraintsSize,genericContainersOffset,genericContainersSize,nestedTypesOffset,nestedTypesSize,interfacesOffset,interfacesSize,vtableMethodsOffset,vtableMethodsSize,interfaceOffsetsOffset,interfaceOffsetsSize,typeDefinitionsOffset,typeDefinitionsSize,rgctxEntriesOffset,rgctxEntriesSize,imagesOffset,imagesSize,assembliesOffset,assembliesSize,metadataUsageListsOffset,metadataUsageListsSize,metadataUsagePairsOffset,metadataUsagePairsSize,fieldRefsOffset,fieldRefsSize,referencedAssembliesOffset,referencedAssembliesSize,attributesInfoOffset,attributesInfoSize,attributeTypesOffset,attributeTypesSize,attributeDataOffset,attributeDataSize,attributeDataRangeOffset,attributeDataRangeSize,unresolvedVirtualCallParameterTypesOffset,unresolvedVirtualCallParameterTypesSize,unresolvedVirtualCallParameterRangesOffset,unresolvedVirtualCallParameterRangesSize,windowsRuntimeTypeNamesOffset,windowsRuntimeTypeNamesSize,windowsRuntimeStringsOffset,windowsRuntimeStringsSize,exportedTypeDefinitionsOffset,exportedTypeDefinitionsSize;
        final LinkedHashMap<String,long[]> layout=new LinkedHashMap<>();
        MetadataHeader(int m,int v){magic=m;version=v;layoutVersion=v;}
        void read(RandomAccessFile r, long len) throws IOException {
            r.seek(8);
            int pairs = 0;
            long[] p;
            p = pair(r); stringLiteralOffset=p[0]; stringLiteralSize=p[1]; pairs++;
            p = pair(r); stringLiteralDataOffset=p[0]; stringLiteralDataSize=p[1]; pairs++;
            p = pair(r); stringOffset=p[0]; stringSize=p[1]; pairs++;
            if (version == 24 && stringLiteralOffset == 264) layoutVersion = 242;
            p = pair(r); eventsOffset=p[0]; eventsSize=p[1]; pairs++;
            p = pair(r); propertiesOffset=p[0]; propertiesSize=p[1]; pairs++;
            p = pair(r); methodsOffset=p[0]; methodsSize=p[1]; pairs++;
            p = pair(r); parameterDefaultValuesOffset=p[0]; parameterDefaultValuesSize=p[1]; pairs++;
            p = pair(r); fieldDefaultValuesOffset=p[0]; fieldDefaultValuesSize=p[1]; pairs++;
            p = pair(r); fieldAndParameterDefaultValueDataOffset=p[0]; fieldAndParameterDefaultValueDataSize=p[1]; pairs++;
            p = pair(r); fieldMarshaledSizesOffset=p[0]; fieldMarshaledSizesSize=p[1]; pairs++;
            p = pair(r); parametersOffset=p[0]; parametersSize=p[1]; pairs++;
            p = pair(r); fieldsOffset=p[0]; fieldsSize=p[1]; pairs++;
            p = pair(r); genericParametersOffset=p[0]; genericParametersSize=p[1]; pairs++;
            p = pair(r); genericParameterConstraintsOffset=p[0]; genericParameterConstraintsSize=p[1]; pairs++;
            p = pair(r); genericContainersOffset=p[0]; genericContainersSize=p[1]; pairs++;
            p = pair(r); nestedTypesOffset=p[0]; nestedTypesSize=p[1]; pairs++;
            p = pair(r); interfacesOffset=p[0]; interfacesSize=p[1]; pairs++;
            p = pair(r); vtableMethodsOffset=p[0]; vtableMethodsSize=p[1]; pairs++;
            p = pair(r); interfaceOffsetsOffset=p[0]; interfaceOffsetsSize=p[1]; pairs++;
            p = pair(r); typeDefinitionsOffset=p[0]; typeDefinitionsSize=p[1]; pairs++;
            if (version < 24 || (version == 24 && layoutVersion != 242)) { p=pair(r); rgctxEntriesOffset=p[0]; rgctxEntriesSize=p[1]; pairs++; }
            p=pair(r); imagesOffset=p[0]; imagesSize=p[1]; pairs++;
            p=pair(r); assembliesOffset=p[0]; assembliesSize=p[1]; pairs++;
            if (version >= 19 && version <= 24) { p=pair(r); metadataUsageListsOffset=p[0]; metadataUsageListsSize=p[1]; pairs++; p=pair(r); metadataUsagePairsOffset=p[0]; metadataUsagePairsSize=p[1]; pairs++; }
            if (version >= 19) { p=pair(r); fieldRefsOffset=p[0]; fieldRefsSize=p[1]; pairs++; }
            if (version >= 20) { p=pair(r); referencedAssembliesOffset=p[0]; referencedAssembliesSize=p[1]; pairs++; }
            if (version >= 21 && version <= 27) { p=pair(r); attributesInfoOffset=p[0]; attributesInfoSize=p[1]; pairs++; p=pair(r); attributeTypesOffset=p[0]; attributeTypesSize=p[1]; pairs++; }
            if (version >= 29) { p=pair(r); attributeDataOffset=p[0]; attributeDataSize=p[1]; pairs++; p=pair(r); attributeDataRangeOffset=p[0]; attributeDataRangeSize=p[1]; pairs++; }
            if (version >= 22) { p=pair(r); unresolvedVirtualCallParameterTypesOffset=p[0]; unresolvedVirtualCallParameterTypesSize=p[1]; pairs++; p=pair(r); unresolvedVirtualCallParameterRangesOffset=p[0]; unresolvedVirtualCallParameterRangesSize=p[1]; pairs++; }
            if (version >= 23) { p=pair(r); windowsRuntimeTypeNamesOffset=p[0]; windowsRuntimeTypeNamesSize=p[1]; pairs++; }
            if (version >= 27) { p=pair(r); windowsRuntimeStringsOffset=p[0]; windowsRuntimeStringsSize=p[1]; pairs++; }
            if (version >= 24) { p=pair(r); exportedTypeDefinitionsOffset=p[0]; exportedTypeDefinitionsSize=p[1]; pairs++; }
            if (version == 24 && layoutVersion != 242) {
                if (stringLiteralOffset == 264) {
                    layoutVersion = 242;
                } else {
                    layoutVersion = 24;
                    try {
                        if (imagesSize >= 40 && imagesOffset + 32 <= len) {
                            r.seek(imagesOffset + 28);
                            long token = readU32(r);
                            if (token != 1) layoutVersion = 241;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if(version==24 && layoutVersion==242 && imagesSize>0 && assembliesSize>0 && assembliesSize / 68 < imagesSize / 40) layoutVersion=244;
            else if(version==24 && layoutVersion==24 && imagesSize>0 && assembliesSize>0 && assembliesSize / 64 == imagesSize / 40) layoutVersion=244;
            headerBytes = 8 + pairs * 8;
            add("stringLiteral",stringLiteralOffset,stringLiteralSize); add("stringLiteralData",stringLiteralDataOffset,stringLiteralDataSize); add("string",stringOffset,stringSize);
            add("events",eventsOffset,eventsSize); add("properties",propertiesOffset,propertiesSize); add("methods",methodsOffset,methodsSize);
            add("parameters",parametersOffset,parametersSize); add("fields",fieldsOffset,fieldsSize); add("genericParameters",genericParametersOffset,genericParametersSize);
            add("genericContainers",genericContainersOffset,genericContainersSize); add("nestedTypes",nestedTypesOffset,nestedTypesSize); add("interfaces",interfacesOffset,interfacesSize);
            add("vtableMethods",vtableMethodsOffset,vtableMethodsSize); add("typeDefinitions",typeDefinitionsOffset,typeDefinitionsSize); add("images",imagesOffset,imagesSize);
            add("assemblies",assembliesOffset,assembliesSize); add("fieldRefs",fieldRefsOffset,fieldRefsSize);
        }
        private static long[] pair(RandomAccessFile r) throws IOException { return new long[]{readU32(r), readU32(r)}; }
        int layoutVersion() { return layoutVersion; }
        private void add(String n,long off,long size){long record=guessRecordSize(n);layout.put(n,new long[]{record,size,off});}
        private long guessRecordSize(String n){return switch(n){case"images"->imageDefSize(version,layoutVersion);case"typeDefinitions"->typeDefSize(layoutVersion());case"methods"->methodDefSize(layoutVersion());case"fields"->version<=24?16:12;case"properties"->version<=24?24:20;case"events"->version<=24?28:24;case"genericContainers"->16;case"genericParameters"->16;case"fieldRefs"->8;case"stringLiteral"->8;default->1;};}
        boolean isStructurallyValid(long len){
            if(magic!=METADATA_MAGIC||version<16||version>31||stringOffset<0||stringSize<0||stringOffset+stringSize>len)return false;
            return validTable(methodsOffset,methodsSize,methodDefSize(layoutVersion),len)
                    && validTable(fieldsOffset,fieldsSize,version<=24?16:12,len)
                    && validTable(typeDefinitionsOffset,typeDefinitionsSize,typeDefSize(layoutVersion),len)
                    && validTable(imagesOffset,imagesSize,imageDefSize(version,layoutVersion),len)
                    && validRange(eventsOffset,eventsSize,len) && validRange(propertiesOffset,propertiesSize,len);
        }
        private static boolean validRange(long off,long size,long len){return off>=0&&size>=0&&off<=len&&size<=len-off;}
        private static boolean validTable(long off,long size,long record,long len){return validRange(off,size,len)&&record>0&&(size==0||size%record==0);}
        private static long readU32(RandomAccessFile r)throws IOException{return Integer.toUnsignedLong(readIntLE(r));}
    }
    private static int readIntLE(RandomAccessFile r)throws IOException{int a=r.read(),b=r.read(),c=r.read(),d=r.read();if((a|b|c|d)<0)throw new EOFException("Unexpected EOF");return (a&255)|((b&255)<<8)|((c&255)<<16)|((d&255)<<24);}

    static final class ImageDef{final long nameIndex;final int assemblyIndex,typeStart;final long typeCount,token,exportedTypeStart,exportedTypeCount,customAttributeStart,customAttributeCount;ImageDef(long n,int a,int ts,long tc,int ep,long t,int ets,long etc,int cas,long cac){nameIndex=n;assemblyIndex=a;typeStart=ts;typeCount=tc;token=t;exportedTypeStart=ets;exportedTypeCount=etc;customAttributeStart=cas;customAttributeCount=cac;}}
    static final class TypeDef{long nameIndex,namespaceIndex,token,flags,bitfield;int declaringTypeIndex,parentIndex,elementTypeIndex,genericContainerIndex,fieldStart,methodStart,eventStart,propertyStart,nestedTypesStart,interfacesStart,vtableStart,interfaceOffsetsStart,customAttributeIndex,byvalTypeIndex,byrefTypeIndex,rgctxStart,rgctxCount;int methodCount,propertyCount,fieldCount,eventCount,nestedTypeCount,vtableCount,interfacesCount,interfaceOffsetsCount;}
    static final class MethodDef{long nameIndex,token;int declaringType,returnType,returnParameterToken,parameterStart,genericContainerIndex,methodIndex;int flags,iflags,slot,parameterCount;}
    static final class ParameterDef{long nameIndex,token;int typeIndex;}
    static final class FieldDef{long nameIndex,token;int typeIndex;}
    private static final class PropertyDef{long nameIndex,token,attrs;int get,set;}
    private static final class EventDef{long nameIndex,token;int typeIndex,add,remove,raise;}

    private static final class RegistrationData {
        final long codeAddress, metadataAddress;
        long fieldOffsetsAddress, fieldOffsetsCount, typesAddress, typesCount;
        final List<Long> methods=new ArrayList<>();
        final List<Long> fieldOffsets=new ArrayList<>();
        final List<CodeGenModule> modules=new ArrayList<>();
        final List<MethodPointerEntry> moduleMethods=new ArrayList<>();
        RegistrationData(long c,long m){codeAddress=c;metadataAddress=m;}
    }
    private record CodeGenModule(String name,long methodPointerCount,long methodPointersAddress) {}
    private record MethodPointerEntry(String module,long index,long pointer) {}

    private static final class Section{int index;long nameOffset,offset,size,flags,type,link,info,entsize;String name="";}
    private record LoadSegment(long fileOffset,long fileSize,long virtualAddress,long memorySize,long flags) {}
    private record SymbolHint(String name,long value,long fileOffset) {}
    private static final class ElfResult{
        final boolean elf; final int clazz; final String machine; final long entry,sectionOffset;
        final int sectionCount,sectionEntrySize,sectionStringIndex; final boolean le;
        final List<LoadSegment> loadSegments=new ArrayList<>(),execSegments=new ArrayList<>(),dataSegments=new ArrayList<>();
        ElfResult(boolean e,int c,String m,long en,long so,int sc){this(e,c,m,en,so,sc,true,0,-1);}
        ElfResult(boolean e,int c,String m,long en,long so,int sc,boolean l,int se,int ss){elf=e;clazz=c;machine=m;entry=en;sectionOffset=so;sectionCount=sc;le=l;sectionEntrySize=se;sectionStringIndex=ss;}
        String toText(){return "ELF="+elf+" class="+(clazz==2?"64":"32")+" machine="+machine+" entry=0x"+Long.toHexString(entry)+" sections="+sectionCount+" PT_LOAD="+loadSegments.size();}
    }
}
