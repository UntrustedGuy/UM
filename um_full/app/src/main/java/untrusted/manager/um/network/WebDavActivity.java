package untrusted.manager.um.network;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** WebDAV network-storage browser with persistent connection details. */
public class WebDavActivity extends AppCompatActivity {
    private static final int PICK_UPLOAD=41;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final List<String> names=new ArrayList<>(); private final List<WebDavClient.Entry> entries=new ArrayList<>();
    private ArrayAdapter<String> adapter; private ListView list; private TextView pathView,status; private WebDavClient client; private String path="/";
    private android.content.SharedPreferences prefs;
    private String profilePrefsName(String base){String id=getIntent().getStringExtra("profile_id");return id==null||id.isEmpty()?base:base+"."+id;}
    @Override protected void onCreate(Bundle b){super.onCreate(b);prefs=getSharedPreferences(profilePrefsName("webdav_profiles"),MODE_PRIVATE); SecureNetworkPrefs.migrate(this,prefs,"pass"); build();}
    private void build(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);MaterialToolbar bar=new MaterialToolbar(this);bar.setTitle("WebDAV Storage");bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);bar.setNavigationOnClickListener(v->finish());root.addView(bar);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);MaterialButton connect=new MaterialButton(this);connect.setText("Connect");connect.setOnClickListener(v->showConnect());MaterialButton upload=new MaterialButton(this);upload.setText("Upload");upload.setOnClickListener(v->pickUpload());MaterialButton mkdir=new MaterialButton(this);mkdir.setText("New folder");mkdir.setOnClickListener(v->showMkdir());actions.addView(connect,new LinearLayout.LayoutParams(0,-2,1));actions.addView(upload,new LinearLayout.LayoutParams(0,-2,1));actions.addView(mkdir,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
        pathView=new TextView(this);pathView.setText("/");pathView.setTextIsSelectable(true);pathView.setPadding(16,12,16,12);root.addView(pathView);status=new TextView(this);status.setPadding(16,4,16,8);root.addView(status);
        list=new ListView(this);adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names);list.setAdapter(adapter);list.setOnItemClickListener((p,v,pos,id)->{WebDavClient.Entry e=entries.get(pos);if(e.directory){path=e.href;load();}else download(e);});list.setOnItemLongClickListener((p,v,pos,id)->{showEntryMenu(entries.get(pos));return true;});root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);pathView.setOnClickListener(v->{if(!"/".equals(path)){int i=path.lastIndexOf('/');path=i<=0?"/":path.substring(0,i);load();}});}
    private void showConnect(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);int p=16;v.setPadding(p,p,p,p);EditText url=e(getIntent().getStringExtra("profile_endpoint") != null ? getIntent().getStringExtra("profile_endpoint") : "https://example.com/dav/","WebDAV URL");EditText user=e(prefs.getString("user",""),"Username");EditText pass=e(SecureNetworkPrefs.get(this,prefs,"pass",""),"Password");pass.setInputType(129);v.addView(url);v.addView(user);v.addView(pass);new AlertDialog.Builder(this).setTitle("Connect WebDAV").setView(v).setPositiveButton("Connect",(d,w)->connect(url.getText().toString(),user.getText().toString(),pass.getText().toString())).setNegativeButton("Cancel",null).show();}
    private EditText e(String value,String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setHint(hint);return e;}
    private void connect(String url,String user,String pass){status.setText("Connecting…");io.execute(()->{try{WebDavClient c=new WebDavClient(url,user,pass);c.list("/");client=c;android.content.SharedPreferences.Editor pe=prefs.edit(); pe.putString("url",url).putString("user",user); SecureNetworkPrefs.put(pe,"pass",pass); pe.apply();path="/";runOnUiThread(()->load());}catch(Exception e){runOnUiThread(()->status.setText("Connection failed: "+e.getMessage()));}});}
    private void load(){if(client==null){status.setText("Not connected");return;}pathView.setText("Path: "+path+"  (tap to go up)");status.setText("Loading…");io.execute(()->{try{List<WebDavClient.Entry> got=client.list(path);runOnUiThread(()->{entries.clear();entries.addAll(got);names.clear();for(WebDavClient.Entry e:got)names.add((e.directory?"📁 ":"📄 ")+e.name);adapter.notifyDataSetChanged();status.setText(entries.size()+" items");});}catch(Exception e){runOnUiThread(()->status.setText("Load failed: "+e.getMessage()));}});}
    private void pickUpload(){if(client==null){toast("Connect first");return;}startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),PICK_UPLOAD);}
    @Override protected void onActivityResult(int r,int c,Intent d){super.onActivityResult(r,c,d);if(r!=PICK_UPLOAD||c!=RESULT_OK||d==null||d.getData()==null)return;final android.net.Uri u=d.getData();NetworkTransferQueue.get().submit("Upload", ()->{try{String name=new File(u.getPath()==null?"upload":u.getPath()).getName();if(name.contains(":"))name="upload";File tmp=new File(getCacheDir(),"webdav-upload-"+System.currentTimeMillis());try(java.io.InputStream in=getContentResolver().openInputStream(u);java.io.OutputStream out=new java.io.FileOutputStream(tmp)){if(in==null)throw new Exception("Cannot open source");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}client.upload(tmp,("/".equals(path)?"/":""+path+"/")+name);tmp.delete();runOnUiThread(this::load);}catch(Exception e){runOnUiThread(()->status.setText("Upload failed: "+e.getMessage()));}});}
    private void download(WebDavClient.Entry e){File dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);if(!dir.exists())dir.mkdirs();File out=new File(dir,e.name);if(out.exists())out=new File(dir,System.currentTimeMillis()+"-"+e.name);File f=out;status.setText("Downloading…");NetworkTransferQueue.get().submit("Download", ()->{try{client.download(e.href,f);runOnUiThread(()->status.setText("Downloaded: "+f.getAbsolutePath()));}catch(Exception x){runOnUiThread(()->status.setText("Download failed: "+x.getMessage()));}});}
    private void showMkdir(){if(client==null){toast("Connect first");return;}EditText e=e("","Folder name");new AlertDialog.Builder(this).setTitle("New WebDAV folder").setView(e).setPositiveButton("Create",(d,w)->{String n=e.getText().toString().trim();if(n.isEmpty())return;io.execute(()->{try{client.mkdir(("/".equals(path)?"":""+path)+"/"+n);runOnUiThread(this::load);}catch(Exception x){runOnUiThread(()->status.setText("Create failed: "+x.getMessage()));}});}).setNegativeButton("Cancel",null).show();}
    private void showEntryMenu(WebDavClient.Entry e){new AlertDialog.Builder(this).setTitle(e.name).setItems(new String[]{"Download","Delete","Rename"},(d,w)->{if(w==0)download(e);else if(w==1)delete(e);else rename(e);}).show();}
    private void delete(WebDavClient.Entry e){new AlertDialog.Builder(this).setTitle("Delete "+e.name+"?").setPositiveButton("Delete",(d,w)->io.execute(()->{try{client.delete(e.href);runOnUiThread(this::load);}catch(Exception x){runOnUiThread(()->status.setText("Delete failed: "+x.getMessage()));}})).setNegativeButton("Cancel",null).show();}
    private void rename(WebDavClient.Entry e){EditText n=e(e.name,"New name");new AlertDialog.Builder(this).setTitle("Rename").setView(n).setPositiveButton("Rename",(d,w)->io.execute(()->{try{String parent="/".equals(path)?"":path;client.move(e.href,parent+"/"+n.getText().toString().trim());runOnUiThread(this::load);}catch(Exception x){runOnUiThread(()->status.setText("Rename failed: "+x.getMessage()));}})).setNegativeButton("Cancel",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
}
