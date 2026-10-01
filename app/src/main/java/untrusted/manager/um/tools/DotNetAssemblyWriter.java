package untrusted.manager.um.tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ECMA-335 method-body writer for UM.
 *
 * Unlike the original in-place patcher, this implementation can relocate an edited
 * method into a new PE section when the new body no longer fits at the original RVA.
 * The MethodDef.RVA metadata cell, section table, SizeOfImage and PE image are updated
 * together. The original file is never modified.
 *
 * Exception-handler method sections are intentionally rejected until their clause
 * offsets can be rewritten from explicit EH syntax. This is safer than emitting an
 * assembly with stale handler offsets.
 */
final class DotNetAssemblyWriter {
    static final class Result {
        final File output;
        final int oldCodeSize;
        final int newCodeSize;
        final boolean relocated;
        Result(File output, int oldCodeSize, int newCodeSize, boolean relocated) {
            this.output=output; this.oldCodeSize=oldCodeSize; this.newCodeSize=newCodeSize; this.relocated=relocated;
        }
    }

    static Result saveMethod(DotNetAssemblyParser parser,
                             DotNetAssemblyParser.MethodDef method,
                             String ilText,
                             File output) throws IOException {
        if (parser == null || method == null) throw new IOException("No managed method is selected");
        DotNetAssemblyParser.MethodBodyInfo body=parser.methodBody(method);
        if (body.hasExtraSections) {
            throw new IOException("This method has exception-handling/extra sections. The editor will not save it until EH clauses can be safely rewritten.");
        }
        if (ilText == null || ilText.trim().isEmpty()) throw new IOException("IL source is empty");

        byte[] code=assemble(ilText);
        if(code.length==0) throw new IOException("Edited IL produced an empty method body");
        byte[] methodBody=buildFatMethodBody(body,code);
        byte[] image=parser.imageCopy();

        boolean inPlace = methodBody.length <= body.headerSize + body.codeSize && body.headerSize >= 12;
        // Even if the original method was tiny, a fat body is used when writing. Tiny
        // methods have only one-byte headers and cannot represent larger bodies safely.
        if (inPlace && methodBody.length <= body.headerSize + body.codeSize && body.headerSize >= 12) {
            int oldTotal=body.headerSize+body.codeSize;
            System.arraycopy(methodBody,0,image,body.fileOffset,methodBody.length);
            if(methodBody.length<oldTotal) Arrays.fill(image,body.fileOffset+methodBody.length,body.fileOffset+oldTotal,(byte)0);
            writeOutput(output,image);
            return new Result(output,body.codeSize,code.length,false);
        }

        Relocation relocation=appendMethodSection(parser,image,methodBody);
        put32(relocation.image,parser.methodRvaFieldOffset(method),relocation.rva);
        writeOutput(output,relocation.image);
        return new Result(output,body.codeSize,code.length,true);
    }

    private static byte[] buildFatMethodBody(DotNetAssemblyParser.MethodBodyInfo old, byte[] code) {
        int alignedCode=(code.length+3)&~3;
        byte[] out=new byte[12+alignedCode];
        int flags=0x0003 | (3<<12);
        if(old.initLocals) flags|=0x0010;
        put16(out,0,flags);
        put16(out,2,Math.max(8,old.maxStack));
        put32(out,4,code.length);
        put32(out,8,old.localVarSigToken);
        System.arraycopy(code,0,out,12,code.length);
        return out;
    }

    private static final class Relocation { final int rva; final byte[] image; Relocation(int rva, byte[] image){this.rva=rva;this.image=image;} }

    private static Relocation appendMethodSection(DotNetAssemblyParser parser, byte[] image, byte[] body) throws IOException {
        int pe=u32(image,0x3c);
        int coff=pe+4;
        int sections=u16(image,coff+2);
        int opt=coff+20;
        int magic=u16(image,opt);
        if(magic!=0x10b && magic!=0x20b) throw new IOException("Unsupported PE optional-header format");
        int optSize=u16(image,coff+16);
        int sectionTable=opt+optSize;
        int firstRaw=Integer.MAX_VALUE;
        int lastEndRva=0;
        for(int i=0;i<sections;i++){
            int sh=sectionTable+i*40;
            if(sh+40>image.length) throw new IOException("Truncated PE section table");
            int va=u32(image,sh+12), vs=u32(image,sh+8), raw=u32(image,sh+20), rs=u32(image,sh+16);
            if(raw>0) firstRaw=Math.min(firstRaw,raw);
            lastEndRva=Math.max(lastEndRva,va+Math.max(vs,rs));
        }
        if(sectionTable+sections*40+40>firstRaw) throw new IOException("PE section table has no room for a new managed-code section");
        int fileAlign=positive(parser.fileAlignment(),0x200);
        int secAlign=positive(parser.sectionAlignment(),0x1000);
        if((fileAlign&(fileAlign-1))!=0 || (secAlign&(secAlign-1))!=0) throw new IOException("Unsupported non-power-of-two PE alignment");
        int newVa=align(lastEndRva,secAlign);
        int newRaw=align(image.length,fileAlign);
        int rawSize=align(body.length,fileAlign);
        byte[] expanded=new byte[newRaw+rawSize];
        System.arraycopy(image,0,expanded,0,image.length);
        System.arraycopy(body,0,expanded,newRaw,body.length);

        int sh=sectionTable+sections*40;
        byte[] name=new byte[]{'.','u','m','c','i','l',0,0};
        System.arraycopy(name,0,expanded,sh,8);
        put32(expanded,sh+8,body.length);
        put32(expanded,sh+12,newVa);
        put32(expanded,sh+16,rawSize);
        put32(expanded,sh+20,newRaw);
        put32(expanded,sh+24,0);
        put32(expanded,sh+28,0);
        put16(expanded,sh+32,0);
        put16(expanded,sh+34,0);
        put32(expanded,sh+36,0x60000020);

        put16(expanded,coff+2,sections+1);
        put32(expanded,opt+56,align(newVa+body.length,secAlign));
        return new Relocation(newVa,expanded);
    }

    private static void writeOutput(File output,byte[] image)throws IOException{
        File parent=output.getParentFile();
        if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs()&&!parent.isDirectory())throw new IOException("Cannot create output directory");
        try(FileOutputStream out=new FileOutputStream(output)){out.write(image);}
    }

    private static int positive(int v,int fallback){return v>0?v:fallback;}
    private static int align(int v,int a){return (v+a-1)&~(a-1);}
    private static void put16(byte[]b,int p,int v){b[p]=(byte)v;b[p+1]=(byte)(v>>>8);}
    private static void put32(byte[]b,int p,int v){b[p]=(byte)v;b[p+1]=(byte)(v>>>8);b[p+2]=(byte)(v>>>16);b[p+3]=(byte)(v>>>24);}
    private static int u16(byte[]b,int p){return(b[p]&255)|((b[p+1]&255)<<8);}
    private static int u32(byte[]b,int p){return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}

    private static byte[] assemble(String source) throws IOException {
        List<Insn> insns=new ArrayList<>(); Map<String,Integer> labels=new HashMap<>();
        String[] lines=source.replace("\r","").split("\n"); int offset=0;
        for(String line:lines){
            String z=line.trim(); if(z.isEmpty()||z.startsWith("//"))continue;
            int colon=z.indexOf(':');
            if(colon>=0&&colon<12&&z.substring(0,colon).trim().toUpperCase(Locale.ROOT).matches("IL_[0-9A-F]+")){
                String label=z.substring(0,colon).trim().toUpperCase(Locale.ROOT); if(labels.put(label,offset)!=null)throw new IOException("Duplicate label: "+label);
                z=z.substring(colon+1).trim(); if(z.isEmpty())continue;
            }
            int sp=z.indexOf(' '); String op=(sp<0?z:z.substring(0,sp)).trim(); String arg=sp<0?"":z.substring(sp+1).trim();
            int opcode=DotNetAssemblyParser.opcodeFor(op); if(opcode<0)throw new IOException("Unsupported IL opcode: "+op);
            Insn ins=new Insn(op,opcode,arg,offset); ins.size=encodedSize(ins); offset+=ins.size; insns.add(ins);
        }
        byte[]out=new byte[offset]; for(Insn i:insns)encode(i,labels,out); return out;
    }
    private static final class Insn{final String op;final int opcode,argOffset;final String arg;int size;Insn(String o,int c,String a,int off){op=o;opcode=c;arg=a;argOffset=off;}}
    private static int encodedSize(Insn i)throws IOException{
        int base=i.opcode>0xFF?2:1;String op=i.op.toLowerCase(Locale.ROOT);
        if(op.equals("switch"))return base+4+4*parseSwitch(i.arg).size();
        if(isBranch(op))return base+(op.endsWith(".s")?1:4);
        if(isToken(op))return base+4;
        if(op.equals("ldarg")||op.equals("ldarga")||op.equals("starg")||op.equals("ldloc")||op.equals("ldloca")||op.equals("stloc"))return base+2;
        if(op.endsWith(".s")&&(op.startsWith("ldarg")||op.startsWith("ldloc")||op.startsWith("starg")||op.startsWith("ldloca")))return base+1;
        if(op.equals("unaligned.")||op.equals("no."))return base+1;
        if(op.equals("ldc.i4.s"))return base+1;if(op.equals("ldc.i4"))return base+4;if(op.equals("ldc.i8")||op.equals("ldc.r8"))return base+8;if(op.equals("ldc.r4"))return base+4;
        return base;
    }
    private static void encode(Insn i,Map<String,Integer>labels,byte[]out)throws IOException{
        int p=i.argOffset;if(i.opcode>0xFF){out[p++]=(byte)0xFE;out[p++]=(byte)(i.opcode&255);}else out[p++]=(byte)i.opcode;
        String op=i.op.toLowerCase(Locale.ROOT);
        if(op.equals("switch")){List<String>xs=parseSwitch(i.arg);put32(out,p,xs.size());p+=4;int base=i.argOffset+i.size;for(String l:xs){Integer t=labels.get(l.toUpperCase(Locale.ROOT));if(t==null)throw new IOException("Unknown switch target: "+l);put32(out,p,t-base);p+=4;}return;}
        if(isBranch(op)){Integer t=labels.get(normalizeLabel(i.arg));if(t==null)throw new IOException("Unknown branch target: "+i.arg);int w=op.endsWith(".s")?1:4;int d=t-(i.argOffset+i.size);if(w==1&&(d<-128||d>127))throw new IOException("Short branch target out of range: "+i.arg);if(w==1)out[p]=(byte)d;else put32(out,p,d);return;}
        if(isToken(op)){put32(out,p,parseInt(i.arg));return;}
        if(op.equals("ldarg")||op.equals("ldarga")||op.equals("starg")||op.equals("ldloc")||op.equals("ldloca")||op.equals("stloc")){put16(out,p,parseInt(i.arg));return;}
        if(op.equals("unaligned.")||op.equals("no.")){int v=parseInt(i.arg);if(v<0||v>255)throw new IOException("Prefix operand out of range: "+i.arg);out[p]=(byte)v;return;}
        if(op.endsWith(".s")&&(op.startsWith("ldarg")||op.startsWith("ldloc")||op.startsWith("starg")||op.startsWith("ldloca"))){int v=parseInt(i.arg);if(v<0||v>255)throw new IOException("Short variable index out of range: "+i.arg);out[p]=(byte)v;return;}
        if(op.equals("ldc.i4.s")){int v=parseInt(i.arg);if(v<-128||v>127)throw new IOException("ldc.i4.s out of range");out[p]=(byte)v;return;}
        if(op.equals("ldc.i4")){put32(out,p,parseInt(i.arg));return;}if(op.equals("ldc.i8")){put64(out,p,parseLong(i.arg));return;}if(op.equals("ldc.r4")){put32(out,p,Float.floatToIntBits(Float.parseFloat(stripComment(i.arg))));return;}if(op.equals("ldc.r8"))put64(out,p,Double.doubleToLongBits(Double.parseDouble(stripComment(i.arg))));
    }
    private static boolean isToken(String op){return op.equals("ldstr")||op.equals("call")||op.equals("callvirt")||op.equals("calli")||op.equals("newobj")||op.equals("ldfld")||op.equals("ldflda")||op.equals("stfld")||op.equals("ldsfld")||op.equals("ldsflda")||op.equals("stsfld")||op.equals("ldtoken")||op.equals("box")||op.equals("unbox")||op.equals("unbox.any")||op.equals("castclass")||op.equals("isinst")||op.equals("newarr")||op.equals("ldobj")||op.equals("stobj")||op.equals("cpobj")||op.equals("initobj")||op.equals("sizeof")||op.equals("ldelem")||op.equals("stelem")||op.equals("ldelema")||op.equals("ldftn")||op.equals("ldvirtftn")||op.equals("constrained");}
    private static boolean isBranch(String op){return op.startsWith("br")||op.startsWith("beq")||op.startsWith("bge")||op.startsWith("bgt")||op.startsWith("ble")||op.startsWith("blt")||op.startsWith("bne")||op.startsWith("leave");}
    private static String normalizeLabel(String s){return s.trim().split("\\s+",2)[0].toUpperCase(Locale.ROOT);}
    private static int parseInt(String s)throws IOException{String z=stripComment(s).trim();try{return z.startsWith("0x")||z.startsWith("0X")?(int)Long.parseLong(z.substring(2),16):Integer.parseInt(z);}catch(Exception e){throw new IOException("Invalid integer operand: "+s);}}
    private static long parseLong(String s)throws IOException{String z=stripComment(s).trim();try{return z.startsWith("0x")||z.startsWith("0X")?Long.parseUnsignedLong(z.substring(2),16):Long.parseLong(z);}catch(Exception e){throw new IOException("Invalid integer operand: "+s);}}
    private static String stripComment(String s){int i=s.indexOf("/*");return i>=0?s.substring(0,i).trim():s.trim();}
    private static List<String> parseSwitch(String s)throws IOException{String z=s.trim();if(!z.startsWith("[")||!z.endsWith("]"))throw new IOException("switch requires [IL_xxxx, ...]");z=z.substring(1,z.length()-1).trim();List<String>r=new ArrayList<>();if(z.isEmpty())return r;for(String x:z.split(",")){String q=x.trim();if(!q.matches("(?i)IL_[0-9A-F]+"))throw new IOException("Invalid switch target: "+q);r.add(q);}return r;}
    private static void put64(byte[]b,int p,long v){for(int i=0;i<8;i++)b[p+i]=(byte)(v>>>(8*i));}
}
