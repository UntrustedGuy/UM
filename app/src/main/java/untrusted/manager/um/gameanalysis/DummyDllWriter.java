package untrusted.manager.um.gameanalysis;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Emits browseable ECMA-335 stub assemblies from IL2CPP metadata. */
final class DummyDllWriter {
    private static final int FILE_ALIGN = 0x200;
    private static final int SECTION_ALIGN = 0x1000;
    private DummyDllWriter() {}

    static void writeAll(File outDir, List<GameAnalysisEngine.ImageDef> images,
                         List<GameAnalysisEngine.TypeDef> types,
                         List<GameAnalysisEngine.MethodDef> methods,
                         List<GameAnalysisEngine.FieldDef> fields,
                         GameAnalysisEngine.StringTable strings) throws IOException {
        File root = new File(outDir, "DummyDll");
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IOException("Cannot create DummyDll directory");
        File[] stale = root.listFiles((dir, name) -> name.toLowerCase().endsWith(".dll"));
        if (stale != null) for (File f : stale) if (!f.delete()) throw new IOException("Cannot replace old DummyDll: " + f.getName());
        int emitted = 0;
        List<String> errors = new ArrayList<>();
        if (images == null || images.isEmpty()) {
            writeAssembly(new File(root, "DummyAssembly.dll"), "DummyAssembly", types, methods, fields, strings);
            emitted = 1;
        } else {
            for (int ii = 0; ii < images.size(); ii++) {
                GameAnalysisEngine.ImageDef image = images.get(ii);
                List<GameAnalysisEngine.TypeDef> imageTypes = new ArrayList<>();
                int start = Math.max(0, image.typeStart);
                int end = Math.min(types.size(), start + safeInt(image.typeCount));
                for (int i = start; i < end; i++) imageTypes.add(types.get(i));
                String imageName = strings.get(image.nameIndex);
                if (imageName == null || imageName.isEmpty()) imageName = "Assembly_" + ii;
                if (imageName.toLowerCase().endsWith(".dll")) imageName = imageName.substring(0,imageName.length()-4);
                String base = safe(imageName);
                File target = new File(root, base + ".dll");
                if (target.exists()) target = new File(root, base + "_" + ii + ".dll");
                try {
                    writeAssembly(target, imageName, imageTypes, methods, fields, strings);
                    emitted++;
                } catch (IOException ex) {
                    errors.add(base + ": " + ex.getMessage());
                    if (target.exists()) target.delete();
                }
            }
        }
        if (!errors.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (String e : errors) b.append(e).append('\n');
            try (FileOutputStream errorOut = new FileOutputStream(new File(root, "generation_errors.txt"))) { errorOut.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        }
        if (emitted == 0) throw new IOException("DummyDll generation produced no assemblies" + (errors.isEmpty() ? "" : ": " + errors.get(0)));
    }

    private static int safeInt(long x) { return x <= 0 ? 0 : x > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)x; }

    private static void writeAssembly(File file, String assemblyName,
                                      List<GameAnalysisEngine.TypeDef> types,
                                      List<GameAnalysisEngine.MethodDef> methods,
                                      List<GameAnalysisEngine.FieldDef> fields,
                                      GameAnalysisEngine.StringTable strings) throws IOException {
        // Build the ordered method/field lists actually referenced by the selected type set.
        List<Integer> fieldIndices = new ArrayList<>();
        List<Integer> methodIndices = new ArrayList<>();
        for (GameAnalysisEngine.TypeDef t : types) {
            int fs=Math.max(0,t.fieldStart), fe=Math.min(fields.size(),fs+Math.max(0,t.fieldCount));
            for(int i=fs;i<fe;i++) fieldIndices.add(i);
            int ms=Math.max(0,t.methodStart), me=Math.min(methods.size(),ms+Math.max(0,t.methodCount));
            for(int i=ms;i<me;i++) methodIndices.add(i);
        }
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        byte[] cli = new byte[72];
        putU32(cli,0,72); putU16(cli,4,2); putU16(cli,6,5); putU32(cli,16,1);
        text.write(cli);
        int bodyRva = 0x1000;
        int codeStart = align(text.size(),4); while(text.size()<codeStart) text.write(0);
        Map<Integer,Integer> rvas = new HashMap<>();
        for (int row=0; row<methodIndices.size(); row++) {
            int rva = bodyRva + text.size();
            rvas.put(methodIndices.get(row),rva);
            text.write(0x06); // tiny header, one-byte code
            text.write(0x2A); // ret
        }
        byte[] metadata = buildMetadata(assemblyName,types,methods,fields,strings,rvas,fieldIndices,methodIndices);
        int metadataRva=align(bodyRva+text.size(),SECTION_ALIGN);
        putU32(cli,8,metadataRva); putU32(cli,12,metadata.length);
        byte[] textBytes=text.toByteArray(); System.arraycopy(cli,0,textBytes,0,cli.length);
        byte[] pe=buildPe(textBytes,metadata,metadataRva);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(pe);}
    }

    private static byte[] buildMetadata(String assemblyName,
                                        List<GameAnalysisEngine.TypeDef> types,
                                        List<GameAnalysisEngine.MethodDef> methods,
                                        List<GameAnalysisEngine.FieldDef> fields,
                                        GameAnalysisEngine.StringTable strings,
                                        Map<Integer,Integer> methodRvas,
                                        List<Integer> fieldIndices,
                                        List<Integer> methodIndices) throws IOException {
        StringHeap sh=new StringHeap();
        int moduleName=sh.add(assemblyName+".dll");
        int asmName=sh.add(assemblyName);
        int objectName=sh.add("Object"), systemNs=sh.add("System");
        int systemRuntime=sh.add("System.Runtime");
        BlobHeap bh=new BlobHeap();
        int voidSig=bh.add(new byte[]{0x00,0x00,0x01});
        int objectFieldSig=bh.add(new byte[]{0x06,0x1C});
        int publicKey=bh.add(new byte[0]);
        GuidHeap gh=new GuidHeap(); int mvid=gh.addZero();

        int moduleCount=1, typeRefCount=1, typeDefCount=types.size()+1;
        int fieldCount=fieldIndices.size(), methodCount=methodIndices.size(), assemblyCount=1, assemblyRefCount=1;
        long valid=(1L<<0)|(1L<<1)|(1L<<2)|(1L<<4)|(1L<<6)|(1L<<32)|(1L<<35);
        // Force four-byte heap indexes so large name/blob heaps never invalidate the assembly.
        int heapSizes=0x07;
        int typeDefOrRefSize=codedSize(Math.max(typeDefCount,Math.max(typeRefCount,1)),2);
        int resolutionScopeSize=codedSize(Math.max(typeRefCount,Math.max(moduleCount,assemblyRefCount)),2);
        int fieldIndexSize=indexSize(fieldCount), methodIndexSize=indexSize(methodCount), paramIndexSize=indexSize(0);

        ByteArrayOutputStream tables=new ByteArrayOutputStream();
        putU32(tables,0); tables.write(2); tables.write(0); tables.write(heapSizes); tables.write(1);
        writeU64(tables,valid); writeU64(tables,0);
        putU32(tables,moduleCount); putU32(tables,typeRefCount); putU32(tables,typeDefCount); putU32(tables,fieldCount); putU32(tables,methodCount); putU32(tables,assemblyCount); putU32(tables,assemblyRefCount);

        // Module
        putU16(tables,0); putU32(tables,moduleName); putU32(tables,mvid); putU32(tables,0); putU32(tables,0);
        // TypeRef System.Object, scoped to AssemblyRef #1
        writeIndex(tables,(1<<2)|2,resolutionScopeSize); putU32(tables,objectName); putU32(tables,systemNs);
        // <Module> TypeDef
        putU32(tables,0); putU32(tables,sh.add("<Module>")); putU32(tables,0); writeIndex(tables,5,typeDefOrRefSize);
        writeIndex(tables,1,fieldIndexSize); writeIndex(tables,1,methodIndexSize);

        int fieldRow=1, methodRow=1;
        for(GameAnalysisEngine.TypeDef t:types){
            putU32(tables,0x00000001); // public
            putU32(tables,sh.add(nullSafe(strings.get(t.nameIndex),"Type")));
            putU32(tables,sh.add(nullSafe(strings.get(t.namespaceIndex),"")));
            writeIndex(tables,5,typeDefOrRefSize);
            writeIndex(tables,fieldRow,fieldIndexSize); writeIndex(tables,methodRow,methodIndexSize);
            fieldRow+=Math.max(0,t.fieldCount); methodRow+=Math.max(0,t.methodCount);
        }
        for(Integer idx:fieldIndices){GameAnalysisEngine.FieldDef f=fields.get(idx);putU16(tables,0x0006);putU32(tables,sh.add(nullSafe(strings.get(f.nameIndex),"field_"+idx)));putU32(tables,objectFieldSig);}
        for(Integer idx:methodIndices){GameAnalysisEngine.MethodDef m=methods.get(idx);putU32(tables,methodRvas.getOrDefault(idx,0));putU16(tables,0);putU16(tables,0x0016);putU32(tables,sh.add(nullSafe(strings.get(m.nameIndex),"Method_"+idx)));putU32(tables,voidSig);writeIndex(tables,1,paramIndexSize);}
        // Assembly
        putU32(tables,0);putU16(tables,1);putU16(tables,0);putU16(tables,0);putU16(tables,0);putU32(tables,0);putU32(tables,publicKey);putU32(tables,asmName);putU32(tables,0);
        // AssemblyRef -> System.Runtime
        putU16(tables,4);putU16(tables,0);putU16(tables,0);putU16(tables,0);putU32(tables,0);putU32(tables,publicKey);putU32(tables,systemRuntime);putU32(tables,0);putU32(tables,0);

        byte[] tableBytes=tables.toByteArray();byte[] stringsBytes=sh.toBytes(),blobBytes=bh.toBytes(),guidBytes=gh.toBytes(),usBytes=new byte[]{0};
        byte[][] payloads={tableBytes,stringsBytes,blobBytes,guidBytes,usBytes};String[] names={"#~","#Strings","#Blob","#GUID","#US"};
        int versionLen=12; // v4.0.30319 including NUL
        int streamHeaders=0;for(String n:names)streamHeaders+=8+align(n.length()+1,4);
        int metadataOffset=16+align(versionLen,4)+4+streamHeaders;int[] offsets=new int[payloads.length];int off=metadataOffset;for(int i=0;i<payloads.length;i++){offsets[i]=off;off+=payloads[i].length;}
        ByteArrayOutputStream md=new ByteArrayOutputStream();putU32(md,0x424A5342);putU16(md,1);putU16(md,1);putU32(md,0);putU32(md,versionLen);md.write("v4.0.30319\0".getBytes(StandardCharsets.US_ASCII));while((md.size()&3)!=0)md.write(0);putU16(md,0);putU16(md,names.length);
        for(int i=0;i<names.length;i++){putU32(md,offsets[i]);putU32(md,payloads[i].length);md.write(names[i].getBytes(StandardCharsets.US_ASCII));md.write(0);while((md.size()&3)!=0)md.write(0);}for(byte[] p:payloads)md.write(p);return md.toByteArray();
    }

    private static String nullSafe(String s,String f){return s==null||s.isEmpty()?f:s;}
    private static int indexSize(int rows){return rows>=65536?4:2;}
    private static int codedSize(int maxRows,int tagBits){return (maxRows << tagBits) >= 65536 ? 4 : 2;}
    private static void writeIndex(ByteArrayOutputStream b,int value,int size){if(size==2)putU16(b,value);else putU32(b,value);}

    private static byte[] buildPe(byte[] text,byte[] metadata,int metadataRva)throws IOException{
        int ntOff=0x80;int headers=align(ntOff+4+20+0xF0+80,FILE_ALIGN);int textRva=0x1000,textRaw=align(text.length,FILE_ALIGN),metaRaw=align(metadata.length,FILE_ALIGN);int textPtr=headers,metaPtr=textPtr+textRaw;int imageSize=align(metadataRva+metadata.length,SECTION_ALIGN);
        ByteArrayOutputStream out=new ByteArrayOutputStream(metaPtr+metaRaw);byte[]dos=new byte[ntOff];dos[0]='M';dos[1]='Z';putU32(dos,0x3c,ntOff);out.write(dos);putU32(out,0x00004550);putU16(out,0x8664);putU16(out,2);putU32(out,0);putU32(out,0);putU32(out,0);putU16(out,0xF0);putU16(out,0x2022);
        byte[]opt=new byte[0xF0];putU16(opt,0,0x20B);opt[2]=8;putU32(opt,4,textRaw);putU32(opt,8,metaRaw);putU32(opt,12,0);putU32(opt,16,0);putU32(opt,20,textRva);writeU64(opt,24,0x180000000L);putU32(opt,32,SECTION_ALIGN);putU32(opt,36,FILE_ALIGN);putU16(opt,40,6);putU16(opt,42,0);putU16(opt,44,0);putU16(opt,46,0);putU16(opt,48,6);putU16(opt,50,0);putU32(opt,52,0);putU32(opt,56,imageSize);putU32(opt,60,headers);putU32(opt,64,0);putU16(opt,68,3);putU16(opt,70,0x8140);writeU64(opt,72,0x100000);writeU64(opt,80,0x1000);writeU64(opt,88,0x100000);writeU64(opt,96,0x1000);putU32(opt,104,0);putU32(opt,108,16);putU32(opt,112+14*8,textRva);putU32(opt,112+14*8+4,72);out.write(opt);
        byte[]sh=new byte[80];section(sh,0,".text",text.length,textRva,textRaw,textPtr,0x60000020);section(sh,40,".meta",metadata.length,metadataRva,metaRaw,metaPtr,0x40000040);out.write(sh);while(out.size()<headers)out.write(0);out.write(text);while(out.size()<metaPtr)out.write(0);out.write(metadata);while(out.size()<metaPtr+metaRaw)out.write(0);return out.toByteArray();
    }
    private static void section(byte[]b,int p,String name,int vs,int va,int raw,int ptr,int chars){byte[]n=name.getBytes(StandardCharsets.US_ASCII);System.arraycopy(n,0,b,p,Math.min(8,n.length));putU32(b,p+8,vs);putU32(b,p+12,va);putU32(b,p+16,raw);putU32(b,p+20,ptr);putU32(b,p+36,chars);}
    private static int align(int n,int a){return (n+a-1)&~(a-1);}
    private static String safe(String s){return s.replaceAll("[^A-Za-z0-9._-]","_");}
    private static void putU32(ByteArrayOutputStream b,int x){b.write(x&255);b.write((x>>>8)&255);b.write((x>>>16)&255);b.write((x>>>24)&255);}private static void putU32(byte[]b,int p,int x){b[p]=(byte)x;b[p+1]=(byte)(x>>>8);b[p+2]=(byte)(x>>>16);b[p+3]=(byte)(x>>>24);}
    private static void putU16(ByteArrayOutputStream b,int x){b.write(x&255);b.write((x>>>8)&255);}private static void putU16(byte[]b,int p,int x){b[p]=(byte)x;b[p+1]=(byte)(x>>>8);}
    private static void writeU64(ByteArrayOutputStream b,long x){for(int i=0;i<8;i++)b.write((int)(x>>(8*i))&255);}private static void writeU64(byte[]b,int p,long x){for(int i=0;i<8;i++)b[p+i]=(byte)(x>>(8*i));}

    private static final class StringHeap {final ByteArrayOutputStream b=new ByteArrayOutputStream();StringHeap(){b.write(0);}int add(String s){byte[]x=(s==null?"":s).getBytes(StandardCharsets.UTF_8);int o=b.size();try{b.write(x);b.write(0);}catch(IOException e){throw new RuntimeException(e);}return o;}byte[]toBytes(){return b.toByteArray();}}
    private static final class BlobHeap {final ByteArrayOutputStream b=new ByteArrayOutputStream();BlobHeap(){b.write(0);}int add(byte[]x){int o=b.size();byte[]len=compressedLength(x.length);try{b.write(len);b.write(x);}catch(IOException e){throw new RuntimeException(e);}return o;}private static byte[]compressedLength(int n){if(n<0x80)return new byte[]{(byte)n};if(n<0x4000)return new byte[]{(byte)((n>>8)|0x80),(byte)n};return new byte[]{(byte)((n>>24)|0xC0),(byte)(n>>16),(byte)(n>>8),(byte)n};}byte[]toBytes(){return b.toByteArray();}}
    private static final class GuidHeap {final ByteArrayOutputStream b=new ByteArrayOutputStream();GuidHeap(){}int addZero(){int o=1;for(int i=0;i<16;i++)b.write(0);return o;}byte[]toBytes(){return b.toByteArray();}}
}
