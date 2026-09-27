package untrusted.manager.um.gameanalysis;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
        File work = new File(root, "input");
        if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Cannot create dump directory");

        AnalysisState state = new AnalysisState(root);
        state.source = input;
        state.companion = companion;

        List<Artifact> artifacts = new ArrayList<>();
        artifacts.addAll(scanInput(input, work, state));
        if (companion != null && companion.isFile()) artifacts.addAll(scanCompanion(companion, work, state));

        // Prefer an explicitly supplied pair when it is valid, then any discovered pair.
        List<File> metadataFiles = new ArrayList<>();
        List<File> nativeFiles = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (a.kind == Kind.METADATA) metadataFiles.add(a.file);
            if (a.kind == Kind.IL2CPP) nativeFiles.add(a.file);
        }

        File bestMetadata = firstValidMetadata(metadataFiles);
        if (bestMetadata != null) {
            MetadataResult mr = parseMetadata(bestMetadata, new File(root, "metadata"));
            state.metadataResult = mr;
        } else if (!metadataFiles.isEmpty()) {
            File recovered = recoverSimpleMetadataRepresentation(metadataFiles.get(0), new File(root, "metadata"));
            if (recovered != null) {
                MetadataResult mr = parseMetadata(recovered, new File(root, "metadata_recovered"));
                state.metadataResult = new MetadataResult(mr.valid, false, mr.version, mr.header, mr.stats, recovered,
                        "Recovered a standard metadata representation from an envelope/simple transform; original input was preserved");
            } else {
                MetadataResult mr = inspectProtectedMetadata(metadataFiles.get(0), new File(root, "metadata"));
                state.metadataResult = mr;
            }
        }

        if (!nativeFiles.isEmpty()) {
            state.nativeResults = new ArrayList<>();
            for (File nativeFile : nativeFiles) {
                int methodCount = state.metadataResult != null && state.metadataResult.stats != null ? state.metadataResult.stats.methods : 0;
                int typeCount = state.metadataResult != null && state.metadataResult.stats != null ? state.metadataResult.stats.types : 0;
                int imageCount = state.metadataResult != null && state.metadataResult.stats != null ? state.metadataResult.stats.images : 0;
                int metadataVersion = state.metadataResult != null ? state.metadataResult.version : -1;
                state.nativeResults.add(analyzeElf(nativeFile, new File(root, "native_" + safeName(nativeFile.getName())), methodCount, typeCount, imageCount, metadataVersion));
            }
        }

        writePairReports(root, state, metadataFiles, nativeFiles);
        writeEncryptionReport(root, state, artifacts);
        String report = writeAnalysisReport(root, state, artifacts);
        return new Result(report, root);
    }

    private static List<Artifact> scanInput(File input, File work, AnalysisState state) throws IOException {
        String n = input.getName().toLowerCase(Locale.ENGLISH);
        if (isMetadataName(n) || looksLikeMetadataCandidate(input)) return List.of(new Artifact(copyIfNeeded(input, work, "global-metadata.dat"), Kind.METADATA, "direct"));
        if (isIl2CppName(n) || looksLikeElf(input)) return List.of(new Artifact(copyIfNeeded(input, work, "libil2cpp.so"), Kind.IL2CPP, "direct"));
        if (isArchiveName(n)) return scanArchive(input, work, state);
        return List.of(new Artifact(copyIfNeeded(input, work, safeName(input.getName())), Kind.OTHER, "direct"));
    }

    private static List<Artifact> scanCompanion(File input, File work, AnalysisState state) throws IOException {
        String n = input.getName().toLowerCase(Locale.ENGLISH);
        if (isMetadataName(n) || looksLikeMetadataCandidate(input)) return List.of(new Artifact(copyIfNeeded(input, work, "companion_global-metadata.dat"), Kind.METADATA, "companion"));
        if (isIl2CppName(n) || looksLikeElf(input)) return List.of(new Artifact(copyIfNeeded(input, work, "companion_libil2cpp.so"), Kind.IL2CPP, "companion"));
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
        long extracted = 0;
        int nestedApk = 0;
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
                boolean il2cppEntry = isIl2CppName(lower) || entryLooksLikeElf(zip, e);
                if (metadataEntry || il2cppEntry) {
                    String outName = (metadataEntry ? "metadata_" : "il2cpp_") + (++state.sequence) + "_" + safeName(new File(path).getName());
                    File out = safeChild(work, outName);
                    long bytes = extractEntry(zip, e, out, MAX_EXTRACT_BYTES - extracted);
                    extracted += bytes;
                    result.add(new Artifact(out, metadataEntry ? Kind.METADATA : Kind.IL2CPP, path));
                } else if (isArchiveName(lower) && lower.endsWith(".apk") && nestedApk < 16) {
                    File nested = safeChild(work, "nested_" + (++state.sequence) + ".apk");
                    long bytes = extractEntry(zip, e, nested, MAX_EXTRACT_BYTES - extracted);
                    extracted += bytes;
                    result.addAll(scanArchive(nested, work, state));
                }
            }
        }
        return result;
    }

    private static boolean entryLooksLikeMetadata(ZipFile zip, ZipEntry e) {
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = new byte[32]; int n = in.read(b);
            if (n >= 8 && u32(b, 0) == METADATA_MAGIC) { int v = u32(b, 4); return v >= 16 && v <= 31; }
            if (n >= 8) {
                for (int key=1; key<=255; key++) {
                    if (((b[0]&255)^key)!=(METADATA_MAGIC&255)) continue;
                    int v=(b[4]&255^key)|((b[5]&255^key)<<8)|((b[6]&255^key)<<16)|((b[7]&255^key)<<24);
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
            h.read(raf, file.length());
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

            List<ImageDef> images = readImages(raf, h);
            List<TypeDef> types = readTypes(raf, h);
            List<MethodDef> methods = readMethods(raf, h);
            List<FieldDef> fields = readFields(raf, h);
            List<PropertyDef> properties = readProperties(raf, h);
            List<EventDef> events = readEvents(raf, h);

            DumpStats stats = new DumpStats(images.size(), types.size(), methods.size(), fields.size(), properties.size(), events.size());
            writeDumpCs(outDir, h, strings, images, types, methods, fields, properties, events);
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
        if (size <= 0 || size > Integer.MAX_VALUE) return new StringTable();
        raf.seek(offset);
        byte[] data = new byte[(int) size];
        raf.readFully(data);
        StringTable result = new StringTable();
        StringBuilder current = new StringBuilder();
        int start = 0;
        for (int pos = 0; pos <= data.length; pos++) {
            int b = pos < data.length ? data[pos] & 0xff : 0;
            if (b >= 0x20 && b <= 0x7e) {
                if (current.length() == 0) start = pos;
                current.append((char) b);
            } else if (b == 0) {
                if (current.length() >= 1) result.put(start, current.toString());
                current.setLength(0);
                if (result.size() >= MAX_STRINGS) break;
            } else {
                if (current.length() >= 4) result.put(start, current.toString());
                current.setLength(0);
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
            if (h.version <= 24) { t.customAttributeIndex=i32(buf,p+o); o+=4; }
            t.byvalTypeIndex=i32(buf,p+o); o+=4;
            if (h.version <= 24) { t.byrefTypeIndex=i32(buf,p+o); o+=4; }
            t.declaringTypeIndex=i32(buf,p+o); o+=4; t.parentIndex=i32(buf,p+o); o+=4; t.elementTypeIndex=i32(buf,p+o); o+=4;
            if (h.version <= 24) { t.rgctxStart=i32(buf,p+o); o+=4; t.rgctxCount=i32(buf,p+o); o+=4; }
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
            if(h.version<=24){o+=4;}
            m.genericContainerIndex=i32(buf,p+o);o+=4;
            if(h.version<=24){o+=16;}
            m.token=u32(buf,p+o);o+=4;m.flags=u16(buf,p+o);o+=2;m.iflags=u16(buf,p+o);o+=2;m.slot=u16(buf,p+o);o+=2;m.parameterCount=u16(buf,p+o);
            return m;
        });
    }

    private static List<FieldDef> readFields(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = h.version >= 19 ? 12 : 12;
        return readRecords(raf, h.fieldsOffset, h.fieldsSize, size, (buf,p) -> {
            FieldDef f = new FieldDef(); f.nameIndex=u32(buf,p); f.typeIndex=i32(buf,p+4); f.token=h.version>=19?u32(buf,p+8):0; return f;
        });
    }

    private static List<PropertyDef> readProperties(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = h.version >= 19 ? 20 : 20;
        return readRecords(raf, h.propertiesOffset, h.propertiesSize, size, (buf,p) -> {
            PropertyDef x=new PropertyDef();x.nameIndex=u32(buf,p);x.get=i32(buf,p+4);x.set=i32(buf,p+8);x.attrs=u32(buf,p+12);x.token=h.version>=19?u32(buf,p+16):0;return x;
        });
    }

    private static List<EventDef> readEvents(RandomAccessFile raf, MetadataHeader h) throws IOException {
        int size = 24;
        return readRecords(raf, h.eventsOffset, h.eventsSize, size, (buf,p) -> {
            EventDef x=new EventDef();x.nameIndex=u32(buf,p);x.typeIndex=i32(buf,p+4);x.add=i32(buf,p+8);x.remove=i32(buf,p+12);x.raise=i32(buf,p+16);x.token=h.version>=19?u32(buf,p+20):0;return x;
        });
    }

    private interface Decoder<T> { T decode(byte[] buf, int p); }
    private static <T> List<T> readRecords(RandomAccessFile raf, long offset, long bytes, int recordSize, Decoder<T> decoder) throws IOException {
        if (recordSize <= 0 || bytes < recordSize || bytes % recordSize != 0) return Collections.emptyList();
        long countL = bytes / recordSize;
        if (countL > MAX_TYPES) throw new IOException("Metadata table is unreasonably large");
        int count = (int) countL;
        byte[] all = new byte[(int) bytes];
        raf.seek(offset); raf.readFully(all);
        List<T> out = new ArrayList<>(count);
        for (int i=0;i<count;i++) out.add(decoder.decode(all,i*recordSize));
        return out;
    }

    private static int typeDefSize(int v) {
        // Sizes derived from the version-gated Il2CppTypeDefinition fields in the
        // public Il2CppDumper metadata model (v24.x is distinguished where possible
        // by the metadata header itself).
        if (v <= 18) return 108;
        if (v <= 20) return 112;
        if (v <= 22) return 120;
        if (v == 23) return 104;
        if (v == 24) return 104;
        if (v == 242) return 88;
        if (v == 25 || v == 26) return 88;
        return 88;
    }
    private static int methodDefSize(int v) {
        if (v >= 31) return 36;
        if (v == 242 || v >= 25) return 32;
        return 56;
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
                    String name=sanitizeIdentifier(stringAt(strings,t.nameIndex,"Type_"+ti));
                    if(name.isEmpty()) name="Type_"+ti;
                    w.write("namespace "+(ns.isEmpty()?"Global":sanitizeNamespace(ns))+" {");w.newLine();
                    String kind=((t.bitfield&0x2)!=0)?"enum":((t.bitfield&0x1)!=0?"struct":"class");
                    w.write("    "+kind+" "+name+" {");w.newLine();
                    int fs=Math.max(0,t.fieldStart), fe=Math.min(fields.size(),fs+t.fieldCount);
                    for(int fi=fs;fi<fe;fi++) {
                        FieldDef f=fields.get(fi);w.write("        // token: 0x"+Long.toHexString(f.token));w.newLine();
                        w.write("        public object "+sanitizeIdentifier(stringAt(strings,f.nameIndex,"field_"+fi))+";");w.newLine();
                    }
                    int ms=Math.max(0,t.methodStart), me=Math.min(methods.size(),ms+t.methodCount);
                    for(int mi=ms;mi<me;mi++) {
                        MethodDef m=methods.get(mi);String mn=sanitizeIdentifier(stringAt(strings,m.nameIndex,"Method_"+mi));
                        w.write("        // token: 0x"+Long.toHexString(m.token)+" parameters: "+m.parameterCount);w.newLine();
                        w.write("        public void "+mn+"() { }");w.newLine();
                    }
                    int ps=Math.max(0,t.propertyStart), pe=Math.min(properties.size(),ps+t.propertyCount);
                    for(int pi=ps;pi<pe;pi++) w.write("        // property "+sanitizeIdentifier(stringAt(strings,properties.get(pi).nameIndex,"property_"+pi))+"\n");
                    int es=Math.max(0,t.eventStart), ee=Math.min(events.size(),es+t.eventCount);
                    for(int ei=es;ei<ee;ei++) w.write("        // event "+sanitizeIdentifier(stringAt(strings,events.get(ei).nameIndex,"event_"+ei))+"\n");
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

    private static ElfResult analyzeElf(File file, File outDir, int methodCount, int typeCount, int imageCount, int metadataVersion) throws IOException {
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
            writeIl2CppRegistrationHints(raf, r, outDir, methodCount, typeCount, imageCount, metadataVersion);
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

    private static void writeIl2CppRegistrationHints(RandomAccessFile raf, ElfResult r, File outDir,
                                                       int methodCount, int typeCount, int imageCount, int metadataVersion) throws IOException {
        File out = new File(outDir, "il2cpp_registration.txt");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out))) {
            w.write("IL2CPP registration discovery (static heuristic)\n");
            w.write("Metadata counts: methods=" + methodCount + " types=" + typeCount + " images=" + imageCount + "\n");
            List<SymbolHint> symbols = findRegistrationSymbols(raf, r);
            for (SymbolHint s : symbols) w.write("SYMBOL\t" + s.name + "\tVA=0x" + Long.toHexString(s.value) + "\tfile=0x" + Long.toHexString(s.fileOffset) + "\n");
            if (!symbols.isEmpty()) {
                w.write("\nNamed registration symbols were present; use those addresses with a full IL2CPP metadata/native parser.\n");
                return;
            }
            if (r.clazz != 2 || !r.le || imageCount <= 0) {
                w.write("Heuristic reference scan is limited to little-endian ELF64 because Android IL2CPP is commonly AArch64.\n");
                return;
            }
            byte[] all = readRange(raf, 0, Math.min(raf.length(), 256L * 1024L * 1024L));
            List<LoadSegment> data = r.loadSegments != null ? r.loadSegments : Collections.emptyList();
            List<LoadSegment> exec = r.execSegments != null ? r.execSegments : Collections.emptyList();
            long mscorlibFile = findAscii(all, "mscorlib.dll\0");
            if (mscorlibFile < 0) {
                w.write("No mscorlib.dll string was found in the scanned image.\n");
                return;
            }
            long mscorlibVa = mapFileToVa(data, mscorlibFile);
            if (mscorlibVa == 0) {
                w.write("mscorlib.dll was found, but its file offset could not be mapped through a PT_LOAD segment.\n");
                return;
            }
            List<Long> refs1 = findPointerReferences(raf, data, mscorlibVa, 200_000);
            w.write("mscorlib.dll file offset: 0x" + Long.toHexString(mscorlibFile) + " VA: 0x" + Long.toHexString(mscorlibVa) + " references: " + refs1.size() + "\n");
            Set<Long> refs2 = new HashSet<>();
            for (long ref : refs1) refs2.addAll(findPointerReferences(raf, data, ref, 20_000));
            w.write("second-level references: " + refs2.size() + "\n");
            int pointerSize=8;
            int maxCandidates=200;
            int found=0;
            for(long ref2:refs2){
                for(int i=Math.max(0,imageCount-1);i>=0;i--){
                    long target=ref2-(long)i*pointerSize;
                    for(long ref3:findPointerReferences(raf,data,target,2_000)){
                        long countAt=readVaPointer(raf,data,ref3-pointerSize);
                        if(countAt==imageCount){
                            int backPointers = metadataVersion >= 29 ? 14 : 13;
                            long reg=ref3-(long)backPointers*pointerSize;
                            w.write("CODE_REGISTRATION_CANDIDATE\tVA=0x"+Long.toHexString(reg)+"\tanchor=0x"+Long.toHexString(ref3)+"\n");
                            found++; if(found>=maxCandidates) break;
                        }
                    }
                    if(found>=maxCandidates)break;
                }
                if(found>=maxCandidates)break;
            }
            if(found==0)w.write("No high-confidence CodeRegistration candidate was found by the static heuristic.\n");
            else w.write("Candidates are hints only; stripped/protected Unity builds may require a full runtime/native dumper.\n");
            if(methodCount>0 && !exec.isEmpty())w.write("Executable PT_LOAD segments: "+exec.size()+"\n");
        }
    }

    private static List<SymbolHint> findRegistrationSymbols(RandomAccessFile raf, ElfResult r) throws IOException {
        List<SymbolHint> out=new ArrayList<>();
        if(r.sectionCount<=0)return out;
        for(Section sec:readSections(raf,r)){
            if(sec.type!=2&&sec.type!=11)continue;
            if(sec.entsize<=0||sec.link<0||sec.link>=r.sectionCount)continue;
            List<Section> sections=readSections(raf,r);Section str=sections.get((int)sec.link);byte[]names=readRange(raf,str.offset,Math.min(str.size,32L*1024*1024));long count=sec.size/sec.entsize;
            for(long i=0;i<count&&i<2_000_000;i++){
                byte[]b=readRange(raf,sec.offset+i*sec.entsize,sec.entsize);long no=u32l(b,0,r.le);String name=cString(names,(int)no);if(!name.equals("g_CodeRegistration")&&!name.equals("g_MetadataRegistration"))continue;long value=r.clazz==2?u64(b,8,r.le):u32l(b,4,r.le);long fo=mapVaToFile(r.loadSegments,value);out.add(new SymbolHint(name,value,fo));
            }
        }
        return out;
    }
    private static long findAscii(byte[] data,String needle){byte[]n=needle.getBytes(StandardCharsets.US_ASCII);outer:for(int i=0;i+n.length<=data.length;i++){for(int j=0;j<n.length;j++)if(data[i+j]!=n[j])continue outer;return i;}return -1;}
    private static long mapFileToVa(List<LoadSegment> segs,long off){for(LoadSegment s:segs)if(off>=s.fileOffset&&off<s.fileOffset+s.fileSize)return s.virtualAddress+(off-s.fileOffset);return 0;}
    private static long mapVaToFile(List<LoadSegment> segs,long va){for(LoadSegment s:segs)if(va>=s.virtualAddress&&va<s.virtualAddress+s.fileSize)return s.fileOffset+(va-s.virtualAddress);return -1;}
    private static List<Long> findPointerReferences(RandomAccessFile raf, List<LoadSegment> segs, long target, long limit) throws IOException {
        List<Long> out = new ArrayList<>();
        byte[] buf = new byte[64 * 1024];
        for (LoadSegment s : segs) {
            long start = s.fileOffset;
            long end = Math.min(s.fileOffset + s.fileSize, raf.length());
            long base = start;
            raf.seek(start);
            while (base < end) {
                int want = (int)Math.min(buf.length, end - base);
                int n = raf.read(buf, 0, want);
                if (n <= 0) break;
                int usable = n - (n % 8);
                for (int i=0;i<usable;i+=8) {
                    long x=0; for(int j=0;j<8;j++) x |= (long)(buf[i+j]&255) << (8*j);
                    if (x == target) {
                        out.add(s.virtualAddress + (base + i - s.fileOffset));
                        if (out.size() >= limit) return out;
                    }
                }
                base += n;
                if (n < want) break;
            }
        }
        return out;
    }
    private static long readVaPointer(RandomAccessFile raf,List<LoadSegment> segs,long va)throws IOException{long fo=mapVaToFile(segs,va);if(fo<0)return -1;raf.seek(fo);return readLongLE(raf);}
    private static long readLongLE(RandomAccessFile r)throws IOException{long x=0;for(int i=0;i<8;i++){int b=r.read();if(b<0)throw new IOException("Unexpected EOF");x|=(long)b<<(8*i);}return x;}

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
    private static byte[] readRange(RandomAccessFile raf,long off,long size)throws IOException{if(size<0||size>Integer.MAX_VALUE)throw new IOException("Range too large");raf.seek(off);byte[]b=new byte[(int)size];raf.readFully(b);return b;}
    private static String stringAt(StringTable strings,long index,String fallback){String s=strings.get(index);return s==null?fallback:s;}
    private static String sanitizeIdentifier(String s){if(s==null||s.isEmpty())return "_";StringBuilder b=new StringBuilder();for(int i=0;i<s.length();i++){char c=s.charAt(i);if((i==0&&Character.isJavaIdentifierStart(c))||(i>0&&Character.isJavaIdentifierPart(c)))b.append(c);else b.append('_');}if(!Character.isJavaIdentifierStart(b.charAt(0)))b.insert(0,'_');return b.toString();}
    private static String sanitizeNamespace(String s){String[]p=s.split("\\.");StringBuilder b=new StringBuilder();for(String x:p){if(b.length()>0)b.append('.');b.append(sanitizeIdentifier(x));}return b.toString();}
    private static long safeCount(long n){return n<0?0:Math.min(n,MAX_TYPES);}
    private static String safeName(String s){return s.replaceAll("[^A-Za-z0-9._-]","_");}
    private static boolean isMetadataName(String s){return s.endsWith("global-metadata.dat")||s.equals("metadata.dat");}
    private static boolean isIl2CppName(String s){return s.endsWith("libil2cpp.so")||s.endsWith("gameassembly.dll")||s.endsWith("globalgamemanagers");}
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
    private static final class StringTable {
        final LinkedHashMap<Long, String> byOffset = new LinkedHashMap<>();
        void put(long offset, String value) { byOffset.put(offset, value); }
        int size() { return byOffset.size(); }
        String get(long offset) { return byOffset.get(offset); }
    }
    private static final class AnalysisState{final File root;File source,companion;int sequence;MetadataResult metadataResult;List<ElfResult> nativeResults;AnalysisState(File r){root=r;}}
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
            if (version <= 24) { p=pair(r); rgctxEntriesOffset=p[0]; rgctxEntriesSize=p[1]; pairs++; }
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
            if (version == 24 && stringLiteralOffset == 264) layoutVersion = 242;
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
        private long guessRecordSize(String n){return switch(n){case"images"->version>=24?40:version>=19?24:20;case"typeDefinitions"->typeDefSize(layoutVersion());case"methods"->methodDefSize(layoutVersion());case"fields"->12;case"properties"->20;case"events"->24;case"genericContainers"->16;case"genericParameters"->16;case"fieldRefs"->8;case"stringLiteral"->8;default->1;};}
        boolean isStructurallyValid(long len){
            if(magic!=METADATA_MAGIC||version<16||version>31||stringOffset<0||stringSize<0||stringOffset+stringSize>len)return false;
            return validTable(methodsOffset,methodsSize,methodDefSize(layoutVersion),len)
                    && validTable(fieldsOffset,fieldsSize,12,len)
                    && validTable(typeDefinitionsOffset,typeDefinitionsSize,typeDefSize(layoutVersion),len)
                    && validTable(imagesOffset,imagesSize,version>=24?40:(version>=19?24:20),len)
                    && validRange(eventsOffset,eventsSize,len) && validRange(propertiesOffset,propertiesSize,len);
        }
        private static boolean validRange(long off,long size,long len){return off>=0&&size>=0&&off<=len&&size<=len-off;}
        private static boolean validTable(long off,long size,long record,long len){return validRange(off,size,len)&&record>0&&(size==0||size%record==0);}
        private static long readU32(RandomAccessFile r)throws IOException{return Integer.toUnsignedLong(readIntLE(r));}
    }
    private static int readIntLE(RandomAccessFile r)throws IOException{int a=r.read(),b=r.read(),c=r.read(),d=r.read();if((a|b|c|d)<0)throw new IOException("Unexpected EOF");return (a&255)|((b&255)<<8)|((c&255)<<16)|((d&255)<<24);}

    private static final class ImageDef{final long nameIndex;final int assemblyIndex,typeStart;final long typeCount,token,exportedTypeStart,exportedTypeCount,customAttributeStart,customAttributeCount;ImageDef(long n,int a,int ts,long tc,int ep,long t,int ets,long etc,int cas,long cac){nameIndex=n;assemblyIndex=a;typeStart=ts;typeCount=tc;token=t;exportedTypeStart=ets;exportedTypeCount=etc;customAttributeStart=cas;customAttributeCount=cac;}}
    private static final class TypeDef{long nameIndex,namespaceIndex,token,flags,bitfield;int declaringTypeIndex,parentIndex,elementTypeIndex,genericContainerIndex,fieldStart,methodStart,eventStart,propertyStart,nestedTypesStart,interfacesStart,vtableStart,interfaceOffsetsStart,customAttributeIndex,byvalTypeIndex,byrefTypeIndex,rgctxStart,rgctxCount;int methodCount,propertyCount,fieldCount,eventCount,nestedTypeCount,vtableCount,interfacesCount,interfaceOffsetsCount;}
    private static final class MethodDef{long nameIndex,token;int declaringType,returnType,returnParameterToken,parameterStart,genericContainerIndex;int flags,iflags,slot,parameterCount;}
    private static final class FieldDef{long nameIndex,token;int typeIndex;}
    private static final class PropertyDef{long nameIndex,token,attrs;int get,set;}
    private static final class EventDef{long nameIndex,token;int typeIndex,add,remove,raise;}

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
