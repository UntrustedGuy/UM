package untrusted.manager.um.network;

import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import java.util.*;

/** Unified view for active and completed network transfers. */
public class NetworkTransferActivity extends AppCompatActivity implements NetworkTransferQueue.Listener {
    private final List<String> rows=new ArrayList<>(); private ArrayAdapter<String> adapter; private List<NetworkTransferQueue.Job> jobs=List.of();
    @Override protected void onCreate(Bundle b){super.onCreate(b); LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        MaterialToolbar bar=new MaterialToolbar(this);bar.setTitle("Network Transfers");bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);bar.setNavigationOnClickListener(v->finish());root.addView(bar);
        LinearLayout actions=new LinearLayout(this);MaterialButton refresh=new MaterialButton(this);refresh.setText("Refresh");refresh.setOnClickListener(v->onQueueChanged(NetworkTransferQueue.get().snapshot()));MaterialButton clear=new MaterialButton(this);clear.setText("Clear finished");clear.setOnClickListener(v->{NetworkTransferQueue.get().clearFinished();});actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));actions.addView(clear,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);
        ListView list=new ListView(this);adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows);list.setAdapter(adapter);list.setOnItemClickListener((p,v,pos,id)->{NetworkTransferQueue.Job j=jobs.get(pos);if(j.state()==NetworkTransferQueue.State.QUEUED||j.state()==NetworkTransferQueue.State.RUNNING) new android.app.AlertDialog.Builder(this).setTitle(j.label()).setMessage(j.detail()).setPositiveButton("Cancel",(d,w)->NetworkTransferQueue.get().cancel(j.id())).setNegativeButton("Close",null).show();});root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);}
    @Override protected void onStart(){super.onStart();NetworkTransferQueue.get().addListener(this);}
    @Override protected void onStop(){NetworkTransferQueue.get().removeListener(this);super.onStop();}
    @Override public void onQueueChanged(List<NetworkTransferQueue.Job> j){jobs=j;runOnUiThread(()->{rows.clear();for(NetworkTransferQueue.Job x:j)rows.add(state(x.state())+"  "+x.label()+"\n"+x.detail());if(adapter!=null)adapter.notifyDataSetChanged();});}
    private static String state(NetworkTransferQueue.State s){return switch(s){case QUEUED->"QUEUED";case RUNNING->"RUNNING";case COMPLETED->"DONE";case FAILED->"FAILED";case CANCELLED->"CANCELLED";};}
}
