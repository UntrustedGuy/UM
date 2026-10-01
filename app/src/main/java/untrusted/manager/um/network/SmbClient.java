package untrusted.manager.um.network;

import org.codelibs.jcifs.smb.CIFSContext;
import org.codelibs.jcifs.smb.config.PropertyConfiguration;
import org.codelibs.jcifs.smb.context.BaseContext;
import org.codelibs.jcifs.smb.impl.NtlmPasswordAuthenticator;
import org.codelibs.jcifs.smb.impl.SmbFile;

import java.io.*;
import java.util.*;

/** SMB2/SMB3 network-storage client. SMB1 is deliberately disabled. */
public final class SmbClient implements Closeable {
    public static final class Entry {
        public final String url, name; public final boolean directory, hidden; public final long size, modified;
        Entry(String u,String n,boolean d,boolean h,long s,long m){url=u;name=n;directory=d;hidden=h;size=s;modified=m;}
    }
    private final CIFSContext context; private final SmbFile root;
    public SmbClient(String url,String domain,String user,String password) throws Exception {
        String base=normalizeRoot(url);
        Properties p=new Properties();
        p.setProperty("jcifs.client.minVersion","SMB202");
        p.setProperty("jcifs.client.maxVersion","SMB311");
        p.setProperty("jcifs.client.signingEnforced","true");
        p.setProperty("jcifs.resolveOrder","DNS,BCAST");
        BaseContext baseContext=new BaseContext(new PropertyConfiguration(p));
        NtlmPasswordAuthenticator auth;
        if(user==null||user.trim().isEmpty()) auth=new NtlmPasswordAuthenticator(null,"","".equals(password)?null:password);
        else auth=new NtlmPasswordAuthenticator(domain==null?"":domain.trim(),user.trim(),password==null?"":password);
        context=baseContext.withCredentials(auth);
        root=new SmbFile(base,context);
    }
    private static String normalizeRoot(String u){
        if(u==null)throw new IllegalArgumentException("SMB URL required"); String s=u.trim().replace('\\','/');
        if(!s.regionMatches(true,0,"smb://",0,6))s="smb://"+s;
        while(s.indexOf("//", 6) >= 0)s=s.replace("//","/");
        if(!s.endsWith("/"))s+="/";
        if(s.indexOf("@")>=0)throw new IllegalArgumentException("Put credentials in the profile, not the SMB URL");
        return s;
    }
    public List<Entry> list(String path) throws Exception {
        SmbFile dir=resource(path); try {
            if(!dir.exists()||!dir.isDirectory())throw new IOException("Not an SMB directory");
            SmbFile[] files=dir.listFiles(); List<Entry> out=new ArrayList<>(); if(files==null)return out;
            for(SmbFile f:files){String n=f.getName(); if(n.endsWith("/"))n=n.substring(0,n.length()-1);out.add(new Entry(f.getPath(),n,f.isDirectory(),f.isHidden(),safeLength(f),safeModified(f)));}
            out.sort((a,b)->{if(a.directory!=b.directory)return a.directory?-1:1;return a.name.compareToIgnoreCase(b.name);}); return out;
        } finally {dir.close();}
    }
    public void download(String remote,File out) throws Exception {
        SmbFile src=resource(remote); File parent=out.getParentFile();if(parent!=null&&!parent.exists()&&!parent.mkdirs())throw new IOException("Cannot create destination");
        File tmp=new File(parent,out.getName()+".part-"+System.nanoTime()); long expected; try(SmbFile f=src){expected=f.length(); try(InputStream in=f.openInputStream();OutputStream os=new BufferedOutputStream(new FileOutputStream(tmp))){copy(in,os);}} if(!tmp.isFile()||tmp.length()!=expected){tmp.delete();throw new IOException("SMB download size verification failed");} if(out.exists()&&!out.delete()){tmp.delete();throw new IOException("Cannot replace destination");}if(!tmp.renameTo(out)){tmp.delete();throw new IOException("Cannot promote download");} if(out.length()!=expected)throw new IOException("SMB destination verification failed");
    }
    public void upload(File local,String remote) throws Exception {
        if(local==null||!local.isFile())throw new FileNotFoundException(String.valueOf(local)); SmbFile dst=resource(remote); try(SmbFile f=dst;InputStream in=new BufferedInputStream(new FileInputStream(local));OutputStream os=new BufferedOutputStream(f.openOutputStream())){copy(in,os);} try(SmbFile f=dst){if(f.length()!=local.length())throw new IOException("SMB upload size verification failed");}
    }
    public void mkdir(String remote) throws Exception {try(SmbFile f=resource(remote)){if(f.exists()){if(!f.isDirectory())throw new IOException("SMB target exists and is not a directory");return;}f.mkdirs();if(!f.exists()||!f.isDirectory())throw new IOException("SMB directory creation failed");}}
    public void delete(String remote) throws Exception {try(SmbFile f=resource(remote)){if(isRoot(f))throw new IOException("Refusing to delete SMB root");f.delete();if(f.exists())throw new IOException("SMB delete verification failed");}}
    public void rename(String remote,String target) throws Exception {try(SmbFile a=resource(remote);SmbFile b=resource(target)){if(isRoot(a)||isRoot(b))throw new IOException("Cannot rename SMB root");if(b.exists())throw new IOException("Destination already exists");a.renameTo(b);}}
    public void copy(String remote,String target) throws Exception {try(SmbFile a=resource(remote);SmbFile b=resource(target)){if(isRoot(a)||isRoot(b))throw new IOException("Cannot copy SMB root");if(b.exists())throw new IOException("Destination already exists");a.copyTo(b);}}
    public void close(){try{root.close();}catch(Exception ignored){}}
    private SmbFile resource(String path) throws Exception {String p=path==null||path.trim().isEmpty()?"/":path.trim().replace('\\','/');while(p.startsWith("/"))p=p.substring(1);if(p.contains("\u0000")||hasTraversal(p))throw new SecurityException("Invalid SMB path");return new SmbFile(root,p);}
    private static boolean hasTraversal(String p){for(String s:p.split("/")){if("..".equals(s))return true;}return false;}
    private boolean isRoot(SmbFile f){String a=root.getCanonicalPath(),b=f.getCanonicalPath();return a.equalsIgnoreCase(b);}
    private static long safeLength(SmbFile f){try{return f.length();}catch(Exception e){return 0;}}
    private static long safeModified(SmbFile f){try{return f.lastModified();}catch(Exception e){return 0;}}
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}
}
