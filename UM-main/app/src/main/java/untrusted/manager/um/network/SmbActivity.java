package untrusted.manager.um.network;

import untrusted.manager.um.utils.EdgeToEdgeUtil;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SMB2/SMB3 network-storage browser with a persistent connection profile. */
public class SmbActivity extends AppCompatActivity {
    private static final int PICK = 91;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<SmbClient.Entry> entries = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView path, status;
    private SmbClient client;
    private String current = "/";
    private android.content.SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(profilePrefsName("smb_profile"), MODE_PRIVATE); SecureNetworkPrefs.migrate(this, prefs, "pass");
        build();
    }

    private String profilePrefsName(String base) { String id=getIntent().getStringExtra("profile_id"); return id==null||id.isEmpty()?base:base+"."+id; }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle("SMB Storage");
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> finish());
        root.addView(bar);

        LinearLayout actions = new LinearLayout(this);
        MaterialButton connect = button("Connect");
        MaterialButton upload = button("Upload");
        MaterialButton mkdir = button("New folder");
        connect.setOnClickListener(v -> connectDialog());
        upload.setOnClickListener(v -> pick());
        mkdir.setOnClickListener(v -> showMkdir());
        actions.addView(connect, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(upload, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(mkdir, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(actions);

        path = new TextView(this);
        path.setPadding(16, 12, 16, 8);
        path.setOnClickListener(v -> goUp());
        root.addView(path);
        status = new TextView(this);
        status.setPadding(16, 4, 16, 8);
        root.addView(status);

        list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            SmbClient.Entry entry = entries.get(pos);
            if (entry.directory) {
                current = childPath(current, entry.name);
                load();
            } else {
                download(entry);
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showEntryMenu(entries.get(pos));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); EdgeToEdgeUtil.applyContentInsets(this);
        showPath();
    }

    private MaterialButton button(String text) {
        MaterialButton button = new MaterialButton(this);
        button.setText(text);
        return button;
    }

    private EditText field(String value, String hint, boolean secret) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setHint(hint);
        edit.setText(value);
        if (secret) edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return edit;
    }

    private void connectDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(16, 8, 16, 8);
        EditText url = field(getIntent().getStringExtra("profile_endpoint") != null ? getIntent().getStringExtra("profile_endpoint") : prefs.getString("url", "smb://192.168.1.10/share/"), "smb://server/share/", false);
        EditText domain = field(prefs.getString("domain", ""), "Domain (optional)", false);
        EditText user = field(prefs.getString("user", ""), "Username", false);
        EditText pass = field(SecureNetworkPrefs.get(this, prefs, "pass", ""), "Password", true);
        box.addView(url); box.addView(domain); box.addView(user); box.addView(pass);
        new AlertDialog.Builder(this)
                .setTitle("Connect SMB")
                .setView(box)
                .setPositiveButton("Connect", (d, w) -> connect(url.getText().toString(), domain.getText().toString(), user.getText().toString(), pass.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connect(String url, String domain, String user, String pass) {
        status.setText("Connecting…");
        io.execute(() -> {
            try {
                SmbClient next = new SmbClient(url, domain, user, pass);
                next.list("/");
                if (client != null) client.close();
                client = next;
                android.content.SharedPreferences.Editor pe=prefs.edit(); pe.putString("url", url).putString("domain", domain).putString("user", user); SecureNetworkPrefs.put(pe,"pass",pass); pe.apply();
                current = "/";
                runOnUiThread(this::load);
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Connection failed: " + e.getMessage()));
            }
        });
    }

    private void showPath() {
        path.setText("Path: " + current + "  (tap to go up)");
    }

    private void goUp() {
        if ("/".equals(current)) return;
        int index = current.lastIndexOf('/', current.length() - 2);
        current = index <= 0 ? "/" : current.substring(0, index + 1);
        load();
    }

    private static String childPath(String parent, String name) {
        return "/".equals(parent) ? "/" + name + "/" : parent + name + "/";
    }

    private void load() {
        showPath();
        if (client == null) {
            status.setText("Not connected");
            return;
        }
        status.setText("Loading…");
        io.execute(() -> {
            try {
                List<SmbClient.Entry> got = client.list(current);
                runOnUiThread(() -> {
                    entries.clear(); entries.addAll(got); names.clear();
                    for (SmbClient.Entry e : got) names.add((e.directory ? "📁 " : "📄 ") + e.name + (e.hidden ? " [hidden]" : ""));
                    adapter.notifyDataSetChanged();
                    status.setText(got.size() + " items");
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Load failed: " + e.getMessage()));
            }
        });
    }

    private void pick() {
        if (client == null) { toast("Connect first"); return; }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        NetworkTransferQueue.get().submit("SMB upload", () -> {
            File tmp = new File(getCacheDir(), "smb-upload-" + System.nanoTime());
            try {
                try (java.io.InputStream in = getContentResolver().openInputStream(uri); java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    if (in == null) throw new Exception("Cannot open source");
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
                String name = new File(uri.getPath() == null ? "upload" : uri.getPath()).getName();
                if (name.contains(":")) name = "upload";
                client.upload(tmp, current + name);
                if (!tmp.delete()) tmp.deleteOnExit();
                runOnUiThread(this::load);
            } catch (Exception e) {
                tmp.delete();
                runOnUiThread(() -> status.setText("Upload failed: " + e.getMessage()));
            }
        });
    }

    private void download(SmbClient.Entry entry) {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) dir.mkdirs();
        File output = new File(dir, entry.name);
        if (output.exists()) output = new File(dir, System.currentTimeMillis() + "-" + entry.name);
        File target = output;
        status.setText("Downloading…");
        NetworkTransferQueue.get().submit("Download", () -> {
            try {
                client.download(entry.url, target);
                runOnUiThread(() -> status.setText("Downloaded: " + target.getAbsolutePath()));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Download failed: " + e.getMessage()));
            }
        });
    }

    private void showMkdir() {
        if (client == null) { toast("Connect first"); return; }
        EditText name = field("", "Folder name", false);
        new AlertDialog.Builder(this).setTitle("New SMB folder").setView(name)
                .setPositiveButton("Create", (d, w) -> io.execute(() -> {
                    try {
                        String value = name.getText().toString().trim();
                        validateName(value);
                        client.mkdir(current + value);
                        runOnUiThread(this::load);
                    } catch (Exception e) { runOnUiThread(() -> status.setText("Create failed: " + e.getMessage())); }
                })).setNegativeButton("Cancel", null).show();
    }

    private void showEntryMenu(SmbClient.Entry entry) {
        new AlertDialog.Builder(this).setTitle(entry.name).setItems(
                entry.directory ? new String[]{"Delete", "Rename"} : new String[]{"Download", "Delete", "Rename"},
                (d, which) -> {
                    if (!entry.directory && which == 0) download(entry);
                    else if (entry.directory ? which == 0 : which == 1) delete(entry);
                    else rename(entry);
                }).show();
    }

    private void delete(SmbClient.Entry entry) {
        new AlertDialog.Builder(this).setTitle("Delete " + entry.name + "?").setPositiveButton("Delete", (d, w) -> io.execute(() -> {
            try { client.delete(entry.url); runOnUiThread(this::load); }
            catch (Exception e) { runOnUiThread(() -> status.setText("Delete failed: " + e.getMessage())); }
        })).setNegativeButton("Cancel", null).show();
    }

    private void rename(SmbClient.Entry entry) {
        EditText name = field(entry.name, "New name", false);
        new AlertDialog.Builder(this).setTitle("Rename").setView(name).setPositiveButton("Rename", (d, w) -> io.execute(() -> {
            try { String value = name.getText().toString().trim(); validateName(value); client.rename(entry.url, current + value); runOnUiThread(this::load); }
            catch (Exception e) { runOnUiThread(() -> status.setText("Rename failed: " + e.getMessage())); }
        })).setNegativeButton("Cancel", null).show();
    }

    private static void validateName(String name) {
        if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..") || name.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid name");
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        if (client != null) client.close();
        io.shutdownNow();
        super.onDestroy();
    }
}
