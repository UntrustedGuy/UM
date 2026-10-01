package untrusted.manager.um.remote;

import android.content.Context;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import untrusted.manager.um.utils.FastDexPatch;
import untrusted.manager.um.utils.FileUtils;
import untrusted.manager.um.utils.SignWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Real APK workspace used by MCP. It never edits the source APK in-place. */
public final class McpApkWorkspace {
    private static final long MAX_ENTRY = 16L * 1024L * 1024L;
    private final Context context;
    private final File base;
    private final File apk;
    private final String id;
    private McpApkWorkspace(Context c, File base, File apk, String id){this.context=c;this.base=base;this.apk=apk;this.id=id;}
    public String id(){return id;} public File apk(){return apk;} public File base(){return base;}

    public static File root(Context c){File r=new File(c.getFilesDir(),"mcp-apk-workspaces");if(!r.exists()&&!r.mkdirs())throw new IllegalStateException("Cannot create APK workspace root");return r;}
    public static McpApkWorkspace create(Context c, File source) throws Exception {
        if(source==null||!source.isFile())throw new IOException("APK file not found");
        if(!source.getName().toLowerCase(Locale.ROOT).endsWith(".apk"))throw new IOException("Only APK files are supported");
        String id=UUID.randomUUID().toString(); File b=new File(root(c),id); if(!b.mkdirs())throw new IOException("Cannot create workspace");
        File a=new File(b,"workspace.apk"); Files.copy(source.toPath(),a.toPath(),StandardCopyOption.REPLACE_EXISTING); return new McpApkWorkspace(c,b,a,id);
    }
    public static McpApkWorkspace open(Context c,String id)throws Exception{if(id==null||!id.matches("[0-9a-fA-F-]{20,64}"))throw new SecurityException("Invalid workspace id");File b=new File(root(c),id).getCanonicalFile();if(!b.getParentFile().equals(root(c).getCanonicalFile()))throw new SecurityException("Invalid workspace");File a=new File(b,"workspace.apk");if(!a.isFile())throw new IOException("Workspace not found");return new McpApkWorkspace(c,b,a,id);}
    public static List<String> list(Context c){File r=root(c);File[] fs=r.listFiles(File::isDirectory);List<String> out=new ArrayList<>();if(fs!=null)for(File f:fs)if(new File(f,"workspace.apk").isFile())out.add(f.getName());Collections.sort(out);return out;}
    public void delete()throws Exception{deleteTree(base);}

    public List<String> entries(String prefix){List<String> out=new ArrayList<>();try(ZipFile z=new ZipFile(apk)){for(FileHeader h:z.getFileHeaders()){String n=h.getFileName();if(prefix==null||prefix.isEmpty()||n.startsWith(prefix))out.add(n);}}catch(Exception e){throw new RuntimeException(e);}Collections.sort(out);return out;}
    public byte[] readEntry(String name)throws Exception{validateEntry(name);try(ZipFile z=new ZipFile(apk)){FileHeader h=z.getFileHeader(name);if(h==null)throw new FileNotFoundException(name);if(h.getUncompressedSize()>MAX_ENTRY)throw new IOException("Entry exceeds 16 MiB limit");try(InputStream in=z.getInputStream(h);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}}
    public void writeEntry(String name,byte[] data)throws Exception{validateEntry(name);if(data==null||data.length>MAX_ENTRY)throw new IOException("Entry exceeds 16 MiB limit");File tmp=new File(base,"write.tmp");rebuildZip(name,data,false,tmp);replace(tmp);}
    public void deleteEntry(String name)throws Exception{validateEntry(name);File tmp=new File(base,"delete.tmp");rebuildZip(name,null,true,tmp);replace(tmp);}
    private void rebuildZip(String changed, byte[] data, boolean remove, File out) throws Exception {
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new BufferedInputStream(new FileInputStream(apk)));
             java.util.zip.ZipOutputStream dst = new java.util.zip.ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            java.util.zip.ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName();
                if (n.equals(changed)) {
                    in.closeEntry();
                    continue;
                }
                java.util.zip.ZipEntry copy = new java.util.zip.ZipEntry(n);
                if (e.getTime() >= 0) copy.setTime(e.getTime());
                dst.putNextEntry(copy);
                int r;
                while ((r = in.read(buf)) != -1) dst.write(buf, 0, r);
                dst.closeEntry();
                in.closeEntry();
            }
            if (!remove) {
                java.util.zip.ZipEntry added = new java.util.zip.ZipEntry(changed);
                dst.putNextEntry(added);
                dst.write(data);
                dst.closeEntry();
            }
        }
    }
    private void replace(File tmp)throws Exception{File backup=new File(base,"workspace.bak");if(backup.exists())backup.delete();Files.move(apk.toPath(),backup.toPath(),StandardCopyOption.REPLACE_EXISTING);try{Files.move(tmp.toPath(),apk.toPath(),StandardCopyOption.REPLACE_EXISTING);backup.delete();}catch(Exception e){Files.move(backup.toPath(),apk.toPath(),StandardCopyOption.REPLACE_EXISTING);throw e;}}

    public List<String> dexEntries(){List<String> out=new ArrayList<>();for(String e:entries(""))if(e.matches("classes(\\d*)?\\.dex"))out.add(e);return out;}
    public List<String> dexClasses(String entry,String query,int limit)throws Exception{byte[] bytes=readEntry(entry);File f=temp("dex");Files.write(f.toPath(),bytes);DexBackedDexFile d=DexFileFactory.loadDexFile(f,Opcodes.getDefault());List<String> out=new ArrayList<>();String q=query==null?"":query.toLowerCase(Locale.ROOT);for(ClassDef c:d.getClasses()){String n=FastDexPatch.descriptorToClassName(c.getType());if(q.isEmpty()||n.toLowerCase(Locale.ROOT).contains(q)){out.add(n);if(out.size()>=Math.min(5000,Math.max(1,limit)))break;}}f.delete();return out;}
    public String smaliRead(String entry,String className)throws Exception{ClassDef c=findClass(entry,className);StringWriter sw=new StringWriter();BaksmaliWriter w=new BaksmaliWriter(sw);new ClassDefinition(new BaksmaliOptions(),c).writeTo(w);w.close();return sw.toString();}
    public void smaliWrite(String entry,String className,String code)throws Exception{if(code==null||code.length()>MAX_ENTRY)throw new IOException("Smali too large");File dexFile=temp("dex");Files.write(dexFile.toPath(),readEntry(entry));DexBackedDexFile orig=DexFileFactory.loadDexFile(dexFile,Opcodes.getDefault());ClassDef replacement=Smali.assemble(code,new SmaliOptions(),detectDexApi(orig));if(!replacement.getType().equals(toDescriptor(className)))throw new IOException("Smali class descriptor does not match target class");File miniDir=new File(base,"smali");if(!miniDir.mkdirs()&&!miniDir.isDirectory())throw new IOException("Cannot create smali staging directory");File smali=new File(miniDir,"Changed.smali");Files.writeString(smali.toPath(),code,StandardCharsets.UTF_8);File miniDex= new File(miniDir,"Changed.dex");SmaliOptions so=new SmaliOptions();so.outputDexFile=miniDex.getPath();so.jobs=1;so.apiLevel=detectDexApi(orig);if(!Smali.assemble(so,miniDir.getPath()))throw new IOException("Smali assembly failed");byte[] merged=FastDexPatch.mergeDex(orig,miniDex,detectDexApi(orig));writeEntry(entry,merged);deleteTree(miniDir);dexFile.delete();}
    private ClassDef findClass(String entry,String className)throws Exception{File f=temp("dex");Files.write(f.toPath(),readEntry(entry));DexBackedDexFile d=DexFileFactory.loadDexFile(f,Opcodes.getDefault());String target=toDescriptor(className);for(ClassDef c:d.getClasses())if(c.getType().equals(target)){f.delete();return c;}f.delete();throw new IOException("Class not found: "+className);}
    private int detectDexApi(DexBackedDexFile d){try{return d.getOpcodes().api;}catch(Exception e){return 28;}}
    private static String toDescriptor(String n){String s=n.trim();if(s.startsWith("L")&&s.endsWith(";"))return s;return "L"+s.replace('.','/')+";";}

    public File build(File output)throws Exception{if(output==null)throw new IOException("Output is required");File parent=output.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("Cannot create output directory");Files.copy(apk.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING);return output;}
    public File sign(File output)throws Exception{File signed=new File(base,"signed.apk");File key=FileUtils.getDebugKeystore(context);new SignWrapper(key,"android",true,true,true,false,"Untrusted Manager").signApk(apk,signed);Files.copy(signed.toPath(),output.toPath(),StandardCopyOption.REPLACE_EXISTING);signed.delete();return output;}
    private File temp(String suffix)throws IOException{return File.createTempFile("mcp-",suffix,base);}
    private static void validateEntry(String n)throws Exception{if(n==null||n.isEmpty()||n.startsWith("/")||n.contains("..")||n.indexOf('\0')>=0)throw new SecurityException("Invalid APK entry");}
    private static void deleteTree(File f)throws Exception{if(f.isDirectory()){File[] fs=f.listFiles();if(fs!=null)for(File x:fs)deleteTree(x);}if(f.exists()&&!f.delete())throw new IOException("Cannot delete "+f);}
}
