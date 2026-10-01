package untrusted.manager.um.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Portable, user-initiated configuration backup. Secrets and network credentials are excluded. */
public final class CloudBackupManager {
    public static final int FORMAT_VERSION = 1;
    public static final String FILE_NAME = "Untrusted-Manager-Backup.json";

    private CloudBackupManager() {}

    public static Uri createBackup(Context context, Uri treeUri) throws Exception {
        if (context == null || treeUri == null) throw new IllegalArgumentException("Missing destination");
        JSONObject root = new JSONObject();
        root.put("format", "untrusted-manager-backup");
        root.put("version", FORMAT_VERSION);
        root.put("createdAt", System.currentTimeMillis());
        JSONObject prefsObject = new JSONObject();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSensitiveKey(key) || value == null) continue;
            JSONObject item = encodeValue(value);
            if (item != null) prefsObject.put(key, item);
        }
        root.put("preferences", prefsObject);

        Uri existing = findChild(context, treeUri, FILE_NAME);
        if (existing != null) {
            try { context.getContentResolver().delete(existing, null, null); } catch (Exception ignored) {}
        }
        Uri file = DocumentsContract.createDocument(context.getContentResolver(), treeUri,
                "application/json", FILE_NAME);
        if (file == null) throw new IllegalStateException("Provider refused file creation");
        try (OutputStream out = context.getContentResolver().openOutputStream(file, "w")) {
            if (out == null) throw new IllegalStateException("Could not open backup destination");
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write(root.toString(2));
            writer.flush();
        }
        return file;
    }

    public static int restoreBackup(Context context, Uri backupUri) throws Exception {
        if (context == null || backupUri == null) throw new IllegalArgumentException("Missing backup");
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getContentResolver().openInputStream(backupUri), StandardCharsets.UTF_8))) {
            if (reader == null) throw new IllegalStateException("Could not open backup");
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                if (json.length() + n > 8 * 1024 * 1024) throw new IllegalArgumentException("Backup is too large");
                json.append(buffer, 0, n);
            }
        }
        JSONObject root = new JSONObject(json.toString());
        if (!"untrusted-manager-backup".equals(root.optString("format"))
                || root.optInt("version", -1) != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported backup format");
        }
        JSONObject prefsObject = root.optJSONObject("preferences");
        if (prefsObject == null) throw new IllegalArgumentException("Missing preferences");

        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        int restored = 0;
        JSONArray names = prefsObject.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, null);
                if (key == null || isSensitiveKey(key)) continue;
                JSONObject item = prefsObject.optJSONObject(key);
                if (item == null) continue;
                if (decodeInto(editor, key, item)) restored++;
            }
        }
        if (!editor.commit()) throw new IllegalStateException("Could not commit restored settings");
        return restored;
    }

    private static JSONObject encodeValue(Object value) throws Exception {
        JSONObject out = new JSONObject();
        if (value instanceof String) { out.put("type", "string"); out.put("value", value); }
        else if (value instanceof Boolean) { out.put("type", "boolean"); out.put("value", value); }
        else if (value instanceof Integer || value instanceof Long) { out.put("type", "long"); out.put("value", ((Number) value).longValue()); }
        else if (value instanceof Float) { out.put("type", "float"); out.put("value", ((Float) value).doubleValue()); }
        else if (value instanceof Double) { out.put("type", "double"); out.put("value", value); }
        else if (value instanceof java.util.Set) {
            out.put("type", "string_set");
            JSONArray array = new JSONArray();
            for (Object item : (java.util.Set<?>) value) if (item != null) array.put(String.valueOf(item));
            out.put("value", array);
        } else return null;
        return out;
    }

    private static boolean decodeInto(SharedPreferences.Editor editor, String key, JSONObject item) {
        try {
            String type = item.optString("type", "");
            if ("string".equals(type)) editor.putString(key, item.optString("value", ""));
            else if ("boolean".equals(type)) editor.putBoolean(key, item.optBoolean("value", false));
            else if ("long".equals(type)) editor.putLong(key, item.getLong("value"));
            else if ("float".equals(type)) editor.putFloat(key, (float) item.getDouble("value"));
            else if ("double".equals(type)) editor.putString(key, Double.toString(item.getDouble("value")));
            else if ("string_set".equals(type)) {
                JSONArray array = item.optJSONArray("value");
                java.util.HashSet<String> set = new java.util.HashSet<>();
                if (array != null) for (int i = 0; i < array.length(); i++) set.add(array.optString(i, ""));
                editor.putStringSet(key, set);
            } else return false;
            return true;
        } catch (Exception ignored) { return false; }
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return true;
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return k.contains("password") || k.contains("passwd") || k.contains("passphrase")
                || k.contains("secret") || k.contains("token") || k.contains("credential")
                || k.contains("access_key") || k.contains("private_key") || k.equals("keypath")
                || k.contains("su_command") || k.contains("auth") || k.contains("cookie");
    }

    private static Uri findChild(Context context, Uri treeUri, String name) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri));
            try (android.database.Cursor c = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c == null) return null;
                int id = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int display = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                while (c.moveToNext()) {
                    if (display >= 0 && name.equals(c.getString(display)) && id >= 0) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(id));
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
