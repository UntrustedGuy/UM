package untrusted.manager.um.gameanalysis;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import untrusted.manager.um.ApkExtractor.APKExtractorActivity;
import untrusted.manager.um.patcher.PatcherActivity;
import untrusted.manager.um.ui.activities.HexEditorActivity;
import untrusted.manager.um.ui.dialogs.FilePickerDialog;
import untrusted.manager.um.utils.AppLogs;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/** Integrated Game Analysis & Modding entry point. */
public class GameAnalysisActivity extends AppCompatActivity {
    private static final int PICK_APK=1, PICK_LIB=2, PICK_METADATA=3, PICK_PRIMARY=4, PICK_COMPANION=5;
    private String mode;
    private File inputFile, companionFile, apkFile, libFile, metadataFile;
    private TextView inputPath, companionPath, apkPath, libPath, metadataPath, status;
    private Button runButton;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        mode=getIntent().getStringExtra("mode");if(mode==null)mode="analyzer";
        boolean il2cpp="il2cpp".equals(mode);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar bar=new MaterialToolbar(this);bar.setTitle(titleForMode(mode));bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);bar.setNavigationOnClickListener(v->finish());root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),dp(16),dp(16),dp(16));
        TextView intro=new TextView(this);intro.setText(descriptionForMode(mode));intro.setTextSize(14);body.addView(intro,lp(0,0,0,12));
        if(il2cpp){
            body.addView(inputCard("libil2cpp.so (required)","Nothing selected","Select libil2cpp.so",PICK_LIB,new String[]{"so"}),lp(0,0,0,12));
            body.addView(inputCard("global-metadata.dat (required)","Nothing selected","Select global-metadata.dat",PICK_METADATA,new String[]{"dat"}),lp(0,0,0,12));
            body.addView(inputCard("APK / game file (optional)","Optional package context. The two files above are the actual dumper inputs.","Select APK / game file",PICK_APK,new String[]{"apk","xapk","apkm","apks","aab","zip"}),lp(0,0,0,12));
        }else{
            body.addView(inputCard("Primary input","Nothing selected","Select file",PICK_PRIMARY,null),lp(0,0,0,12));
            body.addView(inputCard("Companion input (optional)","Nothing selected","Select companion file",PICK_COMPANION,null),lp(0,0,0,12));
        }
        if("modding".equals(mode)){
            MaterialCardView actions=card();LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(12));TextView label=new TextView(this);label.setText("Modding tools already integrated in UM");label.setTextSize(16);box.addView(label,lp(0,0,0,8));
            addAction(box,"APK Patcher",v->startActivity(new Intent(this,PatcherActivity.class)));
            addAction(box,"APK Extractor",v->startActivity(new Intent(this,APKExtractorActivity.class)));
            addAction(box,"IL2CPP Editor",v->openEditorForSelected());
            addAction(box,"Frida Runtime Kit",v->startActivity(new Intent(this,FridaToolkitActivity.class)));
            addAction(box,"Hex Editor — selected file",v->openHexForSelected());actions.addView(box);body.addView(actions,lp(0,0,0,12));
        }
        runButton=button("Analyze");runButton.setOnClickListener(v->runAnalysis());runButton.setEnabled(!il2cpp);body.addView(runButton,lp(0,0,0,12));
        status=new TextView(this);status.setTextIsSelectable(true);status.setText("Output: "+GameAnalysisEngine.dumpRoot().getAbsolutePath());body.addView(status);
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));setContentView(root);EdgeToEdgeUtil.applyContentInsets(this);
        String supplied=getIntent().getStringExtra("input_path");if(supplied!=null&&!supplied.isEmpty())prefill(new File(supplied));updateRunButton();
    }

    private MaterialCardView inputCard(String title,String initial,String buttonText,int kind,String[] extensions){
        MaterialCardView c=card();LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(12));TextView label=new TextView(this);label.setText(title);label.setTextSize(16);box.addView(label);TextView path=new TextView(this);path.setText(initial);path.setTextIsSelectable(true);box.addView(path,lp(0,0,0,8));Button b=button(buttonText);b.setOnClickListener(v->pickInternal(kind,title,extensions));box.addView(b);c.addView(box);
        if(kind==PICK_LIB)libPath=path;else if(kind==PICK_METADATA)metadataPath=path;else if(kind==PICK_APK)apkPath=path;else if(kind==PICK_PRIMARY)inputPath=path;else companionPath=path;return c;
    }
    private void pickInternal(int kind,String title,String[] ext){
        FilePickerDialog.Properties p=new FilePickerDialog.Properties();
        p.selection_type=FilePickerDialog.FILE_SELECT;
        p.preferenceKey="game_analysis_"+kind;
        p.forceStartDirectory=true;
        if(ext!=null)p.extensions=ext;
        configureUmPicker(p);
        FilePickerDialog d=new FilePickerDialog(this,p);
        d.setTitle(title);
        d.setDialogSelectionListener(paths->{
            if(paths==null||paths.length==0)return;
            File f=new File(paths[0]);
            if(!f.isFile())return;
            String n=f.getName().toLowerCase(java.util.Locale.ROOT);
            if(kind==PICK_LIB && !"libil2cpp.so".equals(n)){ status.setText("Select the IL2CPP native library named libil2cpp.so."); return; }
            if(kind==PICK_METADATA && !"global-metadata.dat".equals(n)){ status.setText("Select the metadata file named global-metadata.dat."); return; }
            switch(kind){case PICK_LIB:libFile=f;libPath.setText(f.getAbsolutePath());break;case PICK_METADATA:metadataFile=f;metadataPath.setText(f.getAbsolutePath());break;case PICK_APK:apkFile=f;apkPath.setText(f.getAbsolutePath());break;case PICK_PRIMARY:inputFile=f;inputPath.setText(f.getAbsolutePath());break;case PICK_COMPANION:companionFile=f;companionPath.setText(f.getAbsolutePath());break;}
            status.setText("Selected: "+f.getAbsolutePath());
            updateRunButton();
        });
        d.show();
    }
    private void prefill(File f){if(f==null||!f.isFile())return;String n=f.getName().toLowerCase(java.util.Locale.ROOT);if("il2cpp".equals(mode)){if(n.equals("libil2cpp.so")){libFile=f;libPath.setText(f.getAbsolutePath());status.setText("libil2cpp.so selected: "+f.getAbsolutePath());}else if(n.equals("global-metadata.dat")){metadataFile=f;metadataPath.setText(f.getAbsolutePath());status.setText("global-metadata.dat selected: "+f.getAbsolutePath());}else if(n.endsWith(".apk")||n.endsWith(".xapk")||n.endsWith(".apkm")||n.endsWith(".apks")||n.endsWith(".aab")||n.endsWith(".zip")){apkFile=f;apkPath.setText(f.getAbsolutePath());status.setText("Optional APK selected: "+f.getAbsolutePath());}}else{inputFile=f;inputPath.setText(f.getAbsolutePath());status.setText("Selected: "+f.getAbsolutePath());}}
    private void updateRunButton(){if(runButton==null)return;runButton.setEnabled("il2cpp".equals(mode)?libFile!=null&&libFile.isFile()&&metadataFile!=null&&metadataFile.isFile():inputFile!=null&&inputFile.isFile());}
    private void runAnalysis(){if("il2cpp".equals(mode)){if(libFile==null||!libFile.isFile()||metadataFile==null||!metadataFile.isFile()){status.setText("Select both libil2cpp.so and global-metadata.dat.");return;}}else if(inputFile==null||!inputFile.isFile()){status.setText("Select a primary input first.");return;}runButton.setEnabled(false);status.setText("Analyzing…\nLarge APKs and native libraries can take a while.");executor.execute(()->{try{GameAnalysisEngine.Result r="il2cpp".equals(mode)?GameAnalysisEngine.analyzeIl2CppPair(this,libFile,metadataFile,apkFile):GameAnalysisEngine.analyze(this,inputFile,companionFile);AppLogs.writeEvent("game_analysis","completed: "+r.output().getAbsolutePath());runOnUiThread(()->{status.setText("Completed.\n"+r.report()+"\n\nDump folder:\n"+r.output().getAbsolutePath());runButton.setEnabled(true);});}catch(Throwable t){AppLogs.writeEvent("game_analysis","analysis failed",t);String m=t.getMessage()==null?t.toString():t.getMessage();runOnUiThread(()->{status.setText("Analysis failed: "+m);updateRunButton();});}});}
    private void openEditorForSelected(){File f=selectEditorFile();if(f==null){status.setText("Select a file first.");return;}startActivity(new Intent(this,Il2CppEditorActivity.class).putExtra("path",f.getAbsolutePath()).putExtra("mode",f.getName().toLowerCase().endsWith(".dll")?"dll":"il2cpp"));}
    private File selectEditorFile(){if("il2cpp".equals(mode))return libFile!=null?libFile:metadataFile;return inputFile;}
    private void openHexForSelected(){File f=selectEditorFile();if(f==null||!f.isFile()){status.setText("Select a file first.");return;}startActivity(new Intent(this,HexEditorActivity.class).putExtra("path",f.getAbsolutePath()));}
    private void addAction(LinearLayout p,String text,View.OnClickListener l){Button b=button(text);b.setOnClickListener(l);p.addView(b,lp(0,0,0,6));}
    private MaterialCardView card(){MaterialCardView c=new MaterialCardView(this);c.setRadius(dp(14));c.setCardElevation(dp(1));return c;}
    private Button button(String text){MaterialButton b=new MaterialButton(this);b.setText(text);return b;}
    private LinearLayout.LayoutParams lp(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
    private String titleForMode(String m){return switch(m){case"il2cpp"->"IL2CPP Dumper";case"metadata"->"Global Metadata";case"encryption"->"Game Encryption";case"modding"->"Game Modding Toolkit";default->"Game Analyzer";};}
    private String descriptionForMode(String m){return switch(m){case"il2cpp"->"Dump a Unity IL2CPP binary using the matching libil2cpp.so and global-metadata.dat. The APK/game archive is optional context only.";case"metadata"->"Validate and inspect global-metadata.dat and emit the supported metadata reports.";case"encryption"->"Inspect protected or transformed game data without claiming to decrypt unsupported schemes.";case"modding"->"Use the existing UM patching, extraction, editor and runtime tooling around game files.";default->"Analyze APK/XAPK/APKM/APKS/AAB/ZIP files and locate IL2CPP native libraries and metadata.";};}

    private void configureUmPicker(FilePickerDialog.Properties p) {
        File umRoot = new File(android.os.Environment.getExternalStorageDirectory(), "Untrusted Manager");
        if (!umRoot.isDirectory()) umRoot.mkdirs();
        if (umRoot.isDirectory()) {
            p.offset = umRoot;
            p.forceStartDirectory = true;
        }
    }

    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
