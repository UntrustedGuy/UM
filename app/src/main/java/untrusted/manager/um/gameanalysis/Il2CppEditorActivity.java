package untrusted.manager.um.gameanalysis;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;

import untrusted.manager.um.ui.activities.HexEditorActivity;
import untrusted.manager.um.ui.dialogs.FilePickerDialog;
import untrusted.manager.um.ui.activities.TextEditorActivity;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/** Unified IL2CPP/DLL editor launcher using UM's internal file picker. */
public class Il2CppEditorActivity extends AppCompatActivity {
    private String mode;
    private TextView selected;
    @Override protected void onCreate(Bundle state){super.onCreate(state);mode=getIntent().getStringExtra("mode");if(mode==null)mode="il2cpp";
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);MaterialToolbar bar=new MaterialToolbar(this);bar.setTitle("dll".equals(mode)?"DLL Editor":"IL2CPP Editor");bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);bar.setNavigationOnClickListener(v->finish());root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        ScrollView sc=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(16),dp(16),dp(16));TextView info=new TextView(this);info.setText("dll".equals(mode)?"Edit DLL files with the existing UM text/hex editors. Managed DLLs are binary assemblies, so byte edits are performed with Hex Editor; text/JSON/C# companion files use Text Editor.":"Edit IL2CPP dump text files and native binaries with UM's existing editors.");body.addView(info,lp(0,0,0,12));
        selected=new TextView(this);selected.setText("Nothing selected");selected.setTextIsSelectable(true);body.addView(selected,lp(0,0,0,8));Button pick=button("Select file");pick.setOnClickListener(v->pick());body.addView(pick,lp(0,0,0,12));
        String supplied=getIntent().getStringExtra("path");if(supplied!=null&&!supplied.isEmpty()){File f=new File(supplied);if(f.isFile()){selected.setText(f.getAbsolutePath());open(f);}}
        sc.addView(body);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1f));setContentView(root);EdgeToEdgeUtil.applyContentInsets(this);
    }
    private void pick(){FilePickerDialog.Properties p=new FilePickerDialog.Properties();p.selection_type=FilePickerDialog.FILE_SELECT;p.preferenceKey="dll_editor";p.forceStartDirectory=true;if("dll".equals(mode))p.extensions=new String[]{"dll"};else p.extensions=new String[]{"dll","so","dat","bin","cs","h","json","txt"};configureUmPicker(p);FilePickerDialog d=new FilePickerDialog(this,p);d.setTitle("Select "+("dll".equals(mode)?"DLL":"IL2CPP file"));d.setDialogSelectionListener(paths->{if(paths!=null&&paths.length>0){File f=new File(paths[0]);if(f.isFile()){selected.setText(f.getAbsolutePath());open(f);}}});d.show();}

    private void configureUmPicker(FilePickerDialog.Properties p) {
        File umRoot = new File(android.os.Environment.getExternalStorageDirectory(), "Untrusted Manager");
        if (!umRoot.isDirectory()) umRoot.mkdirs();
        if (umRoot.isDirectory()) {
            p.offset = umRoot;
            p.forceStartDirectory = true;
        }
    }

    private void open(File f){String n=f.getName().toLowerCase();if(n.endsWith(".cs")||n.endsWith(".json")||n.endsWith(".h")||n.endsWith(".txt")){startActivity(new android.content.Intent(this,TextEditorActivity.class).putExtra("path",f.getAbsolutePath()));}else{startActivity(new android.content.Intent(this,HexEditorActivity.class).putExtra("path",f.getAbsolutePath()));}}
    private Button button(String t){MaterialButton b=new MaterialButton(this);b.setText(t);return b;}
    private LinearLayout.LayoutParams lp(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
}
