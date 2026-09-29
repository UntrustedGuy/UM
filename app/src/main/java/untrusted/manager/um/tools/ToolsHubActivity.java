package untrusted.manager.um.tools;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import untrusted.manager.um.R;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

/**
 * Unified in-app Tools hub. It is a launcher/catalog for the tools already
 * implemented by ToolRunnerActivity and the network/storage activities.
 */
public final class ToolsHubActivity extends AppCompatActivity {
    private final List<ToolRegistry.ToolItem> allTools = new ArrayList<>();
    private LinearLayout content;
    private EditText search;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle("Tools");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorSurface, 0));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Tools");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search tools");
        search.setPadding(dp(16), dp(8), dp(16), dp(8));
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(24));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        EdgeToEdgeUtil.applyContentInsets(this);

        allTools.addAll(ToolRegistry.getTools(this));
        render("");

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                render(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void render(String query) {
        content.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        String lastCategory = null;
        int matches = 0;

        for (ToolRegistry.ToolItem item : allTools) {
            if (!q.isEmpty()) {
                String hay = (item.title() + " " + item.subtitle() + " " + item.id() + " " + item.category())
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            matches++;
            if (!item.category().equals(lastCategory)) {
                TextView heading = new TextView(this);
                heading.setText(item.category());
                heading.setTextSize(14);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setPadding(dp(8), dp(16), dp(8), dp(6));
                content.addView(heading);
                lastCategory = item.category();
            }
            content.addView(makeToolRow(item));
        }

        if (matches == 0) {
            TextView empty = new TextView(this);
            empty.setText("No tools found");
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(48), dp(16), dp(48));
            content.addView(empty);
        }
    }

    private View makeToolRow(ToolRegistry.ToolItem item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setOnClickListener(v -> launch(item));

        TextView title = new TextView(this);
        title.setText(item.title());
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText(item.subtitle());
        subtitle.setTextSize(13);
        subtitle.setAlpha(0.75f);
        subtitle.setPadding(0, dp(3), 0, 0);

        row.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void launch(ToolRegistry.ToolItem item) {
        Intent intent = new Intent(this, ToolRunnerActivity.class);
        intent.putExtra("tool_id", item.id());
        intent.putExtra("tool_title", item.title());
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
