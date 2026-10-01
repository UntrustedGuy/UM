package untrusted.manager.um.utils;

import android.content.Context;
import android.content.pm.PackageInfo;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

public class ApkCompareUtil {

    public static String compare(Context context, File apk1, File apk2) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageInfo p1 = ApkInfoUtil.getPackageInfo(context, apk1);
            PackageInfo p2 = ApkInfoUtil.getPackageInfo(context, apk2);

            sb.append("Package:\n");
            sb.append("  ").append(p1 == null ? "?" : p1.packageName).append('\n');
            if (p1 != null && p2 != null && !p1.packageName.equals(p2.packageName))
                sb.append("  ⚠ Different package names!\n");
            sb.append('\n');

            sb.append("Version code:\n");
            sb.append("  ").append(p1 == null ? "?" : ApkInfoUtil.getVersionCode(p1))
              .append("  vs  ").append(p2 == null ? "?" : ApkInfoUtil.getVersionCode(p2)).append('\n');
            sb.append('\n');

            sb.append("Version name:\n");
            sb.append("  ").append(p1 == null || p1.versionName == null ? "?" : p1.versionName)
              .append("  vs  ").append(p2 == null || p2.versionName == null ? "?" : p2.versionName).append('\n');
            sb.append('\n');

            sb.append("Min SDK:\n");
            sb.append("  ").append(p1 == null ? "?" : ApkInfoUtil.getMinSdk(p1))
              .append("  vs  ").append(p2 == null ? "?" : ApkInfoUtil.getMinSdk(p2)).append('\n');
            sb.append('\n');

            sb.append("Target SDK:\n");
            sb.append("  ").append(p1 == null || p1.applicationInfo == null ? "?" : p1.applicationInfo.targetSdkVersion)
              .append("  vs  ").append(p2 == null || p2.applicationInfo == null ? "?" : p2.applicationInfo.targetSdkVersion).append('\n');
            sb.append('\n');

            sb.append("Size (uncompressed):\n");
            sb.append("  ").append(FileSize.getHumanReadableFileSize(ApkInfoUtil.getTotalSize(apk1)))
              .append("  vs  ").append(FileSize.getHumanReadableFileSize(ApkInfoUtil.getTotalSize(apk2))).append('\n');
            sb.append('\n');

            compareEntries(apk1, apk2, sb);

            comparePermissions(p1, p2, sb);

            sb.append("Signer SHA-256:\n");
            try {
                List<X509Certificate> c1 = CertUtil.getCertificates(apk1);
                List<X509Certificate> c2 = CertUtil.getCertificates(apk2);
                String f1 = c1.isEmpty() ? "?" : CertUtil.getSha256(c1.get(0));
                String f2 = c2.isEmpty() ? "?" : CertUtil.getSha256(c2.get(0));
                sb.append("  ").append(f1).append('\n');
                sb.append("  ").append(f2).append('\n');
                if (!"?".equals(f1) && !"?".equals(f2)) sb.append(f1.equals(f2) ? "  ✓ Same signer" : "  ⚠ Different signers").append('\n');
            } catch (Exception e) {
                sb.append("  Failed to read signatures: ").append(e.getMessage()).append('\n');
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private static void compareEntries(File apk1, File apk2, StringBuilder sb) {
        Map<String, EntryInfo> entries1 = getEntries(apk1);
        Map<String, EntryInfo> entries2 = getEntries(apk2);
        if (entries1 == null || entries2 == null) {
            sb.append("Entries: failed to read one or both archives\n\n");
            return;
        }
        int same = 0;
        List<String> modified = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        try (ZipFile zf1 = new ZipFile(apk1); ZipFile zf2 = new ZipFile(apk2)) {
            for (Map.Entry<String, EntryInfo> e : entries2.entrySet()) {
                String name = e.getKey();
                EntryInfo v2 = e.getValue();
                EntryInfo v1 = entries1.get(name);
                if (v1 == null) added.add(name);
                else {
                    boolean equal = v1.size == v2.size && v1.crc == v2.crc;
                    ZipEntry z1 = zf1.getEntry(name);
                    ZipEntry z2 = zf2.getEntry(name);
                    if (equal && z1 != null && z2 != null && !z1.isDirectory()) {
                        equal = ComparisonDigest.sha256(zf1, z1).equals(ComparisonDigest.sha256(zf2, z2));
                    }
                    if (equal) same++; else modified.add(name);
                }
            }
        } catch (Exception e) {
            sb.append("Entries: hash verification failed: ").append(e.getMessage()).append('\n');
        }
        for (String name : entries1.keySet()) if (!entries2.containsKey(name)) removed.add(name);
        sb.append("Entries:\n");
        sb.append("  Identical: ").append(same).append('\n');
        sb.append("  Modified: ").append(modified.size()).append('\n');
        sb.append("  Added: ").append(added.size()).append('\n');
        sb.append("  Removed: ").append(removed.size()).append('\n');
        appendList(sb, "Modified entries", modified);
        appendList(sb, "Added entries", added);
        appendList(sb, "Removed entries", removed);
    }

    private static void appendList(StringBuilder sb, String title, List<String> list) {
        if (list.isEmpty()) return;
        sb.append('\n').append(title).append(':').append('\n');
        appendCapped(sb, list, 100);
    }

    private static void appendCapped(StringBuilder sb, List<String> list, int cap) {
        for (int i = 0; i < list.size() && i < cap; i++) sb.append("  ").append(list.get(i)).append('\n');
        if (list.size() > cap) sb.append("  … and ").append(list.size() - cap).append(" more\n");
    }

    private static Map<String, EntryInfo> getEntries(File apk) {
        Map<String, EntryInfo> map = new HashMap<>();
        try (ZipFile zf = new ZipFile(apk)) {
            List<FileHeader> headers = zf.getFileHeaders();
            if (headers == null) return null;
            for (FileHeader h : headers) {
                if (h.isDirectory()) continue;
                map.put(h.getFileName(), new EntryInfo(h.getUncompressedSize(), h.getCrc()));
            }
            return map;
        } catch (Exception e) { return null; }
    }

    private static final class EntryInfo {
        final long size;
        final long crc;
        EntryInfo(long size, long crc) { this.size = size; this.crc = crc; }
    }

    private static void comparePermissions(PackageInfo p1, PackageInfo p2, StringBuilder sb) {
        if (p1 == null || p2 == null || p1.requestedPermissions == null || p2.requestedPermissions == null) return;
        List<String> only1 = new ArrayList<>();
        List<String> only2 = new ArrayList<>();
        for (String p : p1.requestedPermissions) {
            boolean found = false;
            for (String q : p2.requestedPermissions) if (q != null && q.equals(p)) { found = true; break; }
            if (!found) only1.add(p);
        }
        for (String p : p2.requestedPermissions) {
            boolean found = false;
            for (String q : p1.requestedPermissions) if (q != null && q.equals(p)) { found = true; break; }
            if (!found) only2.add(p);
        }
        if (only1.isEmpty() && only2.isEmpty()) return;
        sb.append("Permissions:\n");
        if (!only1.isEmpty()) {
            sb.append("  Only in #1:\n");
            for (String p : only1) sb.append("    ").append(p).append('\n');
        }
        if (!only2.isEmpty()) {
            sb.append("  Only in #2:\n");
            for (String p : only2) sb.append("    ").append(p).append('\n');
        }
        sb.append('\n');
    }
}
