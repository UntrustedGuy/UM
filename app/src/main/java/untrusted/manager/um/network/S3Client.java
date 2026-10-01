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

/**
 * Dependency-free Amazon S3 Signature V4 client.
 * Supports AWS S3 and S3-compatible endpoints (for example MinIO/Ceph) using
 * path-style addressing. Requests are signed with the exact canonical URI and
 * sorted RFC3986 query string required by SigV4.
 */
public final class S3Client implements Closeable {
    public static final class Entry {
        public final String key, name;
        public final boolean directory;
        public final long size;
        public Entry(String k, String n, boolean d, long s) { key=k; name=n; directory=d; size=s; }
    }

    private final URI endpoint;
    private final String endpointBasePath;
    private final String region, bucket, accessKey, secretKey, sessionToken;

    public S3Client(String endpoint, String region, String bucket, String accessKey,
                    String secretKey, String sessionToken) throws Exception {
        if (endpoint == null || endpoint.trim().isEmpty()) throw new IllegalArgumentException("S3 endpoint is required");
        String raw = endpoint.trim();
        this.endpoint = new URI(raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw);
        if (!"http".equalsIgnoreCase(this.endpoint.getScheme()) && !"https".equalsIgnoreCase(this.endpoint.getScheme())) {
            throw new IllegalArgumentException("S3 endpoint must use HTTP or HTTPS");
        }
        if (this.endpoint.getUserInfo() != null) throw new IllegalArgumentException("Credentials must not be embedded in the S3 URL");
        this.endpointBasePath = normalizeBasePath(this.endpoint.getRawPath());
        this.region = region == null || region.trim().isEmpty() ? "us-east-1" : region.trim();
        this.bucket = require(bucket, "bucket");
        this.accessKey = require(accessKey, "access key");
        this.secretKey = require(secretKey, "secret key");
        this.sessionToken = sessionToken == null ? "" : sessionToken.trim();
    }

    private static String require(String s, String n) {
        if (s == null || s.trim().isEmpty()) throw new IllegalArgumentException(n + " is required");
        return s.trim();
    }

    public List<Entry> list(String prefix) throws Exception {
        String p = normalizePrefix(prefix);
        String token = "";
        List<Entry> out = new ArrayList<>();
        do {
            Map<String, String> q = new HashMap<>();
            q.put("delimiter", "/");
            q.put("list-type", "2");
            q.put("prefix", p);
            if (!token.isEmpty()) q.put("continuation-token", token);
            Response r = request("GET", bucketPath(), q, null, null);
            Document d = xml(r.body);
            NodeList cps = d.getElementsByTagNameNS("*", "CommonPrefixes");
            for (int i = 0; i < cps.getLength(); i++) {
                String k = text(cps.item(i), "Prefix");
                if (k == null) continue;
                String n = k.startsWith(p) ? k.substring(p.length()) : k;
                if (n.endsWith("/")) n = n.substring(0, n.length() - 1);
                if (!n.isEmpty()) out.add(new Entry(k, n, true, 0));
            }
            NodeList objs = d.getElementsByTagNameNS("*", "Contents");
            for (int i = 0; i < objs.getLength(); i++) {
                String k = text(objs.item(i), "Key");
                if (k == null || k.equals(p) || k.endsWith("/")) continue;
                String n = k.startsWith(p) ? k.substring(p.length()) : k;
                if (n.contains("/")) continue;
                long size = parseLong(text(objs.item(i), "Size"));
                out.add(new Entry(k, n, false, size));
            }
            token = text(d.getDocumentElement(), "NextContinuationToken");
            if (token == null) token = "";
        } while (!token.isEmpty());
        return out;
    }

    public void download(String key, File out) throws Exception {
        if (out == null) throw new IllegalArgumentException("Destination required");
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create destination directory");
        }
        File tmp = File.createTempFile(".um-s3-", ".part", parent == null ? new File(".") : parent);
        boolean promoted = false;
        try {
            HttpURLConnection c = openSigned("GET", objectPath(key), Collections.<String,String>emptyMap(), null, null, 0);
            try {
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw httpError(c, "S3 download failed");
                try (InputStream in = new BufferedInputStream(c.getInputStream());
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp))) {
                    copy(in, os);
                }
            } finally { c.disconnect(); }
            if (out.exists() && !out.delete()) throw new IOException("Cannot replace destination");
            if (!tmp.renameTo(out)) throw new IOException("Cannot promote downloaded file");
            promoted = true;
        } finally { if (!promoted && tmp.exists()) tmp.delete(); }
    }

    public void upload(File local, String key) throws Exception {
        if (local == null || !local.isFile()) throw new FileNotFoundException(String.valueOf(local));
        String contentType = "application/octet-stream";
        long length = local.length();
        String payloadHash = sha256Hex(local);
        HttpURLConnection c = openSigned("PUT", objectPath(key), Collections.<String,String>emptyMap(), payloadHash, contentType, length);
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(length);
            try (InputStream in = new BufferedInputStream(new FileInputStream(local)); OutputStream out = new BufferedOutputStream(c.getOutputStream())) {
                copy(in, out);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw httpError(c, "S3 upload failed");
        } finally { c.disconnect(); }
    }

    public void delete(String key) throws Exception {
        Response r = request("DELETE", objectPath(key), Collections.<String,String>emptyMap(), null, null);
        if (r.code != 204 && r.code != 200) throw new IOException("Unexpected S3 delete status: " + r.code);
    }

    public void mkdir(String key) throws Exception {
        String k = normalizeKey(key);
        if (!k.endsWith("/")) k += "/";
        request("PUT", objectPath(k), Collections.<String,String>emptyMap(), new byte[0], "application/octet-stream");
    }

    public void move(String from, String to) throws Exception {
        String source = "/" + encPath(bucket) + "/" + encPath(normalizeKey(from));
        Map<String,String> h = new HashMap<>();
        h.put("x-amz-copy-source", source);
        request("PUT", objectPath(to), Collections.<String,String>emptyMap(), new byte[0], "application/octet-stream", h);
        delete(from);
    }

    private String bucketPath() { return joinPath(endpointBasePath, "/" + encPath(bucket)); }
    private String objectPath(String key) { return joinPath(bucketPath(), "/" + encPath(normalizeKey(key))); }

    private static String normalizeBasePath(String p) {
        if (p == null || p.isEmpty() || "/".equals(p)) return "";
        return p.startsWith("/") ? p.replaceAll("/+$", "") : "/" + p.replaceAll("/+$", "");
    }

    private static String joinPath(String a, String b) {
        if (a == null || a.isEmpty()) return b.startsWith("/") ? b : "/" + b;
        if (b == null || b.isEmpty()) return a;
        return a.replaceAll("/+$", "") + "/" + b.replaceAll("^/+", "");
    }

    private static String normalizePrefix(String p) {
        if (p == null || p.trim().isEmpty() || "/".equals(p)) return "";
        p = p.trim();
        while (p.startsWith("/")) p = p.substring(1);
        if (p.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in object prefix");
        if (!p.endsWith("/")) p += "/";
        return p;
    }

    private static String normalizeKey(String k) {
        if (k == null) throw new IllegalArgumentException("key required");
        k = k.trim();
        while (k.startsWith("/")) k = k.substring(1);
        if (k.isEmpty() || k.indexOf('\0') >= 0) throw new IllegalArgumentException("invalid object key");
        return k;
    }

    private Response request(String method, String path, Map<String,String> query, byte[] body, String contentType) throws Exception {
        return request(method, path, query, body, contentType, Collections.<String,String>emptyMap());
    }

    private Response request(String method, String path, Map<String,String> query, byte[] body, String contentType, Map<String,String> extra) throws Exception {
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = hex(sha(payload));
        HttpURLConnection c = openSigned(method, path, query, payloadHash, contentType, payload.length, extra);
        try {
            if (payload.length > 0 || "PUT".equals(method) || "POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] resp = read(in);
            if (code < 200 || code >= 300) throw new IOException("S3 HTTP " + code + ": " + new String(resp, StandardCharsets.UTF_8));
            return new Response(code, resp);
        } finally { c.disconnect(); }
    }

    private HttpURLConnection openSigned(String method, String path, Map<String,String> query, String payloadHash,
                                         String contentType, long contentLength) throws Exception {
        return openSigned(method, path, query, payloadHash, contentType, contentLength, Collections.<String,String>emptyMap());
    }

    private HttpURLConnection openSigned(String method, String path, Map<String,String> query, String payloadHash,
                                         String contentType, long contentLength, Map<String,String> extra) throws Exception {
        byte[] empty = new byte[0];
        String actualHash = payloadHash == null ? hex(sha(empty)) : payloadHash;
        String amzDate = utcNow();
        String date = amzDate.substring(0, 8);
        String host = endpoint.getHost() + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
        Map<String,String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("host", host);
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", actualHash);
        if (contentType != null) headers.put("content-type", contentType);
        if (contentLength >= 0) headers.put("content-length", String.valueOf(contentLength));
        if (!sessionToken.isEmpty()) headers.put("x-amz-security-token", sessionToken);
        if (extra != null) headers.putAll(extra);

        String canonicalQuery = canonicalQuery(query);
        String canonicalHeaders = canonicalHeaders(headers);
        String signedHeaders = joinLower(headers.keySet());
        String canonicalRequest = method + "\n" + path + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + actualHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String sig = hex(hmac(signingKey(date), stringToSign));
        String auth = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + sig;

        String url = endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setUseCaches(false);
        for (Map.Entry<String,String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setRequestProperty("Authorization", auth);
        return c;
    }

    private static String canonicalHeaders(Map<String,String> headers) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String,String> e : headers.entrySet()) {
            b.append(e.getKey().toLowerCase(Locale.ROOT)).append(':')
                    .append(e.getValue().trim().replaceAll("\\s+", " ")).append('\n');
        }
        return b.toString();
    }

    private static String canonicalQuery(Map<String,String> query) {
        if (query == null || query.isEmpty()) return "";
        List<String> keys = new ArrayList<>(query.keySet());
        Collections.sort(keys);
        StringBuilder b = new StringBuilder();
        for (String key : keys) {
            if (b.length() > 0) b.append('&');
            b.append(rfc3986(key)).append('=').append(rfc3986(query.get(key) == null ? "" : query.get(key)));
        }
        return b.toString();
    }

    private static String encPath(String s) {
        StringBuilder b = new StringBuilder();
        for (String part : s.split("/", -1)) {
            if (b.length() > 0) b.append('/');
            b.append(rfc3986(part));
        }
        return b.toString();
    }

    private static String rfc3986(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder(bytes.length + 16);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte x : bytes) {
            int v = x & 0xff;
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z') || (v >= '0' && v <= '9') || v == '-' || v == '_' || v == '.' || v == '~') b.append((char)v);
            else b.append('%').append(hex[v >>> 4]).append(hex[v & 15]);
        }
        return b.toString();
    }

    private byte[] signingKey(String date) throws Exception {
        return hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), region), "s3"), "aws4_request");
    }

    private static String joinLower(Collection<String> c) {
        StringBuilder b = new StringBuilder();
        for (String s : c) { if (b.length() > 0) b.append(';'); b.append(s.toLowerCase(Locale.ROOT)); }
        return b.toString();
    }

    private static String utcNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    private static byte[] hmac(byte[] key, String data) throws Exception { return hmac(key, data.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] hmac(byte[] key, byte[] data) throws Exception { Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));return m.doFinal(data); }
    private static byte[] sha(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
    private static String hex(byte[] b) { StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString(); }
    private static String sha256Hex(File f) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest()); }
    private static byte[] read(InputStream in) throws IOException { if(in==null)return new byte[0];ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[65536];int n;while((n=in.read(x))!=-1)b.write(x,0,n);return b.toByteArray(); }
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}
    private static long parseLong(String s){try{return s==null?0:Long.parseLong(s.trim());}catch(Exception e){return 0;}}
    private static IOException httpError(HttpURLConnection c,String message)throws IOException{int code=c.getResponseCode();InputStream in=c.getErrorStream();return new IOException(message+" (HTTP "+code+")"+(in==null?"":" "+new String(read(in),StandardCharsets.UTF_8)));}
    private static Document xml(byte[] b)throws Exception{DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);}catch(Exception ignored){}return f.newDocumentBuilder().parse(new ByteArrayInputStream(b));}
    private static String text(org.w3c.dom.Node n,String tag){if(n==null)return null;NodeList l=((org.w3c.dom.Element)n).getElementsByTagNameNS("*",tag);return l.getLength()==0?null:l.item(0).getTextContent();}
    private static final class Response{final int code;final byte[] body;Response(int c,byte[]b){code=c;body=b;}}
    @Override public void close() { }
}
