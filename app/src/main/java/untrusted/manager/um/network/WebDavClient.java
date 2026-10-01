package untrusted.manager.um.network;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Small WebDAV client used by UM's network-storage browser. */
public final class WebDavClient {
    public static final class Entry {
        public final String name, href;
        public final boolean directory;
        public final long size, modified;
        Entry(String name, String href, boolean directory, long size, long modified) {
            this.name=name; this.href=href; this.directory=directory; this.size=size; this.modified=modified;
        }
    }
    private final URI base;
    private final String user, password;
    private final int connectTimeout, readTimeout;

    public WebDavClient(String baseUrl, String user, String password) throws Exception {
        if (baseUrl == null || baseUrl.trim().isEmpty()) throw new IllegalArgumentException("WebDAV URL is required");
        String normalized = baseUrl.trim();
        if (!normalized.endsWith("/")) normalized += "/";
        URI u = URI.create(normalized);
        String scheme=u.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) throw new IllegalArgumentException("WebDAV URL must use HTTP or HTTPS");
        this.base=u; this.user=user==null?"":user; this.password=password==null?"":password;
        connectTimeout=10000; readTimeout=30000;
    }

    public List<Entry> list(String path) throws Exception {
        String href = resolvePath(path);
        HttpURLConnection c=open("PROPFIND", href);
        c.setRequestProperty("Depth", "1");
        c.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
        c.setDoOutput(true);
        byte[] body="<?xml version=\"1.0\" encoding=\"utf-8\" ?><propfind xmlns=\"DAV:\"><prop><displayname/><resourcetype/><getcontentlength/><getlastmodified/></prop></propfind>".getBytes(StandardCharsets.UTF_8);
        try(OutputStream out=c.getOutputStream()){out.write(body);}
        ensure2xx(c, "WebDAV listing failed");
        try(InputStream in=c.getInputStream()){ return parse(in, href); }
        finally { c.disconnect(); }
    }

    public void download(String path, File destination) throws Exception {
        HttpURLConnection c=open("GET", resolvePath(path));
        ensure2xx(c,"WebDAV download failed");
        File parent=destination.getParentFile(); if(parent!=null&&!parent.exists()&&!parent.mkdirs()) throw new Exception("Cannot create destination");
        File tmp=File.createTempFile(".um-webdav-", ".part", parent);
        try(InputStream in=c.getInputStream();OutputStream out=new java.io.FileOutputStream(tmp)){copy(in,out);}
        finally{c.disconnect();}
        if(destination.exists()&&!destination.delete()) { tmp.delete(); throw new Exception("Destination exists and cannot be replaced"); }
        if(!tmp.renameTo(destination)){try(InputStream in=new FileInputStream(tmp);OutputStream out=new java.io.FileOutputStream(destination)){copy(in,out);}tmp.delete();}
    }

    public void upload(File source, String remotePath) throws Exception {
        if(source==null||!source.isFile()) throw new IllegalArgumentException("Local file required");
        HttpURLConnection c=open("PUT", resolvePath(remotePath)); c.setDoOutput(true); c.setFixedLengthStreamingMode(source.length()); c.setRequestProperty("Content-Type","application/octet-stream");
        try(InputStream in=new FileInputStream(source);OutputStream out=c.getOutputStream()){copy(in,out);}
        ensure2xx(c,"WebDAV upload failed"); c.disconnect();
    }

    public void mkdir(String remotePath) throws Exception { simple("MKCOL",remotePath,"WebDAV directory creation failed"); }
    public void delete(String remotePath) throws Exception { simple("DELETE",remotePath,"WebDAV delete failed"); }
    public void move(String from,String to) throws Exception { HttpURLConnection c=open("MOVE",resolvePath(from)); c.setRequestProperty("Destination",resolvePath(to)); c.setRequestProperty("Overwrite","F"); ensure2xx(c,"WebDAV move failed"); c.disconnect(); }

    private void simple(String method,String path,String message)throws Exception{HttpURLConnection c=open(method,resolvePath(path));try{ensure2xx(c,message);}finally{c.disconnect();}}

    private HttpURLConnection open(String method,String target)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(target).openConnection(); c.setConnectTimeout(connectTimeout);c.setReadTimeout(readTimeout);c.setRequestMethod(method);c.setUseCaches(false);c.setDoInput(true);
        if(!user.isEmpty()){String raw=user+":"+password;String b=android.util.Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);c.setRequestProperty("Authorization","Basic "+b);}
        return c;
    }
    private String resolvePath(String path)throws Exception{
        String p=path==null?"/":path.trim();
        if(p.isEmpty())p="/";
        p=p.replace('\\','/');
        if(p.indexOf('\0')>=0) throw new IllegalArgumentException("NUL in WebDAV path");
        if(!p.startsWith("/"))p="/"+p;
        String[] parts=p.split("/");
        StringBuilder safe=new StringBuilder();
        for(String part:parts){
            if(part.isEmpty()||".".equals(part)) continue;
            if("..".equals(part)) throw new SecurityException("Parent traversal is not allowed");
            safe.append('/').append(part);
        }
        if(safe.length()==0) safe.append('/');
        // A relative URI keeps the configured WebDAV base path (e.g. /remote/dav/) intact.
        URI u=base.resolve("."+safe.toString());
        return u.toASCIIString();
    }
    private static void ensure2xx(HttpURLConnection c,String message)throws Exception{int code=c.getResponseCode();if(code<200||code>=300){String detail="";try(InputStream e=c.getErrorStream()){if(e!=null)detail=new String(readAll(e),StandardCharsets.UTF_8);}throw new Exception(message+" (HTTP "+code+")"+(detail.isEmpty()?"":" "+detail.substring(0,Math.min(300,detail.length()))));}}
    private static void copy(InputStream in,OutputStream out)throws Exception{byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    private static byte[] readAll(InputStream in)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();copy(in,b);return b.toByteArray();}

    private static List<Entry> parse(InputStream in,String requestHref)throws Exception{
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);}catch(Exception ignored){}
        try{f.setFeature("http://xml.org/sax/features/external-general-entities",false);}catch(Exception ignored){}
        try{f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);}catch(Exception ignored){}
        try{f.setXIncludeAware(false);}catch(Exception ignored){}
        try{f.setExpandEntityReferences(false);}catch(Exception ignored){}Document d=f.newDocumentBuilder().parse(in);NodeList responses=d.getElementsByTagNameNS("DAV:","response");List<Entry> out=new ArrayList<>();String requested=normalizeHref(requestHref);
        for(int i=0;i<responses.getLength();i++){Element r=(Element)responses.item(i);String href=text(r,"href");if(href==null)continue;String normalized=normalizeHref(href);if(normalized.equals(requested))continue;String name=displayName(r);if(name==null||name.isEmpty())name=lastSegment(normalized);boolean dir=!elements(r,"resourcetype","collection").isEmpty();long size=parseLong(text(r,"getcontentlength"));long modified=parseDate(text(r,"getlastmodified"));out.add(new Entry(name,normalized,dir,size,modified));}
        Collections.sort(out,(a,b)->{if(a.directory!=b.directory)return a.directory?-1:1;return a.name.compareToIgnoreCase(b.name);});return out;
    }
    private static String displayName(Element r){String v=text(r,"displayname");return v==null?null:v.trim();}
    private static List<Element> elements(Element p,String local,String child)throws Exception{NodeList props=p.getElementsByTagNameNS("DAV:",local);List<Element> out=new ArrayList<>();for(int i=0;i<props.getLength();i++){Element e=(Element)props.item(i);NodeList c=e.getElementsByTagNameNS("DAV:",child);for(int j=0;j<c.getLength();j++)out.add((Element)c.item(j));}return out;}
    private static String text(Element p,String local){NodeList n=p.getElementsByTagNameNS("DAV:",local);return n.getLength()==0?null:n.item(0).getTextContent();}
    private static long parseLong(String s){try{return s==null?0:Long.parseLong(s.trim());}catch(Exception e){return 0;}}
    private static long parseDate(String s){if(s==null)return 0;String[] patterns={"EEE, dd MMM yyyy HH:mm:ss z","EEE, dd MMM yyyy HH:mm:ss Z"};for(String p:patterns)try{SimpleDateFormat f=new SimpleDateFormat(p,Locale.US);return f.parse(s.trim()).getTime();}catch(Exception ignored){}return 0;}
    private static String normalizeHref(String s)throws Exception{if(s==null)return "/";String p=s;try{p=new URI(p).getPath();}catch(Exception ignored){}if(p==null)p="/";p=URLDecoder.decode(p,"UTF-8");if(!p.startsWith("/"))p="/";while(p.contains("//"))p=p.replace("//","/");return p.length()>1&&p.endsWith("/")?p.substring(0,p.length()-1):p;}
    private static String lastSegment(String p){if(p==null||p.isEmpty()||"/".equals(p))return "/";int i=p.lastIndexOf('/');return i<0?p:p.substring(i+1);}
}
