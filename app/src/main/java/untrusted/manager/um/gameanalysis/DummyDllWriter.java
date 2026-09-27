package untrusted.manager.um.gameanalysis;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Writes small, valid ECMA-335 browseable stub assemblies from IL2CPP metadata. */
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
        if (!root.exists() && !root.mkdirs()) throw new IOException("Cannot create DummyDll");
        if (images.isEmpty()) {
            writeAssembly(new File(root, "DummyAssembly.dll"), "DummyAssembly", Collections.emptyList(), methods, fields, strings);
            return;
        }
        for (int ii = 0; ii < images.size(); ii++) {
            GameAnalysisEngine.ImageDef image = images.get(ii);
            List<GameAnalysisEngine.TypeDef> imageTypes = new ArrayList<>();
            List<GameAnalysisEngine.FieldDef> imageFields = new ArrayList<>();
            List<GameAnalysisEngine.MethodDef> imageMethods = new ArrayList<>();
            int start = Math.max(0, image.typeStart);
            int end = Math.min(types.size(), start + Math.max(0, (int)image.typeCount));
            for (int i = start; i < end; i++) {
                GameAnalysisEngine.TypeDef src = types.get(i);
                GameAnalysisEngine.TypeDef copy = new GameAnalysisEngine.TypeDef();
                copy.nameIndex=src.nameIndex; copy.namespaceIndex=src.namespaceIndex; copy.token=src.token; copy.flags=src.flags; copy.bitfield=src.bitfield;
                copy.fieldStart=imageFields.size(); copy.methodStart=imageMethods.size();
                copy.fieldCount=src.fieldCount; copy.methodCount=src.methodCount; copy.eventCount=0; copy.propertyCount=0;
                int fs=Math.max(0,src.fieldStart), fe=Math.min(fields.size(),fs+src.fieldCount); for(int fi=fs;fi<fe;fi++) imageFields.add(fields.get(fi));
                int ms=Math.max(0,src.methodStart), me=Math.min(methods.size(),ms+src.methodCount); for(int mi=ms;mi<me;mi++) imageMethods.add(methods.get(mi));
                imageTypes.add(copy);
            }
            String imageName = strings.get(image.nameIndex);
            if (imageName == null || imageName.isEmpty()) imageName = "Assembly_" + ii;
            String base = imageName.endsWith(".dll") ? imageName.substring(0, imageName.length() - 4) : imageName;
            String outputBase=safe(base); File target=new File(root,outputBase+".dll"); if(target.exists()) target=new File(root,outputBase+"_"+ii+".dll"); writeAssembly(target, base, imageTypes, imageMethods, imageFields, strings);
        }
    }

    private static void writeAssembly(File file, String assemblyName,
                                      List<GameAnalysisEngine.TypeDef> types,
                                      List<GameAnalysisEngine.MethodDef> methods,
                                      List<GameAnalysisEngine.FieldDef> fields,
                                      GameAnalysisEngine.StringTable strings) throws IOException {
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        // CLI header occupies the beginning of .text. Method bodies follow it.
        byte[] cli = new byte[72];
        putI32(cli, 0, 72);
        putU16(cli, 4, 2); putU16(cli, 6, 5);
        text.write(cli);
        int bodyRvaBase = 0x1000;
        int bodyStart = align(text.size(), 4);
        while (text.size() < bodyStart) text.write(0);
        Map<Integer, Integer> methodRvas = new HashMap<>();
        for (int i = 0; i < methods.size(); i++) {
            int rva = bodyRvaBase + text.size();
            methodRvas.put(i, rva);
            text.write(0x06); // tiny IL method body: one-byte code, one-byte header
            text.write(0x2A); // ret
        }
        byte[] metadata = buildMetadata(assemblyName, types, methods, fields, strings, methodRvas);
        int metadataRva = 0x2000;
        putI32(cli, 8, metadataRva); putI32(cli, 12, metadata.length); putI32(cli, 16, 1);
        // entry point is zero: this is a library.
        byte[] textBytes = text.toByteArray();
        System.arraycopy(cli, 0, textBytes, 0, cli.length);
        byte[] pe = buildPe(textBytes, metadata, metadataRva);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(pe); }
    }

    private static byte[] buildMetadata(String assemblyName,
                                        List<GameAnalysisEngine.TypeDef> types,
                                        List<GameAnalysisEngine.MethodDef> methods,
                                        List<GameAnalysisEngine.FieldDef> fields,
                                        GameAnalysisEngine.StringTable strings,
                                        Map<Integer,Integer> methodRvas) throws IOException {
        // Build the heaps first. All heaps remain <64 KiB, so the compact 2-byte indexes apply.
        Heap stringsHeap = new Heap();
        int moduleName = stringsHeap.add(assemblyName + ".dll");
        int asmName = stringsHeap.add(assemblyName);
        int systemObject = stringsHeap.add("Object");
        int systemNs = stringsHeap.add("System");
        Heap blob = new Heap();
        int voidSig = blob.add(new byte[]{0x00, 0x00, 0x01}); // static void()
        int objectFieldSig = blob.add(new byte[]{0x06, 0x1C}); // FIELD object
        byte[] guidBytes = new byte[16];

        // One TypeRef (System.Object), module type + user types.
        int typeRefCount = 1;
        int typeDefCount = 1 + types.size();
        int fieldCount = 0;
        int methodCount = 0;
        for (GameAnalysisEngine.TypeDef t : types) {
            fieldCount += Math.max(0, t.fieldCount);
            methodCount += Math.max(0, t.methodCount);
        }
        int assemblyRefCount = 1;
        if(typeDefCount>65535||fieldCount>65535||methodCount>65535) throw new IOException("DummyDll compact metadata limit exceeded; use the full dump artifacts instead");

        ByteArrayOutputStream tables = new ByteArrayOutputStream();
        putU32(tables, 0); // reserved
        tables.write(2); tables.write(0); // major/minor
        tables.write(0); // heapSizes: all compact
        tables.write(1); // reserved
        long valid = (1L<<0)|(1L<<1)|(1L<<2)|(1L<<4)|(1L<<6)|(1L<<32)|(1L<<35);
        writeU64(tables, valid); writeU64(tables, 0);
        int[] rowCounts = {1, typeRefCount, typeDefCount, fieldCount, methodCount, 1, assemblyRefCount};
        for (int c : rowCounts) putU32(tables, c);

        // Module
        putU16(tables, 0); putU16(tables, moduleName); putU16(tables, 1); putU16(tables, 0); putU16(tables, 0);
        // TypeRef: ResolutionScope -> AssemblyRef #1, Name, Namespace
        putU16(tables, (1<<2)|2); putU16(tables, systemObject); putU16(tables, systemNs);
        // <Module>
        putU32(tables, 0); putU16(tables, stringsHeap.add("<Module>")); putU16(tables, 0); putU16(tables, 0);
        putU16(tables, 1); putU16(tables, 1);
        int fieldRow = 1, methodRow = 1;
        for (GameAnalysisEngine.TypeDef t : types) {
            int flags = 0x00000001; // public
            int name = stringsHeap.add(strings.get(t.nameIndex));
            int ns = stringsHeap.add(strings.get(t.namespaceIndex));
            putU32(tables, flags); putU16(tables, name); putU16(tables, ns);
            putU16(tables, (1<<2)|1); // extends TypeRef #1 (System.Object)
            putU16(tables, fieldRow); putU16(tables, methodRow);
            fieldRow += Math.max(0, t.fieldCount); methodRow += Math.max(0, t.methodCount);
        }
        // Fields
        for (GameAnalysisEngine.TypeDef t : types) {
            int end = Math.min(fields.size(), t.fieldStart+Math.max(0,t.fieldCount));
            for (int i=Math.max(0,t.fieldStart); i<end; i++) {
                String n = strings.get(fields.get(i).nameIndex);
                putU16(tables, 0x0006); putU16(tables, stringsHeap.add(n)); putU16(tables, objectFieldSig);
            }
        }
        // Methods
        int globalMethod = 0;
        for (GameAnalysisEngine.TypeDef t : types) {
            int end = Math.min(methods.size(), t.methodStart+Math.max(0,t.methodCount));
            for (int i=Math.max(0,t.methodStart); i<end; i++,globalMethod++) {
                GameAnalysisEngine.MethodDef m = methods.get(i);
                putU32(tables, methodRvas.getOrDefault(globalMethod, 0));
                putU16(tables, 0); putU16(tables, 0x0016); // IL, managed; public static
                putU16(tables, stringsHeap.add(strings.get(m.nameIndex))); putU16(tables, voidSig); putU16(tables, 1);
            }
        }
        // Assembly
        putU32(tables, 0); putU16(tables,1);putU16(tables,0);putU16(tables,0);putU16(tables,0);putU32(tables,0);putU16(tables,0);putU16(tables,asmName);putU16(tables,0);
        // AssemblyRef -> System.Runtime
        putU16(tables,1);putU16(tables,0);putU16(tables,0);putU16(tables,0);putU32(tables,0);putU16(tables,0);putU16(tables,stringsHeap.add("System.Runtime"));putU16(tables,0);putU16(tables,0);

        byte[] tableBytes=tables.toByteArray();
        byte[] stringsBytes=stringsHeap.toBytes(); byte[] blobBytes=blob.toBytes(); byte[] usBytes=new byte[]{0};
        if(stringsBytes.length>65535||blobBytes.length>65535) throw new IOException("DummyDll heap limit exceeded; use the full dump artifacts instead");
        String ver="v4.0.30319\0"; byte[] vb=ver.getBytes(StandardCharsets.US_ASCII);
        int root=16+align(vb.length,4)+4+4; // sig+version fields + flags/streams; stream headers added below
        String[] names={"#~","#Strings","#Blob","#GUID","#US"};
        int streamHeaders=0; for(String n:names) streamHeaders += 8+align(n.length()+1,4);
        root=16+align(vb.length,4)+4+streamHeaders;
        int offset=root;
        int[] offs=new int[5];byte[][] payloads={tableBytes,stringsBytes,blobBytes,guidBytes,usBytes};
        for(int i=0;i<payloads.length;i++){offs[i]=offset;offset+=payloads[i].length;}
        ByteArrayOutputStream md=new ByteArrayOutputStream();putI32(md,0x424A5342);putU16(md,1);putU16(md,1);putI32(md,0);putI32(md,vb.length);md.write(vb);while((md.size()&3)!=0)md.write(0);putU16(md,0);putU16(md,names.length);
        for(int i=0;i<names.length;i++){putI32(md,offs[i]);putI32(md,payloads[i].length);md.write(names[i].getBytes(StandardCharsets.US_ASCII));md.write(0);while((md.size()&3)!=0)md.write(0);}
        for(byte[] p:payloads)md.write(p);
        return md.toByteArray();
    }

    private static byte[] buildPe(byte[] text, byte[] metadata, int metadataRva) throws IOException {
        int ntOff=0x80, headers=align(ntOff+4+20+0xF0+80,FILE_ALIGN);
        int textRva=0x1000, metaRva=0x2000;
        int textRaw=align(text.length,FILE_ALIGN), metaRaw=align(metadata.length,FILE_ALIGN);
        int textPtr=headers, metaPtr=textPtr+textRaw;
        int imageSize=align(metaRva+metadata.length,SECTION_ALIGN);
        ByteArrayOutputStream out=new ByteArrayOutputStream(metaPtr+metaRaw);byte[] dos=new byte[ntOff];dos[0]='M';dos[1]='Z';putI32(dos,0x3c,ntOff);out.write(dos);putI32(out,0x00004550);
        putU16(out,0x8664);putU16(out,2);putI32(out,0);putI32(out,0);putI32(out,0);putU16(out,0xF0);putU16(out,0x2022);
        byte[] opt=new byte[0xF0];putU16(opt,0,0x20B);opt[2]=8;putI32(opt,4,textRaw);putI32(opt,8,metaRaw);putI32(opt,12,0);putI32(opt,16,0);putI32(opt,20,textRva);writeU64(opt,24,0x180000000L);putI32(opt,32,SECTION_ALIGN);putI32(opt,36,FILE_ALIGN);putU16(opt,40,6);putU16(opt,42,0);putU16(opt,44,0);putU16(opt,46,0);putU16(opt,48,6);putU16(opt,50,0);putI32(opt,52,0);putI32(opt,56,imageSize);putI32(opt,60,headers);putI32(opt,64,0);putU16(opt,68,3);putU16(opt,70,0x8140);writeU64(opt,72,0x100000);writeU64(opt,80,0x1000);writeU64(opt,88,0x100000);writeU64(opt,96,0x1000);putI32(opt,104,0);putI32(opt,108,16);putI32(opt,112+14*8,textRva);putI32(opt,112+14*8+4,72);out.write(opt);
        byte[] sh=new byte[80];section(sh,0,".text",text.length,textRva,textRaw,textPtr,0x60000020);section(sh,40,".meta",metadata.length,metaRva,metaRaw,metaPtr,0x40000040);out.write(sh);while(out.size()<headers)out.write(0);
        out.write(text);while(out.size()<metaPtr)out.write(0);out.write(metadata);while(out.size()<metaPtr+metaRaw)out.write(0);return out.toByteArray();
    }
    private static void section(byte[] b,int p,String name,int vs,int va,int raw,int ptr,int chars){byte[] n=name.getBytes(StandardCharsets.US_ASCII);System.arraycopy(n,0,b,p,Math.min(8,n.length));putI32(b,p+8,vs);putI32(b,p+12,va);putI32(b,p+16,raw);putI32(b,p+20,ptr);putI32(b,p+36,chars);}
    private static int align(int n,int a){return (n+a-1)&~(a-1);}
    private static String safe(String s){return s.replaceAll("[^A-Za-z0-9._-]","_");}
    private static void putU32(ByteArrayOutputStream b,int x){b.write(x&255);b.write((x>>>8)&255);b.write((x>>>16)&255);b.write((x>>>24)&255);}
    private static void putU16(ByteArrayOutputStream b,int x){b.write(x&255);b.write((x>>>8)&255);} private static void putU16(byte[]b,int p,int x){b[p]=(byte)x;b[p+1]=(byte)(x>>>8);}
    private static void putI32(ByteArrayOutputStream b,int x){b.write(x&255);b.write((x>>>8)&255);b.write((x>>>16)&255);b.write((x>>>24)&255);} private static void putI32(byte[]b,int p,int x){b[p]=(byte)x;b[p+1]=(byte)(x>>>8);b[p+2]=(byte)(x>>>16);b[p+3]=(byte)(x>>>24);}
    private static void writeU64(ByteArrayOutputStream b,long x){for(int i=0;i<8;i++)b.write((int)(x>>(8*i))&255);} private static void writeU64(byte[]b,int p,long x){for(int i=0;i<8;i++)b[p+i]=(byte)(x>>(8*i));}
    private static final class Heap { final ByteArrayOutputStream b=new ByteArrayOutputStream(); Heap(){b.write(0);} int add(String s){byte[]x=s==null?new byte[0]:s.getBytes(StandardCharsets.UTF_8);int o=b.size();try{b.write(x);b.write(0);}catch(IOException e){throw new RuntimeException(e);}return o;} int add(byte[]x){int o=b.size();try{b.write(x);}catch(IOException e){throw new RuntimeException(e);}return o;} byte[]toBytes(){return b.toByteArray();} }
}
