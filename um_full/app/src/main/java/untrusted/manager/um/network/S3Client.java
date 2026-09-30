package untrusted.manager.um.network;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Minimal dependency-free Amazon S3 Signature V4 client for UM network storage. */
public final class S3Client {
    public static final class Entry { public final String key,name; public final boolean directory; public final long size; public Entry(String k,String n,boolean d,long s){key=k;name=n;directory=d;size=s;} }
    private final URI endpoint; private final String region,bucket,accessKey,secretKey,sessionToken;
    public S3Client(String endpoint,String region,String bucket,String accessKey,String secretKey,String sessionToken) throws Exception {
        this.endpoint=new URI(endpoint.endsWith("/")?endpoint.substring(0,endpoint.length()-1):endpoint);
        if (!"http".equalsIgnoreCase(this.endpoint.getScheme()) && !"https".equalsIgnoreCase(this.endpoint.getScheme())) throw new IllegalArgumentException("S3 endpoint must use HTTP or HTTPS");
        this.region=region==null||region.trim().isEmpty()?"us-east-1":region.trim(); this.bucket=require(bucket,"bucket"); this.accessKey=require(accessKey,"access key"); this.secretKey=require(secretKey,"secret key"); this.sessionToken=sessionToken==null?"":sessionToken.trim();
    }
    private static String require(String s,String n){if(s==null||s.trim().isEmpty())throw new IllegalArgumentException(n+" is required");return s.trim();}
    public List<Entry> list(String prefix) throws Exception {
        String p=normalizePrefix(prefix); String marker=""; List<Entry> out=new ArrayList<>();
        do { String query="list-type=2&delimiter=%2F&prefix="+enc(p)+(marker.isEmpty()?"":"&continuation-token="+enc(marker)); Response r=request("GET","/"+encPath(bucket),query,null,null); Document d=xml(r.body); NodeList cps=d.getElementsByTagNameNS("*","CommonPrefixes"); for(int i=0;i<cps.getLength();i++){String k=text(cps.item(i),"Prefix"); if(k==null)continue; String n=k.substring(p.length()); if(n.endsWith("/"))n=n.substring(0,n.length()-1); if(!n.isEmpty())out.add(new Entry(k,n,true,0));} NodeList objs=d.getElementsByTagNameNS("*","Contents"); for(int i=0;i<objs.getLength();i++){String k=text(objs.item(i),"Key"); if(k==null||k.equals(p)||k.endsWith("/"))continue; String n=k.substring(p.length()); if(n.contains("/"))continue; long size=0;try{size=Long.parseLong(text(objs.item(i),"Size"));}catch(Exception ignored){} out.add(new Entry(k,n,false,size));} marker=text(d.getDocumentElement(),"NextContinuationToken"); if(marker==null)marker=""; } while(!marker.isEmpty()); return out;
    }
    public void download(String key,File out) throws Exception { Response r=request("GET",objectPath(key),null,null,null); File tmp=new File(out.getParentFile(),out.getName()+".part"); try(FileOutputStream f=new FileOutputStream(tmp)){f.write(r.body);} if(out.exists()&&!out.delete())throw new IOException("Cannot replace destination"); if(!tmp.renameTo(out)){tmp.delete();throw new IOException("Cannot promote downloaded file");} }
    public void upload(File local,String key) throws Exception { if(!local.isFile())throw new FileNotFoundException(local.toString()); byte[] data=read(local); request("PUT",objectPath(key),null,data,"application/octet-stream"); }
    public void delete(String key) throws Exception { request("DELETE",objectPath(key),null,null,null); }
    public void mkdir(String key) throws Exception { String k=normalizeKey(key); if(!k.endsWith("/"))k+="/"; request("PUT",objectPath(k),null,new byte[0],"application/octet-stream"); }
    public void move(String from,String to) throws Exception { String src=objectPath(from); String dst=objectPath(to); Map<String,String> h=new HashMap<>(); h.put("x-amz-copy-source", "/"+bucket+"/"+normalizeKey(from)); request("PUT",dst,null,new byte[0],"application/octet-stream",h); delete(from); }
    private String objectPath(String key){return "/"+encPath(bucket)+"/"+encPath(normalizeKey(key));}
    private static String normalizePrefix(String p){if(p==null||p.trim().isEmpty()||"/".equals(p))return ""; p=p.trim();while(p.startsWith("/"))p=p.substring(1);if(!p.endsWith("/"))p+="/";return p;}
    private static String normalizeKey(String k){if(k==null)throw new IllegalArgumentException("key required");k=k.trim();while(k.startsWith("/"))k=k.substring(1);if(k.contains(".."))throw new IllegalArgumentException("invalid object key");return k;}
    private Response request(String method,String path,String query,byte[] body,String contentType)throws Exception{return request(method,path,query,body,contentType,Collections.emptyMap());}
    private Response request(String method,String path,String query,byte[] body,String contentType,Map<String,String> extra)throws Exception {
        byte[] payload=body==null?new byte[0]:body; String host=endpoint.getHost()+(endpoint.getPort()>0?":"+endpoint.getPort():""); String amzDate=new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'"){ {setTimeZone(TimeZone.getTimeZone("UTC"));} }.format(new Date()); String date=amzDate.substring(0,8); String payloadHash=hex(sha(payload)); Map<String,String> h=new TreeMap<>(String.CASE_INSENSITIVE_ORDER); h.put("host",host);h.put("x-amz-date",amzDate);h.put("x-amz-content-sha256",payloadHash);if(contentType!=null)h.put("content-type",contentType);if(!sessionToken.isEmpty())h.put("x-amz-security-token",sessionToken);h.putAll(extra); String canonicalHeaders="";for(Map.Entry<String,String> e:h.entrySet())canonicalHeaders+=e.getKey().toLowerCase(Locale.ROOT)+":"+e.getValue().trim().replaceAll("\\s+"," ")+"\n";String signed=joinLower(h.keySet());String canonical=method+"\n"+path+"\n"+(query==null?"":query)+"\n"+canonicalHeaders+"\n"+signed+"\n"+payloadHash;String scope=date+"/"+region+"/s3/aws4_request";String sig=hex(hmac(signingKey(date),"AWS4-HMAC-SHA256\n"+amzDate+"\n"+scope+"\n"+hex(sha(canonical.getBytes(StandardCharsets.UTF_8)))));String auth="AWS4-HMAC-SHA256 Credential="+accessKey+"/"+scope+", SignedHeaders="+signed+", Signature="+sig;
        URI u=new URI(endpoint.getScheme(),endpoint.getUserInfo(),endpoint.getHost(),endpoint.getPort(),path,null,null); if(query!=null&&!query.isEmpty())u=new URI(u.toString()+"?"+query);HttpURLConnection c=(HttpURLConnection)u.toURL().openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(30000);for(Map.Entry<String,String> e:h.entrySet())c.setRequestProperty(e.getKey(),e.getValue());c.setRequestProperty("Authorization",auth);if(method.equals("PUT")){c.setDoOutput(true);try(OutputStream os=c.getOutputStream()){os.write(payload);}}int code=c.getResponseCode();InputStream in=code>=400?c.getErrorStream():c.getInputStream();byte[] resp=read(in);c.disconnect();if(code<200||code>=300)throw new IOException("S3 HTTP "+code+": "+new String(resp,StandardCharsets.UTF_8));return new Response(code,resp);
    }
    private byte[] signingKey(String date)throws Exception{return hmac(hmac(hmac(hmac(("AWS4"+secretKey).getBytes(StandardCharsets.UTF_8),date),region),"s3"),"aws4_request");}
    private static String joinLower(Collection<String> c){StringBuilder b=new StringBuilder();for(String s:c){if(b.length()>0)b.append(';');b.append(s.toLowerCase(Locale.ROOT));}return b.toString();}
    private static byte[] hmac(byte[] key,String data)throws Exception{return hmac(key,data.getBytes(StandardCharsets.UTF_8));} private static byte[] hmac(byte[] key,byte[] data)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(data);}
    private static byte[] sha(byte[] b)throws Exception{return MessageDigest.getInstance("SHA-256").digest(b);}private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    private static byte[] read(InputStream in)throws IOException{if(in==null)return new byte[0];ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[65536];int n;while((n=in.read(x))!=-1)b.write(x,0,n);return b.toByteArray();}private static byte[] read(File f)throws IOException{try(FileInputStream in=new FileInputStream(f)){return read(in);}}
    private static String enc(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalArgumentException(e);}}private static String encPath(String s){StringBuilder b=new StringBuilder();for(String p:s.split("/",-1)){if(b.length()>0)b.append('/');b.append(enc(p).replace("%2F","/"));}return b.toString();}
    private static Document xml(byte[] b)throws Exception{DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);}catch(Exception ignored){}return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));}
    private static String text(org.w3c.dom.Node n,String tag){NodeList l=((org.w3c.dom.Element)n).getElementsByTagNameNS("*",tag);return l.getLength()==0?null:l.item(0).getTextContent();}
    private static final class Response{final int code;final byte[] body;Response(int c,byte[]b){code=c;body=b;}}
}
