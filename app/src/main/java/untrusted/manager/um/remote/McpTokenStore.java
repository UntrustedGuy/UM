package untrusted.manager.um.remote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Named MCP bearer-token store. Tokens are persisted only when authentication is enabled. */
public final class McpTokenStore {
    private static final String PREFS = "mcp_tokens";
    private static final String KEY = "tokens";
    public record Token(String name, String value) {}
    private McpTokenStore() {}

    public static List<Token> get(Context c) {
        List<Token> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i); if(o==null) continue;
                String n=o.optString("name","").trim(), v=o.optString("value","").trim();
                if(!n.isEmpty() && v.length()>=16) out.add(new Token(n,v));
            }
        } catch(Exception ignored) {}
        return out;
    }
    public static Token create(Context c, String requestedName) throws Exception {
        String name = requestedName == null ? "" : requestedName.trim();
        if(name.isEmpty()) throw new IllegalArgumentException("Token name is required");
        List<Token> list=get(c); for(Token t:list) if(t.name().equalsIgnoreCase(name)) throw new IllegalArgumentException("Token name already exists");
        byte[] b=new byte[32]; new SecureRandom().nextBytes(b);
        String value=android.util.Base64.encodeToString(b,android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING);
        list.add(new Token(name,value)); save(c,list); return new Token(name,value);
    }
    public static void revoke(Context c,String name) throws Exception {
        List<Token> list=get(c); boolean removed=list.removeIf(t->t.name().equals(name)); if(!removed) throw new IllegalArgumentException("Token not found"); save(c,list);
    }
    public static boolean accepts(Context c,String value) {
        if(value==null) return false; for(Token t:get(c)) if(constantTime(t.value(),value.trim())) return true; return false;
    }
    public static void ensureLegacyToken(Context c,String value) throws Exception {
        if(value==null || value.length()<16) return; List<Token> list=get(c); for(Token t:list) if(constantTime(t.value(),value)) return;
        String name="Default"; int n=2; while(containsName(list,name)) name="Default " + n++;
        list.add(new Token(name,value)); save(c,list);
    }
    private static boolean containsName(List<Token> list,String name){for(Token t:list)if(t.name().equalsIgnoreCase(name))return true;return false;}
    private static void save(Context c,List<Token> list)throws Exception{JSONArray a=new JSONArray();for(Token t:list){JSONObject o=new JSONObject();o.put("name",t.name());o.put("value",t.value());a.put(o);}c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply();}
    private static boolean constantTime(String a,String b){return java.security.MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),b.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
