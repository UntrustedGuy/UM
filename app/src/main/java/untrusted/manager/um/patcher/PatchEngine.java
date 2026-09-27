package untrusted.manager.um.patcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Xml;
import dalvik.system.DexClassLoader;

import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.VersionMap;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.dexbacked.raw.HeaderItem;
import com.android.tools.smali.smali.Smali;
import com.reandroid.apk.ApkModule;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.xmlpull.v1.XmlPullParser;

import untrusted.manager.um.utils.FastDexPatch;

/**
 * APK patch engine for the patch formats documented by APK Editor/PCompiler and
 * Lucky Patcher custom patches.
 *
 * APK Editor rules: ADD_FILES, REMOVE_FILES, MATCH_REPLACE, MATCH_ASSIGN,
 * MATCH_GOTO, GOTO, MERGE, EXECUTE_DEX and DUMMY. Metadata sections such as
 * PACKAGE/AUTHOR/MIN_ENGINE_VER are accepted and ignored by the executor.
 * Lucky Patcher supports CLASSES/ODEX/LIB byte-pattern rules with double-star/question-mark wildcards
 * and R/W capture registers.
 *
 * Patches are executed in a private work directory and the original APK is never
 * modified in place. The resulting APK is unsigned because changing its bytes
 * invalidates an existing APK v2/v3/v4 signature; callers must sign before install.
 */
public final class PatchEngine {
    public record Result(boolean success, String message, File output) {}

    private static final int MAX_PATCH_ARCHIVE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_APK_BYTES = 1024 * 1024 * 1024;
    private PatchEngine() {}

    public static Result apply(File apk, File patchZip, File outputDir) {
        return apply(null, apk, patchZip, outputDir);
    }

    public static Result apply(Context context, File apk, File patchZip, File outputDir) {
        File effectiveOutputDir = outputDir;
        if (effectiveOutputDir == null) effectiveOutputDir = apk == null ? null : apk.getParentFile();
        if (effectiveOutputDir == null) return fail("No patch output directory is available");
        File work = new File(effectiveOutputDir, "patch-work-" + System.currentTimeMillis());
        try {
            requireFile(apk, "APK");
            requireFile(patchZip, "patch archive");
            if (apk.length() > MAX_APK_BYTES) throw new IOException("APK is too large for the patch engine");
            if (patchZip.length() > MAX_PATCH_ARCHIVE_BYTES) throw new IOException("Patch archive is too large");
            if (!work.mkdirs() && !work.isDirectory()) throw new IOException("Cannot create patch workspace");

            File extractedPatch = new File(work, "patch");
            File apkWork = new File(work, "apk");
            mkdirs(extractedPatch);
            mkdirs(apkWork);
            unzip(patchZip, extractedPatch, MAX_PATCH_ARCHIVE_BYTES);
            unzip(apk, apkWork, MAX_APK_BYTES);

            File patchTxt = findPatchTxt(extractedPatch);
            File lucky = findLuckyPatchTxt(extractedPatch);
            if (patchTxt == null) {
                if (lucky == null) return failAndCleanup(work, "Patch archive contains neither patch.txt nor a Lucky Patcher patch text");
                boolean changed = applyLuckyPatcher(apkWork, lucky);
                if (!changed) return failAndCleanup(work, "Lucky Patcher patch did not change the selected APK");
                return finish(apk, effectiveOutputDir, apkWork, work, "Lucky Patcher patch applied");
            }
            // Lucky Patcher archives are also commonly named patch.txt. Prefer
            // their byte-pattern grammar when the file contains LP sections and
            // no APK Editor rule headers; otherwise retain normal patch.txt semantics.
            String patchText = Files.readString(patchTxt.toPath(), StandardCharsets.UTF_8);
            if (looksLikeLuckyPatcher(patchText) && !looksLikeApkEditorPatch(patchText)) {
                boolean changed = applyLuckyPatcher(apkWork, patchTxt);
                if (!changed) return failAndCleanup(work, "Lucky Patcher patch did not change the selected APK");
                return finish(apk, effectiveOutputDir, apkWork, work, "Lucky Patcher patch applied");
            }

            List<Rule> rules = parse(patchTxt);
            Map<String, String> vars = new LinkedHashMap<>();
            Map<String, String> metadata = new LinkedHashMap<>();
            for (Rule r : rules) if (r.name != null && !r.name.isEmpty()) vars.putIfAbsent("RULE_" + r.name, r.name);
            String packageConstraint = "";
            for (Rule r : rules) {
                if ("PACKAGE".equals(r.type)) packageConstraint = expand(firstValue(r), vars).trim();
            }
            if (!packageConstraint.isEmpty() && !"*".equals(packageConstraint)) {
                String actualPackage = readPackageName(context, apk);
                if (actualPackage == null || !packageConstraint.equals(actualPackage)) {
                    return failAndCleanup(work, "Patch package mismatch: expected " + packageConstraint + ", APK is " + (actualPackage == null ? "unknown" : actualPackage));
                }
            }
            boolean changed = false;
            int executedRules = 0;
            boolean smaliPrepared = false;
            boolean smaliDirty = false;

            for (int i = 0; i < rules.size(); i++) {
                if (++executedRules > 10000) throw new IOException("Patch exceeded the 10000-rule execution limit (possible GOTO loop)");
                Rule r = rules.get(i);
                if (isMetadata(r.type)) {
                    if (!r.get("VALUE").isEmpty()) metadata.put(r.type, expand(r.get("VALUE"), vars));
                    continue;
                }
                switch (r.type) {
                    case "DUMMY":
                        // DUMMY is a named terminal label in the APK Editor engine.
                        return changed ? finish(apk, effectiveOutputDir, apkWork, work, "Patch applied")
                                : failAndCleanup(work, "Patch reached DUMMY before making a change");
                    case "GOTO": {
                        int j = indexOf(rules, r.get("GOTO"));
                        if (j < 0) throw new IOException("GOTO target not found: " + r.get("GOTO"));
                        i = j - 1;
                        break;
                    }
                    case "MATCH_GOTO": {
                        String targetSpec = expand(r.get("TARGET"), vars);
                        if ((targetSpec.contains("[LAUNCHER_ACTIVITIES]") || targetSpec.contains("[SMALI]")) && !smaliPrepared) {
                            prepareSmali(apkWork, work); smaliPrepared = true;
                        }
                        List<File> targets = resolveTextTargets(context, apk, apkWork, targetSpec);
                        boolean found = false;
                        for (File target : targets) {
                            if (matches(target, expand(r.get("MATCH"), vars), bool(r.get("REGEX")))) {
                                found = true;
                                break;
                            }
                        }
                        if (found) {
                            int j = indexOf(rules, r.get("GOTO"));
                            if (j < 0) throw new IOException("MATCH_GOTO target not found: " + r.get("GOTO"));
                            i = j - 1;
                        }
                        break;
                    }
                    case "ADD_FILES":
                        changed |= addFiles(extractedPatch, apkWork, r, vars);
                        break;
                    case "REMOVE_FILES":
                        changed |= removeFiles(apkWork, r, vars);
                        break;
                    case "MATCH_REPLACE": {
                        String targetSpec = expand(r.get("TARGET"), vars);
                        if (targetSpec.contains("[LAUNCHER_ACTIVITIES]") || targetSpec.contains("[SMALI]")) {
                            if (!smaliPrepared) { prepareSmali(apkWork, work); smaliPrepared = true; }
                        }
                        boolean localChanged = matchReplace(context, apk, apkWork, r, vars, bool(r.get("SMALI_NEEDED")));
                        changed |= localChanged;
                        smaliDirty |= localChanged && smaliPrepared;
                        break;
                    }
                    case "MATCH_ASSIGN": {
                        String targetSpec = expand(r.get("TARGET"), vars);
                        if ((targetSpec.contains("[LAUNCHER_ACTIVITIES]") || targetSpec.contains("[SMALI]")) && !smaliPrepared) {
                            prepareSmali(apkWork, work); smaliPrepared = true;
                        }
                        changed |= matchAssign(context, apk, apkWork, r, vars);
                        break;
                    }
                    case "MERGE":
                        changed |= merge(extractedPatch, apkWork, r, vars);
                        break;
                    case "EXECUTE_DEX":
                        if (context == null) throw new IOException("EXECUTE_DEX requires an Android context");
                        if (bool(r.get("SMALI_NEEDED")) && !smaliPrepared) {
                            prepareSmali(apkWork, work);
                            smaliPrepared = true;
                        }
                        executeDex(context, extractedPatch, apk, patchZip, apkWork, work, r, vars);
                        changed = true;
                        smaliDirty = smaliDirty || bool(r.get("SMALI_NEEDED"));
                        break;
                    default:
                        throw new IOException("Unsupported patch rule: [" + r.type + "]");
                }
            }
            if (smaliPrepared && smaliDirty) rebuildChangedSmali(apkWork, work);
            if (!changed) return failAndCleanup(work, "Patch made no changes");
            return finish(apk, effectiveOutputDir, apkWork, work, "APK Editor patch applied");
        } catch (Throwable e) {
            delete(work);
            String message = e.getMessage();
            return fail(message == null || message.isEmpty() ? e.toString() : message);
        }
    }

    private static Result finish(File apk, File outputDir, File apkWork, File work, String prefix) throws IOException {
        stripInvalidatedSignatures(apkWork);
        mkdirs(outputDir);
        File out = new File(outputDir, apk.getName().replaceFirst("(?i)\\.apk$", "") + "-patched.apk");
        if (out.exists() && !out.delete()) throw new IOException("Cannot replace existing output: " + out);
        zipDirectory(apkWork, out);
        delete(work);
        return new Result(true, prefix + ": " + out.getAbsolutePath() + " (sign before installing)", out);
    }

    private static Result fail(String message) { return new Result(false, message, null); }

    private static Result failAndCleanup(File work, String message) { delete(work); return fail(message); }

    private static void requireFile(File f, String label) throws IOException {
        if (f == null || !f.isFile() || !f.canRead()) throw new IOException("Cannot read " + label);
    }

    private static boolean addFiles(File patchRoot, File apkRoot, Rule r, Map<String, String> vars) throws IOException {
        String source = expand(r.get("SOURCE"), vars).trim();
        String target = expand(r.get("TARGET"), vars).trim();
        if (source.isEmpty() || target.isEmpty()) throw new IOException("ADD_FILES requires SOURCE and TARGET");
        File src = target(patchRoot, source);
        if (!src.exists()) throw new IOException("Missing patch source: " + source);
        File dst = target(apkRoot, target);
        if (bool(r.get("EXTRACT"))) {
            if (!src.isFile()) throw new IOException("EXTRACT source must be a zip file: " + source);
            if (!src.getName().toLowerCase(Locale.US).endsWith(".zip")) throw new IOException("EXTRACT source is not a zip: " + source);
            mkdirs(dst);
            unzip(src, dst, MAX_PATCH_ARCHIVE_BYTES);
            return true;
        }
        // APK Editor treats TARGET as a destination path. For a file source, an
        // existing directory receives the source basename; otherwise TARGET is
        // the exact output file path. For directories TARGET is the destination
        // directory and the directory's contents are copied into it.
        if (src.isDirectory()) {
            mkdirs(dst);
            File[] children = src.listFiles();
            if (children != null) for (File child : children) copy(child, new File(dst, child.getName()));
        } else {
            boolean targetDirectory = target.endsWith("/") || dst.isDirectory();
            File actual;
            if (targetDirectory) {
                mkdirs(dst);
                actual = new File(dst, src.getName());
            } else {
                actual = dst;
            }
            copy(src, actual);
        }
        return true;
    }

    private static boolean removeFiles(File apkRoot, Rule r, Map<String, String> vars) throws IOException {
        String value = expand(r.get("TARGET"), vars);
        boolean changed = false;
        for (String line : value.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            File f = target(apkRoot, t);
            if (f.exists()) { delete(f); changed = true; }
        }
        return changed;
    }

    private static boolean matchReplace(Context context, File originalApk, File apkRoot, Rule r, Map<String, String> vars, boolean smaliMode) throws IOException {
        List<File> files = resolveTextTargets(context, originalApk, apkRoot, expand(r.get("TARGET"), vars));
        if (files.isEmpty()) return false;
        String match = expand(r.get("MATCH"), vars);
        String repl = expand(r.get("REPLACE"), vars);
        boolean changed = false;
        for (File f : files) {
            String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            String n;
            if (bool(r.get("REGEX"))) {
                Matcher m = Pattern.compile(match, Pattern.MULTILINE | Pattern.DOTALL).matcher(text);
                n = m.replaceAll(Matcher.quoteReplacement(repl));
            } else {
                n = text.replace(match, repl);
            }
            if (!n.equals(text)) {
                Files.writeString(f.toPath(), n, StandardCharsets.UTF_8);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean matchAssign(Context context, File originalApk, File apkRoot, Rule r, Map<String, String> vars) throws IOException {
        List<File> files = resolveTextTargets(context, originalApk, apkRoot, expand(r.get("TARGET"), vars));
        String match = expand(r.get("MATCH"), vars);
        Pattern p = bool(r.get("REGEX")) ? Pattern.compile(match, Pattern.MULTILINE | Pattern.DOTALL) : Pattern.compile(Pattern.quote(match), Pattern.MULTILINE | Pattern.DOTALL);
        for (File f : files) {
            Matcher m = p.matcher(Files.readString(f.toPath(), StandardCharsets.UTF_8));
            if (!m.find()) continue;
            for (String line : r.get("ASSIGN").split("\\r?\\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String name = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                Matcher gm = Pattern.compile("\\$\\s*\\{GROUP(\\d+)\\}").matcher(value);
                StringBuffer sb = new StringBuffer();
                while (gm.find()) {
                    int group = Integer.parseInt(gm.group(1));
                    String gv = group <= m.groupCount() ? Optional.ofNullable(m.group(group)).orElse("") : "";
                    gm.appendReplacement(sb, Matcher.quoteReplacement(gv));
                }
                gm.appendTail(sb);
                vars.put(name, expand(sb.toString(), vars));
            }
            return true;
        }
        return false;
    }

    private static boolean merge(File patchRoot, File apkRoot, Rule r, Map<String, String> vars) throws IOException {
        String source = expand(r.get("SOURCE"), vars).trim();
        if (source.isEmpty()) throw new IOException("MERGE requires SOURCE");
        File src = target(patchRoot, source);
        if (!src.exists()) throw new IOException("Missing merge source: " + source);

        File mergeRoot = new File(patchRoot, "merge-runtime-" + System.nanoTime());
        mkdirs(mergeRoot);
        File sourceApk;
        if (src.isFile() && src.getName().toLowerCase(Locale.US).endsWith(".apk")) {
            sourceApk = src;
        } else {
            File sourceDir = src;
            if (src.isFile() && src.getName().toLowerCase(Locale.US).endsWith(".zip")) {
                sourceDir = new File(mergeRoot, "source");
                mkdirs(sourceDir);
                unzip(src, sourceDir, MAX_PATCH_ARCHIVE_BYTES);
            }
            if (!sourceDir.isDirectory()) throw new IOException("MERGE source must be an APK, directory, or zip archive");
            // APK Editor's MERGE resource form is an unpacked resource tree. ReAndroid's
            // module merger performs resource-table ID remapping when the source contains
            // a resources.arsc. If there is no resource table, the operation is a normal
            // collision-safe file merge instead of pretending IDs can be remapped.
            File table = new File(sourceDir, "resources.arsc");
            if (!table.isFile()) {
                boolean changed = false;
                List<Path> paths = new ArrayList<>();
                Files.walk(sourceDir.toPath()).filter(Files::isRegularFile).forEach(paths::add);
                for (Path p : paths) {
                    String rel = sourceDir.toPath().relativize(p).toString().replace(File.separatorChar, '/');
                    File dst = target(apkRoot, rel);
                    copy(p.toFile(), dst);
                    changed = true;
                }
                return changed;
            }
            sourceApk = new File(mergeRoot, "source.apk");
            zipDirectory(sourceDir, sourceApk);
        }

        File baseApk = new File(mergeRoot, "base.apk");
        zipDirectory(apkRoot, baseApk);
        File merged = new File(mergeRoot, "merged.apk");
        try (ApkModule base = ApkModule.loadApkFile(baseApk);
             ApkModule incoming = ApkModule.loadApkFile(sourceApk)) {
            base.merge(incoming, true);
            if (base.hasTableBlock()) base.refreshTable();
            if (base.hasAndroidManifest()) base.refreshManifest();
            base.writeApk(merged);
        }
        delete(apkRoot);
        mkdirs(apkRoot);
        unzip(merged, apkRoot, MAX_APK_BYTES);
        return true;
    }

    private static void executeDex(Context context, File patchRoot, File apk, File patchZip, File apkWork,
                                   File work, Rule r, Map<String, String> vars) throws Exception {
        String script = expand(r.get("SCRIPT"), vars).trim();
        String mainClass = expand(r.get("MAIN_CLASS"), vars).trim().replace('/', '.');
        String entrance = expand(r.get("ENTRANCE"), vars).trim();
        String param = expand(r.get("PARAM"), vars);
        if (script.isEmpty() || mainClass.isEmpty() || entrance.isEmpty()) throw new IOException("EXECUTE_DEX requires SCRIPT, MAIN_CLASS and ENTRANCE");
        File dex = target(patchRoot, script);
        if (!dex.isFile()) throw new IOException("Missing EXECUTE_DEX script: " + script);
        File dexDir = new File(work, "dex-exec");
        mkdirs(dexDir);
        File optimized = new File(dexDir, "optimized"); mkdirs(optimized);
        DexClassLoader loader = new DexClassLoader(dex.getAbsolutePath(), optimized.getAbsolutePath(), null, context.getClassLoader());
        Class<?> clazz = loader.loadClass(mainClass);
        Method method = null;
        for (Method candidate : clazz.getDeclaredMethods()) {
            if (candidate.getName().equals(entrance) && candidate.getParameterTypes().length == 4) {
                Class<?>[] t = candidate.getParameterTypes();
                if (t[0] == String.class && t[1] == String.class && t[2] == String.class && t[3] == String.class) { method = candidate; break; }
            }
        }
        if (method == null) throw new NoSuchMethodException(mainClass + "#" + entrance + "(String,String,String,String)");
        method.setAccessible(true);
        Object receiver = java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? null : newInstance(clazz);
        Object result = method.invoke(receiver, apk.getAbsolutePath(), patchZip.getAbsolutePath(), apkWork.getAbsolutePath(), param);
        if (result instanceof Boolean && !((Boolean) result)) throw new IOException("EXECUTE_DEX script returned false");
    }

    private static Object newInstance(Class<?> clazz) throws Exception {
        Constructor<?> c = clazz.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private static void prepareSmali(File apkRoot, File work) throws Exception {
        File smali = new File(apkRoot, "smali");
        mkdirs(smali);
        File map = new File(work, "smali-map.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(map.toPath(), StandardCharsets.UTF_8)) {
            for (File dex : dexFiles(apkRoot)) {
                byte[] bytes = Files.readAllBytes(dex.toPath());
                int api = VersionMap.mapDexVersionToApi(HeaderItem.getVersion(bytes, 0));
                DexBackedDexFile db = new DexBackedDexFile(
                        com.android.tools.smali.dexlib2.Opcodes.forApi(Math.max(1, api)), bytes);
                File one = new File(work, "smali-source-" + dex.getName());
                mkdirs(one);
                BaksmaliOptions options = FastDexPatch.defaultBaksmaliOptions();
                options.apiLevel = Math.max(1, api);
                FastDexPatch.disassembleClasses(db, allDescriptors(db), one, options, null);
                List<File> files = new ArrayList<>();
                collectFiles(one, files, ".smali");
                for (File source : files) {
                    Path rel = one.toPath().relativize(source.toPath());
                    File destination = new File(smali, rel.toString());
                    copy(source, destination);
                    writer.write(rel.toString().replace(File.separatorChar, '/') + "=" + dex.getName());
                    writer.newLine();
                }
            }
        }
    }

    private static Set<String> allDescriptors(DexBackedDexFile dex) {
        Set<String> result = new LinkedHashSet<>();
        for (com.android.tools.smali.dexlib2.iface.ClassDef c : dex.getClasses()) result.add(c.getType());
        return result;
    }

    private static void rebuildChangedSmali(File apkRoot, File work) throws Exception {
        File smali = new File(apkRoot, "smali");
        if (!smali.isDirectory()) return;
        Map<String, String> mapping = new HashMap<>();
        File mapFile = new File(work, "smali-map.txt");
        if (mapFile.isFile()) {
            for (String line : Files.readAllLines(mapFile.toPath(), StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) mapping.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        Map<String, List<File>> byDex = new LinkedHashMap<>();
        List<File> all = new ArrayList<>();
        collectFiles(smali, all, ".smali");
        for (File file : all) {
            String rel = smali.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
            String dexName = mapping.get(rel);
            if (dexName == null) dexName = "classes.dex";
            byDex.computeIfAbsent(dexName, k -> new ArrayList<>()).add(file);
        }
        for (Map.Entry<String, List<File>> entry : byDex.entrySet()) {
            File dex = new File(apkRoot, entry.getKey());
            if (!dex.isFile()) continue;
            File miniDir = new File(work, "mini-" + entry.getKey());
            mkdirs(miniDir);
            for (File source : entry.getValue()) {
                Path rel = smali.toPath().relativize(source.toPath());
                copy(source, new File(miniDir, rel.toString()));
            }
            File mini = new File(work, entry.getKey() + ".mini.dex");
            com.android.tools.smali.smali.SmaliOptions options = new com.android.tools.smali.smali.SmaliOptions();
            options.outputDexFile = mini.getAbsolutePath();
            options.jobs = 1;
            byte[] bytes = Files.readAllBytes(dex.toPath());
            int api = VersionMap.mapDexVersionToApi(HeaderItem.getVersion(bytes, 0));
            options.apiLevel = Math.max(1, api);
            if (!Smali.assemble(options, miniDir.getAbsolutePath())) {
                throw new IOException("Failed to rebuild " + dex.getName() + " from smali");
            }
            DexBackedDexFile orig = DexFileFactory.loadDexFile(dex, null);
            byte[] merged = FastDexPatch.mergeDex(orig, mini, Math.max(1, api));
            Files.write(dex.toPath(), merged);
        }
        delete(smali);
    }

    private static List<File> dexFiles(File apkRoot) {
        List<File> result = new ArrayList<>();
        File[] files = apkRoot.listFiles((dir, name) -> name.matches("classes\\d*\\.dex"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            result.addAll(Arrays.asList(files));
        }
        return result;
    }

    private static List<File> resolveTextTargets(Context context, File originalApk, File root, String raw) throws IOException {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) return Collections.emptyList();
        List<File> result = new ArrayList<>();
        for (String line : t.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase("[SMALI]")) {
                collectFiles(target(root, "smali"), result, ".smali");
            } else if (line.equalsIgnoreCase("[RES]")) {
                collectFiles(target(root, "res"), result, null);
            } else if (line.equalsIgnoreCase("[ASSETS]")) {
                collectFiles(target(root, "assets"), result, null);
            } else if (line.equalsIgnoreCase("[LAUNCHER_ACTIVITIES]")) {
                if (context == null || originalApk == null) continue;
                for (String descriptor : launcherActivityDescriptors(context, originalApk)) {
                    File smali = target(root, "smali/" + descriptor.substring(1, descriptor.length() - 1) + ".smali");
                    if (smali.isFile()) result.add(smali);
                }
            } else if (line.startsWith("[")) {
                String plain = line.substring(1, line.endsWith("]") ? line.length() - 1 : line.length());
                File direct = target(root, plain);
                if (direct.isFile()) result.add(direct);
            } else {
                File f = target(root, line);
                if (f.isFile()) result.add(f);
            }
        }
        return result;
    }

    private static Set<String> launcherActivityDescriptors(Context context, File apk) {
        Set<String> result = new LinkedHashSet<>();
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_ACTIVITIES);
            String packageName = info == null ? null : info.packageName;
            if (packageName == null) return result;
            com.apk.axml.APKParser parser = new com.apk.axml.APKParser();
            parser.parse(apk.getAbsolutePath(), context);
            String xml = parser.getManifestAsString();
            if (xml == null) return result;
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            String current = null;
            boolean inActivity = false, inFilter = false, main = false, launcher = false;
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                String name = p.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("activity".equals(name) || "activity-alias".equals(name)) {
                        inActivity = true;
                        current = p.getAttributeValue("http://schemas.android.com/apk/res/android", "name");
                        if (current != null && current.startsWith(".")) current = packageName + current;
                        else if (current != null && current.indexOf('.') < 0) current = packageName + "." + current;
                    } else if (inActivity && "intent-filter".equals(name)) {
                        inFilter = true; main = false; launcher = false;
                    } else if (inFilter && "action".equals(name)) {
                        main |= "android.intent.action.MAIN".equals(p.getAttributeValue("http://schemas.android.com/apk/res/android", "name"));
                    } else if (inFilter && "category".equals(name)) {
                        launcher |= "android.intent.category.LAUNCHER".equals(p.getAttributeValue("http://schemas.android.com/apk/res/android", "name"));
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("intent-filter".equals(name)) {
                        if (inActivity && main && launcher && current != null) result.add("L" + current.replace('.', '/') + ";");
                        inFilter = false;
                    } else if ("activity".equals(name) || "activity-alias".equals(name)) {
                        inActivity = false; current = null; inFilter = false;
                    }
                }
                event = p.next();
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static void collectFiles(File root, List<File> out, String suffix) {
        if (!root.isDirectory()) return;
        File[] fs = root.listFiles(); if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectFiles(f, out, suffix);
            else if (suffix == null || f.getName().endsWith(suffix)) out.add(f);
        }
    }

    private static boolean matches(File f, String match, boolean regex) throws IOException {
        if (!f.isFile()) return false;
        String t = Files.readString(f.toPath(), StandardCharsets.UTF_8);
        return regex ? Pattern.compile(match, Pattern.MULTILINE | Pattern.DOTALL).matcher(t).find() : t.contains(match);
    }

    private static boolean applyLuckyPatcher(File apkRoot, File patchFile) throws IOException {
        String text = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
        Matcher sections = Pattern.compile("(?m)^\\s*\\[(BEGIN|PACKAGE|CLASSES|ODEX|LIB|END)\\]\\s*$").matcher(text);
        List<Section> parsed = new ArrayList<>();
        String current = null;
        int start = 0;
        while (sections.find()) {
            if (current != null) parsed.add(new Section(current, text.substring(start, sections.start())));
            current = sections.group(1);
            start = sections.end();
            if ("END".equals(current)) break;
        }
        if (current != null && !"END".equals(current) && start <= text.length()) parsed.add(new Section(current, text.substring(start)));
        boolean changed = false;
        for (Section s : parsed) {
            if ("CLASSES".equals(s.name) || "ODEX".equals(s.name)) {
                for (File dex : dexFiles(apkRoot)) changed |= applyLuckyBlock(dex, s.body);
            } else if ("LIB".equals(s.name)) {
                String libName = null;
                Matcher nm = Pattern.compile("\\{\\s*\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*\\}").matcher(s.body);
                if (nm.find()) libName = nm.group(1);
                if (libName == null || libName.isEmpty()) throw new IOException("[LIB] requires a name object");
                File dir = new File(apkRoot, "lib");
                List<File> libs = new ArrayList<>(); collectNamed(dir, libName, libs);
                for (File lib : libs) changed |= applyLuckyBlock(lib, s.body.substring(nm.end()));
            }
        }
        return changed;
    }

    private static void collectNamed(File root, String name, List<File> out) {
        if (!root.isDirectory()) return;
        File[] fs = root.listFiles(); if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collectNamed(f, name, out);
            else if (f.getName().equals(name)) out.add(f);
        }
    }

    private static boolean applyLuckyBlock(File file, String block) throws IOException {
        if (!file.isFile()) return false;
        byte[] data = Files.readAllBytes(file.toPath());
        Map<Integer, Byte> registers = new HashMap<>();
        int nextRegister = 0;
        boolean changed = false;
        Pattern object = Pattern.compile("\\{\\s*\"(search|original|replaced)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\}");
        Matcher m = object.matcher(block);
        String pendingOriginal = null;
        while (m.find()) {
            String kind = m.group(1);
            String value = unescapeJson(m.group(2));
            if ("search".equals(kind)) {
                BytePattern bp = BytePattern.parse(value, false, nextRegister);
                nextRegister = bp.nextRegister;
                Match hit = bp.find(data, 0, registers);
                if (hit == null) throw new IOException("Lucky Patcher pattern did not match " + file.getName() + ": " + value);
                continue;
            }
            if ("original".equals(kind)) {
                if (pendingOriginal != null) throw new IOException("Lucky Patcher original is missing its replaced pair in " + file.getName());
                pendingOriginal = value;
                continue;
            }
            if (pendingOriginal == null) throw new IOException("Lucky Patcher replaced pattern has no preceding original in " + file.getName());
            BytePattern bp = BytePattern.parse(pendingOriginal, false);
            BytePattern rp = BytePattern.parse(value, true);
            if (rp.length() != bp.length()) throw new IOException("Lucky Patcher replacement length mismatch in " + file.getName());
            Match hit = bp.find(data, 0, registers);
            if (hit == null) throw new IOException("Lucky Patcher original pattern did not match " + file.getName() + ": " + pendingOriginal);
            byte[] next = bp.patch(data, hit.offset, rp, registers);
            if (!Arrays.equals(next, data)) { data = next; changed = true; }
            pendingOriginal = null;
        }
        if (pendingOriginal != null) throw new IOException("Lucky Patcher original is missing its replaced pair in " + file.getName());
        if (changed) Files.write(file.toPath(), data);
        return changed;
    }

    private static String unescapeJson(String s) { return s.replace("\\\"", "\"").replace("\\\\", "\\"); }

    private record Section(String name, String body) {}
    private record Match(int offset) {}

    private static final class BytePattern {
        final byte[] bytes; final boolean[] wild; final Map<Integer, Integer> captures = new LinkedHashMap<>();
        private BytePattern(byte[] b, boolean[] w) { bytes = b; wild = w; }
        int length() { return bytes.length; }
        int nextRegister;
        static BytePattern parse(String s, boolean replacement) throws IOException { return parse(s, replacement, 0); }
        static BytePattern parse(String s, boolean replacement, int registerStart) throws IOException {
            String[] toks = s.trim().isEmpty() ? new String[0] : s.trim().split("\\s+");
            if (toks.length == 0) throw new IOException("Empty Lucky Patcher byte pattern");
            byte[] b = new byte[toks.length]; boolean[] w = new boolean[toks.length]; BytePattern p = new BytePattern(b, w);
            int nextRegister = registerStart;
            Set<Integer> seenRegisters = new HashSet<>();
            for (int i = 0; i < toks.length; i++) {
                String t = toks[i];
                if (t.equals("**") || t.equals("??")) { w[i] = true; continue; }
                Matcher r = Pattern.compile("R(\\d+)").matcher(t);
                Matcher wr = Pattern.compile("W(\\d+)").matcher(t);
                if (r.matches()) {
                    w[i] = true;
                    int register = Integer.parseInt(r.group(1));
                    if (!seenRegisters.add(register) || register != nextRegister) {
                        throw new IOException("Lucky Patcher R registers must be unique and sequential from R0");
                    }
                    nextRegister++;
                    p.captures.put(i, register);
                    continue;
                }
                if (wr.matches()) {
                    w[i] = true; p.captures.put(i, -Integer.parseInt(wr.group(1)) - 1); continue;
                }
                if (!t.matches("[0-9A-Fa-f]{2}")) throw new IOException("Invalid Lucky Patcher hex token: " + t);
                b[i] = (byte) Integer.parseInt(t, 16);
            }
            p.nextRegister = nextRegister;
            return p;
        }
        Match find(byte[] data, int from, Map<Integer, Byte> registers) {
            for (int i = Math.max(0, from); i + bytes.length <= data.length; i++) {
                boolean ok = true;
                for (int j = 0; j < bytes.length; j++) {
                    if (!wild[j]) {
                        if (data[i+j] != bytes[j]) { ok = false; break; }
                    } else {
                        int c = captures.getOrDefault(j, Integer.MIN_VALUE);
                        if (c <= -1) {
                            Byte expected = registers.get(-c - 1);
                            if (expected == null || data[i+j] != expected) { ok = false; break; }
                        }
                    }
                }
                if (!ok) continue;
                for (Map.Entry<Integer,Integer> e : captures.entrySet()) if (e.getValue() >= 0) registers.put(e.getValue(), data[i+e.getKey()]);
                return new Match(i);
            }
            return null;
        }
        byte[] patch(byte[] data, int at, BytePattern repl, Map<Integer, Byte> registers) throws IOException {
            byte[] out = data.clone();
            for (int i = 0; i < repl.bytes.length; i++) {
                int c = repl.captures.getOrDefault(i, Integer.MIN_VALUE);
                if (c != Integer.MIN_VALUE && c <= -1) {
                    Byte value = registers.get(-c - 1);
                    if (value == null) throw new IOException("Lucky Patcher W" + (-c - 1) + " has no captured R value");
                    out[at+i] = value;
                } else if (!repl.wild[i]) out[at+i] = repl.bytes[i];
            }
            return out;
        }
    }

    private static String firstValue(Rule rule) {
        if (rule == null) return "";
        String value = rule.get("VALUE");
        if (!value.isEmpty()) return value;
        // PACKAGE/AUTHOR/MIN_ENGINE_VER are commonly emitted as a bare value
        // on the line immediately following the section header.
        if (!rule.values.isEmpty()) return rule.values.values().iterator().next();
        return "";
    }

    private static String readPackageName(Context context, File apk) {
        if (context == null || apk == null) return null;
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return info == null ? null : info.packageName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean looksLikeLuckyPatcher(String text) {
        return text != null && Pattern.compile("(?m)^\\s*\\[(?:CLASSES|ODEX|LIB)\\]\\s*$").matcher(text).find();
    }

    private static boolean looksLikeApkEditorPatch(String text) {
        if (text == null) return false;
        return Pattern.compile("(?m)^\\s*\\[(?:ADD_FILES|REMOVE_FILES|MATCH_REPLACE|MATCH_ASSIGN|MATCH_GOTO|GOTO|MERGE|EXECUTE_DEX|DUMMY)\\]\\s*$").matcher(text).find();
    }

    private static boolean isMetadata(String type) {
        return type.equals("MIN_ENGINE_VER") || type.equals("AUTHOR") || type.equals("PACKAGE")
                || type.equals("BEGIN") || type.equals("END") || type.equals("START");
    }

    private static int indexOf(List<Rule> rules, String name) {
        for (int i = 0; i < rules.size(); i++) if (name.equals(rules.get(i).name)) return i;
        return -1;
    }

    private static final class Rule {
        String type, name;
        final Map<String,String> values = new LinkedHashMap<>();
        String get(String key) { return values.getOrDefault(key, ""); }
    }

    private static List<Rule> parse(File f) throws IOException {
        List<Rule> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        Rule cur = null; String key = null;
        for (String raw : lines) {
            String l = raw.replace("\r", "");
            if (l.trim().startsWith("#")) continue;
            Matcher h = Pattern.compile("^\\[(/?)([A-Za-z0-9_]+)\\]\\s*$").matcher(l.trim());
            if (h.matches()) {
                // Bracketed target selectors such as [SMALI] and
                // [LAUNCHER_ACTIVITIES] are values of TARGET, not rule headers.
                if (cur != null && key != null && h.group(1).isEmpty()) {
                    String old = cur.values.get(key);
                    cur.values.put(key, old == null || old.isEmpty() ? l.trim() : old + "\n" + l.trim());
                    continue;
                }
                if (cur != null) out.add(cur);
                if (h.group(1).isEmpty()) { cur = new Rule(); cur.type = h.group(2).toUpperCase(Locale.US); }
                else cur = null;
                key = null;
                continue;
            }
            if (cur == null) continue;
            Matcher kv = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):\\s*$").matcher(l);
            if (kv.matches()) { key = kv.group(1).toUpperCase(Locale.US); cur.values.putIfAbsent(key, ""); continue; }
            if (key != null) {
                String old = cur.values.get(key);
                cur.values.put(key, old == null || old.isEmpty() ? l : old + "\n" + l);
            } else if (isMetadata(cur.type) && !l.trim().isEmpty()) {
                String old = cur.values.get("VALUE");
                cur.values.put("VALUE", old == null || old.isEmpty() ? l : old + "\n" + l);
            }
        }
        if (cur != null) out.add(cur);
        for (Rule r : out) r.name = r.get("NAME");
        return out;
    }

    private static File findPatchTxt(File root) {
        File direct = new File(root, "patch.txt"); if (direct.isFile()) return direct;
        File[] fs = root.listFiles(); if (fs == null) return null;
        for (File f : fs) if (f.isDirectory()) { File found = findPatchTxt(f); if (found != null) return found; }
        return null;
    }

    private static File findLuckyPatchTxt(File root) {
        File[] fs = root.listFiles(); if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase(Locale.US).endsWith(".txt")) {
                try {
                    String s = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                    if (s.contains("[CLASSES]") || s.contains("[LIB]") || s.contains("[ODEX]")) return f;
                } catch (Exception ignored) {}
            }
            if (f.isDirectory()) { File r = findLuckyPatchTxt(f); if (r != null) return r; }
        }
        return null;
    }

    private static void stripInvalidatedSignatures(File apkRoot) {
        File meta = new File(apkRoot, "META-INF");
        if (!meta.isDirectory()) return;
        File[] files = meta.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toUpperCase(Locale.US);
            if (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")) delete(f);
        }
    }

    private static File target(File root, String path) throws IOException {
        String p = path == null ? "" : path.replace('\\', '/').trim();
        while (p.startsWith("/")) p = p.substring(1);
        File f = new File(root, p).getCanonicalFile();
        Path base = root.getCanonicalFile().toPath();
        if (!f.toPath().startsWith(base)) throw new IOException("Unsafe patch path: " + path);
        return f;
    }

    private static String expand(String s, Map<String,String> vars) {
        if (s == null || s.isEmpty() || vars.isEmpty()) return s == null ? "" : s;
        String out = s;
        // Resolve chained assignments (A=${B}, B=${C}) without imposing an
        // ordering requirement on patch rules. Stop when stable so malformed
        // cyclic variables cannot spin forever.
        for (int pass = 0; pass < vars.size() + 1; pass++) {
            String before = out;
            for (Map.Entry<String,String> e : vars.entrySet()) {
                out = out.replace("${" + e.getKey() + "}", e.getValue());
                out = out.replace("$ {" + e.getKey() + "}", e.getValue());
            }
            if (out.equals(before)) break;
        }
        return out;
    }

    private static boolean bool(String s) { return "true".equalsIgnoreCase(s == null ? "" : s.trim()); }

    private static void mkdirs(File dir) throws IOException { if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create directory: " + dir); }

    private static void unzip(File zip, File out, long maxBytes) throws IOException {
        mkdirs(out); long total = 0;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e; byte[] buf = new byte[65536];
            while ((e = in.getNextEntry()) != null) {
                File f = target(out, e.getName());
                if (e.isDirectory()) { mkdirs(f); continue; }
                if (e.getCompressedSize() > maxBytes || e.getSize() > maxBytes) throw new IOException("Zip entry is too large: " + e.getName());
                mkdirs(f.getParentFile());
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(f))) {
                    int n; while ((n = in.read(buf)) != -1) { total += n; if (total > maxBytes) throw new IOException("Extracted archive exceeds safety limit"); os.write(buf, 0, n); }
                }
            }
        }
    }

    private static void zipDirectory(File root, File out) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            Path base = root.toPath();
            try (java.util.stream.Stream<Path> stream = Files.walk(base)) {
                stream.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        String n = base.relativize(p).toString().replace(File.separatorChar, '/');
                        ZipEntry entry = new ZipEntry(n);
                        z.putNextEntry(entry);
                        Files.copy(p, z);
                        z.closeEntry();
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            } catch (UncheckedIOException e) { throw e.getCause(); }
        }
    }

    private static void copy(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            mkdirs(dst); File[] fs = src.listFiles(); if (fs != null) for (File f : fs) copy(f, new File(dst, f.getName()));
        } else {
            mkdirs(dst.getParentFile()); Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) { File[] fs = f.listFiles(); if (fs != null) for (File x : fs) delete(x); }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
