package untrusted.manager.um.tools;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import untrusted.manager.um.R;
import untrusted.manager.um.ui.activities.HexEditorActivity;
import untrusted.manager.um.ui.fragment.UnifiedEditorFragment;
import untrusted.manager.um.ui.dialogs.FilePickerDialog;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/**
 * dnSpy/ILSpy-style managed DLL browser.
 *
 * Managed assemblies are browsed as namespaces/types/members and reconstructed to C#.
 * Native/unmanaged PE DLLs remain available through the dedicated Hex Editor action.
 * The main pane intentionally stays a plain, scrollable C# source view.
 */
public final class DllEditorActivity extends AppCompatActivity implements UnifiedEditorFragment.EditorCallback {
    private final ArrayList<Node> allNodes = new ArrayList<>();
    private final ArrayList<Node> visibleNodes = new ArrayList<>();
    private ArrayAdapter<Node> adapter;
    private ListView tree;
    private EditText search;
    private FrameLayout editorContainer;
    private UnifiedEditorFragment editorFragment;
    private DotNetAssemblyParser parser;
    private File currentFile;
    private Node selectedNode;
    private final ExecutorService dllExecutor = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("DLL Editor");
        buildUi();
        String supplied = getIntent().getStringExtra("path");
        if (supplied != null && !supplied.isEmpty()) openFile(new File(supplied));
        else if (getIntent().getData() != null) {
            try { openFile(new File(getIntent().getData().getPath())); }
            catch (Throwable t) { Toast.makeText(this, "Unable to open DLL", Toast.LENGTH_SHORT).show(); }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK));

        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle("");
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> finish());
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton treeToggle = new MaterialButton(this);
        treeToggle.setText("☰");
        treeToggle.setContentDescription("Show assembly tree");
        treeToggle.setMinWidth(0);
        treeToggle.setMinimumWidth(0);
        treeToggle.setPadding(0, 0, 0, 0);
        treeToggle.setOnClickListener(v -> showTree(true));
        titleRow.addView(treeToggle, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView title = new TextView(this);
        title.setText("DLL Editor");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE));
        titleRow.addView(title, new LinearLayout.LayoutParams(-2, dp(48)));
        bar.addView(titleRow, new MaterialToolbar.LayoutParams(-2, dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(8), dp(4), dp(8), dp(4));
        addAction(actions, "Open", v -> pickDll());
        addAction(actions, "C#", v -> openSelectedSource());
        addAction(actions, "IL", v -> openSelectedIl());
        addAction(actions, "Hex", v -> openHex());
        addAction(actions, "Export", v -> exportAll());
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(52)));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search types, methods and fields");
        search.setPadding(dp(12), 0, dp(12), 0);
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));

        FrameLayout body = new FrameLayout(this);
        editorContainer = new FrameLayout(this);
        editorContainer.setId(View.generateViewId());
        body.addView(editorContainer, new FrameLayout.LayoutParams(-1, -1));

        // The assembly tree is an overlay, not a permanent split pane. The source
        // remains the only scrollable document area when the tree is closed.
        FrameLayout treePanel = new FrameLayout(this);
        treePanel.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, Color.DKGRAY));
        treePanel.setElevation(dp(10));

        LinearLayout treeLayout = new LinearLayout(this);
        treeLayout.setOrientation(LinearLayout.VERTICAL);
        treeLayout.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, Color.DKGRAY));

        LinearLayout treeHeader = new LinearLayout(this);
        treeHeader.setGravity(Gravity.CENTER_VERTICAL);
        treeHeader.setPadding(dp(12), 0, dp(4), 0);
        TextView treeTitle = new TextView(this);
        treeTitle.setText("Assembly");
        treeTitle.setTextSize(16);
        treeTitle.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        treeTitle.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE));
        treeHeader.addView(treeTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        MaterialButton treeClose = new MaterialButton(this);
        treeClose.setText("×");
        treeClose.setContentDescription("Close assembly tree");
        treeClose.setMinWidth(0);
        treeClose.setMinimumWidth(0);
        treeClose.setPadding(0, 0, 0, 0);
        treeClose.setOnClickListener(v -> showTree(false));
        treeHeader.addView(treeClose, new LinearLayout.LayoutParams(dp(48), dp(48)));
        treeLayout.addView(treeHeader, new LinearLayout.LayoutParams(-1, dp(48)));

        tree = new ListView(this);
        tree.setDivider(null);
        tree.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer, Color.DKGRAY));
        adapter = new ArrayAdapter<Node>(this, android.R.layout.simple_list_item_1, visibleNodes) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                Node n = getItem(position);
                v.setText(n == null ? "" : n.label);
                v.setTextSize(13);
                v.setTextColor(MaterialColors.getColor(DllEditorActivity.this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE));
                v.setBackgroundColor(Color.TRANSPARENT);
                v.setPadding(dp(n == null ? 8 : n.depth * 14 + 8), dp(7), dp(8), dp(7));
                return v;
            }
        };
        tree.setAdapter(adapter);
        treeLayout.addView(tree, new LinearLayout.LayoutParams(-1, 0, 1));
        treePanel.addView(treeLayout, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout.LayoutParams treeParams = new FrameLayout.LayoutParams(dp(340), -1, Gravity.START);
        treePanel.setVisibility(View.GONE);
        body.addView(treePanel, treeParams);
        this.treePanel = treePanel;

        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        EdgeToEdgeUtil.applyContentInsets(this);
        editorFragment = UnifiedEditorFragment.newInstance("", "DLL", "", UnifiedEditorFragment.TYPE_TEXT);
        editorFragment.setCallback(this);
        getSupportFragmentManager().beginTransaction().replace(editorContainer.getId(), editorFragment).commitNow();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable e) {}
        });
        tree.setOnItemClickListener((p, v, pos, id) -> {
            select(visibleNodes.get(pos));
            showTree(false);
        });
    }

    private FrameLayout treePanel;

    private void showTree(boolean show) {
        if (treePanel != null) treePanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void addAction(LinearLayout row, String title, View.OnClickListener click) {
        MaterialButton b = new MaterialButton(this);
        b.setText(title);
        b.setOnClickListener(click);
        row.addView(b, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void pickDll() {
        FilePickerDialog.Properties p = new FilePickerDialog.Properties();
        p.selection_type = FilePickerDialog.FILE_SELECT;
        p.preferenceKey = "dll_editor_managed";
        p.extensions = new String[]{"dll", "exe"};
        configureUmPicker(p);
        FilePickerDialog d = new FilePickerDialog(this, p);
        d.setTitle("Select managed DLL / EXE");
        d.setDialogSelectionListener(paths -> {
            if (paths != null && paths.length > 0) openFile(new File(paths[0]));
        });
        d.show();
    }

    private void configureUmPicker(FilePickerDialog.Properties p) {
        File root = new File(android.os.Environment.getExternalStorageDirectory(), "Untrusted Manager");
        if (!root.isDirectory()) root.mkdirs();
        if (root.isDirectory()) { p.offset = root; p.forceStartDirectory = true; }
    }

    private void openFile(File f) {
        if (f == null || !f.isFile()) { Toast.makeText(this, "DLL file not found", Toast.LENGTH_SHORT).show(); return; }
        currentFile = f;
        selectedNode = null;
        setEditorText("Loading managed assembly…", "loading.txt");
        dllExecutor.execute(() -> {
            DotNetAssemblyParser parsed = null;
            Throwable error = null;
            try { parsed = DotNetAssemblyParser.parse(f); } catch (Throwable e) { error = e; }
            final DotNetAssemblyParser result = parsed;
            final Throwable failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (failure != null || result == null) {
                    parser = null; allNodes.clear(); visibleNodes.clear(); adapter.notifyDataSetChanged();
                    String detail = failure == null || failure.getMessage() == null ? "Invalid CLR metadata." : failure.getMessage();
                    setEditorText("Unable to decompile this file as a managed .NET assembly.\n\n" + detail, "dll.txt");
                    Toast.makeText(this, "This DLL cannot be decompiled as a managed .NET assembly", Toast.LENGTH_LONG).show();
                    return;
                }
                parser = result;
                rebuildTree();
                if (!visibleNodes.isEmpty()) select(visibleNodes.get(0));
                else setEditorText("// Assembly contains no user-defined types.", "assembly.cs");
            });
        });
    }

    @Override protected void onDestroy() {
        dllExecutor.shutdownNow();
        super.onDestroy();
    }

    private void rebuildTree() {
        allNodes.clear();
        if (parser == null) return;
        for (DotNetAssemblyParser.TypeDef t : parser.types) {
            if ("<Module>".equals(t.name)) continue;
            addType(t, 0);
        }
        filter(search.getText() == null ? "" : search.getText().toString());
    }

    private void addType(DotNetAssemblyParser.TypeDef t, int depth) {
        Node tn = Node.type(t, depth); allNodes.add(tn);
        for (DotNetAssemblyParser.FieldDef f : t.fields) allNodes.add(Node.field(t, f, depth + 1));
        for (DotNetAssemblyParser.PropertyDef p : t.properties) allNodes.add(Node.property(t, p, depth + 1));
        for (DotNetAssemblyParser.MethodDef m : t.methods) allNodes.add(Node.method(t, m, depth + 1));
        for (DotNetAssemblyParser.TypeDef n : t.nested) addType(n, depth + 1);
    }

    private void filter(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        visibleNodes.clear();
        for (Node n : allNodes) if (needle.isEmpty() || n.matches(needle)) visibleNodes.add(n);
        adapter.notifyDataSetChanged();
    }

    private void select(Node n) {
        selectedNode = n;
        if (parser == null || n == null) return;
        if (n.type == Node.TYPE) setEditorText(parser.decompile(n.owner), n.owner.name + ".cs");
        else if (n.type == Node.METHOD) setEditorText(methodSource(n.method), n.method.name + ".cs");
        else if (n.type == Node.FIELD) setEditorText(fieldSource(n.field), n.field.name + ".cs");
        else if (n.type == Node.PROPERTY) setEditorText(propertySource(n.property), n.property.name + ".cs");
    }

    private String methodSource(DotNetAssemblyParser.MethodDef m) {
        if (m == null) return "";
        boolean noBody = (m.flags & 0x0400) != 0 || (m.flags & 0x2000) != 0 || m.rva == 0;
        if (noBody) return m.signature() + ";";
        String body = m.source == null ? "" : m.source;
        return m.signature() + "\n{\n" + indent(body, 4) + "\n}";
    }

    private String fieldSource(DotNetAssemblyParser.FieldDef f) { return f.type + " " + f.name + ";"; }
    private String propertySource(DotNetAssemblyParser.PropertyDef p) {
        return "public " + p.type + " " + p.name + " { " + (p.getter != null ? "get; " : "") + (p.setter != null ? "set; " : "") + "}";
    }

    private void setEditorText(String text, String documentName) {
        if (editorFragment == null) return;
        editorFragment.setDocumentName(documentName == null ? "dll.cs" : documentName);
        editorFragment.setText(text == null ? "" : text);
        if (editorFragment.getEditor() != null) {
            editorFragment.getEditor().setEditable(false);
            editorFragment.applyPreferences();
        }
    }

    private void openSelectedSource() {
        if (parser == null || selectedNode == null) return;
        select(selectedNode);
    }

    private void openSelectedIl() {
        if (parser == null || selectedNode == null || selectedNode.method == null) {
            Toast.makeText(this, "Select a method first", Toast.LENGTH_SHORT).show(); return;
        }
        setEditorText(selectedNode.method.il == null ? "" : selectedNode.method.il, selectedNode.method.name + ".il");
    }

    private void openHex() {
        if (currentFile == null || !currentFile.isFile()) { Toast.makeText(this, "Select a DLL first", Toast.LENGTH_SHORT).show(); return; }
        startActivity(new Intent(this, HexEditorActivity.class).putExtra("path", currentFile.getAbsolutePath()));
    }

    private void exportAll() {
        if (parser == null) { Toast.makeText(this, "Open a managed DLL first", Toast.LENGTH_SHORT).show(); return; }
        File out = writeExport(stripExt(currentFile.getName()) + ".cs", parser.decompileAll());
        if (out != null) Toast.makeText(this, "Exported C# to " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
    }

    private File writeExport(String name, String text) {
        try {
            File dir = new File(android.os.Environment.getExternalStorageDirectory(), "Untrusted Manager/DLL/Decompiled");
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new java.io.IOException("Cannot create DLL output directory");
            File out = new File(dir, name);
            try (FileOutputStream stream = new FileOutputStream(out)) { stream.write(text.getBytes(StandardCharsets.UTF_8)); }
            return out;
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return null; }
    }

    private static String indent(String s, int spaces) { String p = " ".repeat(spaces); StringBuilder b = new StringBuilder(); for (String line : (s == null ? "" : s).split("\\n", -1)) b.append(p).append(line).append('\n'); return b.toString().trim(); }
    private static String safe(String s) { return s == null ? "Selection" : s.replaceAll("[^A-Za-z0-9._$-]", "_"); }
    private static String stripExt(String s) { int i = s.lastIndexOf('.'); return i > 0 ? s.substring(0, i) : s; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onContentModified(String className) { }
    @Override public void onUndoRedoChanged(boolean canUndo, boolean canRedo) { }
    @Override public void onSaveRequested() { }
    @Override public void onCloseRequested() { finish(); }
    @Override public void onPreferencesRequested() { }

    private static final class Node {
        static final int TYPE=0,METHOD=1,FIELD=2,PROPERTY=3;
        final int type,depth; final String label; final DotNetAssemblyParser.TypeDef owner; final DotNetAssemblyParser.MethodDef method; final DotNetAssemblyParser.FieldDef field; final DotNetAssemblyParser.PropertyDef property;
        private Node(int t,String l,int d,DotNetAssemblyParser.TypeDef o,DotNetAssemblyParser.MethodDef m,DotNetAssemblyParser.FieldDef f,DotNetAssemblyParser.PropertyDef p){type=t;label=l;depth=d;owner=o;method=m;field=f;property=p;}
        static Node type(DotNetAssemblyParser.TypeDef t,int d){return new Node(TYPE,"▾ "+t.fullName(),d,t,null,null,null);}
        static Node method(DotNetAssemblyParser.TypeDef t,DotNetAssemblyParser.MethodDef m,int d){return new Node(METHOD,"ƒ "+m.signature(),d,t,m,null,null);}
        static Node field(DotNetAssemblyParser.TypeDef t,DotNetAssemblyParser.FieldDef f,int d){return new Node(FIELD,"▣ "+f.type+" "+f.name,d,t,null,f,null);}
        static Node property(DotNetAssemblyParser.TypeDef t,DotNetAssemblyParser.PropertyDef p,int d){return new Node(PROPERTY,"□ "+p.type+" "+p.name,d,t,null,null,p);}
        boolean matches(String q){return label.toLowerCase(Locale.ROOT).contains(q)||owner.fullName().toLowerCase(Locale.ROOT).contains(q);}
        @Override public String toString(){return label;}
    }
}
