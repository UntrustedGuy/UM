package untrusted.manager.um.tools;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

import untrusted.manager.um.R;
import untrusted.manager.um.ui.activities.HexEditorActivity;
import untrusted.manager.um.ui.activities.TextEditorActivity;
import untrusted.manager.um.ui.dialogs.FilePickerDialog;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/**
 * dnSpy/ILSpy-style managed DLL browser.
 *
 * Managed assemblies are browsed as namespaces/types/members and reconstructed to C#.
 * Native/unmanaged PE DLLs remain available through the dedicated Hex Editor action.
 */
public final class DllEditorActivity extends AppCompatActivity {
    private final ArrayList<Node> allNodes = new ArrayList<>();
    private final ArrayList<Node> visibleNodes = new ArrayList<>();
    private ArrayAdapter<Node> adapter;
    private ListView tree;
    private EditText search;
    private TextView summary;
    private TextView source;
    private DotNetAssemblyParser parser;
    private File currentFile;
    private Node selectedNode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("DLL Editor");
        buildUi();
        String supplied = getIntent().getStringExtra("path");
        if (supplied != null && !supplied.isEmpty()) openFile(new File(supplied));
        else if (getIntent().getData() != null) {
            try { openFile(new File(getIntent().getData().getPath())); }
            catch (Throwable t) { summary.setText(t.getMessage()); }
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK));

        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle("DLL Editor");
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        bar.setNavigationOnClickListener(v -> finish());
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

        summary = new TextView(this);
        summary.setPadding(dp(12), dp(8), dp(12), dp(8));
        summary.setTextIsSelectable(true);
        root.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
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
                v.setPadding(dp(n == null ? 8 : n.depth * 14 + 8), dp(7), dp(8), dp(7));
                return v;
            }
        };
        tree.setAdapter(adapter);
        content.addView(tree, new LinearLayout.LayoutParams(0, -1, 0.37f));

        source = new TextView(this);
        source.setTextSize(13);
        source.setTypeface(android.graphics.Typeface.MONOSPACE);
        source.setTextIsSelectable(true);
        source.setGravity(Gravity.TOP | Gravity.START);
        source.setPadding(dp(12), dp(12), dp(12), dp(12));
        source.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK));
        content.addView(source, new LinearLayout.LayoutParams(0, -1, 0.63f));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        EdgeToEdgeUtil.applyContentInsets(this);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable e) {}
        });
        tree.setOnItemClickListener((p, v, pos, id) -> select(visibleNodes.get(pos)));
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
        if (f == null || !f.isFile()) { summary.setText("DLL file not found"); return; }
        currentFile = f;
        try {
            parser = DotNetAssemblyParser.parse(f);
            rebuildTree();
            summary.setText(parser.summary() + "\n\n" + f.getAbsolutePath());
            if (!visibleNodes.isEmpty()) select(visibleNodes.get(0));
        } catch (Throwable e) {
            parser = null;
            allNodes.clear(); visibleNodes.clear(); adapter.notifyDataSetChanged();
            summary.setText("This file is not a readable managed CLR assembly.\n" + e.getMessage());
            source.setText("Native/unmanaged PE DLL\n\nUse Hex to inspect the PE image.\n\nA managed C# view is only available when the PE contains a valid CLR metadata directory.");
        }
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
        if (n.type == Node.TYPE) source.setText(parser.decompile(n.owner));
        else if (n.type == Node.METHOD) source.setText(methodSource(n.method));
        else if (n.type == Node.FIELD) source.setText(fieldSource(n.field));
        else if (n.type == Node.PROPERTY) source.setText(propertySource(n.property));
    }

    private String methodSource(DotNetAssemblyParser.MethodDef m) {
        String body = m.source == null ? "" : m.source;
        return m.signature() + "\n{\n" + indent(body, 4) + "\n}\n\n// IL\n" + (m.il == null ? "" : m.il);
    }

    private String fieldSource(DotNetAssemblyParser.FieldDef f) { return "// Field\n" + f.type + " " + f.name + ";"; }
    private String propertySource(DotNetAssemblyParser.PropertyDef p) {
        return "// Property\npublic " + p.type + " " + p.name + " { " + (p.getter != null ? "get; " : "") + (p.setter != null ? "set; " : "") + "}";
    }

    private void openSelectedSource() {
        if (parser == null || selectedNode == null) return;
        String name = selectedNode.type == Node.METHOD ? selectedNode.method.name : selectedNode.type == Node.TYPE ? selectedNode.owner.name : "Selection";
        String text = selectedNode.type == Node.TYPE ? parser.decompile(selectedNode.owner) : source.getText().toString();
        File out = writeExport(safe(name) + ".cs", text);
        if (out != null) startActivity(new Intent(this, TextEditorActivity.class).setAction(Intent.ACTION_EDIT).setData(Uri.fromFile(out)).putExtra("path", out.getAbsolutePath()));
    }

    private void openSelectedIl() {
        if (parser == null || selectedNode == null || selectedNode.method == null) {
            Toast.makeText(this, "Select a method first", Toast.LENGTH_SHORT).show(); return;
        }
        File out = writeExport(safe(selectedNode.method.name) + ".il", selectedNode.method.il == null ? "" : selectedNode.method.il);
        if (out != null) startActivity(new Intent(this, TextEditorActivity.class).setAction(Intent.ACTION_EDIT).setData(Uri.fromFile(out)).putExtra("path", out.getAbsolutePath()));
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
