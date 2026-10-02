package untrusted.manager.um.gameanalysis;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import untrusted.manager.um.ui.activities.TextEditorActivity;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/** Frida runtime script kit integrated into UM. It generates and runs scripts through Termux when available. */
public class FridaToolkitActivity extends AppCompatActivity {
    private EditText packageName;
    private TextView status;
    private File scriptDir;
    private File fridaRuntimeDir;
    private File metadataScript;
    private File bridgeScript;

    private static final String METADATA_SCRIPT = """
'use strict';
// UM Frida runtime metadata extractor. It searches readable Android memory for a valid
// IL2CPP global-metadata.dat header and writes the recovered plaintext representation.
const OUT = '/sdcard/Untrusted Manager/Dump/FridaRuntime/UM_Frida_';
const MAGIC = 0xFAB11BAF;
function u32(p){ return p.readU32(); }
function validHeader(p, range){
  try {
    if (u32(p) !== MAGIC) return null;
    const v = u32(p.add(4));
    if (v < 16 || v > 31) return null;
    const so = u32(p.add(24));
    const ss = u32(p.add(28));
    const mo = u32(p.add(40));
    const ms = u32(p.add(44));
    let end = Math.max(so + ss, mo + ms, 4096);
    // The header is a sequence of offset/size pairs; find the largest coherent section.
    for (let off = 8; off + 8 <= 400; off += 8) {
      const a = u32(p.add(off)); const z = u32(p.add(off + 4));
      if (a > 0 && z > 0 && a < 512 * 1024 * 1024 && z < 512 * 1024 * 1024) end = Math.max(end, a + z);
    }
    if (end <= 0 || end > 512 * 1024 * 1024) return null;
    if (range && p.compare(range.base) < 0) return null;
    if (range && p.add(end).compare(range.base.add(range.size)) > 0) return null;
    return { version:v, size:end };
  } catch (_) { return null; }
}
function dumpAt(p, h){
  try {
    const bytes = Memory.readByteArray(p, h.size);
    if (bytes === null) return false;
    const f = new File(OUT + 'global-metadata-v' + h.version + '-' + p.toString().replace('0x','') + '.dat', 'wb');
    f.write(bytes); f.flush(); f.close();
    send({type:'metadata_dump', address:p.toString(), version:h.version, size:h.size});
    return true;
  } catch (e) { send({type:'metadata_error', error:String(e)}); return false; }
}
function main(){
  let found=0;
  for (const protection of ['r--','rw-','r-x']) for (const r of Process.enumerateRangesSync({protection:protection, coalesce:true})) {
    if (r.size < 8 || r.size > 1024 * 1024 * 1024) continue;
    try {
      const hits = Memory.scanSync(r.base, r.size, 'AF 1B B1 FA ?? ?? ?? ??');
      for (const hit of hits) { const h=validHeader(hit.address,r); if(h && found<8 && dumpAt(hit.address,h)) found++; }
    } catch (_) {}
  }
  send({type:'complete', found:found});
}
setImmediate(main);
""";

    private static final String BRIDGE_SCRIPT = """
import "frida-il2cpp-bridge";

Il2Cpp.perform(() => {
  Il2Cpp.dump("um_il2cpp_dump", "/sdcard/Download");
});
""";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File umRoot = new File(Environment.getExternalStorageDirectory(), "Untrusted Manager");
        scriptDir = new File(umRoot, "Frida");
        fridaRuntimeDir = new File(new File(umRoot, "Dump"), "FridaRuntime");
        if (!scriptDir.isDirectory()) scriptDir.mkdirs();
        if (!fridaRuntimeDir.isDirectory()) fridaRuntimeDir.mkdirs();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar bar=new MaterialToolbar(this);bar.setTitle("Frida Runtime Kit");bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);bar.setNavigationOnClickListener(v->finish());root.addView(bar);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int p=dp(16);body.setPadding(p,p,p,p);
        TextView intro=new TextView(this);intro.setText("Generate Frida runtime scripts for authorized IL2CPP analysis. UM supplies the scripts and Termux runner; Frida itself is an external runtime dependency.");body.addView(intro,lp(0,0,0,12));
        TextInputLayout til=new TextInputLayout(this);til.setHint("Target package name");packageName=new TextInputEditText(this);packageName.setSingleLine(true);String initial=getIntent().getStringExtra("package");if(initial!=null)packageName.setText(initial);til.addView(packageName);body.addView(til,lp(0,0,0,12));
        addCard(body,"Runtime metadata extractor","Finds a valid IL2CPP metadata header in readable process memory and writes the recovered bytes to the UM Dump/FridaRuntime directory.",v->{metadataScript=write("dump_metadata.js",METADATA_SCRIPT);openText(metadataScript);});
        addCard(body,"IL2CPP bridge dump script","Uses the frida-il2cpp-bridge runtime API to request a live IL2CPP dump.",v->{bridgeScript=write("il2cpp_bridge_dump.ts",BRIDGE_SCRIPT);openText(bridgeScript);});
        MaterialButton check=button("Check Frida in Termux");check.setOnClickListener(v->runTermux("command -v frida && frida --version && frida-ps -U"));body.addView(check,lp(0,0,0,8));
        MaterialButton run=button("Run metadata extractor in Termux");run.setOnClickListener(v->runMetadata());body.addView(run,lp(0,0,0,8));
        MaterialButton runBridge=button("Run IL2CPP bridge dump in Termux");runBridge.setOnClickListener(v->runBridge());body.addView(runBridge,lp(0,0,0,8));
        status=new TextView(this);status.setText("Scripts are saved under /storage/emulated/0/Untrusted Manager/Frida");status.setTextIsSelectable(true);body.addView(status,lp(0,8,0,0));
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root); EdgeToEdgeUtil.applyContentInsets(this);
    }
    private void addCard(LinearLayout body,String title,String desc,View.OnClickListener listener){MaterialCardView c=new MaterialCardView(this);LinearLayout b=new LinearLayout(this);b.setOrientation(LinearLayout.VERTICAL);b.setPadding(dp(14),dp(12),dp(14),dp(12));TextView t=new TextView(this);t.setText(title);t.setTextSize(16);b.addView(t,lp(0,0,0,4));TextView d=new TextView(this);d.setText(desc);b.addView(d,lp(0,0,0,8));MaterialButton x=button("Generate / edit script");x.setOnClickListener(listener);b.addView(x);c.addView(b);body.addView(c,lp(0,0,0,12));}
    private File write(String name,String text){try{if(!scriptDir.isDirectory()&&!scriptDir.mkdirs())throw new IllegalStateException("Cannot create Frida directory");File f=new File(scriptDir,name);try(FileOutputStream out=new FileOutputStream(f)){out.write(text.getBytes(StandardCharsets.UTF_8));}status.setText("Generated: "+f.getAbsolutePath());return f;}catch(Exception e){status.setText("Generation failed: "+e.getMessage());return null;}}
    private void openText(File f){if(f!=null)startActivity(new Intent(this,TextEditorActivity.class).putExtra("path",f.getAbsolutePath()));}
    private void runMetadata(){if(!fridaRuntimeDir.isDirectory()&&!fridaRuntimeDir.mkdirs()){status.setText("Cannot create Frida runtime output directory.");return;}if(metadataScript==null)metadataScript=write("dump_metadata.js",METADATA_SCRIPT);String pkg=packageName.getText()==null?"":packageName.getText().toString().trim();if(metadataScript==null||pkg.isEmpty()){status.setText("Enter a target package name first.");return;}runTermux("frida -U -f '"+sh(pkg)+"' -l '"+sh(metadataScript.getAbsolutePath())+"' --no-pause");}
    private void runBridge(){String pkg=packageName.getText()==null?"":packageName.getText().toString().trim();if(pkg.isEmpty()){status.setText("Enter a target package name first.");return;}runTermux("mkdir -p \"$HOME/storage/shared/Untrusted Manager/Dump/FridaBridge\" && npm exec frida-il2cpp-bridge -- -U -f '"+sh(pkg)+"' dump --out-dir \"$HOME/storage/shared/Untrusted Manager/Dump/FridaBridge\"");}
    private String sh(String s){return s.replace("'","'\\''");}
    private void runTermux(String command){try{getPackageManager().getPackageInfo("com.termux",0);}catch(Exception e){status.setText("Termux is not installed.");return;}Intent i=new Intent();i.setClassName("com.termux","com.termux.app.RunCommandService");i.setAction("com.termux.RUN_COMMAND");i.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"-c",command+"; exec bash"});i.putExtra("com.termux.RUN_COMMAND_BACKGROUND",false);i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION","0");try{startService(i);status.setText("Started in Termux: "+command);}catch(SecurityException e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+getPackageName())));}catch(IllegalStateException e){try{startActivity(new Intent().setClassName("com.termux","com.termux.app.TermuxActivity"));new Handler(Looper.getMainLooper()).postDelayed(()->{try{startService(i);}catch(Exception ignored){}},2000);}catch(Exception ignored){status.setText("Termux could not be started: "+e.getMessage());}}}
    private MaterialButton button(String text){MaterialButton b=new MaterialButton(this);b.setText(text);return b;}
    private LinearLayout.LayoutParams lp(int l,int t,int r,int b){LinearLayout.LayoutParams x=new LinearLayout.LayoutParams(-1,LinearLayout.LayoutParams.WRAP_CONTENT);x.setMargins(dp(l),dp(t),dp(r),dp(b));return x;}private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
}
