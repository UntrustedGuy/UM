package untrusted.manager.um.tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Lightweight PE/COFF inspector used as the non-managed fallback of DLL Editor. */
final class PeFileInspector {
    private PeFileInspector() {}

    static String inspect(File file) throws IOException {
        byte[] b=read(file, 4 * 1024 * 1024);
        if (b.length < 64 || u16(b,0)!=0x5A4D) return "Not a PE/COFF file.\n\nUse Hex to inspect the raw file.";
        int pe=u32(b,0x3c);
        if(pe<0 || pe+24>b.length || u32(b,pe)!=0x4550) return "Invalid PE signature.\n\nUse Hex to inspect the raw file.";
        int coff=pe+4, machine=u16(b,coff), count=u16(b,coff+2), opt=coff+20, magic=u16(b,opt);
        boolean plus=magic==0x20b;
        int dataDir=opt+(plus?112:96), cliDir=dataDir+14*8;
        long clr=cliDir+8<=b.length?Integer.toUnsignedLong(u32(b,cliDir)):0;
        String arch=switch(machine){case 0x8664->"x64";case 0x014c->"x86";case 0xAA64->"ARM64";case 0x01c4->"ARM";default->String.format(Locale.ROOT,"machine 0x%04X",machine);};
        StringBuilder s=new StringBuilder();
        s.append("// Untrusted Manager PE inspection\n");
        s.append("// File: ").append(file.getName()).append('\n');
        s.append("// Size: ").append(file.length()).append(" bytes\n");
        s.append("// Architecture: ").append(arch).append('\n');
        s.append("// Optional header: ").append(plus?"PE32+":"PE32").append('\n');
        s.append("// Sections: ").append(count).append('\n');
        s.append("// CLR directory RVA: 0x").append(Long.toHexString(clr)).append('\n');
        if(clr==0){
            s.append("\n// This file is a native/unmanaged PE. It does not contain a CLI/.NET header, so\n");
            s.append("// there is no CIL or managed C# metadata to reconstruct. Use Hex for raw bytes.\n");
        } else {
            s.append("\n// A CLR directory is present, but the managed metadata reader could not parse it.\n");
            s.append("// The file may be protected, malformed, or use metadata features not supported by this reader.\n");
        }
        s.append("\n// Section table:\n");
        int sh=opt+u16(b,coff+16);
        for(int i=0;i<count;i++){
            int o=sh+i*40; if(o+40>b.length) break;
            String name=readName(b,o,8);
            long va=Integer.toUnsignedLong(u32(b,o+12));
            long vs=Integer.toUnsignedLong(u32(b,o+8));
            long raw=Integer.toUnsignedLong(u32(b,o+20));
            long rs=Integer.toUnsignedLong(u32(b,o+16));
            s.append(String.format(Locale.ROOT,"// %-8s RVA 0x%08X  VS 0x%08X  RAW 0x%08X  SIZE 0x%08X%n",name,va,vs,raw,rs));
        }
        return s.toString();
    }

    private static byte[] read(File f,int max) throws IOException {
        long len=f.length(); int cap=(int)Math.min(Math.max(64,len),max);
        byte[] out=new byte[cap]; int n=0;
        try(FileInputStream in=new FileInputStream(f)){
            while(n<out.length){int r=in.read(out,n,out.length-n);if(r<0)break;n+=r;}
        }
        if(n==out.length && len>max) return out;
        if(n==out.length) return out;
        byte[] trimmed=new byte[n];System.arraycopy(out,0,trimmed,0,n);return trimmed;
    }
    private static String readName(byte[] b,int p,int n){int e=p;while(e<p+n&&e<b.length&&b[e]!=0)e++;return new String(b,p,Math.max(0,e-p),StandardCharsets.US_ASCII);}
    private static int u16(byte[] b,int p){return p>=0&&p+2<=b.length?(b[p]&255)|((b[p+1]&255)<<8):0;}
    private static int u32(byte[] b,int p){return p>=0&&p+4<=b.length?(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24):0;}
}
