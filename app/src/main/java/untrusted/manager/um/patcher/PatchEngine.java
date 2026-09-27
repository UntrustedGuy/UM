package untrusted.manager.um.patcher;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Safe APK patch engine. Supports the file/text subset common to APK Editor patch.txt
 * files and LPZIP containers. Binary/resource rebuild rules are deliberately rejected
 * rather than silently producing a corrupt APK.
 */
public final class PatchEngine {
    public record Result(boolean success, String message, File output) {}
    private PatchEngine() {}

    public static Result apply(File apk, File patchZip, File outputDir) {
        File work = new File(outputDir, "patch-work-" + System.currentTimeMillis());
        try {
            work.mkdirs();
            File extractedPatch = new File(work, "patch"); extractedPatch.mkdirs();
            unzip(patchZip, extractedPatch);
            File patchTxt = findPatchTxt(extractedPatch);

            File apkWork = new File(work, "apk"); apkWork.mkdirs();
            unzip(apk, apkWork);
            if (patchTxt == null) {
                File lucky = findLuckyPatchTxt(extractedPatch);
                if (lucky == null) return new Result(false, "Patch archive contains neither APK Editor patch.txt nor a Lucky Patcher patch text", null);
                boolean changed = applyLuckyPatcher(apkWork, lucky);
                if (!changed) return new Result(false, "Lucky Patcher patch did not match the selected APK", null);
                File out = new File(outputDir, apk.getName().replaceFirst("(?i)\\.apk$", "") + "-patched.apk");
                zipDirectory(apkWork, out);
                delete(work);
                return new Result(true, "Lucky Patcher patch applied: " + out.getAbsolutePath() + " (sign before installing)", out);
            }
            List<Rule> rules = parse(patchTxt);
            Map<String,String> vars = new HashMap<>();
            Set<String> labels = new HashSet<>();
            for (Rule r: rules) if (r.name != null) labels.add(r.name);
            boolean changed = false;
            for (int i=0;i<rules.size();i++) {
                Rule r=rules.get(i);
                if (r.type.equals("DUMMY")) continue;
                if (r.type.equals("GOTO")) { i=indexOf(rules,r.get("GOTO"))-1; continue; }
                if (r.type.equals("MATCH_GOTO")) {
                    File target = target(apkWork, expand(r.get("TARGET"), vars));
                    boolean found = target.exists() && matches(target, expand(r.get("MATCH"),vars), bool(r.get("REGEX")));
                    if (found) { int j=indexOf(rules,r.get("GOTO")); if(j>=0)i=j-1; }
                    continue;
                }
                switch(r.type) {
                    case "ADD_FILES": changed |= addFiles(extractedPatch, apkWork, r, vars); break;
                    case "REMOVE_FILES": changed |= removeFiles(apkWork, r, vars); break;
                    case "MATCH_REPLACE": changed |= matchReplace(apkWork, r, vars); break;
                    case "MATCH_ASSIGN": changed |= matchAssign(apkWork, r, vars); break;
                    case "MERGE": changed |= merge(extractedPatch, apkWork, r, vars); break;
                    case "EXECUTE_DEX": return new Result(false, "EXECUTE_DEX patch rules are not supported by the safe engine", null);
                    default: break;
                }
            }
            if (!changed) return new Result(false, "Patch made no changes", null);
            File out = new File(outputDir, apk.getName().replaceFirst("(?i)\\.apk$", "") + "-patched.apk");
            zipDirectory(apkWork, out);
            delete(work);
            return new Result(true, "Patched APK created: " + out.getAbsolutePath(), out);
        } catch (Exception e) {
            delete(work);
            return new Result(false, e.getMessage() == null ? e.toString() : e.getMessage(), null);
        }
    }

    private static boolean addFiles(File patchRoot, File apkRoot, Rule r, Map<String,String> vars) throws IOException {
        String source=expand(r.get("SOURCE"),vars), target=expand(r.get("TARGET"),vars);
        File src=target(patchRoot,source), dst=target(apkRoot,target); if(!src.exists()) throw new IOException("Missing patch source: "+source);
        if(bool(r.get("EXTRACT")) && src.isFile() && source.toLowerCase().endsWith(".zip")){ unzip(src,dst); return true; }
        copy(src,dst); return true;
    }
    private static boolean removeFiles(File apkRoot, Rule r, Map<String,String> vars) throws IOException {
        boolean changed=false; for(String t:r.get("TARGET").split("\\r?\\n")){t=expand(t.trim(),vars);if(t.isEmpty())continue;File f=target(apkRoot,t);if(f.exists()){delete(f);changed=true;}}return changed;
    }
    private static boolean matchReplace(File apkRoot, Rule r, Map<String,String> vars) throws IOException {
        File f=target(apkRoot,expand(r.get("TARGET"),vars)); if(!f.isFile()) return false;
        byte[] data=Files.readAllBytes(f.toPath()); String text=new String(data,StandardCharsets.UTF_8); String match=expand(r.get("MATCH"),vars), repl=expand(r.get("REPLACE"),vars);
        if(!bool(r.get("REGEX"))){ if(!text.contains(match))return false;text=text.replace(match,repl); }
        else { String n=text.replaceAll(match,Matcher.quoteReplacement(repl)); if(n.equals(text))return false;text=n; }
        Files.write(f.toPath(),text.getBytes(StandardCharsets.UTF_8)); return true;
    }
    private static boolean matchAssign(File apkRoot, Rule r, Map<String,String> vars) throws IOException {
        File f=target(apkRoot,expand(r.get("TARGET"),vars)); if(!f.isFile())return false;String text=Files.readString(f.toPath());String m=expand(r.get("MATCH"),vars);Matcher mm=(bool(r.get("REGEX"))?Pattern.compile(m):Pattern.compile(Pattern.quote(m))).matcher(text);if(!mm.find())return false;
        for(String a:r.get("ASSIGN").split("\\r?\\n")){int eq=a.indexOf('=');if(eq>0){String name=a.substring(0,eq).trim(), val=a.substring(eq+1).trim();if(val.equals("${GROUP1}"))vars.put(name,mm.groupCount()>=1?mm.group(1):"");else vars.put(name,expand(val,vars));}}return true;
    }
    private static boolean merge(File patchRoot, File apkRoot, Rule r, Map<String,String> vars) throws IOException { File src=target(patchRoot,expand(r.get("SOURCE"),vars));if(!src.exists())throw new IOException("Missing merge source: "+src);if(src.isFile()&&src.getName().toLowerCase().endsWith(".zip")){File tmp=new File(patchRoot,"merge-tmp");tmp.mkdirs();unzip(src,tmp);copy(tmp,apkRoot);return true;}copy(src,apkRoot);return true; }
    private static boolean matches(File f,String m,boolean regex)throws IOException{String t=Files.readString(f.toPath());return regex?Pattern.compile(m).matcher(t).find():t.contains(m);}

    private static boolean applyLuckyPatcher(File apkRoot, File patchFile) throws IOException {
        String text = Files.readString(patchFile.toPath(), StandardCharsets.UTF_8);
        String section = ""; String libName = null; boolean changed = false;
        Matcher sectionMatcher = Pattern.compile("(?m)^\\[(BEGIN|PACKAGE|CLASSES|ODEX|LIB|END)\\]\\s*$").matcher(text);
        int pos = 0;
        while (sectionMatcher.find()) {
            if ((section.equals("CLASSES") || section.equals("ODEX")) && pos < sectionMatcher.start()) {
                changed |= applyDexJsonLines(new File(apkRoot, "classes.dex"), text.substring(pos, sectionMatcher.start()));
            } else if (section.equals("LIB") && pos < sectionMatcher.start() && libName != null) {
                File lib = findEntry(apkRoot, "lib", libName);
                if (lib != null) changed |= applyDexJsonLines(lib, text.substring(pos, sectionMatcher.start()));
            }
            section = sectionMatcher.group(1);
            pos = sectionMatcher.end();
            if (section.equals("END")) break;
            if (section.equals("LIB")) {
                Matcher nm = Pattern.compile("\\{\\s*\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*\\}").matcher(text.substring(pos));
                if (nm.find()) { libName = nm.group(1); pos += nm.end(); }
            }
        }
        if ((section.equals("CLASSES") || section.equals("ODEX")) && pos < text.length()) changed |= applyDexJsonLines(new File(apkRoot, "classes.dex"), text.substring(pos));
        if (section.equals("LIB") && pos < text.length() && libName != null) { File lib=findEntry(apkRoot,"lib",libName); if(lib!=null) changed |= applyDexJsonLines(lib,text.substring(pos)); }
        return changed;
    }

    private static boolean applyDexJsonLines(File file, String block) throws IOException {
        if (!file.isFile()) return false;
        boolean changed = false;
        Pattern line = Pattern.compile("\\{\\s*\\\"(search|original)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"(?:\\s*,\\s*\\\"replaced\\\"\\s*:\\s*\\\"([^\\\"]+)\\\")?\\s*\\}");
        Matcher m = line.matcher(block);
        byte[] data = Files.readAllBytes(file.toPath());
        while (m.find()) {
            String original = m.group(2);
            String replacement = m.group(3);
            BytePattern bp = BytePattern.parse(original, false);
            int at = bp.find(data, 0);
            if (at < 0) continue;
            if (replacement == null) { changed = true; continue; }
            BytePattern rp = BytePattern.parse(replacement, true);
            if (rp.bytes.length != bp.bytes.length) throw new IOException("Lucky Patcher replacement length mismatch");
            data = bp.patch(data, at, rp);
            changed = true;
        }
        Files.write(file.toPath(), data);
        return changed;
    }

    private static File findEntry(File root, String dirName, String name) {
        File dir = new File(root, dirName);
        if (!dir.isDirectory()) return null;
        File[] abi = dir.listFiles();
        if (abi != null) for (File a : abi) {
            if (!a.isDirectory()) continue;
            File f = new File(a, name);
            if (f.isFile()) return f;
        }
        return null;
    }

    private static File findLuckyPatchTxt(File root) {
        File[] fs = root.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".txt")) return f;
            if (f.isDirectory()) { File r = findLuckyPatchTxt(f); if (r != null) return r; }
        }
        return null;
    }

    private static final class BytePattern {
        final byte[] bytes; final boolean[] wild; final Map<Integer,Integer> capture = new HashMap<>(); final boolean write;
        private BytePattern(byte[] b, boolean[] w, boolean write) { bytes=b; wild=w; this.write=write; }
        static BytePattern parse(String s, boolean write) throws IOException {
            String[] toks=s.trim().split("\\s+"); byte[] b=new byte[toks.length]; boolean[] w=new boolean[toks.length]; BytePattern p=new BytePattern(b,w,write);
            for(int i=0;i<toks.length;i++){String t=toks[i];if(t.equals("**")){w[i]=true;continue;} if(t.matches("R\\d+")){w[i]=true;try{p.capture.put(i,Integer.parseInt(t.substring(1)));}catch(Exception e){throw new IOException("Invalid R placeholder");}continue;} if(t.matches("W\\d+")){w[i]=true;try{p.capture.put(i,-Integer.parseInt(t.substring(1))-1);}catch(Exception e){throw new IOException("Invalid W placeholder");}continue;} try{b[i]=(byte)Integer.parseInt(t,16);}catch(Exception e){throw new IOException("Invalid hex token: "+t);}}
            return p;
        }
        int find(byte[] data,int from){for(int i=Math.max(0,from);i+bytes.length<=data.length;i++){boolean ok=true;for(int j=0;j<bytes.length;j++){if(!wild[j]&&data[i+j]!=bytes[j]){ok=false;break;}}if(ok)return i;}return -1;}
        byte[] patch(byte[] data,int at,BytePattern repl){byte[] out=data.clone();Map<Integer,Byte> regs=new HashMap<>();for(Map.Entry<Integer,Integer> e:capture.entrySet())if(e.getValue()>=0)regs.put(e.getValue(),data[at+e.getKey()]);for(int i=0;i<repl.bytes.length;i++){int c=repl.capture.getOrDefault(i,Integer.MIN_VALUE);if(c<=-1){int idx=-c-1;out[at+i]=regs.getOrDefault(idx,repl.bytes[i]);}else if(!repl.wild[i])out[at+i]=repl.bytes[i];}return out;}
    }
    private static File target(File root,String path)throws IOException{path=path==null?"":path.replace('\\','/');while(path.startsWith("/"))path=path.substring(1);File f=new File(root,path).getCanonicalFile();File r=root.getCanonicalFile();if(!f.toPath().startsWith(r.toPath()))throw new IOException("Unsafe patch path: "+path);return f;}
    private static String expand(String s,Map<String,String>v){if(s==null)return "";String out=s;for(Map.Entry<String,String>e:v.entrySet())out=out.replace("${"+e.getKey()+"}",e.getValue());return out;}
    private static boolean bool(String s){return "true".equalsIgnoreCase(s==null?"":s.trim());}
    private static int indexOf(List<Rule> rs,String name){if(name==null)return -1;for(int i=0;i<rs.size();i++)if(name.equals(rs.get(i).name))return i;return -1;}
    private static class Rule{String type,name;Map<String,String>m=new LinkedHashMap<>();String get(String k){return m.getOrDefault(k,"");}}
    private static List<Rule> parse(File f)throws IOException{List<Rule>out=new ArrayList<>();List<String>lines=Files.readAllLines(f.toPath(),StandardCharsets.UTF_8);Rule cur=null;String key=null;for(String raw:lines){String l=raw.replace("\r","");if(l.trim().startsWith("#"))continue;Matcher h=Pattern.compile("^\\[(/?)([A-Za-z0-9_]+)\\]\\s*$").matcher(l.trim());if(h.matches()){if(h.group(1).isEmpty()){if(cur!=null)out.add(cur);cur=new Rule();cur.type=h.group(2).toUpperCase();}else{if(cur!=null)out.add(cur);cur=null;}key=null;continue;}if(cur==null)continue;if(l.matches("^[A-Za-z_][A-Za-z0-9_]*:\\s*$")){key=l.substring(0,l.indexOf(':')).trim();cur.m.putIfAbsent(key,"");continue;}if(key!=null){String old=cur.m.get(key);cur.m.put(key,old.isEmpty()?l:old+"\n"+l);}}if(cur!=null)out.add(cur);for(Rule r:out){r.name=r.m.getOrDefault("NAME","");}return out;}
    private static File findPatchTxt(File root){File f=new File(root,"patch.txt");if(f.isFile())return f;File[] fs=root.listFiles();if(fs!=null)for(File x:fs){if(x.isDirectory()){File y=findPatchTxt(x);if(y!=null)return y;}}return null;}
    private static void unzip(File z,File out)throws IOException{try(ZipInputStream in=new ZipInputStream(new BufferedInputStream(new FileInputStream(z)))){ZipEntry e;byte[]buf=new byte[8192];while((e=in.getNextEntry())!=null){File f=target(out,e.getName());if(e.isDirectory()){f.mkdirs();continue;}f.getParentFile().mkdirs();try(OutputStream os=new BufferedOutputStream(new FileOutputStream(f))){int n;while((n=in.read(buf))>0)os.write(buf,0,n);}}}}
    private static void zipDirectory(File root,File out)throws IOException{try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))){Path base=root.toPath();Files.walk(base).filter(Files::isRegularFile).forEach(p->{try{String n=base.relativize(p).toString().replace(File.separatorChar,'/');z.putNextEntry(new ZipEntry(n));Files.copy(p,z);z.closeEntry();}catch(IOException e){throw new UncheckedIOException(e);}});}}
    private static void copy(File s,File d)throws IOException{if(s.isDirectory()){d.mkdirs();File[]fs=s.listFiles();if(fs!=null)for(File f:fs)copy(f,new File(d,f.getName()));}else{d.getParentFile().mkdirs();Files.copy(s.toPath(),d.toPath(),StandardCopyOption.REPLACE_EXISTING);}}
    private static void delete(File f){if(!f.exists())return;if(f.isDirectory()){File[]fs=f.listFiles();if(fs!=null)for(File x:fs)delete(x);}f.delete();}
}
