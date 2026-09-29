package untrusted.manager.um.remote;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local HTTP file-management server. All filesystem paths are confined to the configured root. */
public final class HttpRemoteServer {
    public interface Listener {
        void onStarted(int port, String token);
        void onStopped();
        void onError(Exception error);
    }

    private final File root;
    private final String token;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "UM-HTTP");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int port;

    public HttpRemoteServer(File root, int requestedPort, String token, Listener listener) throws IOException {
        if (root == null) throw new IOException("Root is required");
        File canonical = root.getCanonicalFile();
        if (!canonical.isDirectory() && !canonical.mkdirs() && !canonical.isDirectory()) {
            throw new IOException("Cannot create server root: " + canonical);
        }
        this.root = canonical;
        this.token = token == null ? "" : token.trim();
        this.listener = listener;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress("0.0.0.0", requestedPort <= 0 ? 0 : requestedPort));
        this.port = serverSocket.getLocalPort();
    }

    public synchronized void start() {
        if (running.getAndSet(true)) return;
        acceptThread = new Thread(() -> {
            try {
                if (listener != null) listener.onStarted(port, token);
                while (running.get()) {
                    Socket socket = serverSocket.accept();
                    workers.execute(() -> handle(socket));
                }
            } catch (Exception e) {
                if (running.get() && listener != null) listener.onError(e);
            } finally {
                running.set(false);
                if (listener != null) listener.onStopped();
            }
        }, "UM-HTTP-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try { serverSocket.close(); } catch (Exception ignored) { }
        workers.shutdownNow();
    }

    public boolean isRunning() { return running.get(); }
    public int getPort() { return port; }
    public File getRoot() { return root; }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30_000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream());
            Request req = readRequest(in);
            if (req == null) return;
            if (!authorized(req)) {
                // Never accept credentials in query parameters: URLs are routinely logged, copied,
                // bookmarked and exposed through browser history/referrers. Offer only a token
                // bootstrap page at the root; the token itself is kept in the URL fragment, which
                // is not transmitted to this server. The page exchanges it for an HttpOnly cookie.
                if ("GET".equals(req.method) && "/".equals(req.path)) {
                    writeResponse(out, 200, "OK", "text/html; charset=utf-8", bootstrapHtml().getBytes(StandardCharsets.UTF_8), "Cache-Control: no-store\r\n");
                } else {
                    writeResponse(out, 401, "Unauthorized", "text/plain; charset=utf-8", "Bearer token required".getBytes(StandardCharsets.UTF_8), "Cache-Control: no-store\r\n");
                }
                return;
            }
            try {
                dispatch(req, in, out);
            } catch (SecurityException e) {
                writeResponse(out, 403, "Forbidden", "text/plain; charset=utf-8", "Path outside server root".getBytes(StandardCharsets.UTF_8), "Cache-Control: no-store\r\n");
            }
        } catch (Exception ignored) {
            // Client disconnects and malformed requests must not terminate the server.
        }
    }

    private boolean authorized(Request req) {
        if (token.isEmpty()) return true;
        String auth = req.headers.get("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return constantTimeEquals(token, auth.substring(7).trim());
        }
        String cookie = req.headers.get("cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String value = part.trim();
                if (value.startsWith("um_auth=")) return constantTimeEquals(token, value.substring("um_auth=".length()));
            }
        }
        return false;
    }

    private void dispatch(Request req, InputStream in, OutputStream out) throws Exception {
        if ("OPTIONS".equals(req.method)) {
            writeResponse(out, 204, "No Content", null, new byte[0], "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\nAccess-Control-Allow-Methods: GET, HEAD, PUT, POST, DELETE, OPTIONS\r\n");
            return;
        }
        if ("GET".equals(req.method) || "HEAD".equals(req.method)) {
            if ("/api/session".equals(req.path)) {
                writeResponse(out, 204, "No Content", null, new byte[0], sessionCookieHeader());
                return;
            }
            handleGet(req, out); return;
        }
        if ("PUT".equals(req.method)) {
            handlePut(req, in, out); return;
        }
        if ("DELETE".equals(req.method)) {
            handleDelete(req, out); return;
        }
        if ("POST".equals(req.method)) {
            handlePost(req, in, out); return;
        }
        writeResponse(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Method not supported".getBytes(StandardCharsets.UTF_8), null);
    }

    private void handleGet(Request req, OutputStream out) throws Exception {
        if ("/api/list".equals(req.path)) {
            File dir = resolve(req.query.getOrDefault("path", "/"), false);
            if (!dir.isDirectory()) { json(out, 404, "{\"error\":\"Directory not found\"}"); return; }
            StringBuilder b = new StringBuilder("{\"path\":").append(jsonQuote(relative(dir))).append(",\"items\":[");
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files, Comparator.comparing(File::isFile).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
                for (int i = 0; i < files.length; i++) {
                    if (i > 0) b.append(',');
                    File f = files[i];
                    b.append("{\"name\":").append(jsonQuote(f.getName()))
                            .append(",\"directory\":").append(f.isDirectory())
                            .append(",\"size\":").append(f.isFile() ? f.length() : 0)
                            .append(",\"modified\":").append(f.lastModified()).append('}');
                }
            }
            b.append("]}");
            json(out, 200, b.toString());
            return;
        }
        if ("/api/info".equals(req.path)) {
            File f = resolve(req.query.getOrDefault("path", "/"), false);
            if (!f.exists()) { json(out, 404, "{\"error\":\"Not found\"}"); return; }
            json(out, 200, "{\"path\":" + jsonQuote(relative(f)) + ",\"directory\":" + f.isDirectory() + ",\"size\":" + f.length() + ",\"modified\":" + f.lastModified() + "}");
            return;
        }
        String path = req.path;
        if (path.startsWith("/api/")) { json(out, 404, "{\"error\":\"Unknown endpoint\"}"); return; }
        File target = resolve(path, false);
        if (target.isDirectory()) {
            byte[] page = directoryHtml(target).getBytes(StandardCharsets.UTF_8);
            writeResponse(out, 200, "OK", "text/html; charset=utf-8", "HEAD".equals(req.method) ? new byte[0] : page, null);
        } else if (target.isFile()) {
            String type = Files.probeContentType(target.toPath());
            if (type == null) type = "application/octet-stream";
            writeFileResponse(out, target, type, "HEAD".equals(req.method), null);
        } else {
            writeResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), null);
        }
    }

    private void handlePut(Request req, InputStream in, OutputStream out) throws Exception {
        File target = resolve(req.query.get("path"), true);
        if (target.equals(root)) { json(out, 400, "{\"error\":\"A file path is required\"}"); return; }
        File parent = target.getParentFile();
        if (parent == null || !parent.exists()) { json(out, 409, "{\"error\":\"Parent does not exist\"}"); return; }
        long length = parseLength(req.headers.get("content-length"));
        File temp = File.createTempFile(".um-upload-", ".part", parent);
        try (OutputStream fos = new BufferedOutputStream(new java.io.FileOutputStream(temp))) {
            copyLimited(in, fos, length);
        }
        if (!temp.renameTo(target)) {
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace target");
            if (!temp.renameTo(target)) throw new IOException("Cannot finalize upload");
        }
        json(out, 200, "{\"ok\":true,\"path\":" + jsonQuote(relative(target)) + "}");
    }

    private void handleDelete(Request req, OutputStream out) throws Exception {
        File target = resolve(req.query.get("path"), false);
        if (target.equals(root)) { json(out, 403, "{\"error\":\"Root cannot be deleted\"}"); return; }
        if (!target.exists()) { json(out, 404, "{\"error\":\"Not found\"}"); return; }
        deleteTree(target);
        json(out, 200, "{\"ok\":true}");
    }

    private void handlePost(Request req, InputStream in, OutputStream out) throws Exception {
        String action = req.path.startsWith("/api/") ? req.path.substring(5) : "";
        if ("mkdir".equals(action)) {
            File dir = resolve(req.query.get("path"), true);
            if (dir.exists() || !dir.mkdirs()) { json(out, 409, "{\"error\":\"Cannot create directory\"}"); return; }
            json(out, 200, "{\"ok\":true}"); return;
        }
        if ("move".equals(action) || "copy".equals(action)) {
            File src = resolve(req.query.get("from"), false);
            File dst = resolve(req.query.get("to"), true);
            if (!src.exists() || src.equals(root)) { json(out, 404, "{\"error\":\"Source not found\"}"); return; }
            if (src.isDirectory() && isDescendant(dst, src)) { json(out, 409, "{\"error\":\"Destination is inside source\"}"); return; }
            if ("move".equals(action)) {
                if (!src.renameTo(dst)) {
                    copyTree(src, dst);
                    deleteTree(src);
                }
            } else copyTree(src, dst);
            json(out, 200, "{\"ok\":true}"); return;
        }
        if ("rename".equals(action)) {
            File src = resolve(req.query.get("from"), false);
            File dst = resolve(req.query.get("to"), true);
            if (!src.exists() || src.equals(root) || !src.renameTo(dst)) { json(out, 409, "{\"error\":\"Rename failed\"}"); return; }
            json(out, 200, "{\"ok\":true}"); return;
        }
        if ("write-text".equals(action)) {
            File target = resolve(req.query.get("path"), true);
            byte[] body = readBody(in, parseLength(req.headers.get("content-length")), 16 * 1024 * 1024);
            Files.write(target.toPath(), body);
            json(out, 200, "{\"ok\":true}"); return;
        }
        json(out, 404, "{\"error\":\"Unknown endpoint\"}");
    }

    private File resolve(String requested, boolean allowMissing) throws IOException {
        if (requested == null) throw new IOException("Missing path");
        String decoded = requested.replace('\\', '/');
        while (decoded.startsWith("/")) decoded = decoded.substring(1);
        File candidate = new File(root, decoded).getCanonicalFile();
        String base = root.getCanonicalPath();
        String cp = candidate.getCanonicalPath();
        if (!cp.equals(base) && !cp.startsWith(base + File.separator)) throw new SecurityException("Path escapes server root");
        if (!allowMissing && !candidate.exists()) return candidate;
        return candidate;
    }

    private String relative(File f) throws IOException {
        String p = f.getCanonicalPath();
        String base = root.getCanonicalPath();
        if (p.equals(base)) return "/";
        return "/" + p.substring(base.length() + 1).replace(File.separatorChar, '/');
    }


    private String bootstrapHtml() {
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Untrusted Manager</title><style>body{font:16px sans-serif;margin:24px;max-width:680px}code{word-break:break-all}</style>"
                + "<h2>Untrusted Manager</h2><p>Authenticating this browser session…</p><p id=e></p>"
                + "<script>(async()=>{try{const t=new URL(location.href).hash.slice(1);"
                + "if(!t.startsWith('token='))throw Error('Missing token. Open the URL supplied by Untrusted Manager.');"
                + "const token=decodeURIComponent(t.slice(6));"
                + "const r=await fetch('/api/session',{headers:{Authorization:'Bearer '+token}});"
                + "if(!r.ok)throw Error('Authentication failed ('+r.status+').');"
                + "location.replace(location.pathname||'/');"
                + "}catch(x){document.getElementById('e').textContent=x.message;}})();</script>";
    }

    private String sessionCookieHeader() {
        return "Set-Cookie: um_auth=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400\r\n"
                + "Cache-Control: no-store\r\n";
    }

    private String directoryHtml(File dir) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>Untrusted Manager</title><style>body{font:16px sans-serif;margin:24px}a{display:block;padding:8px;text-decoration:none}small{opacity:.65}form{margin:12px 0}</style>")
                .append("<h2>Untrusted Manager</h2><p><small>").append(html(relative(dir))).append("</small></p>");
        File parent = dir.equals(root) ? null : dir.getParentFile();
        if (parent != null) b.append("<a href='../'>⬆ Parent</a>");
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, Comparator.comparing(File::isFile).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
            for (File f : files) {
                String href = encodePath(f);
                b.append("<div style='display:flex;gap:8px;align-items:center'><a style='flex:1' href='").append(href).append("'>").append(html(f.getName()))
                        .append(f.isDirectory() ? "/" : " <small>(" + f.length() + " bytes)</small>").append("</a>")
                        .append("<button onclick=del(&quot;").append(js(relative(f))).append("&quot;)>Delete</button></div>");
            }
        }
        String current = relative(dir);
        b.append("<hr><input id=file type=file><button onclick=upload()>Upload</button> <button onclick=mkdir()>New folder</button>");
        b.append("<script>")
                .append("const current=").append(js(current)).append(";")
                .append("const H=()=>({});")
                .append("async function mkdir(){let n=prompt('Folder name');if(!n)return;let p=current.endsWith('/')?current+ n:current+'/'+n;let r=await fetch('/api/mkdir?path='+encodeURIComponent(p),{method:'POST',headers:H()});if(r.ok)location.reload();else alert(await r.text());}")
                .append("async function upload(){let f=document.getElementById('file').files[0];if(!f)return;let p=current.endsWith('/')?current+f.name:current+'/'+f.name;let r=await fetch('/api/file?path='+encodeURIComponent(p),{method:'PUT',headers:H(),body:f});if(r.ok)location.reload();else alert(await r.text());}")
                .append("async function del(p){if(!confirm('Delete '+p+'?'))return;let r=await fetch('/api/item?path='+encodeURIComponent(p),{method:'DELETE',headers:H()});if(r.ok)location.reload();else alert(await r.text());}")
                .append("</script>");
        return b.toString();
    }

    private String encodePath(File f) throws IOException {
        String rel = relative(f);
        String[] parts = rel.split("/");
        StringBuilder b = new StringBuilder();
        for (String part : parts) if (!part.isEmpty()) b.append('/').append(URLEncoder.encode(part, StandardCharsets.UTF_8.name()).replace("+", "%20"));
        return b.length() == 0 ? "/" : b.toString();
    }

    private static void writeFileResponse(OutputStream out, File file, String type, boolean head, String extraCookie) throws IOException {
        String headers = "Content-Length: " + file.length() + "\r\nCache-Control: no-store\r\n" + (extraCookie == null ? "" : extraCookie);
        writeResponse(out, 200, "OK", type, null, headers);
        if (head) return;
        try (InputStream is = new BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] buf = new byte[65536]; int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void json(OutputStream out, int code, String body) throws IOException {
        writeResponse(out, code, code == 200 ? "OK" : "Error", "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), "Cache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n");
    }

    private static void writeResponse(OutputStream out, int code, String text, String type, byte[] body, String extra) throws IOException {
        boolean hasBody = body != null;
        if (body == null) body = new byte[0];
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(text).append("\r\nConnection: close\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        if (hasBody) h.append("Content-Length: ").append(body.length).append("\r\n");
        if (extra != null) h.append(extra);
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static Request readRequest(InputStream in) throws IOException {
        String line = readLine(in, 8192);
        if (line == null || line.isEmpty()) return null;
        String[] p = line.split(" ", 3);
        if (p.length != 3) return null;
        String target = p[1];
        int q = target.indexOf('?');
        String rawPath = q >= 0 ? target.substring(0, q) : target;
        String rawQuery = q >= 0 ? target.substring(q + 1) : "";
        Request r = new Request(p[0].toUpperCase(Locale.ROOT), URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()));
        if (!rawQuery.isEmpty()) for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            r.query.put(URLDecoder.decode(k, StandardCharsets.UTF_8.name()), URLDecoder.decode(v, StandardCharsets.UTF_8.name()));
        }
        String h;
        while ((h = readLine(in, 16384)) != null && !h.isEmpty()) {
            int c = h.indexOf(':');
            if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
        }
        return r;
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > max) throw new IOException("Header too large");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.UTF_8.name());
    }

    private static long parseLength(String value) throws IOException {
        if (value == null) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { throw new IOException("Invalid content length", e); }
    }

    private static void copyLimited(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[65536]; long remaining = length; int n;
        while (remaining != 0 && (n = in.read(buf, 0, remaining < 0 ? buf.length : (int)Math.min(buf.length, remaining))) != -1) {
            out.write(buf, 0, n);
            if (remaining > 0) remaining -= n;
        }
        if (remaining > 0) throw new IOException("Unexpected end of upload");
    }

    private static byte[] readBody(InputStream in, long length, int max) throws IOException {
        if (length > max) throw new IOException("Request body too large");
        ByteArrayOutputStream b = new ByteArrayOutputStream((int)Math.max(0, length));
        copyLimited(in, b, length);
        if (b.size() > max) throw new IOException("Request body too large");
        return b.toByteArray();
    }

    private static void deleteTree(File f) throws IOException {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteTree(c);
        }
        if (!f.delete() && f.exists()) throw new IOException("Cannot delete " + f);
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (Files.isSymbolicLink(src.toPath())) throw new IOException("Symbolic links are not supported");
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create destination");
            File[] children = src.listFiles();
            if (children != null) for (File c : children) copyTree(c, new File(dst, c.getName()));
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Cannot create destination parent");
            Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isDescendant(File candidate, File ancestor) throws IOException {
        String a = ancestor.getCanonicalPath();
        String c = candidate.getCanonicalPath();
        return !a.equals(c) && c.startsWith(a + File.separator);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8), y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int diff = 0; for (int i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
        return diff == 0;
    }

    private static String jsonQuote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\"' -> b.append("\\\"");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        return b.append('\"').toString();
    }
    private static String js(String s) { return jsonQuote(s); }
    private static String html(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    private static final class Request {
        final String method, path;
        final java.util.Map<String,String> headers = new java.util.HashMap<>();
        final java.util.Map<String,String> query = new java.util.HashMap<>();
        Request(String method, String path) { this.method = method; this.path = path.isEmpty() ? "/" : path; }
    }
}
