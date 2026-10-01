package untrusted.manager.um.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Managed .NET/CLI assembly reader and C#-style source/IL renderer.
 *
 * This is intentionally self-contained so UM can inspect assemblies on Android without
 * requiring a desktop .NET runtime. It reads ECMA-335 PE/CLI metadata, signatures and
 * method bodies and reconstructs useful C# for normal managed assemblies. When a method
 * contains constructs the renderer cannot safely reconstruct, the generated source keeps
 * the original IL as a comment instead of inventing semantics.
 */
final class DotNetAssemblyParser {
    /** Lightweight PE/CLR probe used by the UI to distinguish native PE files from
     * managed assemblies before attempting the full metadata reader. */
    static final class Probe {
        final boolean pe;
        final boolean managed;
        final boolean pe32Plus;
        final int machine;
        final int sectionCount;
        final long clrRva;
        final long metadataRva;
        final String reason;
        Probe(boolean pe, boolean managed, boolean pe32Plus, int machine, int sectionCount,
              long clrRva, long metadataRva, String reason) {
            this.pe=pe; this.managed=managed; this.pe32Plus=pe32Plus; this.machine=machine;
            this.sectionCount=sectionCount; this.clrRva=clrRva; this.metadataRva=metadataRva; this.reason=reason;
        }
        String architecture() {
            switch (machine) {
                case 0x8664: return "x64";
                case 0x014c: return "x86";
                case 0xAA64: return "ARM64";
                case 0x01c4: return "ARM";
                default: return String.format(Locale.ROOT, "0x%04X", machine);
            }
        }
    }
    static Probe probe(File f) throws IOException { return probe(read(f)); }
    static Probe probe(byte[] b) throws IOException {
        if (b == null || b.length < 0x40 || u16(b,0) != 0x5A4D)
            return new Probe(false,false,false,0,0,0,0,"Missing DOS MZ header");
        int pe=u32(b,0x3c);
        if (pe < 0 || pe > b.length-24 || u32(b,pe) != 0x4550)
            return new Probe(false,false,false,0,0,0,0,"Invalid PE signature");
        int coff=pe+4, machine=u16(b,coff), sections=u16(b,coff+2), opt=coff+20;
        if(sections<0||sections>96||opt<0||opt>b.length-2)return new Probe(true,false,false,machine,sections,0,0,"Invalid PE section/optional-header bounds");
        if (opt+2 > b.length) return new Probe(true,false,false,machine,sections,0,0,"Truncated optional header");
        int magic=u16(b,opt); boolean plus=magic==0x20b;
        if (magic!=0x10b && magic!=0x20b)
            return new Probe(true,false,plus,machine,sections,0,0,String.format(Locale.ROOT,"Unsupported PE optional-header magic 0x%04X",magic));
        int dataDir=opt+(plus?112:96), cliDir=dataDir+14*8;
        int sizeOpt=u16(b,coff+16);
        if(sizeOpt<0||opt+sizeOpt>b.length||cliDir+8>opt+sizeOpt)return new Probe(true,false,plus,machine,sections,0,0,"PE optional header has no CLR directory slot");
        if (cliDir+8 > b.length) return new Probe(true,false,plus,machine,sections,0,0,"PE has no complete CLR data-directory entry");
        long clr=Integer.toUnsignedLong(u32(b,cliDir));
        if (clr==0) return new Probe(true,false,plus,machine,sections,0,0,"PE has no CLR directory; this is a native/unmanaged PE file");
        Section[] ss=new Section[Math.max(0,sections)];
        int sh=opt+u16(b,coff+16);
        for(int i=0;i<ss.length;i++){int o=sh+i*40;if(o+40>b.length)return new Probe(true,true,plus,machine,sections,clr,0,"CLR directory exists but the PE section table is truncated");ss[i]=new Section(u32(b,o+12),u32(b,o+16),u32(b,o+20),u32(b,o+8));}
        int cliOff=rva(ss,(int)clr);
        if(cliOff<0||cliOff+16>b.length)return new Probe(true,true,plus,machine,sections,clr,0,"CLR directory points outside the PE sections");
        long md=Integer.toUnsignedLong(u32(b,cliOff+8));
        int mdOff=rva(ss,(int)md);
        boolean root=mdOff>=0 && mdOff+4<=b.length && u32(b,mdOff)==0x424A5342;
        if(!root) {
            // Obfuscators and post-processing tools sometimes leave a stale CLR metadata RVA.
            // A valid BSJB root anywhere in the image is enough to try the metadata reader.
            root=findMetadataRoot(b)>=0;
        }
        return new Probe(true,root,plus,machine,sections,clr,md,root?"CLR directory present; metadata root detected":"CLR directory present, but no readable BSJB metadata root was found");
    }
    private static int findMetadataRoot(byte[] b){
        for(int i=0;i+4<=b.length;i++) if((b[i]&255)==0x42&&(b[i+1]&255)==0x53&&(b[i+2]&255)==0x4A&&(b[i+3]&255)==0x42)return i;
        return -1;
    }
    static final class TypeDef {
        final String namespace, name, baseType;
        final int token;
        final List<FieldDef> fields = new ArrayList<>();
        final List<MethodDef> methods = new ArrayList<>();
        final List<PropertyDef> properties = new ArrayList<>();
        final List<TypeDef> nested = new ArrayList<>();
        final List<String> interfaces = new ArrayList<>();
        final List<String> genericParams = new ArrayList<>();
        TypeDef enclosing;
        int flags;
        TypeDef(String ns, String n, String b, int token, int flags) {
            namespace = ns == null ? "" : ns; name = n == null ? "" : n; baseType = b == null ? "" : b;
            this.token = token; this.flags = flags;
        }
        String fullName() { return namespace.isEmpty() ? name : namespace + "." + name; }
        String displayName() { return name; }
    }
    static final class FieldDef {
        final String name, type; final int flags, token;
        FieldDef(String n, String t, int f, int tok) { name=n; type=t; flags=f; token=tok; }
    }
    static final class PropertyDef {
        final String name, type; MethodDef getter, setter;
        PropertyDef(String n, String t) { name=n; type=t; }
    }
    static final class MethodDef {
        final String name; String ownerType=""; String ret="void"; int params; int flags; int token; int rva;
        String[] paramTypes = new String[0]; String[] paramNames = new String[0];
        final List<String> genericParams = new ArrayList<>();
        String il = ""; String source = "";
        MethodDef(String n) { name=n; }
        String signature() {
            String display = ".ctor".equals(name) || ".cctor".equals(name) ? ownerType : cleanMemberName(name);
            if (display == null || display.isEmpty()) display = name;
            StringBuilder b=new StringBuilder((".ctor".equals(name)||".cctor".equals(name)) ? "" : ret+" ").append(display);
            if(!genericParams.isEmpty() && !(".ctor".equals(name)||".cctor".equals(name))){b.append('<');for(int i=0;i<genericParams.size();i++){if(i>0)b.append(", ");b.append(cleanGeneric(genericParams.get(i)));}b.append('>');}
            b.append('(');
            for(int i=0;i<paramTypes.length;i++){if(i>0)b.append(", ");b.append(paramTypes[i]).append(' ').append(paramNames.length>i&&paramNames[i]!=null&&!paramNames[i].isEmpty()?paramNames[i]:"arg"+i);}
            return b.append(')').toString();
        }
    }

    final List<TypeDef> types = new ArrayList<>();
    final String assemblyName;
    final byte[] image;
    final Section[] sections;
    final int metadataOffset;
    final Tables tables;
    final int stringsBase, blobBase, usBase;
    private final Map<Integer,String> tokenNames = new HashMap<>();
    private final Map<Integer,String> tokenTypes = new HashMap<>();

    private DotNetAssemblyParser(String assemblyName, byte[] image, Section[] sections, int metadataOffset,
                                 Tables tables, int stringsBase, int blobBase, int usBase) {
        this.assemblyName=assemblyName; this.image=image; this.sections=sections; this.metadataOffset=metadataOffset;
        this.tables=tables; this.stringsBase=stringsBase; this.blobBase=blobBase; this.usBase=usBase;
    }

    static DotNetAssemblyParser parse(File f) throws IOException {
        return parse(read(f));
    }

    static DotNetAssemblyParser parse(byte[] b) throws IOException {
        Probe probe=probe(b);
        if(!probe.pe) throw new IOException(probe.reason);
        // Do not reject a PE solely because its CLR directory is missing: a few
        // packers/rewriters leave the directory stale while preserving the metadata root.
        if(!probe.managed) {
            int root=findMetadataRoot(b);
            if(root<0) throw new IOException(probe.reason);
        }
        int pe=u32(b,0x3c);
        int coff=pe+4, sections=u16(b,coff+2), opt=coff+20, magic=u16(b,opt);
        int dataDir=opt+(magic==0x20b?112:96), cliDir=dataDir+14*8;
        Section[] ss=new Section[sections]; int sh=opt+u16(b,coff+16);
        for(int i=0;i<sections;i++){int o=sh+i*40;if(o+40>b.length)throw new IOException("Invalid PE section table");ss[i]=new Section(u32(b,o+12),u32(b,o+16),u32(b,o+20),u32(b,o+8));}
        IOException primary=null;
        if(cliDir+8<=b.length){
            int cliRva=u32(b,cliDir);
            if(cliRva!=0){
                int cliOff=rva(ss,cliRva);
                if(cliOff>=0&&cliOff+16<=b.length){
                    int mdRva=u32(b,cliOff+8), mdOff=rva(ss,mdRva);
                    if(mdOff>=0){
                        try{return parseMetadata(b,ss,mdOff);}catch(IOException e){primary=e;}
                    }
                }
            }
        }
        // Recovery for stale/relocated CLR metadata directories.
        for(int i=0;i+4<=b.length;i++){
            if((b[i]&255)==0x42&&(b[i+1]&255)==0x53&&(b[i+2]&255)==0x4A&&(b[i+3]&255)==0x42){
                try{return parseMetadata(b,ss,i);}catch(IOException ignored){}
            }
        }
        if(primary!=null) throw primary;
        throw new IOException("CLR metadata root (BSJB) could not be parsed");
    }

    private static DotNetAssemblyParser parseMetadata(byte[] b, Section[] ss, int off) throws IOException {
        if(off+20>b.length||u32(b,off)!=0x424A5342) throw new IOException("Invalid CLR metadata root");
        int verLen=u32(b,off+12); if(verLen<0||off+16+verLen+4>b.length)throw new IOException("Invalid CLR version string");
        int pos=(off+16+verLen+3)&~3; int streams=u16(b,pos+2); pos+=4;
        Map<String,int[]> sm=new HashMap<>();
        for(int i=0;i<streams;i++){
            if(pos+8>b.length)throw new IOException("Truncated metadata stream header");
            int so=u32(b,pos), sz=u32(b,pos+4); int p=pos+8,q=p; while(q<b.length&&b[q]!=0)q++;
            if(q>=b.length)throw new IOException("Unterminated metadata stream name");
            if(so<0||sz<0||so>b.length-off||sz>b.length-off-so)throw new IOException("Metadata stream exceeds file");
            String name=new String(b,p,q-p,StandardCharsets.US_ASCII); q=(q+4)&~3; sm.put(name,new int[]{off+so,sz}); pos=q;
        }
        int[] tablesStream=sm.get("#~"); if(tablesStream==null)tablesStream=sm.get("#-"); if(tablesStream==null)throw new IOException("No metadata tables stream");
        int[] strings=sm.get("#Strings"), blob=sm.get("#Blob"), us=sm.get("#US");
        if(strings==null)throw new IOException("No #Strings stream");
        Tables t=Tables.read(b,tablesStream[0], tablesStream[0]+tablesStream[1]);
        String assembly="ManagedAssembly";
        Row asm=t.row(32,0); if(asm!=null) assembly=t.str(strings[0], asm.u4(7));
        DotNetAssemblyParser out=new DotNetAssemblyParser(assembly,b,ss,off,t,strings[0],blob==null?0:blob[0],us==null?0:us[0]);
        out.buildModel();
        return out;
    }

    private void buildModel() throws IOException {
        List<TypeDef> byRid=new ArrayList<>(); byRid.add(null);
        int typeCount=tables.count(2);
        for(int rid=1;rid<=typeCount;rid++){
            Row r=tables.row(2,rid-1); int flags=r.u4(0); int nameIx=r.strIndex(1); int nsIx=r.strIndex(2); int extendsTok=r.coded(3,"TypeDefOrRef");
            TypeDef td=new TypeDef(str(nsIx), str(nameIx), resolveTypeToken(extendsTok), 0x02000000|rid, flags); byRid.add(td); types.add(td);
            tokenNames.put(td.token,td.fullName()); tokenTypes.put(td.token,"type");
        }
        // Nested types are kept visible in the assembly browser and use their full name for source rendering.
        int nestedCount=tables.count(41);
        for(int i=0;i<nestedCount;i++){Row r=tables.row(41,i);int nested=r.table(0), enclosing=r.table(1);if(nested>0&&enclosing>0&&nested<byRid.size()&&enclosing<byRid.size()){TypeDef child=byRid.get(nested), parent=byRid.get(enclosing);child.enclosing=parent;parent.nested.add(child);}}
        // Interface implementations.
        int ifaceCount=tables.count(9);
        for(int i=0;i<ifaceCount;i++){Row r=tables.row(9,i);int owner=r.table(0), iface=r.coded(1,"TypeDefOrRef");if(owner>0&&owner<byRid.size()){String n=resolveTypeToken(iface);if(n!=null&&!n.isEmpty())byRid.get(owner).interfaces.add(simple(n));}}

        int fieldCount=tables.count(4); List<FieldDef> fieldByRid=new ArrayList<>();fieldByRid.add(null);
        for(int rid=1;rid<=fieldCount;rid++){Row r=tables.row(4,rid-1);String n=str(r.strIndex(1));String ty=decodeFieldSig(r.blobIndex(2));FieldDef f=new FieldDef(n,ty,r.u2(0),0x04000000|rid);fieldByRid.add(f);tokenNames.put(f.token,n);tokenTypes.put(f.token,"field");}
        int methodCount=tables.count(6); List<MethodDef> methods=new ArrayList<>();methods.add(null);
        for(int rid=1;rid<=methodCount;rid++){
            Row r=tables.row(6,rid-1); MethodDef m=new MethodDef(str(r.strIndex(3)));m.flags=r.u2(2);m.rva=r.u4(0);m.token=0x06000000|rid;
            MethodSig sig=decodeMethodSig(r.blobIndex(4));m.ret=sig.ret;m.paramTypes=sig.params;m.params=sig.params.length;m.paramNames=new String[sig.params.length];
            methods.add(m);tokenNames.put(m.token,m.name);tokenTypes.put(m.token,"method");
        }
        // Method parameter names.
        int paramCount=tables.count(8); for(int i=0;i<paramCount;i++){Row r=tables.row(8,i);int seq=r.u2(1);int nameIx=r.strIndex(2);int owner=findMethodForParam(i+1,methods);if(owner>0){MethodDef m=methods.get(owner);if(seq>=1&&seq<=m.paramNames.length)m.paramNames[seq-1]=str(nameIx);}}
        for(MethodDef m:methods)if(m!=null&&m.paramNames.length!=m.paramTypes.length)m.paramNames=new String[m.paramTypes.length];
        // Fields/methods are assigned to TypeDefs by their list-start columns.
        for(int rid=1;rid<=typeCount;rid++){
            TypeDef td=byRid.get(rid);Row tr=tables.row(2,rid-1);int nextField=(rid<typeCount)?tables.row(2,rid).table(4):fieldCount+1;int firstField=tr.table(4);
            for(int x=firstField;x<nextField&&x>0&&x<=fieldCount;x++)td.fields.add(fieldByRid.get(x));
            int nextMethod=(rid<typeCount)?tables.row(2,rid).table(5):methodCount+1;int firstMethod=tr.table(5);
            for(int x=firstMethod;x<nextMethod&&x>0&&x<=methodCount;x++){ methods.get(x).ownerType=td.name; td.methods.add(methods.get(x)); }
        }
        // Generic type and method parameters.
        int gpCount=tables.count(42);
        for(int i=0;i<gpCount;i++){Row r=tables.row(42,i);int owner=r.coded(2,"TypeOrMethodDef"), number=r.u2(0);String name=str(r.strIndex(3));String n=(name==null||name.isEmpty())?"T"+number:name;int ot=(owner>>>24)&255,or=owner&0xffffff;if(ot==2&&or>0&&or<byRid.size())ensureGeneric(byRid.get(or).genericParams,number,n);else if(ot==6){for(TypeDef td:byRid)if(td!=null)for(MethodDef md:td.methods)if(md.token==owner)ensureGeneric(md.genericParams,number,n);}}
        int gpcCount=tables.count(44);
        for(int i=0;i<gpcCount;i++){Row r=tables.row(44,i);int owner=r.table(0), constraint=r.coded(1,"TypeDefOrRef");if(owner<=0||owner>gpCount)continue;Row gr=tables.row(42,owner-1);String c=resolveTypeToken(constraint);int own=gr.coded(2,"TypeOrMethodDef"),num=gr.u2(0);int ot=(own>>>24)&255,or=own&0xffffff;if(ot==2&&or>0&&or<byRid.size()){TypeDef td=byRid.get(or);String n=genericName(td.genericParams,num);if(n!=null&&c!=null&&!c.isEmpty())replaceGenericConstraint(td.genericParams,num,n+" : "+simple(c));}else if(ot==6){for(TypeDef td:byRid)if(td!=null)for(MethodDef md:td.methods)if(md.token==own){String n=genericName(md.genericParams,num);if(n!=null&&c!=null&&!c.isEmpty())replaceGenericConstraint(md.genericParams,num,n+" : "+simple(c));}}}
        // Properties and accessor methods.
        int propCount=tables.count(23); List<PropertyDef> props=new ArrayList<>();props.add(null);
        for(int rid=1;rid<=propCount;rid++){Row r=tables.row(23,rid-1);PropertyDef p=new PropertyDef(str(r.strIndex(2)),decodePropertySig(r.blobIndex(3)));props.add(p);}
        int semCount=tables.count(24);
        for(int i=0;i<semCount;i++){
            Row r=tables.row(24,i);
            int methodRid=r.table(1), assoc=r.coded(2,"HasSemantics");
            int bits=r.u2(0);
            if(methodRid>0&&methodRid<methods.size()&&(assoc>>>24)==23){
                int pr=assoc&0xffffff;
                if(pr>0&&pr<props.size()){
                    if((bits&2)!=0)props.get(pr).getter=methods.get(methodRid);
                    if((bits&1)!=0)props.get(pr).setter=methods.get(methodRid);
                }
            }
        }
        // Assign properties by TypeDef property list starts through PropertyMap.
        int mapCount=tables.count(21);for(int i=0;i<mapCount;i++){Row r=tables.row(21,i);int td=r.table(0),first=r.table(1),next=i+1<mapCount?tables.row(21,i+1).table(1):propCount+1;if(td>0&&td<byRid.size())for(int x=first;x<next&&x>0&&x<=propCount;x++)byRid.get(td).properties.add(props.get(x));}
        // Decode method bodies only after all token names exist.
        for(MethodDef m:methods)if(m!=null){m.il=decodeMethodIL(m);m.source=renderMethod(m);}
    }

    private static void ensureGeneric(List<String> list,int index,String name){while(list.size()<=index)list.add(null);if(list.get(index)==null)list.set(index,name);}
    private static String genericName(List<String> list,int index){return index>=0&&index<list.size()?list.get(index):null;}
    private static void replaceGenericConstraint(List<String> list,int index,String value){if(index>=0&&index<list.size())list.set(index,value);}

    private int findMethodForParam(int paramRid,List<MethodDef> methods){
        // Param rows are contiguous by owner; infer owner from MethodDef ParamList boundaries.
        int methodCount=tables.count(6);for(int rid=1;rid<=methodCount;rid++){Row mr=tables.row(6,rid-1);int first=mr.table(5);int next=rid<methodCount?tables.row(6,rid).table(5):tables.count(8)+1;if(paramRid>=first&&paramRid<next)return rid;}return 0;
    }

    String summary(){return "Managed .NET assembly\nAssembly: "+assemblyName+"\nTypes: "+types.size()+"\nMethods: "+tables.count(6)+"\nFields: "+tables.count(4)+"\nSelect a type, method or property to inspect reconstructed C# and IL.";}

    String metadataReport(){
        StringBuilder s=new StringBuilder();
        s.append("// Untrusted Manager managed-assembly report\n");
        s.append("// Assembly: ").append(assemblyName).append('\n');
        s.append("// Types: ").append(types.size()).append("  Methods: ").append(tables.count(6)).append("  Fields: ").append(tables.count(4)).append('\n');
        s.append("// Metadata offset: 0x").append(Integer.toHexString(metadataOffset)).append("\n\n");
        for(TypeDef t:types){if("<Module>".equals(t.name))continue;s.append("// ").append(t.fullName()).append("\n");}
        return s.toString();
    }


    String decompile(TypeDef t){StringBuilder s=new StringBuilder();appendType(s,t,0);return s.toString();}
    String decompileAll(){StringBuilder s=new StringBuilder();for(TypeDef t:types){if(t.name.equals("<Module>")||t.enclosing!=null)continue;s.append(decompile(t)).append('\n');}return s.toString();}

    private void appendType(StringBuilder s,TypeDef t,int depth){
        String ind=indent(depth);
        if(!t.namespace.isEmpty()&&depth==0)s.append("namespace ").append(t.namespace).append(" {\n\n");
        String kind=typeKind(t);
        s.append(ind).append(access(t.flags)).append(kind).append(' ').append(t.name);
        if(!t.genericParams.isEmpty()){s.append('<');for(int i=0;i<t.genericParams.size();i++){if(i>0)s.append(", ");s.append(cleanGeneric(t.genericParams.get(i)));}s.append('>');}
        boolean normalBase=!t.baseType.isEmpty()&&!"System.Object".equals(t.baseType)&&!"System.ValueType".equals(t.baseType)&&!"System.Enum".equals(t.baseType)&&!"System.MulticastDelegate".equals(t.baseType);
        if(normalBase)s.append(" : ").append(simple(t.baseType));
        for(String iface:t.interfaces){if(iface==null||iface.isEmpty())continue;if(!normalBase&&t.interfaces.indexOf(iface)==0)s.append(" : ");else s.append(", ");s.append(simple(iface));}
        s.append(" {\n");
        for(FieldDef f:t.fields)s.append(indent(depth+1)).append(fieldAccess(f.flags)).append((f.flags&0x10)!=0?"static ":"").append(simple(f.type)).append(' ').append(f.name).append(";\n");
        for(PropertyDef p:t.properties)s.append(indent(depth+1)).append("public ").append(simple(p.type)).append(' ').append(p.name).append(" { ").append(p.getter!=null?"get; ":"").append(p.setter!=null?"set; ":"").append("}\n");
        for(MethodDef m:t.methods){
            s.append(indent(depth+1)).append(methodAccess(m.flags)).append(m.signature());
            boolean noBody=(m.flags&0x0400)!=0||(m.flags&0x2000)!=0||m.rva==0;
            if(noBody){s.append(';').append("\n\n");continue;}
            s.append(" {\n");String src=m.source==null?"":m.source;if(src.isEmpty())src="// No method body";
            for(String line:src.split("\n",-1))s.append(indent(depth+2)).append(line).append('\n');
            s.append(indent(depth+1)).append("}\n\n");
        }
        for(TypeDef n:t.nested)appendType(s,n,depth+1);
        s.append(ind).append("}\n");if(!t.namespace.isEmpty()&&depth==0)s.append("}\n");
    }

    private static String typeKind(TypeDef t){if((t.flags&0x20)!=0)return "interface";if("System.Enum".equals(t.baseType))return "enum";if("System.MulticastDelegate".equals(t.baseType)||"System.Delegate".equals(t.baseType))return "delegate";if("System.ValueType".equals(t.baseType))return "struct";return "class";}
    private static String cleanGeneric(String s){if(s==null||s.isEmpty())return "T";int i=s.indexOf(" : ");return i>0?s.substring(0,i):s;}

    private String renderMethod(MethodDef m){
        if(m.rva==0)return "// abstract/external method\n";
        if(m.il==null||m.il.isEmpty())return "// No CIL body decoded\n";
        String[] lines=m.il.split("\\n");
        ArrayDeque<String> stack=new ArrayDeque<>();
        Map<String,String> locals=new HashMap<>();
        Set<String> declared=new HashSet<>();
        StringBuilder out=new StringBuilder();
        boolean degraded=false;
        int statements=0;
        for(String raw:lines){
            String z=raw.trim(); if(z.isEmpty()) continue;
            int colon=z.indexOf(':');
            String offset=colon>0?z.substring(3,colon):"";
            String ins=colon>0?z.substring(colon+1).trim():z;
            int sp=ins.indexOf(' ');
            String op=sp<0?ins:ins.substring(0,sp);
            String arg=sp<0?"":ins.substring(sp+1).trim();
            String value;
            switch(op){
                case "nop": case "break": case "volatile.": case "readonly.": case "tail.": case "constrained.":
                    break;
                case "ldnull": stack.push("null"); break;
                case "ldstr": stack.push(csharpStringOperand(arg)); break;
                case "ldc.i4.m1": stack.push("-1"); break;
                case "ldc.i4.0": case "ldc.i4.1": case "ldc.i4.2": case "ldc.i4.3": case "ldc.i4.4": case "ldc.i4.5": case "ldc.i4.6": case "ldc.i4.7": case "ldc.i4.8": stack.push(op.substring(op.length()-1)); break;
                case "ldc.i4": case "ldc.i4.s": case "ldc.i8": case "ldc.r4": case "ldc.r8": stack.push(arg); break;
                case "ldarg.0": stack.push(m.paramNames.length==0?"this":(m.flags&0x0010)!=0?paramName(m,0):"this"); break;
                case "ldarg.1": stack.push(paramName(m,1)); break;
                case "ldarg.2": stack.push(paramName(m,2)); break;
                case "ldarg.3": stack.push(paramName(m,3)); break;
                case "ldarg": case "ldarg.s": stack.push(paramName(m,parseVar(arg))); break;
                case "ldarga": case "ldarga.s": stack.push("ref "+paramName(m,parseVar(arg))); break;
                case "starg": case "starg.s": { int ai=parseVar(arg); value=pop(stack); out.append(paramName(m,ai)).append(" = ").append(value).append(';').append('\n'); statements++; } break;
                case "ldloc.0": case "ldloc.1": case "ldloc.2": case "ldloc.3": stack.push(locals.getOrDefault(op.substring(op.length()-1),"local"+op.substring(op.length()-1))); break;
                case "ldloc": case "ldloc.s": case "ldloca": case "ldloca.s": stack.push(locals.getOrDefault(arg,"local"+arg)); break;
                case "stloc.0": case "stloc.1": case "stloc.2": case "stloc.3": {String id=op.substring(op.length()-1); value=pop(stack); emitLocal(out,locals,declared,id,value); statements++;} break;
                case "stloc": case "stloc.s": case "stloca": case "stloca.s": {String id=arg; value=pop(stack); emitLocal(out,locals,declared,id,value); statements++;} break;
                case "dup": if(stack.isEmpty()){degraded=true;}else stack.push(stack.peek()); break;
                case "pop": if(stack.isEmpty()) degraded=true; else stack.pop(); break;
                case "add": case "add.ovf": case "add.ovf.un": degraded|=!binary(stack,"+"); break;
                case "sub": case "sub.ovf": case "sub.ovf.un": degraded|=!binary(stack,"-"); break;
                case "mul": case "mul.ovf": case "mul.ovf.un": degraded|=!binary(stack,"*"); break;
                case "div": case "div.un": degraded|=!binary(stack,"/"); break;
                case "rem": case "rem.un": degraded|=!binary(stack,"%"); break;
                case "and": degraded|=!binary(stack,"&"); break;
                case "or": degraded|=!binary(stack,"|"); break;
                case "xor": degraded|=!binary(stack,"^"); break;
                case "shl": degraded|=!binary(stack,"<<"); break;
                case "shr": case "shr.un": degraded|=!binary(stack,">>"); break;
                case "ceq": degraded|=!binary(stack,"=="); break;
                case "cgt": case "cgt.un": degraded|=!binary(stack,">"); break;
                case "clt": case "clt.un": degraded|=!binary(stack,"<"); break;
                case "neg": if(stack.isEmpty())degraded=true; else stack.push("(-"+stack.pop()+")"); break;
                case "not": if(stack.isEmpty())degraded=true; else stack.push("(~"+stack.pop()+")"); break;
                case "conv.i1":case "conv.i2":case "conv.i4":case "conv.i8":case "conv.u1":case "conv.u2":case "conv.u4":case "conv.u8":case "conv.i":case "conv.u":case "conv.r4":case "conv.r8":case "conv.r.un":
                    if(stack.isEmpty()) degraded=true; else { value=stack.pop(); stack.push("("+convType(op)+")"+value); } break;
                case "box": case "unbox.any": case "castclass": case "isinst":
                    if(stack.isEmpty()) degraded=true; else { value=stack.pop(); stack.push("("+tokenTypeName(arg)+")"+value); } break;
                case "ldsfld": { String f=resolveToken(parseToken(arg)); stack.push(f==null?arg:f); } break;
                case "ldfld": { String f=resolveToken(parseToken(arg)); value=pop(stack); stack.push(value+"."+(f==null?arg:shortMember(f))); } break;
                case "stsfld": { String f=resolveToken(parseToken(arg)); value=pop(stack); out.append(f==null?arg:shortMember(f)).append(" = ").append(value).append(';').append('\n'); statements++; } break;
                case "stfld": { String f=resolveToken(parseToken(arg)); value=pop(stack); String obj=pop(stack); out.append(obj).append('.').append(f==null?arg:shortMember(f)).append(" = ").append(value).append(';').append('\n'); statements++; } break;
                case "ldlen": if(stack.isEmpty())degraded=true; else stack.push(stack.pop()+".Length"); break;
                case "newarr": { value=pop(stack); stack.push("new "+tokenTypeName(arg)+"["+value+"]"); } break;
                case "ldelem": case "ldelem.i1":case "ldelem.u1":case "ldelem.i2":case "ldelem.u2":case "ldelem.i4":case "ldelem.u4":case "ldelem.i8":case "ldelem.i":case "ldelem.r4":case "ldelem.r8":case "ldelem.ref":
                    {String idx=pop(stack), arr=pop(stack);stack.push(arr+"["+idx+"]");} break;
                case "stelem": case "stelem.i":case "stelem.i1":case "stelem.i2":case "stelem.i4":case "stelem.i8":case "stelem.r4":case "stelem.r8":case "stelem.ref":
                    {String val=pop(stack),idx=pop(stack),arr=pop(stack);out.append(arr).append('[').append(idx).append("] = ").append(val).append(';').append('\n');statements++;} break;
                case "call": case "callvirt": case "newobj":
                    value=renderCall(parseToken(arg), op.equals("newobj"), stack, arg);
                    if(value==null) degraded=true; else stack.push(value); break;
                case "ret":
                    if("void".equals(m.ret)){out.append("return;");}else{value=pop(stack);out.append("return ").append(value).append(';');}out.append('\n');statements++;break;
                case "throw": value=pop(stack);out.append("throw ").append(value).append(';').append('\n');statements++;break;
                case "br": case "br.s": case "leave": case "leave.s": out.append("goto ").append(labelFor(arg)).append(';').append('\n'); statements++; break;
                case "brtrue": case "brtrue.s": value=pop(stack);out.append("if (").append(value).append(") goto ").append(labelFor(arg)).append(';').append('\n');statements++;break;
                case "brfalse": case "brfalse.s": value=pop(stack);out.append("if (!(").append(value).append(") goto ").append(labelFor(arg)).append(';').append('\n');statements++;break;
                case "beq":case "beq.s":case "bne.un":case "bne.un.s":case "bge":case "bge.s":case "bgt":case "bgt.s":case "ble":case "ble.s":case "blt":case "blt.s":case "bge.un":case "bge.un.s":case "bgt.un":case "bgt.un.s":case "ble.un":case "ble.un.s":case "blt.un":case "blt.un.s":
                    {String r=pop(stack),l=pop(stack);String cmp=branchCmp(op);out.append("if (").append(l).append(' ').append(cmp).append(' ').append(r).append(") goto ").append(labelFor(arg)).append(';').append('\n');statements++;}break;
                case "switch":
                    value=pop(stack); out.append("switch (").append(value).append(") {").append('\n');
                    if(arg.startsWith("[")) { String x=arg.substring(1,arg.length()-1); String[] ts=x.split(","); for(int i=0;i<ts.length;i++) out.append("    case ").append(i).append(": goto ").append(labelFor(ts[i].trim())).append(';').append('\n'); }
                    out.append("}").append('\n'); statements++; break;
                case "ldtoken": stack.push(arg); break;
                default:
                    degraded=true; out.append("// ").append(z).append('\n'); break;
            }
        }
        if(statements==0 && !degraded)return "// Method body contains no statements\n";
        if(degraded)out.insert(0,"// C# reconstruction uses conservative fallbacks where control flow or metadata could not be proven.\n");
        return out.toString().trim();
    }
    private static String csharpStringOperand(String arg){
        if(arg==null)return "\"\"";
        int a=arg.indexOf("/* \""), b=arg.lastIndexOf("\" */");
        if(a>=0&&b>a+5)return arg.substring(a+3,b+1);
        return arg;
    }
    private static String pop(ArrayDeque<String>s){return s.isEmpty()?"/* stack value unavailable */":s.pop();}
    private static String paramName(MethodDef m,int i){return (m.flags&0x0010)!=0?argName(m,i):i==0?"this":argName(m,i-1);}
    private static String argName(MethodDef m,int i){if(i>=0&&i<m.paramNames.length&&m.paramNames[i]!=null&&!m.paramNames[i].isEmpty())return m.paramNames[i];return "arg"+i;}
    private static int parseVar(String s){try{return Integer.parseInt(s.replaceAll("[^0-9-]",""));}catch(Exception e){return 0;}}
    private static void emitLocal(StringBuilder out,Map<String,String> locals,Set<String> declared,String id,String value){String n=locals.computeIfAbsent(id,k->"local"+k);if(declared.add(id))out.append("var ").append(n).append(" = ").append(value).append(';').append('\n');else out.append(n).append(" = ").append(value).append(';').append('\n');}
    private static String branchCmp(String op){if(op.startsWith("beq"))return"==";if(op.startsWith("bne"))return"!=";if(op.startsWith("bge"))return">=";if(op.startsWith("bgt"))return">";if(op.startsWith("ble"))return"<=";return"<";}
    private static String labelFor(String s){s=s.trim();if(s.startsWith("IL_"))return s;try{return "IL_"+String.format(Locale.ROOT,"%04X",Integer.parseInt(s));}catch(Exception e){return "IL_"+s;}}
    private static String shortMember(String s){int i=s.lastIndexOf('.');return i<0?s:s.substring(i+1);}
    private static String convType(String op){if(op.contains("r"))return"double";if(op.endsWith("i8")||op.endsWith("u8"))return"long";return"int";}
    private String tokenTypeName(String token){String r=resolveToken(parseToken(token));return r==null?token:simple(r);}
    private String renderCall(int tok,boolean ctor,ArrayDeque<String>stack,String raw){
        String sig=resolveMethodToken(tok); if(sig==null)return null;
        MethodRef ref=methodRef(tok); if(ref==null)return sig+"()";
        int n=ref.params.length; List<String>args=new ArrayList<>(); for(int i=0;i<n;i++)args.add(0,pop(stack));
        String target=shortMember(sig);
        if(ctor){return "new "+simple(ref.owner)+"("+String.join(", ",args)+")";}
        String instance=""; if(!ref.isStatic){instance=pop(stack)+".";}
        return instance+target+"("+String.join(", ",args)+")";
    }

    private static boolean binary(ArrayDeque<String>s,String op){if(s.size()<2)return false;String r=s.pop(),l=s.pop();s.push("("+l+" "+op+" "+r+")");return true;}

    private static final class MethodRef { final String owner,name; final String[] params; final boolean isStatic; MethodRef(String o,String n,String[]p,boolean st){owner=o;name=n;params=p;isStatic=st;} }
    private MethodRef methodRef(int tok){
        int table=(tok>>>24)&255,rid=tok&0xffffff; if(rid<=0)return null;
        try{
            if(table==6){ Row r=tables.row(6,rid-1); if(r==null)return null; MethodSig s=decodeMethodSig(r.blobIndex(4)); String owner="object"; for(TypeDef t:types)for(MethodDef m:t.methods)if(m.token==tok)owner=t.fullName(); return new MethodRef(owner,str(r.strIndex(3)),s.params,(r.u2(2)&0x10)!=0); }
            if(table==10){ Row r=tables.row(10,rid-1); if(r==null)return null; byte[] sig=blob(r.blobIndex(2)); if(sig.length==0)return null; MethodSig ms; try{ms=decodeMethodSig(r.blobIndex(2));}catch(Exception ex){return null;} int parent=r.coded(0,"MemberRefParent"); String owner=resolveToken(parent); if(owner==null)owner="object"; return new MethodRef(owner,str(r.strIndex(1)),ms.params,false); }
        }catch(Exception ignored){}
        return null;
    }

    private String decodeMethodIL(MethodDef m){
        if(m.rva==0)return ""; int off=rva(sections,m.rva); if(off<0||off>=image.length)return "";
        int p=off, first=image[p]&255, codeSize;
        if((first&3)==2){codeSize=first>>>2;p++;}
        else{int flags=u16(image,p);if((flags&3)!=3)return "";int words=(flags>>>12)&15;p+=words*4;codeSize=u32(image,off+4);}
        if(codeSize<0||p+codeSize>image.length)return "";int codeStart=p,end=p+codeSize;StringBuilder s=new StringBuilder();
        while(p<end){int at=p-codeStart;int op=image[p++]&255;String name=opcodeName(op);if(op==0xFE&&p<end){name=opcodeName(0xFE00|(image[p++]&255));}
            if(name.equals("switch")){if(p+4>end){s.append(String.format(Locale.ROOT,"IL_%04X: switch <truncated>\n",at));break;}int count=u32(image,p);p+=4;int base=p+count*4;StringBuilder a=new StringBuilder("[");for(int i=0;i<count;i++){int d=u32(image,p);p+=4;if(i>0)a.append(", ");a.append(String.format(Locale.ROOT,"IL_%04X",base+(int)d-codeStart));}a.append(']');s.append(String.format(Locale.ROOT,"IL_%04X: switch %s\n",at,a));continue;}
            int n=operandSize(name);String operand="";if(n>0){if(p+n>end){operand="<truncated>";p=end;}else{operand=operandValue(name,p,n);if(isBranch(name)){int delta=n==1?(byte)image[p]:(int)u32(image,p);int target=p+n+delta-codeStart;operand="IL_"+String.format(Locale.ROOT,"%04X",target);}p+=n;}}
            s.append(String.format(Locale.ROOT,"IL_%04X: %s",at,name));if(!operand.isEmpty())s.append(' ').append(operand);s.append('\n');}
        return s.toString().trim();
    }
    private static boolean isBranch(String n){return n.startsWith("br")||n.startsWith("beq")||n.startsWith("bge")||n.startsWith("bgt")||n.startsWith("ble")||n.startsWith("blt")||n.startsWith("bne")||n.startsWith("leave");}

    private String operandValue(String name,int p,int n){long v=0;for(int i=0;i<n;i++)v|=(long)(image[p+i]&255)<<(8*i);if(name.equals("ldstr")){int tok=(int)v;String us=resolveUserString(tok);if(us!=null)return String.format(Locale.ROOT,"0x%08X /* \"%s\" */",tok,escape(us));}if(name.equals("ldc.r4")&&n==4)return Float.toString(Float.intBitsToFloat((int)v));if(name.equals("ldc.r8")&&n==8)return Double.toString(Double.longBitsToDouble(v));if(name.contains("token")||name.equals("call")||name.equals("callvirt")||name.equals("newobj")||name.equals("ldfld")||name.equals("stfld")||name.equals("ldsfld")||name.equals("stsfld")){int tok=(int)v;String resolved=resolveToken(tok);if(resolved!=null)return String.format(Locale.ROOT,"0x%08X /* %s */",tok,resolved);}if(n==1)return Integer.toString((byte)v);if(n==2)return Integer.toString((short)v);if(n==4)return Integer.toString((int)v);return Long.toString(v);}
    private int operandSize(String n){
        if(n==null)return 0;
        if(n.equals("ldstr")||n.equals("call")||n.equals("callvirt")||n.equals("calli")||n.equals("newobj")||n.equals("ldfld")||n.equals("stfld")||n.equals("ldsfld")||n.equals("stsfld")||n.equals("ldtoken")||n.equals("box")||n.equals("unbox")||n.equals("unbox.any")||n.equals("castclass")||n.equals("isinst")||n.equals("newarr")||n.equals("ldobj")||n.equals("stobj")||n.equals("cpobj")||n.equals("initobj")||n.equals("sizeof")||n.equals("ldelem")||n.equals("stelem")||n.equals("ldelema")||n.equals("ldftn")||n.equals("ldvirtftn")||n.equals("constrained"))return 4;
        if(n.equals("ldarg.s")||n.equals("ldarga.s")||n.equals("starg.s")||n.equals("ldloc.s")||n.equals("ldloca.s")||n.equals("stloc.s")||n.equals("unaligned.")||n.equals("no.")||n.endsWith(".s")&&isBranch(n)||n.equals("ldc.i4.s"))return 1;
        if(n.equals("ldarg")||n.equals("ldarga")||n.equals("starg")||n.equals("ldloc")||n.equals("ldloca")||n.equals("stloc"))return 2;
        if(n.equals("ldc.i4")||n.equals("br")||n.startsWith("br")||n.equals("leave")||n.startsWith("beq")||n.startsWith("bge")||n.startsWith("bgt")||n.startsWith("ble")||n.startsWith("blt")||n.startsWith("bne")||n.equals("ldc.r4"))return 4;
        if(n.equals("ldc.i8")||n.equals("ldc.r8"))return 8;
        return 0;
    }
    private String resolveToken(int tok){String s=tokenNames.get(tok);if(s!=null)return s;int table=(tok>>>24)&255,rid=tok&0xffffff;try{if(rid<=0)return null;Row r=tables.row(table,rid-1);if(r==null)return null;if(table==1)return tokenNames.computeIfAbsent(tok,k->str(r.strIndex(1))+"."+str(r.strIndex(2)));if(table==2)return tokenNames.computeIfAbsent(tok,k->str(r.strIndex(1)));if(table==4)return tokenNames.computeIfAbsent(tok,k->str(r.strIndex(1)));if(table==6)return tokenNames.computeIfAbsent(tok,k->str(r.strIndex(3)));if(table==10)return tokenNames.computeIfAbsent(tok,k->str(r.strIndex(1)));if(table==27)return tokenNames.computeIfAbsent(tok,k->"TypeSpec");}catch(Exception ignored){}return null;}
    private String resolveTypeToken(int tok){
        if(tok==0)return "";
        String cached=tokenNames.get(tok); if(cached!=null)return cached;
        int table=(tok>>>24)&255, rid=tok&0xffffff; if(rid<=0)return "";
        try{Row r=tables.row(table,rid-1); if(r==null)return ""; String value;
            if(table==1) value=str(r.strIndex(2))+"."+str(r.strIndex(1));
            else if(table==2) value=str(r.strIndex(2))+"."+str(r.strIndex(1));
            else if(table==27) value="TypeSpec"; else value=resolveToken(tok);
            if(value==null)value=""; tokenNames.put(tok,value); return value;
        }catch(Exception ignored){return "";}
    }
    private String resolveUserString(int tok){
        if((tok>>>24)!=0x70 || usBase==0)return null;
        int ix=tok&0x00ffffff; if(ix<=0)return null; int p=usBase+ix; if(p<0||p>=image.length)return null;
        int[] q={p}; int len=readCompressed(image,q); if(q[0]+len>image.length||len<1)return null;
        int chars=(len-1)/2; return new String(image,q[0],chars*2,StandardCharsets.UTF_16LE);
    }
    private static String escape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
    private String resolveMethodToken(int tok){return resolveToken(tok);}
    private int parseToken(String s){try{int i=s.indexOf("0x");if(i>=0)return (int)Long.parseLong(s.substring(i+2).split(" ")[0],16);}catch(Exception ignored){}return 0;}

    private String decodeFieldSig(int blobIx){byte[] x=blob(blobIx);if(x.length<2)return "object";int[] p={0};int cc=x[p[0]++]&255;if(cc==0x06)return decodeType(x,p);return decodeType(x,new int[]{0});}
    private String decodePropertySig(int blobIx){byte[]x=blob(blobIx);if(x.length<2)return "object";int[]p={0};p[0]++;readCompressed(x,p);String ret=decodeType(x,p);return ret;}
    private MethodSig decodeMethodSig(int blobIx){byte[]x=blob(blobIx);if(x.length==0)return new MethodSig("void",new String[0]);int[]p={0};int cc=x[p[0]++]&255;if((cc&0x10)!=0)readCompressed(x,p);int count=readCompressed(x,p);String ret=decodeType(x,p);String[]params=new String[count];for(int i=0;i<count&&p[0]<x.length;i++)params[i]=decodeType(x,p);return new MethodSig(ret,params);}
    private String decodeType(byte[]x,int[]p){if(p[0]>=x.length)return "object";int e=x[p[0]++]&255;switch(e){case 0x01:return"void";case 0x02:return"bool";case 0x03:return"char";case 0x04:return"sbyte";case 0x05:return"byte";case 0x06:return"short";case 0x07:return"ushort";case 0x08:return"int";case 0x09:return"uint";case 0x0A:return"long";case 0x0B:return"ulong";case 0x0C:return"float";case 0x0D:return"double";case 0x0E:return"string";case 0x10:return decodeType(x,p)+"&";case 0x0F:return decodeType(x,p)+"*";case 0x11:case 0x12:{int coded=readCompressed(x,p);String t=resolveTypeToken(decodeSigToken(coded));return simple(t==null||t.isEmpty()?"object":t);}case 0x13:return"T"+readCompressed(x,p);case 0x1E:return"!!"+readCompressed(x,p);case 0x1D:return decodeType(x,p)+"[]";case 0x14:{String elem=decodeType(x,p);int rank=readCompressed(x,p);int ns=readCompressed(x,p);int sizes=readCompressed(x,p);for(int i=0;i<sizes;i++)readCompressed(x,p);int lbs=readCompressed(x,p);for(int i=0;i<lbs;i++)readCompressed(x,p);return elem+"["+",".repeat(Math.max(0,rank-1))+"]";}case 0x15:{readCompressed(x,p);int n=readCompressed(x,p);String base=decodeType(x,p);String[]args=new String[n];for(int i=0;i<n;i++)args[i]=decodeType(x,p);return simple(base)+"<"+String.join(", ",args)+">";}case 0x16:return"typedref";case 0x18:return"nint";case 0x19:return"nuint";case 0x1B:return"delegate*";case 0x1C:return"object";case 0x1F:case 0x20:readCompressed(x,p);return decodeType(x,p);case 0x45:return decodeType(x,p);default:return"object";}}
    private int decodeSigToken(int coded){int tag=coded&3,rid=coded>>>2;int table=switch(tag){case 0->2;case 1->1;default->27;};return table<<24|rid;}
    private int readCompressed(byte[]x,int[]p){if(p[0]>=x.length)return 0;int b=x[p[0]++]&255;if((b&0x80)==0)return b;if((b&0xC0)==0x80){if(p[0]>=x.length)return 0;return((b&0x3F)<<8)|(x[p[0]++]&255);}if(p[0]+2>=x.length)return 0;return((b&0x1F)<<24)|((x[p[0]++]&255)<<16)|((x[p[0]++]&255)<<8)|(x[p[0]++]&255);}
    private byte[] blob(int ix){if(blobBase==0||ix<=0)return new byte[0];int p=blobBase+ix;int[]q={p};int len=readCompressed(image,q);if(q[0]<0||q[0]+len>image.length)return new byte[0];return Arrays.copyOfRange(image,q[0],q[0]+len);}
    private String str(int ix){if(ix<=0)return"";int p=stringsBase+ix;if(p<0||p>=image.length)return"";int e=p;while(e<image.length&&image[e]!=0)e++;return new String(image,p,e-p,StandardCharsets.UTF_8);}

    private static String access(int flags){int a=flags&7;return switch(a){case 1->"public ";case 2,3->"private ";case 4->"protected ";case 5->"internal ";default->"";};}
    private static String fieldAccess(int flags){return switch(flags&7){case 6->"public ";case 1->"private ";case 4->"protected ";case 3->"internal ";case 5->"protected internal ";case 2->"private protected ";default->"private ";};}
    private static String methodAccess(int flags){String s=switch(flags&7){case 1,2->"private ";case 3->"internal ";case 4->"protected ";case 5->"protected internal ";case 6->"public ";default->"private ";};if((flags&0x10)!=0)s+="static ";if((flags&0x20)!=0)s+="final ";if((flags&0x40)!=0)s+="virtual ";if((flags&0x400)!=0)s+="abstract ";if((flags&0x2000)!=0)s+="extern ";return s;}
    private static String simple(String s){if(s==null||s.isEmpty())return"object";int i=Math.max(s.lastIndexOf('.'),s.lastIndexOf('/'));return i>=0?s.substring(i+1):s;}
    private static String indent(int n){return"    ".repeat(Math.max(0,n));}
    private static String opcodeName(int op){return OPCODES.getOrDefault(op,"op_"+Integer.toHexString(op));}
    private static final Map<Integer,String> OPCODES=new HashMap<>();static{String[][]a={{"00","nop"},{"01","break"},{"02","ldarg.0"},{"03","ldarg.1"},{"04","ldarg.2"},{"05","ldarg.3"},{"06","ldloc.0"},{"07","ldloc.1"},{"08","ldloc.2"},{"09","ldloc.3"},{"0A","stloc.0"},{"0B","stloc.1"},{"0C","stloc.2"},{"0D","stloc.3"},{"0E","ldarg.s"},{"0F","ldarga.s"},{"10","starg.s"},{"11","ldloc.s"},{"12","ldloca.s"},{"13","stloc.s"},{"14","ldnull"},{"15","ldc.i4.m1"},{"16","ldc.i4.0"},{"17","ldc.i4.1"},{"18","ldc.i4.2"},{"19","ldc.i4.3"},{"1A","ldc.i4.4"},{"1B","ldc.i4.5"},{"1C","ldc.i4.6"},{"1D","ldc.i4.7"},{"1E","ldc.i4.8"},{"1F","ldc.i4.s"},{"20","ldc.i4"},{"21","ldc.i8"},{"22","ldc.r4"},{"23","ldc.r8"},{"25","dup"},{"26","pop"},{"28","call"},{"29","calli"},{"2A","ret"},{"2B","br.s"},{"2C","brfalse.s"},{"2D","brtrue.s"},{"2E","beq.s"},{"2F","bge.s"},{"30","bgt.s"},{"31","ble.s"},{"32","blt.s"},{"33","bne.un.s"},{"34","bge.un.s"},{"35","bgt.un.s"},{"36","ble.un.s"},{"37","blt.un.s"},{"38","br"},{"39","brfalse"},{"3A","brtrue"},{"3B","beq"},{"3C","bge"},{"3D","bgt"},{"3E","ble"},{"3F","blt"},{"40","bne.un"},{"41","bge.un"},{"42","bgt.un"},{"43","ble.un"},{"44","blt.un"},{"45","switch"},{"46","ldind.i1"},{"47","ldind.u1"},{"48","ldind.i2"},{"49","ldind.u2"},{"4A","ldind.i4"},{"4B","ldind.u4"},{"4C","ldind.i8"},{"4D","ldind.i"},{"4E","ldind.r4"},{"4F","ldind.r8"},{"50","ldind.ref"},{"51","stind.ref"},{"52","stind.i1"},{"53","stind.i2"},{"54","stind.i4"},{"55","stind.i8"},{"56","stind.i"},{"57","stind.r4"},{"58","stind.r8"},{"59","add"},{"5A","sub"},{"5B","mul"},{"5C","div"},{"5D","div.un"},{"5E","rem"},{"5F","rem.un"},{"60","and"},{"61","or"},{"62","xor"},{"63","shl"},{"64","shr"},{"65","shr.un"},{"66","neg"},{"67","not"},{"68","conv.i1"},{"69","conv.i2"},{"6A","conv.i4"},{"6B","conv.i8"},{"6C","conv.r4"},{"6D","conv.r8"},{"6F","callvirt"},{"70","cpobj"},{"71","ldobj"},{"72","ldstr"},{"73","newobj"},{"74","castclass"},{"75","isinst"},{"76","conv.r.un"},{"79","unbox"},{"7A","throw"},{"7B","ldfld"},{"7C","ldflda"},{"7D","stfld"},{"7E","ldsfld"},{"7F","ldsflda"},{"80","stsfld"},{"81","stobj"},{"8C","box"},{"8D","newarr"},{"8E","ldlen"},{"8F","ldelema"},{"90","ldelem.i1"},{"91","ldelem.u1"},{"92","ldelem.i2"},{"93","ldelem.u2"},{"94","ldelem.i4"},{"95","ldelem.u4"},{"96","ldelem.i8"},{"97","ldelem.i"},{"98","ldelem.r4"},{"99","ldelem.r8"},{"9A","ldelem.ref"},{"9B","stelem.i"},{"9C","stelem.i1"},{"9D","stelem.i2"},{"9E","stelem.i4"},{"9F","stelem.i8"},{"A0","stelem.r4"},{"A1","stelem.r8"},{"A2","stelem.ref"},{"A3","ldelem"},{"A4","stelem"},{"A5","unbox.any"},{"B3","conv.u4"},{"B4","conv.u8"},{"B5","conv.i"},{"B6","conv.ovf.i"},{"B7","conv.ovf.u"},{"B8","add.ovf"},{"B9","add.ovf.un"},{"BA","mul.ovf"},{"BB","mul.ovf.un"},{"BC","sub.ovf"},{"BD","sub.ovf.un"},{"BE","endfinally"},{"C2","refanytype"},{"C3","readonly"},{"D0","ldtoken"},{"D1","conv.u2"},{"D2","conv.u1"},{"D3","conv.i1"},{"D4","conv.i2"},{"D5","conv.i"},{"D6","conv.ovf.i1"},{"D7","conv.ovf.u1"},{"D8","conv.ovf.i2"},{"D9","conv.ovf.u2"},{"DA","conv.ovf.i4"},{"DB","conv.ovf.u4"},{"DC","conv.ovf.i8"},{"DD","conv.ovf.u8"},{"DE","starg"},{"DF","ldarg"},{"E0","ceq"},{"E1","cgt"},{"E2","cgt.un"},{"E3","clt"},{"E4","clt.un"},{"FE00","arglist"},{"FE01","ceq"},{"FE02","cgt"},{"FE03","cgt.un"},{"FE04","clt"},{"FE05","clt.un"},{"FE09","ldarg"},{"FE0A","ldarga"},{"FE0B","starg"},{"FE0C","ldloc"},{"FE0D","ldloca"},{"FE0E","stloc"},{"FE12","unaligned."},{"FE13","volatile."},{"FE14","tail."},{"FE16","constrained"},{"FE19","no."},{"FE1A","readonly"}};for(String[]x:a)OPCODES.put(Integer.parseInt(x[0],16),x[1]);}

    private static String cleanMemberName(String n){ if(n==null)return ""; int i=n.indexOf('`'); return i>0?n.substring(0,i):n; }

    byte[] imageCopy() { return Arrays.copyOf(image, image.length); }

    /** File offset of the MethodDef.RVA cell for a method token. */
    int methodRvaFieldOffset(MethodDef m) throws IOException {
        if (m == null || (m.token >>> 24) != 0x06) throw new IOException("Invalid MethodDef token");
        int rid = m.token & 0x00FFFFFF;
        if (rid <= 0 || rid > tables.count(6)) throw new IOException("MethodDef token is outside the metadata table");
        Row row = tables.row(6, rid - 1);
        if (row == null) throw new IOException("MethodDef row is unavailable");
        return row.p;
    }

    int sectionAlignment() throws IOException { return peOptionalU32(0x38); }
    int fileAlignment() throws IOException { return peOptionalU32(0x24); }
    int numberOfSectionsFieldOffset() throws IOException { return peOffset() + 6; }
    int sizeOfImageFieldOffset() throws IOException { return peOffset() + 4 + 20 + 56; }
    int sectionTableOffset() throws IOException { return peOffset() + 4 + 20 + peOptionalU16(0x10); }
    int optionalHeaderMagic() throws IOException { return peOptionalU16(0); }
    int optionalHeaderSize() throws IOException { return peOptionalU16(0x14); }
    private int peOffset() throws IOException {
        int pe=u32(image,0x3c); if (pe < 0 || pe+24 > image.length || u32(image,pe)!=0x4550) throw new IOException("Invalid PE header"); return pe;
    }
    private int peOptionalU16(int rel) throws IOException { int pe=peOffset(); int opt=pe+4+20; return u16(image,opt+rel); }
    private int peOptionalU32(int rel) throws IOException { int pe=peOffset(); int opt=pe+4+20; return u32(image,opt+rel); }

    static final class MethodBodyInfo {
        final int fileOffset, headerSize, codeOffset, codeSize, maxStack;
        final int flags, localVarSigToken;
        final boolean tiny, hasExtraSections, initLocals;
        MethodBodyInfo(int fileOffset, int headerSize, int codeOffset, int codeSize, int maxStack, int flags, int localVarSigToken, boolean tiny, boolean hasExtraSections, boolean initLocals) {
            this.fileOffset=fileOffset; this.headerSize=headerSize; this.codeOffset=codeOffset; this.codeSize=codeSize;
            this.maxStack=maxStack; this.flags=flags; this.localVarSigToken=localVarSigToken; this.tiny=tiny; this.hasExtraSections=hasExtraSections; this.initLocals=initLocals;
        }
    }

    MethodBodyInfo methodBody(MethodDef m) throws IOException {
        if (m == null || m.rva == 0) throw new IOException("Method has no body");
        int off=rva(sections,m.rva);
        if(off<0||off>=image.length) throw new IOException("Method RVA is outside the PE image");
        int first=image[off]&255;
        if((first&3)==2){
            int codeSize=first>>>2;
            if(off+1+codeSize>image.length) throw new IOException("Tiny method body exceeds file");
            return new MethodBodyInfo(off,1,off+1,codeSize,8,first,0,true,false,false);
        }
        int flags=u16(image,off);
        if((flags&3)!=3) throw new IOException("Unsupported method-body header format");
        int words=(flags>>>12)&15;
        if(words<3||off+words*4>image.length) throw new IOException("Invalid fat method-body header");
        int codeSize=u32(image,off+4);
        int codeOff=off+words*4;
        if(codeSize<0||codeOff+codeSize>image.length) throw new IOException("Fat method body exceeds file");
        boolean more=(flags&0x8)!=0;
        int localSig=u32(image,off+8);
        return new MethodBodyInfo(off,words*4,codeOff,codeSize,u16(image,off+2),flags,localSig,false,more,(flags&0x10)!=0);
    }

    static int opcodeFor(String name) {
        if(name==null) return -1;
        for(Map.Entry<Integer,String> e:OPCODES.entrySet()) if(e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return -1;
    }

    private static final class MethodSig{final String ret;final String[]params;MethodSig(String r,String[]p){ret=r;params=p;}}
    private static final class Section{final int va,vs,raw,rs;Section(int a,int b,int c,int d){va=a;vs=b;raw=c;rs=d;}}
    private static int rva(Section[]s,int r){for(Section x:s)if(r>=x.va&&r<x.va+Math.max(x.vs,x.rs))return x.raw+(r-x.va);return -1;}
    private static byte[] read(File f)throws IOException{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}}
    private static int u16(byte[]b,int p){return p>=0&&p+2<=b.length?(b[p]&255)|((b[p+1]&255)<<8):0;}
    private static int u32(byte[]b,int p){return p>=0&&p+4<=b.length?(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24):0;}

    /** Generic ECMA-335 table reader. */
    private static final class Tables {
        final byte[] b;final int base;final int[] rows=new int[64];final int[] rowSizes=new int[64];final int[] offsets=new int[64];final int heapFlags;final Map<String,int[]> coded=new HashMap<>();
        private Tables(byte[]b,int base){this.b=b;this.base=base;heapFlags=b[base+6]&255;}
        static Tables read(byte[]b,int base,int streamEnd)throws IOException{
            if(base<0||base+24>b.length||streamEnd<base+24||streamEnd>b.length)throw new IOException("Invalid metadata tables stream bounds");
            Tables t=new Tables(b,base);
            int validLo=u32(b,base+8),validHi=u32(b,base+12);
            int p=base+24;
            for(int i=0;i<64;i++){
                if(i<32&&((validLo>>>i)&1)!=0){if(p+4>streamEnd)throw new IOException("Truncated metadata row-count header");t.rows[i]=u32(b,p);p+=4;}
                else if(i>=32&&((validHi>>>(i-32))&1)!=0){if(p+4>streamEnd)throw new IOException("Truncated metadata row-count header");t.rows[i]=u32(b,p);p+=4;}
            }
            long cursor=p;
            for(int id=0;id<64;id++){
                if(t.rows[id]==0)continue;
                t.offsets[id]=(int)cursor;
                t.rowSizes[id]=t.rowSize(id);
                if(t.rowSizes[id]<=0)throw new IOException("Unsupported metadata table schema "+id);
                long end=cursor+(long)t.rowSizes[id]*(long)(t.rows[id]&0xffffffffL);
                if(end<cursor||end>streamEnd)throw new IOException("Metadata table exceeds #~/#- stream (table="+id+", rows="+(t.rows[id]&0xffffffffL)+", rowSize="+t.rowSizes[id]+")");
                cursor=end;
            }
            return t;
        }
        int count(int id){return id>=0&&id<64?rows[id]:0;}
        Row row(int id,int index){if(id<0||id>=64||index<0||index>=rows[id])return null;return new Row(this,id,offsets[id]+index*rowSizes[id]);}
        int indexSize(int id){return rows[id]>65535?4:2;}int heapSize(int bit){return (heapFlags&bit)!=0?4:2;}
        int codedSize(String name){int[]d=CODED.get(name);int max=0;for(int i=0;i<d.length;i++)max=Math.max(max,rows[d[i]]);int bits=0;switch(name){case"TypeDefOrRef":case"HasConstant":case"HasFieldMarshal":case"HasDeclSecurity":case"HasSemantics":case"MethodDefOrRef":case"MemberForwarded":case"Implementation":case"CustomAttributeType":case"ResolutionScope":case"TypeOrMethodDef":case"MemberRefParent":break;default:break;}int tagBits=TAG_BITS.getOrDefault(name,2);return max<(1<<(16-tagBits))?2:4;}
        int rowSize(int id){Col[]c=SCHEMA.get(id);if(c==null)return -1;int n=0;for(Col x:c)n+=x.size(this);return n;}
        String str(int strings,int ix){if(ix<=0||strings<=0)return"";int p=strings+ix;if(p<0||p>=b.length)return"";int e=p;while(e<b.length&&b[e]!=0)e++;return new String(b,p,e-p,StandardCharsets.UTF_8);}
        static final class Col{final int k;final int v;Col(int k,int v){this.k=k;this.v=v;}int size(Tables t){return switch(k){case U2->2;case U4->4;case STR->t.heapSize(1);case GUID->t.heapSize(2);case BLOB->t.heapSize(4);case TAB->t.indexSize(v);case COD->t.codedSize(CODED_NAME[v]);default->0;};}}
        static final int U2=1,U4=2,STR=3,GUID=4,BLOB=5,TAB=6,COD=7;static final String[] CODED_NAME={"","TypeDefOrRef","HasConstant","HasCustomAttribute","HasFieldMarshal","HasDeclSecurity","MemberRefParent","HasSemantics","MethodDefOrRef","MemberForwarded","Implementation","CustomAttributeType","ResolutionScope","TypeOrMethodDef"};
        static final Map<String,Integer> TAG_BITS=new HashMap<>(); static { TAG_BITS.put("TypeDefOrRef",2); TAG_BITS.put("HasConstant",2); TAG_BITS.put("HasCustomAttribute",5); TAG_BITS.put("HasFieldMarshal",1); TAG_BITS.put("HasDeclSecurity",2); TAG_BITS.put("MemberRefParent",3); TAG_BITS.put("HasSemantics",1); TAG_BITS.put("MethodDefOrRef",1); TAG_BITS.put("MemberForwarded",1); TAG_BITS.put("Implementation",2); TAG_BITS.put("CustomAttributeType",3); TAG_BITS.put("ResolutionScope",2); TAG_BITS.put("TypeOrMethodDef",1); }
        static final Map<String,int[]>CODED=new HashMap<>();static{CODED.put("TypeDefOrRef",new int[]{2,1,27});CODED.put("HasConstant",new int[]{4,8,23});CODED.put("HasCustomAttribute",new int[]{6,10,9,0,4,8,23,20,17,26,27,32,35,1,2,38,39,40,42,43,44});CODED.put("HasFieldMarshal",new int[]{4,8});CODED.put("HasDeclSecurity",new int[]{2,6,32});CODED.put("MemberRefParent",new int[]{2,1,26,6,27});CODED.put("HasSemantics",new int[]{20,23});CODED.put("MethodDefOrRef",new int[]{6,10});CODED.put("MemberForwarded",new int[]{4,6});CODED.put("Implementation",new int[]{38,35,39});CODED.put("CustomAttributeType",new int[]{6,10});CODED.put("ResolutionScope",new int[]{0,26,35,1});CODED.put("TypeOrMethodDef",new int[]{2,6});}
        static Col S(){return new Col(STR,0);}static Col B(){return new Col(BLOB,0);}static Col U2(){return new Col(Tables.U2,0);}static Col U4(){return new Col(Tables.U4,0);}static Col T(int id){return new Col(TAB,id);}static Col C(String n){return new Col(COD,Arrays.asList(CODED_NAME).indexOf(n));}
        static final Map<Integer,Col[]>SCHEMA=new HashMap<>();static{SCHEMA.put(0,new Col[]{U2(),S(),G(),G(),G()});SCHEMA.put(1,new Col[]{C("ResolutionScope"),S(),S()});SCHEMA.put(2,new Col[]{U4(),S(),S(),C("TypeDefOrRef"),T(4),T(6)});SCHEMA.put(3,new Col[]{T(2),T(1)});SCHEMA.put(4,new Col[]{U2(),S(),B()});SCHEMA.put(5,new Col[]{T(6)});SCHEMA.put(6,new Col[]{U4(),U2(),U2(),S(),B(),T(8)});SCHEMA.put(7,new Col[]{T(8)});SCHEMA.put(8,new Col[]{U2(),U2(),S()});SCHEMA.put(9,new Col[]{T(2),C("TypeDefOrRef")});SCHEMA.put(10,new Col[]{C("MemberRefParent"),S(),B()});SCHEMA.put(11,new Col[]{U2(),C("HasConstant"),B()});SCHEMA.put(12,new Col[]{C("HasCustomAttribute"),C("CustomAttributeType"),B()});SCHEMA.put(13,new Col[]{C("HasFieldMarshal"),B()});SCHEMA.put(14,new Col[]{U2(),C("HasDeclSecurity"),B()});SCHEMA.put(15,new Col[]{U2(),U4(),T(2)});SCHEMA.put(16,new Col[]{U4(),T(4)});SCHEMA.put(17,new Col[]{B()});SCHEMA.put(18,new Col[]{T(2),T(20)});SCHEMA.put(19,new Col[]{T(20)});SCHEMA.put(20,new Col[]{U2(),S(),C("TypeDefOrRef")});SCHEMA.put(21,new Col[]{T(2),T(23)});SCHEMA.put(22,new Col[]{T(23)});SCHEMA.put(23,new Col[]{U2(),S(),B()});SCHEMA.put(24,new Col[]{U2(),T(6),C("HasSemantics")});SCHEMA.put(25,new Col[]{T(2),C("MethodDefOrRef"),C("MethodDefOrRef")});SCHEMA.put(26,new Col[]{S()});SCHEMA.put(27,new Col[]{B()});SCHEMA.put(28,new Col[]{U2(),C("MemberForwarded"),S(),T(26)});SCHEMA.put(29,new Col[]{U4(),T(4)});SCHEMA.put(30,new Col[]{U4(),U4(),T(32)});SCHEMA.put(31,new Col[]{U4()});SCHEMA.put(32,new Col[]{U4(),U2(),U2(),U2(),U2(),U4(),S(),S(),B()});SCHEMA.put(33,new Col[]{U4()});SCHEMA.put(34,new Col[]{U4(),U4(),U4()});SCHEMA.put(35,new Col[]{U2(),U2(),U2(),U2(),U4(),S(),S(),B()});SCHEMA.put(36,new Col[]{U4(),T(6),T(35)});SCHEMA.put(37,new Col[]{U4(),U4(),U4(),T(35)});SCHEMA.put(38,new Col[]{U4(),S(),B()});SCHEMA.put(39,new Col[]{U4(),U4(),U2(),C("Implementation"),S(),S()});SCHEMA.put(40,new Col[]{U4(),U4(),S(),S(),U2()});SCHEMA.put(41,new Col[]{T(2),T(2)});SCHEMA.put(42,new Col[]{U2(),U2(),C("TypeOrMethodDef"),S()});SCHEMA.put(43,new Col[]{C("MethodDefOrRef"),B()});SCHEMA.put(44,new Col[]{C("TypeDefOrRef"),B()});}
        static Col G(){return new Col(GUID,0);}
    }
    private static final class Row{final Tables t;final int id,p;Row(Tables t,int id,int p){this.t=t;this.id=id;this.p=p;}int u2(int c){return u16(t.b,p+offset(c));}int u4(int c){return u32(t.b,p+offset(c));}int strIndex(int c){return idx(c,Tables.STR);}int blobIndex(int c){return idx(c,Tables.BLOB);}int table(int c){return idx(c,Tables.TAB);}int coded(int c,String name){return coded(c,name,Tables.COD);}private int idx(int c,int kind){int off=offset(c);int size=sizes()[c];return size==4?u32(t.b,p+off):u16(t.b,p+off);}private int coded(int c,String name,int kind){int raw=idx(c,kind);int bits=Tables.TAG_BITS.getOrDefault(name,2);int tag=raw&((1<<bits)-1);int rid=raw>>>bits;int[]d=Tables.CODED.get(name);return d!=null&&tag<d.length?(d[tag]<<24)|rid:0;}private int offset(int c){int o=0;Tables.Col[]cs=Tables.SCHEMA.get(id);for(int i=0;i<c;i++)o+=cs[i].size(t);return o;}private int[]sizes(){Tables.Col[]cs=Tables.SCHEMA.get(id);int[]s=new int[cs.length];for(int i=0;i<s.length;i++)s[i]=cs[i].size(t);return s;}}
}
