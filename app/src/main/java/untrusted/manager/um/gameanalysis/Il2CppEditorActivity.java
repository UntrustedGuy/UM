package untrusted.manager.um.gameanalysis;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import untrusted.manager.um.ui.activities.HexEditorActivity;
import untrusted.manager.um.ui.activities.TextEditorActivity;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/** IL2CPP artifact editor: text artifacts use UM's text editor; native binaries use UM's hex editor. */
public class Il2CppEditorActivity extends AppCompatActivity {
    private Uri pendingBinaryUri;
    private File pendingBinaryCache;
    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    openSelected(result.getData().getData());
                }
            });
    private final ActivityResultLauncher<Intent> hexLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> { if (result.getResultCode() == RESULT_OK) saveBinaryBack(); else discardBinaryStage(); });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdgeUtil.applyContentInsets(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar bar = new MaterialToolbar(this); bar.setTitle("IL2CPP Editor"); bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material); bar.setNavigationOnClickListener(v -> finish()); root.addView(bar);
        ScrollView scroll = new ScrollView(this); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); int p=dp(16); body.setPadding(p,p,p,p);
        TextView intro = new TextView(this); intro.setText("Edit generated IL2CPP artifacts with UM's existing editors. Native binaries are edited as bytes; generated C# / JSON / headers use the text editor. Changes are made to the selected file or its SAF document."); body.addView(intro,lp(0,0,0,12));
        MaterialCardView card=new MaterialCardView(this); LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(14),dp(12),dp(14),dp(12));
        TextView title=new TextView(this);title.setText("Select artifact");title.setTextSize(16);box.addView(title,lp(0,0,0,8));
        MaterialButton select=new MaterialButton(this);select.setText("Select IL2CPP artifact");select.setOnClickListener(v->pickArtifact());box.addView(select);card.addView(box);body.addView(card,lp(0,0,0,12));
        String path=getIntent().getStringExtra("path"); if(path!=null&&!path.isEmpty()) addPathAction(body,new File(path));
        String input=getIntent().getStringExtra("input_path"); if(input!=null&&!input.isEmpty()) addPathAction(body,new File(input));
        scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }
    private void pickArtifact(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);picker.launch(i);}
    private void addPathAction(LinearLayout body,File f){if(!f.isFile())return;MaterialButton b=new MaterialButton(this);b.setText("Open: "+f.getName());b.setOnClickListener(v->openFilePath(f));body.addView(b,lp(0,0,0,8));}
    private void openSelected(Uri uri){String name=queryName(uri);String lower=name.toLowerCase();if(isBinary(lower)){try{pendingBinaryUri=uri;pendingBinaryCache=new File(getCacheDir(),"il2cpp_editor_"+System.nanoTime()+"_"+safe(name));copy(uri,pendingBinaryCache);hexLauncher.launch(new Intent(this,HexEditorActivity.class).putExtra("path",pendingBinaryCache.getAbsolutePath()));}catch(Exception e){show("Cannot stage binary: "+e.getMessage());}}else{Intent i=new Intent(Intent.ACTION_EDIT,uri);i.setClass(this,TextEditorActivity.class);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);startActivity(i);}}
    private void openFilePath(File f){String l=f.getName().toLowerCase();if(isBinary(l))startActivity(new Intent(this,HexEditorActivity.class).putExtra("path",f.getAbsolutePath()));else startActivity(new Intent(Intent.ACTION_EDIT,Uri.fromFile(f)).setClass(this,TextEditorActivity.class).putExtra("path",f.getAbsolutePath()));}
    private void saveBinaryBack(){if(pendingBinaryUri==null||pendingBinaryCache==null||!pendingBinaryCache.isFile())return;try(OutputStream out=getContentResolver().openOutputStream(pendingBinaryUri,"wt");InputStream in=new FileInputStream(pendingBinaryCache)){if(out==null)throw new IllegalStateException("Document is not writable");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(Exception e){show("Could not write edited binary back: "+e.getMessage());}finally{pendingBinaryUri=null;if(pendingBinaryCache!=null)pendingBinaryCache.delete();pendingBinaryCache=null;}}
    private void discardBinaryStage(){ pendingBinaryUri=null; if(pendingBinaryCache!=null) pendingBinaryCache.delete(); pendingBinaryCache=null; }
    private String queryName(Uri u){try(CursorHolder c=new CursorHolder(u)){return c.name!=null?c.name:"artifact";}catch(Exception e){return "artifact";}}
    private final class CursorHolder implements AutoCloseable{String name;android.database.Cursor c;CursorHolder(Uri u){c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);if(c!=null&&c.moveToFirst())name=c.getString(0);}public void close(){if(c!=null)c.close();}}
    private void copy(Uri u,File f)throws Exception{try(InputStream in=getContentResolver().openInputStream(u);FileOutputStream out=new FileOutputStream(f)){if(in==null)throw new IllegalStateException("Cannot read document");byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}}
    private boolean isBinary(String s){return s.endsWith(".so")||s.endsWith(".dll")||s.endsWith(".bin")||s.endsWith(".dat")||s.endsWith(".aab")||s.endsWith(".apk");}
    private String safe(String s){return s.replaceAll("[^A-Za-z0-9._-]","_");}
    private void show(String s){new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setMessage(s).setPositiveButton(android.R.string.ok,null).show();}
    private LinearLayout.LayoutParams lp(int a,int b,int c,int d){LinearLayout.LayoutParams x=new LinearLayout.LayoutParams(-1,LinearLayout.LayoutParams.WRAP_CONTENT);x.setMargins(a,b,c,d);return x;} private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
}
