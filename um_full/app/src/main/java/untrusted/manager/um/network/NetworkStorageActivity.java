package untrusted.manager.um.network;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Unified manager for saved network-storage locations. Credentials remain in each protocol's secure/profile flow. */
public class NetworkStorageActivity extends AppCompatActivity {
    private static final String PREF = "um_network_profiles";
    private final List<Profile> profiles = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private final List<String> labels = new ArrayList<>();

    private record Profile(String id, String name, String type, String endpoint) {}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle("Network Storage");
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> finish());
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        MaterialButton add = new MaterialButton(this); add.setText("Add"); add.setOnClickListener(v -> addProfile());
        MaterialButton refresh = new MaterialButton(this); refresh.setText("Refresh"); refresh.setOnClickListener(v -> load());
        MaterialButton transfers = new MaterialButton(this); transfers.setText("Transfers"); transfers.setOnClickListener(v -> startActivity(new Intent(this, NetworkTransferActivity.class)));
        actions.addView(add, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(transfers, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);

        TextView hint = new TextView(this);
        hint.setText("Saved locations for SMB, SFTP, WebDAV and S3. Credentials are managed by the individual protocol clients.");
        hint.setPadding(16, 12, 16, 12);
        root.addView(hint);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> open(profiles.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { menu(pos); return true; });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        profiles.clear(); labels.clear();
        boolean migratedIds = false;
        try {
            JSONArray a = new JSONArray(PreferenceManager.getDefaultSharedPreferences(this).getString(PREF, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                String id = o.optString("id", "");
                if (id.isEmpty()) {
                    id = java.util.UUID.randomUUID().toString();
                    o.put("id", id);
                    migratedIds = true;
                }
                String name = o.optString("name", "Location");
                String type = o.optString("type", "sftp").toLowerCase(java.util.Locale.ROOT);
                String endpoint = o.optString("endpoint", "");
                profiles.add(new Profile(id, name, type, endpoint));
                labels.add(name + "\n" + type.toUpperCase() + (endpoint.isEmpty() ? "" : " • " + endpoint));
            }
            // IDs are part of the persistent identity of a saved location. Persist the
            // migration immediately; otherwise a legacy entry would receive a new ID
            // on every launch and lose access to its profile-scoped credentials.
            if (migratedIds) {
                save();
            }
        } catch (Exception ignored) { save(); }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void save() {
        JSONArray a = new JSONArray();
        for (Profile p : profiles) {
            JSONObject o = new JSONObject();
            try { o.put("id", p.id()); o.put("name", p.name()); o.put("type", p.type()); o.put("endpoint", p.endpoint()); } catch (Exception ignored) {}
            a.put(o);
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit().putString(PREF, a.toString()).apply();
    }

    private void addProfile() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(16, 8, 16, 8);
        EditText name = field("Display name");
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"SFTP", "SMB", "WebDAV", "S3"}));
        EditText endpoint = field("Endpoint / host / URL");
        box.addView(name); box.addView(type); box.addView(endpoint);
        new AlertDialog.Builder(this).setTitle("Add network location").setView(box)
            .setPositiveButton("Save", (d,w) -> {
                String n = name.getText().toString().trim(), e = endpoint.getText().toString().trim();
                String t = ((String) type.getSelectedItem()).toLowerCase(java.util.Locale.ROOT);
                if (!validateProfile(n, t, e)) return;
                profiles.add(new Profile(java.util.UUID.randomUUID().toString(), n, t, e)); save(); load();
            }).setNegativeButton("Cancel", null).show();
    }

    private EditText field(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); return e; }

    private void open(Profile p) {
        Class<?> c = switch (p.type()) {
            case "smb" -> SmbActivity.class;
            case "webdav" -> WebDavActivity.class;
            case "s3" -> S3Activity.class;
            default -> SftpActivity.class;
        };
        Intent intent = new Intent(this, c);
        intent.putExtra("profile_id", p.id());
        if (!p.endpoint().isEmpty()) {
            intent.putExtra("profile_endpoint", p.endpoint());
            if ("sftp".equals(p.type())) {
                String raw = p.endpoint().trim();
                if (raw.startsWith("sftp://")) raw = raw.substring(7);
                int slash = raw.indexOf('/');
                String authority = slash >= 0 ? raw.substring(0, slash) : raw;
                int colon = authority.lastIndexOf(':');
                if (colon > 0 && colon < authority.length() - 1) {
                    intent.putExtra("profile_host", authority.substring(0, colon));
                    intent.putExtra("profile_port", authority.substring(colon + 1));
                } else {
                    intent.putExtra("profile_host", authority);
                    intent.putExtra("profile_port", "22");
                }
            }
        }
        startActivity(intent);
    }

    private void menu(int pos) {
        new AlertDialog.Builder(this).setTitle(profiles.get(pos).name())
            .setItems(new String[]{"Open", "Edit", "Delete"}, (d,w) -> {
                if (w == 0) open(profiles.get(pos));
                else if (w == 1) editProfile(pos);
                else { String id=profiles.get(pos).id(); clearProfileCredentials(id); profiles.remove(pos); save(); load(); }
            }).show();
    }

    private void editProfile(int pos) {
        Profile old = profiles.get(pos);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(16, 8, 16, 8);
        EditText name = field(old.name());
        Spinner type = new Spinner(this);
        type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"SFTP", "SMB", "WebDAV", "S3"}));
        int selected = java.util.Arrays.asList("sftp", "smb", "webdav", "s3").indexOf(old.type());
        type.setSelection(Math.max(0, selected));
        EditText endpoint = field(old.endpoint());
        box.addView(name); box.addView(type); box.addView(endpoint);
        new AlertDialog.Builder(this).setTitle("Edit network location").setView(box)
            .setPositiveButton("Save", (d,w) -> {
                String n = name.getText().toString().trim(), e = endpoint.getText().toString().trim();
                String t = ((String) type.getSelectedItem()).toLowerCase(java.util.Locale.ROOT);
                if (!validateProfile(n, t, e)) return;
                if (!old.type().equals(t)) clearProfileCredentials(old.id());
                profiles.set(pos, new Profile(old.id(), n, t, e)); save(); load();
            }).setNegativeButton("Cancel", null).show();
    }

    private boolean validateProfile(String name, String type, String endpoint) {
        if (name.isEmpty()) { toast("Display name required"); return false; }
        if (endpoint.isEmpty()) { toast("Endpoint required"); return false; }
        String e = endpoint.toLowerCase(java.util.Locale.ROOT);
        if (type.equals("sftp") && !(e.startsWith("sftp://") || !endpoint.contains("://"))) { toast("Use an SFTP host or sftp:// URL"); return false; }
        if (type.equals("webdav") && !(e.startsWith("http://") || e.startsWith("https://"))) { toast("WebDAV requires an HTTP or HTTPS URL"); return false; }
        if (type.equals("smb") && !(e.startsWith("smb://") || !endpoint.contains("://"))) { toast("Use an SMB host or smb:// URL"); return false; }
        if (type.equals("s3") && !(e.startsWith("http://") || e.startsWith("https://") || !endpoint.contains("://"))) { toast("Use an S3 endpoint or HTTP/HTTPS URL"); return false; }
        return true;
    }

    private void clearProfileCredentials(String id) {
        if (id == null || id.isEmpty()) return;
        String[] bases = {"sftp_profile", "smb_profile", "webdav_profiles", "s3_profile"};
        for (String base : bases) getSharedPreferences(base + "." + id, MODE_PRIVATE).edit().clear().apply();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
