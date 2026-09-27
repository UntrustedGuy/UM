package untrusted.manager.um.patcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import untrusted.manager.um.utils.AppLogs;

public class PatcherActivity extends Activity {
    private static final int PICK_APK=10, PICK_PATCH=11;
    private Uri apkUri, patchUri; private TextView status; private Button applyButton;
    @Override public void onCreate(Bundle b){super.onCreate(b); LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,32,32,32);
        TextView title=new TextView(this);title.setText("APK Patcher");title.setTextSize(22);root.addView(title);
        TextView info=new TextView(this);info.setText("Apply APK Editor patch.zip or Lucky Patcher custom patches. APK output is unsigned and must be signed before installation.");root.addView(info);
        Button apk=button("Select APK",v->pick(PICK_APK,"application/vnd.android.package-archive"));root.addView(apk);
        Button patch=button("Select patch / LPZIP",v->pick(PICK_PATCH,"*/*"));root.addView(patch);
        applyButton=button("Apply patch",v->apply());root.addView(applyButton);
        status=new TextView(this);status.setPadding(0,24,0,0);root.addView(status);setContentView(root);
        untrusted.manager.um.utils.EdgeToEdgeUtil.applyContentInsets(this);
    }
    private Button button(String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);return b;}
    private void pick(int req,String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,req);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(c==RESULT_OK&&d!=null){if(r==PICK_APK)apkUri=d.getData();else patchUri=d.getData();status.setText((apkUri!=null?"APK selected\n":"")+(patchUri!=null?"Patch selected":""));}}
    private void apply(){if(apkUri==null||patchUri==null){status.setText("Select both an APK and a patch first.");return;}if(applyButton!=null)applyButton.setEnabled(false);status.setText("Applying patch…");new Thread(()->{try{File dir=new File(getCacheDir(),"patcher");dir.mkdirs();File apk=copyUri(apkUri,new File(dir,"input.apk"));File selectedPatch=copyUri(patchUri,new File(dir,"selected.patch"));
                File patch=normalizePatch(selectedPatch, dir);File output=getExternalFilesDir(null);if(output==null)output=new File(getCacheDir(),"patch-output");if(!output.isDirectory()&&!output.mkdirs()&&!output.isDirectory())throw new IOException("Cannot create patch output directory");PatchEngine.Result r=PatchEngine.apply(this,apk,patch,output);AppLogs.writeEvent("patcher",r.message()); runOnUiThread(()->{status.setText(r.message());if(applyButton!=null)applyButton.setEnabled(true);});}catch(Throwable t){AppLogs.writeEvent("patcher","failed",t);runOnUiThread(()->{status.setText("Patch failed: "+t.getMessage());if(applyButton!=null)applyButton.setEnabled(true);});}}).start();}
    private File normalizePatch(File selected, File dir) throws IOException {
        if (isZip(selected)) return selected;
        File patchZip = new File(dir, "patch.zip");
        try (FileOutputStream out = new FileOutputStream(patchZip);
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("patch.txt");
            zip.putNextEntry(entry);
            try (FileInputStream in = new FileInputStream(selected)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) zip.write(b, 0, n);
            }
            zip.closeEntry();
        }
        return patchZip;
    }
    private boolean isZip(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K' && in.read() == 3 && in.read() == 4;
        }
    }
    private File copyUri(Uri u,File f)throws IOException{try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(f)){if(in==null)throw new IOException("Unable to open selected file");byte[]b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}return f;}
}
