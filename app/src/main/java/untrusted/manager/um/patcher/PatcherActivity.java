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
    private Uri apkUri, patchUri; private TextView status;
    @Override public void onCreate(Bundle b){super.onCreate(b); LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,32,32,32);
        TextView title=new TextView(this);title.setText("APK Patcher");title.setTextSize(22);root.addView(title);
        TextView info=new TextView(this);info.setText("Apply APK Editor patch.zip or LPZIP package safely. Unsupported executable patch rules are rejected instead of producing a corrupt APK.");root.addView(info);
        Button apk=button("Select APK",v->pick(PICK_APK,"application/vnd.android.package-archive"));root.addView(apk);
        Button patch=button("Select patch / LPZIP",v->pick(PICK_PATCH,"application/zip"));root.addView(patch);
        Button apply=button("Apply patch",v->apply());root.addView(apply);
        status=new TextView(this);status.setPadding(0,24,0,0);root.addView(status);setContentView(root);
        untrusted.manager.um.utils.EdgeToEdgeUtil.applyContentInsets(this);
    }
    private Button button(String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);return b;}
    private void pick(int req,String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,req);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(c==RESULT_OK&&d!=null){if(r==PICK_APK)apkUri=d.getData();else patchUri=d.getData();status.setText((apkUri!=null?"APK selected\n":"")+(patchUri!=null?"Patch selected":""));}}
    private void apply(){if(apkUri==null||patchUri==null){status.setText("Select both an APK and a patch first.");return;}status.setText("Applying patch…");new Thread(()->{try{File dir=new File(getCacheDir(),"patcher");dir.mkdirs();File apk=copyUri(apkUri,new File(dir,"input.apk"));File patch=copyUri(patchUri,new File(dir,"patch.zip"));PatchEngine.Result r=PatchEngine.apply(apk,patch,getExternalFilesDir(null));AppLogs.writeEvent("patcher",r.message());runOnUiThread(()->status.setText(r.message()));}catch(Throwable t){AppLogs.writeEvent("patcher","failed",t);runOnUiThread(()->status.setText("Patch failed: "+t.getMessage()));}}).start();}
    private File copyUri(Uri u,File f)throws IOException{try(InputStream in=getContentResolver().openInputStream(u);OutputStream out=new FileOutputStream(f)){if(in==null)throw new IOException("Unable to open selected file");byte[]b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}return f;}
}
