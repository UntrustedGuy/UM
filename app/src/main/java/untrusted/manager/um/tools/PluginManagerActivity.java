package untrusted.manager.um.tools;

import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

import java.util.List;

import untrusted.manager.um.R;
import untrusted.manager.um.plugin.PluginDescriptor;
import untrusted.manager.um.plugin.PluginManager;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

public final class PluginManagerActivity extends AppCompatActivity {
    private LinearLayout list;
    @Override protected void onCreate(Bundle state) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        setTheme(androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getInt("theme", dark ? R.style.Theme_MyApp_Dark : R.style.Theme_MyApp_Light));
        super.onCreate(state);
        DynamicColors.applyToActivitiesIfAvailable(getApplication());
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK));
        MaterialToolbar bar = new MaterialToolbar(this); bar.setTitle("Plugins");
        bar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material); bar.setNavigationOnClickListener(v -> finish());
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root); EdgeToEdgeUtil.applyContentInsets(this); refresh();
    }
    private void refresh() {
        list.removeAllViews(); List<PluginDescriptor> plugins = PluginManager.discover(this);
        if (plugins.isEmpty()) { TextView empty = new TextView(this); empty.setText("No compatible plugins installed."); empty.setPadding(dp(20), dp(20), dp(20), dp(20)); list.addView(empty); return; }
        for (PluginDescriptor p : plugins) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL); labels.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            TextView name = new TextView(this); name.setText(p.name); name.setTextSize(16); labels.addView(name);
            TextView sub = new TextView(this); sub.setText(p.description + "\n" + p.packageName + " • API " + p.apiVersion); sub.setTextSize(12); sub.setAlpha(.7f); labels.addView(sub);
            Switch sw = new Switch(this); sw.setChecked(PluginManager.isEnabled(this, p)); sw.setOnCheckedChangeListener((b, checked) -> PluginManager.setEnabled(this, p, checked));
            top.addView(labels); top.addView(sw); row.addView(top); list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    @Override protected void onResume() { super.onResume(); if (list != null) refresh(); }
}
