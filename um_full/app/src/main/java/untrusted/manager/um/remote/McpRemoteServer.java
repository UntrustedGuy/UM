package untrusted.manager.um.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small dependency-free MCP Streamable HTTP server for UM's local file tools.
 * The filesystem boundary is the same canonical root used by remote management.
 */
public final class McpRemoteServer {
    private static final long MAX_READ = 4L * 1024L * 1024L;
    private static final long MAX_WRITE = 4L * 1024L * 1024L;
    private static final int MAX_SEARCH_RESULTS = 500;
    private static final String PROTOCOL = "2025-11-25";

    private final File root;
    private final String token;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "UM-MCP");
        t.setDaemon(true);
        return t;
    });
    private ServerSocket socket;
    private Thread acceptThread;
    private int port;

    public McpRemoteServer(File root, int requestedPort, String token) throws Exception {
        if (root == null) throw new IllegalArgumentException("Root is required");
        this.root = root.getCanonicalFile();
        if (!this.root.isDirectory() && !this.root.mkdirs() && !this.root.isDirectory()) {
            throw new IllegalArgumentException("Cannot create MCP root");
        }
        this.token = token == null ? "" : token.trim();
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("0.0.0.0", requestedPort <= 0 ? 0 : requestedPort));
        port = socket.getLocalPort();
    }

    public synchronized void start() {
        if (running.getAndSet(true)) return;
        acceptThread = new Thread(() -> {
            try {
                while (running.get()) {
                    Socket s = socket.accept();
                    workers.execute(() -> handle(s));
                }
            } catch (Exception ignored) {
            } finally {
                running.set(false);
            }
        }, "UM-MCP-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        try { socket.close(); } catch (Exception ignored) { }
        workers.shutdownNow();
    }

    public boolean isRunning() { return running.get(); }
    public int getPort() { return port; }
    public File getRoot() { return root; }

    private void handle(Socket s) {
        try (Socket socket = s) {
            socket.setSoTimeout(60_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            Request req = readRequest(in);
            if (req == null) return;
            if (!authorized(req)) {
                response(out, 401, "Unauthorized", jsonError(null, -32001, "Bearer token required"), "WWW-Authenticate: Bearer\r\n");
                return;
            }
            if ("OPTIONS".equals(req.method)) {
                response(out, 204, "No Content", null, "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type, MCP-Protocol-Version, MCP-Session-Id\r\nAccess-Control-Allow-Methods: POST, GET, DELETE, OPTIONS\r\n");
                return;
            }
            if (!"POST".equals(req.method) || !"/mcp".equals(req.path)) {
                response(out, 404, "Not Found", jsonError(null, -32601, "MCP endpoint not found"), null);
                return;
            }
            JsonElement parsed;
            try { parsed = JsonParser.parseString(new String(req.body, StandardCharsets.UTF_8)); }
            catch (Exception e) { response(out, 400, "Bad Request", jsonError(null, -32700, "Invalid JSON"), null); return; }
            if (!parsed.isJsonObject()) { response(out, 400, "Bad Request", jsonError(null, -32600, "Request must be an object"), null); return; }
            JsonObject request = parsed.getAsJsonObject();
            JsonElement id = request.get("id");
            String method = request.has("method") && request.get("method").isJsonPrimitive() ? request.get("method").getAsString() : "";
            JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
            JsonObject result = dispatch(id, method, params);
            if (result != null) response(out, 200, "OK", result.toString(), "MCP-Protocol-Version: " + PROTOCOL + "\r\n");
        } catch (Exception ignored) {
        }
    }

    private boolean authorized(Request req) {
        if (token.isEmpty()) return true;
        String auth = req.headers.get("authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return constantTime(token, auth.substring(7).trim());
    }

    private JsonObject dispatch(JsonElement id, String method, JsonObject params) throws Exception {
        if ("notifications/initialized".equals(method) || "notifications/cancelled".equals(method)) return null;
        if ("ping".equals(method)) return result(id, new JsonObject());
        if ("initialize".equals(method)) {
            JsonObject r = new JsonObject();
            r.addProperty("protocolVersion", PROTOCOL);
            JsonObject capabilities = new JsonObject();
            capabilities.add("tools", new JsonObject());
            r.add("capabilities", capabilities);
            JsonObject info = new JsonObject(); info.addProperty("name", "Untrusted Manager"); info.addProperty("version", "1.0.9");
            r.add("serverInfo", info);
            return result(id, r);
        }
        if ("tools/list".equals(method)) return result(id, toolsList());
        if ("tools/call".equals(method)) {
            String name = params.has("name") ? params.get("name").getAsString() : "";
            JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject() ? params.getAsJsonObject("arguments") : new JsonObject();
            return result(id, callTool(name, args));
        }
        return error(id, -32601, "Method not found: " + method);
    }

    private JsonObject toolsList() {
        JsonArray tools = new JsonArray();
        tools.add(tool("um_list_files", "List files in a UM-accessible directory.", schema("path", "string", false)));
        tools.add(tool("um_read_file", "Read a UTF-8 text file, capped at 4 MiB.", schema("path", "string", true)));
        tools.add(tool("um_write_file", "Write a UTF-8 text file, capped at 4 MiB.", schema("path", "string", true, "content", "string", true)));
        tools.add(tool("um_create_directory", "Create a directory inside the UM MCP root.", schema("path", "string", true)));
        tools.add(tool("um_delete", "Delete a file or empty/recursive directory inside the UM MCP root.", schema("path", "string", true)));
        tools.add(tool("um_copy", "Copy a file or directory inside the UM MCP root.", schema("source", "string", true, "destination", "string", true)));
        tools.add(tool("um_move", "Move a file or directory inside the UM MCP root.", schema("source", "string", true, "destination", "string", true)));
        tools.add(tool("um_search_files", "Search file names recursively inside the UM MCP root.", schema("query", "string", true, "path", "string", false)));
        JsonObject r = new JsonObject(); r.add("tools", tools); return r;
    }

    private JsonObject callTool(String name, JsonObject a) throws Exception {
        JsonObject r = new JsonObject();
        JsonArray content = new JsonArray();
        try {
            String text;
            switch (name) {
                case "um_list_files": text = listFiles(arg(a, "path", "/")); break;
                case "um_read_file": text = readFile(arg(a, "path", null)); break;
                case "um_write_file": text = writeFile(arg(a, "path", null), arg(a, "content", null)); break;
                case "um_create_directory": text = createDirectory(arg(a, "path", null)); break;
                case "um_delete": text = delete(arg(a, "path", null)); break;
                case "um_copy": text = copy(arg(a, "source", null), arg(a, "destination", null)); break;
                case "um_move": text = move(arg(a, "source", null), arg(a, "destination", null)); break;
                case "um_search_files": text = search(arg(a, "query", null), arg(a, "path", "/")); break;
                default: return errorResult("Unknown tool: " + name);
            }
            JsonObject item = new JsonObject(); item.addProperty("type", "text"); item.addProperty("text", text); content.add(item);
            r.add("content", content); r.addProperty("isError", false); return r;
        } catch (Exception e) {
            return errorResult(e.getMessage() == null ? "Operation failed" : e.getMessage());
        }
    }

    private String listFiles(String path) throws Exception {
        File dir = resolve(path, false); if (!dir.isDirectory()) throw new Exception("Not a directory");
        File[] files = dir.listFiles(); if (files == null) return "[]";
        List<File> list = new ArrayList<>(); Collections.addAll(list, files);
        list.sort(Comparator.comparing(File::isFile).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        JsonArray arr = new JsonArray();
        for (File f : list) { JsonObject o=new JsonObject(); o.addProperty("name",f.getName()); o.addProperty("path",relative(f)); o.addProperty("directory",f.isDirectory()); o.addProperty("size",f.isFile()?f.length():0); o.addProperty("modified",f.lastModified()); arr.add(o); }
        return arr.toString();
    }

    private String readFile(String path) throws Exception {
        File f=resolve(path,false); if(!f.isFile()) throw new Exception("Not a file"); if(f.length()>MAX_READ) throw new Exception("File exceeds 4 MiB read limit");
        byte[] b=new byte[(int)f.length()]; try(InputStream in=new FileInputStream(f)){int off=0,n;while(off<b.length&&(n=in.read(b,off,b.length-off))>0)off+=n;}
        return new String(b,StandardCharsets.UTF_8);
    }

    private String writeFile(String path,String content) throws Exception {
        if(content==null) throw new Exception("content is required"); byte[] b=content.getBytes(StandardCharsets.UTF_8); if(b.length>MAX_WRITE) throw new Exception("Content exceeds 4 MiB write limit");
        File f=resolve(path,true); if(f.equals(root)||f.isDirectory()) throw new Exception("File path required"); File p=f.getParentFile(); if(p==null||!p.isDirectory()) throw new Exception("Parent directory does not exist");
        File tmp=File.createTempFile(".um-mcp-", ".part", p); try{try(OutputStream out=new FileOutputStream(tmp)){out.write(b);out.flush();} if(!tmp.renameTo(f)){try(OutputStream out=new FileOutputStream(f)){out.write(b);out.flush();}}}finally{if(tmp.exists())tmp.delete();}
        return "Wrote " + relative(f);
    }

    private String createDirectory(String path) throws Exception { File f=resolve(path,true); if(f.exists()) throw new Exception("Already exists"); if(!f.mkdirs()&&!f.isDirectory()) throw new Exception("Could not create directory"); return "Created " + relative(f); }

    private String delete(String path) throws Exception { File f=resolve(path,false); if(f.equals(root)) throw new Exception("Refusing to delete MCP root"); deleteTree(f); return "Deleted " + relative(f); }

    private String copy(String source,String destination) throws Exception { File s=resolve(source,false),d=resolve(destination,true); if(s.equals(root)) throw new Exception("Refusing to copy MCP root"); if(d.equals(s)||isDescendant(d,s)) throw new Exception("Destination is inside source"); copyTree(s,d); return "Copied " + relative(s) + " to " + relative(d); }
    private String move(String source,String destination) throws Exception { File s=resolve(source,false),d=resolve(destination,true); if(s.equals(root)) throw new Exception("Refusing to move MCP root"); if(d.equals(s)||isDescendant(d,s)) throw new Exception("Destination is inside source"); copyTree(s,d); deleteTree(s); return "Moved " + relative(s) + " to " + relative(d); }

    private String search(String query,String path) throws Exception { if(query==null||query.trim().isEmpty()) throw new Exception("query is required"); File start=resolve(path==null?"/":path,false); if(!start.isDirectory()) throw new Exception("Search root is not a directory"); String q=query.toLowerCase(Locale.ROOT); JsonArray arr=new JsonArray(); ArrayDeque<File> stack=new ArrayDeque<>(); stack.push(start); while(!stack.isEmpty()&&arr.size()<MAX_SEARCH_RESULTS){File f=stack.pop(); File[] fs=f.listFiles(); if(fs==null)continue; for(File x:fs){if(x.getName().toLowerCase(Locale.ROOT).contains(q)){JsonObject o=new JsonObject();o.addProperty("path",relative(x));o.addProperty("directory",x.isDirectory());o.addProperty("size",x.isFile()?x.length():0);arr.add(o);if(arr.size()>=MAX_SEARCH_RESULTS)break;} if(x.isDirectory()&&!java.nio.file.Files.isSymbolicLink(x.toPath()))stack.push(x);}} return arr.toString(); }

    private File resolve(String path, boolean allowMissing) throws Exception {
        if(path==null) throw new Exception("path is required"); String p=path.trim(); if(p.isEmpty()) p="/"; if(p.indexOf('\0')>=0) throw new SecurityException("NUL in path");
        File f=new File(root,p.startsWith("/")?p.substring(1):p).getCanonicalFile(); String base=root.getPath(); String fp=f.getPath(); if(!fp.equals(base)&&!fp.startsWith(base+File.separator)) throw new SecurityException("Path outside MCP root");
        if(!allowMissing && !f.exists()) throw new Exception("Path not found: "+p); return f;
    }
    private String relative(File f) throws Exception { String p=f.getCanonicalPath(); String base=root.getCanonicalPath(); if(p.equals(base))return "/"; return "/"+p.substring(base.length()+1).replace(File.separatorChar,'/'); }
    private static boolean isDescendant(File child,File parent)throws Exception{String c=child.getCanonicalPath(),p=parent.getCanonicalPath();return c.startsWith(p+File.separator);}
    private static void copyTree(File s,File d)throws Exception{if(d.exists())throw new Exception("Destination already exists");if(s.isDirectory()){if(!d.mkdirs()&&!d.isDirectory())throw new Exception("Cannot create destination");File[] fs=s.listFiles();if(fs!=null)for(File f:fs)copyTree(f,new File(d,f.getName()));}else{File p=d.getParentFile();if(p!=null&&!p.exists()&&!p.mkdirs())throw new Exception("Cannot create destination parent");try(InputStream in=new FileInputStream(s);OutputStream out=new FileOutputStream(d)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}}
    private static void deleteTree(File f)throws Exception{if(f.isDirectory()&&!java.nio.file.Files.isSymbolicLink(f.toPath())){File[] fs=f.listFiles();if(fs!=null)for(File x:fs)deleteTree(x);}if(!f.delete()&&f.exists())throw new Exception("Cannot delete "+f);}

    private static JsonObject schema(Object... values){JsonObject p=new JsonObject(),props=new JsonObject();JsonArray req=new JsonArray();for(int i=0;i<values.length;i+=3){String n=String.valueOf(values[i]),type=String.valueOf(values[i+1]);boolean required=Boolean.TRUE.equals(values[i+2]);JsonObject s=new JsonObject();s.addProperty("type",type);props.add(n,s);if(required)req.add(new JsonPrimitive(n));}p.add("type",new JsonPrimitive("object"));p.add("properties",props);p.add("required",req);return p;}
    private static JsonObject tool(String name,String desc,JsonObject schema){JsonObject t=new JsonObject();t.addProperty("name",name);t.addProperty("description",desc);t.add("inputSchema",schema);return t;}
    private static JsonObject result(JsonElement id,JsonObject r){JsonObject o=new JsonObject();o.addProperty("jsonrpc","2.0");if(id!=null)o.add("id",id);o.add("result",r);return o;}
    private static JsonObject error(JsonElement id,int code,String message){JsonObject o=new JsonObject();o.addProperty("jsonrpc","2.0");if(id!=null)o.add("id",id);JsonObject e=new JsonObject();e.addProperty("code",code);e.addProperty("message",message);o.add("error",e);return o;}
    private static JsonObject errorResult(String message){JsonObject o=new JsonObject();JsonArray c=new JsonArray();JsonObject x=new JsonObject();x.addProperty("type","text");x.addProperty("text",message);c.add(x);o.add("content",c);o.addProperty("isError",true);return o;}
    private static String jsonError(JsonElement id,int code,String message){return error(id,code,message).toString();}
    private static boolean constantTime(String a,String b){try{return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}catch(Exception e){return false;}}
    private static String arg(JsonObject o,String n,String d){JsonElement e=o.get(n);return e==null||e.isJsonNull()?d:e.getAsString();}

    private static Request readRequest(InputStream in)throws Exception{ByteArrayOutputStream h=new ByteArrayOutputStream();int a=0,b=0,c=0,d=0;while(true){int x=in.read();if(x<0)return null;h.write(x);a=b;b=c;c=d;d=x;if(a=='\r'&&b=='\n'&&c=='\r'&&d=='\n')break;if(h.size()>32768)throw new Exception("headers too large");}String hs=new String(h.toByteArray(),StandardCharsets.ISO_8859_1);String[] lines=hs.split("\\r\\n");String[] first=lines[0].split(" ",3);if(first.length<2)return null;Request r=new Request();r.method=first[0];String target=first[1];int q=target.indexOf('?');r.path=q>=0?target.substring(0,q):target;if(q>=0){for(String part:target.substring(q+1).split("&")){int eq=part.indexOf('=');if(eq>0)r.query.put(java.net.URLDecoder.decode(part.substring(0,eq),"UTF-8"),java.net.URLDecoder.decode(part.substring(eq+1),"UTF-8"));}}for(int i=1;i<lines.length;i++){int x=lines[i].indexOf(':');if(x>0)r.headers.put(lines[i].substring(0,x).trim().toLowerCase(Locale.ROOT),lines[i].substring(x+1).trim());}int len=0;try{len=Integer.parseInt(r.headers.getOrDefault("content-length","0"));}catch(Exception ignored){}if(len>MAX_WRITE)throw new Exception("body too large");r.body=new byte[len];int off=0;while(off<len){int n=in.read(r.body,off,len-off);if(n<0)throw new Exception("truncated body");off+=n;}return r;}
    private static void response(OutputStream out,int status,String text,String body,String extra)throws Exception{byte[] b=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);StringBuilder h=new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(text).append("\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ").append(b.length).append("\r\nConnection: close\r\n");if(extra!=null)h.append(extra);h.append("\r\n");out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));out.write(b);out.flush();}
    private static final class Request{String method,path;byte[] body;java.util.Map<String,String> headers=new java.util.HashMap<>();java.util.Map<String,String> query=new java.util.HashMap<>();}
}
