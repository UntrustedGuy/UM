package untrusted.manager.um.patcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import untrusted.manager.um.ui.dialogs.FilePickerDialog;
import untrusted.manager.um.utils.AppLogs;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

public class PatcherActivity extends Activity {
    private String mode = "apk";
    private File apkFile, patchFile;
    private TextView apkPath, patchPath, status;
    private Button applyButton;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mode = getIntent().getStringExtra("mode"); if (mode == null) mode = "apk";
        boolean lucky = "lucky".equals(mode);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16),dp(16),dp(16),dp(16));
        TextView title = new TextView(this); title.setText(lucky ? "Lucky Patcher" : "APK Patcher"); title.setTextSize(22); body.addView(title,lp(0,0,0,10));
        TextView info = new TextView(this); info.setText(lucky
                ? "Apply Lucky Patcher custom patches using CLASSES, ODEX or LIB byte-pattern rules. The original APK is not modified in place; the patched APK is unsigned."
                : "Apply APK Editor patch packages. Standalone Lucky Patcher patch text and LPZIP packages are also accepted. The patched APK is unsigned.");
        body.addView(info,lp(0,0,0,12));
        apkPath = pathView(); patchPath = pathView();
        body.addView(label("APK"),lp(0,0,0,4)); body.addView(apkPath,lp(0,0,0,6)); body.addView(button("Select APK",v->pickApk()),lp(0,0,0,12));
        body.addView(label(lucky?"Lucky Patcher patch":"Patch / LPZIP"),lp(0,0,0,4)); body.addView(patchPath,lp(0,0,0,6)); body.addView(button(lucky?"Select Lucky Patcher patch":"Select patch / LPZIP",v->pickPatch()),lp(0,0,0,12));
        applyButton=button(lucky?"Apply Lucky Patcher patch":"Apply patch",v->apply()); applyButton.setEnabled(false); body.addView(applyButton,lp(0,0,0,12));
        status=new TextView(this); status.setTextIsSelectable(true); body.addView(status);
        scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f)); setContentView(root); EdgeToEdgeUtil.applyContentInsets(this);
    }
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(16);return t;}
    private TextView pathView(){TextView t=new TextView(this);t.setText("Nothing selected");t.setTextIsSelectable(true);return t;}
    private Button button(String s,View.OnClickListener l){com.google.android.material.button.MaterialButton b=new com.google.android.material.button.MaterialButton(this);b.setText(s);b.setOnClickListener(l);return b;}
    private void pickApk(){FilePickerDialog.Properties p=new FilePickerDialog.Properties();p.selection_type=FilePickerDialog.FILE_SELECT;p.preferenceKey="patcher_apk";p.extensions=new String[]{"apk"};configureUmPicker(p);FilePickerDialog d=new FilePickerDialog(this,p);d.setTitle("Select APK");d.setDialogSelectionListener(a->{if(a!=null&&a.length>0){apkFile=new File(a[0]);apkPath.setText(apkFile.getAbsolutePath());update();}});d.show();}
    private void pickPatch(){FilePickerDialog.Properties p=new FilePickerDialog.Properties();p.selection_type=FilePickerDialog.FILE_SELECT;p.preferenceKey="patcher_patch";configureUmPicker(p);FilePickerDialog d=new FilePickerDialog(this,p);d.setTitle("Select patch / LPZIP");d.setDialogSelectionListener(a->{if(a!=null&&a.length>0){patchFile=new File(a[0]);patchPath.setText(patchFile.getAbsolutePath());update();}});d.show();}
    private void update(){if(applyButton!=null)applyButton.setEnabled(apkFile!=null&&apkFile.isFile()&&patchFile!=null&&patchFile.isFile());}
    private void apply(){if(apkFile==null||!apkFile.isFile()){status.setText("Select an APK first.");return;}if(patchFile==null||!patchFile.isFile()){status.setText("Select a patch first.");return;}applyButton.setEnabled(false);status.setText("Applying patch…");new Thread(()->{try{File workspace=new File(getCacheDir(),"patcher");if(!workspace.isDirectory()&&!workspace.mkdirs()&&!workspace.isDirectory())throw new IOException("Cannot create patch workspace");File patch=normalizePatch(patchFile,workspace);File output=new File(new File(android.os.Environment.getExternalStorageDirectory(),"Untrusted Manager"),"Patches");if(!output.isDirectory()&&!output.mkdirs()&&!output.isDirectory())throw new IOException("Cannot create patch output directory");PatchEngine.Result r=PatchEngine.apply(this,apkFile,patch,output);AppLogs.writeEvent("patcher",r.message());runOnUiThread(()->{status.setText(r.message()+ (r.output()!=null?"\nOutput: "+r.output().getAbsolutePath():""));applyButton.setEnabled(true);});}catch(Throwable t){AppLogs.writeEvent("patcher","failed",t);String msg=t.getMessage()==null?t.toString():t.getMessage();runOnUiThread(()->{status.setText("Patch failed: "+msg);applyButton.setEnabled(true);});}}).start();}
    private File normalizePatch(File selected,File dir)throws IOException{if(isZip(selected))return selected;File z=new File(dir,"patch.zip");try(FileInputStream in=new FileInputStream(selected);FileOutputStream out=new FileOutputStream(z);java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(out)){zip.putNextEntry(new java.util.zip.ZipEntry("patch.txt"));byte[]b=new byte[8192];int n;while((n=in.read(b))!=-1)zip.write(b,0,n);zip.closeEntry();}return z;}
    private boolean isZip(File f)throws IOException{try(FileInputStream in=new FileInputStream(f)){return in.read()=='P'&&in.read()=='K'&&in.read()==3&&in.read()==4;}}

    private void configureUmPicker(FilePickerDialog.Properties p) {
        File umRoot = new File(android.os.Environment.getExternalStorageDirectory(), "Untrusted Manager");
        if (!umRoot.isDirectory()) umRoot.mkdirs();
        if (umRoot.isDirectory()) {
            p.offset = umRoot;
            p.forceStartDirectory = true;
        }
    }

    private LinearLayout.LayoutParams lp(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+.5f);}
}
