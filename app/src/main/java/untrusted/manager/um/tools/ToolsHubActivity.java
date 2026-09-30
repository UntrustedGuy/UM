package untrusted.manager.um.tools;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.ImageViewCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import untrusted.manager.um.R;
import untrusted.manager.um.ui.UiFields;
import untrusted.manager.um.utils.EdgeToEdgeUtil;

public final class ToolsHubActivity extends AppCompatActivity {
    private RecyclerView grid;
    private EditText searchInput;
    private ToolAdapter adapter;
    private List<ToolRegistry.ToolItem> allTools = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(prefs.getInt("theme", dark ? R.style.Theme_MyApp_Dark : R.style.Theme_MyApp_Light));
        super.onCreate(savedInstanceState);
        DynamicColors.applyToActivitiesIfAvailable(getApplication());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK));

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("Tools Kit");
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextInputLayout searchBox = UiFields.box(this, "Search tools");
        searchInput = new TextInputEditText(this);
        searchInput.setSingleLine(true);
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(12);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(pad, pad, pad, dp(4));
        root.addView(searchBox, searchParams);

        grid = new RecyclerView(this);
        GridLayoutManager layout = new GridLayoutManager(this, 3);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter != null && adapter.isHeader(position) ? 3 : 1;
            }
        });
        grid.setLayoutManager(layout);
        int gridPad = dp(8);
        grid.setPadding(gridPad, gridPad, gridPad, gridPad);
        grid.setClipToPadding(false);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        EdgeToEdgeUtil.applyContentInsets(this);

        allTools = ToolRegistry.getTools(this);
        toolbar.setSubtitle(allTools.size() + " tools");
        adapter = new ToolAdapter(buildRows(allTools));
        grid.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setRows(buildRows(filterTools(s == null ? "" : s.toString())));
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private List<ToolRegistry.ToolItem> filterTools(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return new ArrayList<>(allTools);
        List<ToolRegistry.ToolItem> result = new ArrayList<>();
        for (ToolRegistry.ToolItem item : allTools) {
            String hay = (item.title() + " " + item.subtitle() + " " + item.id() + " " + item.category())
                    .toLowerCase(Locale.ROOT);
            if (hay.contains(q)) result.add(item);
        }
        return result;
    }

    private List<Object> buildRows(List<ToolRegistry.ToolItem> items) {
        Map<String, List<ToolRegistry.ToolItem>> grouped = new LinkedHashMap<>();
        for (String cat : ToolRegistry.categoriesInOrder()) grouped.put(cat, new ArrayList<>());
        for (ToolRegistry.ToolItem item : items) {
            grouped.computeIfAbsent(item.category(), k -> new ArrayList<>()).add(item);
        }
        List<Object> rows = new ArrayList<>();
        for (Map.Entry<String, List<ToolRegistry.ToolItem>> e : grouped.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            rows.add(e.getKey());
            rows.addAll(e.getValue());
        }
        return rows;
    }

    private void openTool(ToolRegistry.ToolItem item) {
        Intent intent;
        switch (item.id()) {
            case "wifimanager" -> intent = new Intent(this, WifiManagerActivity.class);
            case "storagemanager" -> intent = new Intent(this, StorageManagerActivity.class);
            case "patcher", "luckypatcher" -> intent = new Intent(this, untrusted.manager.um.patcher.PatcherActivity.class);
            case "rootmanager" -> intent = new Intent(this, RootManagerActivity.class);
            case "gameanalyzer", "metadata", "encryption", "gamemodding" ->
                    intent = new Intent(this, untrusted.manager.um.gameanalysis.GameAnalysisActivity.class);
            case "il2cppeditor" -> intent = new Intent(this, untrusted.manager.um.gameanalysis.Il2CppEditorActivity.class);
            case "frida" -> intent = new Intent(this, untrusted.manager.um.gameanalysis.FridaToolkitActivity.class);
            case "dlleditor" -> intent = new Intent(this, DllEditorActivity.class);
            default -> {
                intent = new Intent(this, ToolRunnerActivity.class);
                intent.putExtra("tool_id", item.id());
                intent.putExtra("tool_title", item.title());
            }
        }
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class ToolAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Object> rows;
        ToolAdapter(List<Object> initial) { rows = initial; }
        void setRows(List<Object> next) {
            rows = next;
            notifyDataSetChanged();
            if (grid != null) grid.scrollToPosition(0);
        }
        boolean isHeader(int position) { return rows.get(position) instanceof String; }
        @Override public int getItemViewType(int position) { return isHeader(position) ? 0 : 1; }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float density = parent.getContext().getResources().getDisplayMetrics().density;
            if (viewType == 0) {
                TextView header = new TextView(parent.getContext());
                header.setTextSize(15);
                header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                header.setTextColor(MaterialColors.getColor(parent.getContext(),
                        com.google.android.material.R.attr.colorPrimary, Color.WHITE));
                header.setPadding(dp(6), dp(12), dp(6), dp(4));
                header.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new HeaderHolder(header);
            }

            MaterialCardView card = new MaterialCardView(parent.getContext());
            card.setRadius(16 * density);
            card.setCardElevation(2 * density);
            card.setStrokeWidth(Math.max(1, dp(1)));
            card.setStrokeColor(MaterialColors.getColor(parent.getContext(),
                    com.google.android.material.R.attr.colorOutline, Color.GRAY));
            card.setCardBackgroundColor(MaterialColors.getColor(parent.getContext(),
                    com.google.android.material.R.attr.colorSurfaceContainer, Color.BLACK));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int margin = dp(6);
            params.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(params);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout box = new LinearLayout(parent.getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            int p = dp(12);
            box.setPadding(p, p, p, p);

            ImageView icon = new ImageView(parent.getContext());
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
            iconParams.gravity = Gravity.CENTER;
            box.addView(icon, iconParams);

            TextView title = new TextView(parent.getContext());
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextSize(13);
            box.addView(title);

            TextView subtitle = new TextView(parent.getContext());
            subtitle.setGravity(Gravity.CENTER);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            subtitle.setTextSize(10);
            subtitle.setAlpha(0.7f);
            box.addView(subtitle);

            card.addView(box);
            return new ToolViewHolder(card, icon, title, subtitle);
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object row = rows.get(position);
            if (holder instanceof HeaderHolder h) {
                String cat = (String) row;
                int count = 0;
                for (int i = position + 1; i < rows.size() && rows.get(i) instanceof ToolRegistry.ToolItem; i++) count++;
                h.label.setText(cat + "  (" + count + ")");
            } else if (holder instanceof ToolViewHolder h) {
                ToolRegistry.ToolItem item = (ToolRegistry.ToolItem) row;
                h.icon.setImageResource(item.iconRes());
                ImageViewCompat.setImageTintList(h.icon, ColorStateList.valueOf(MaterialColors.getColor(
                        h.card.getContext(), com.google.android.material.R.attr.colorPrimary, Color.WHITE)));
                h.title.setText(item.title());
                h.subtitle.setText(item.subtitle());
                h.card.setOnClickListener(v -> openTool(item));
            }
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label;
        HeaderHolder(@NonNull View itemView) { super(itemView); label = (TextView) itemView; }
    }

    private static final class ToolViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView icon;
        final TextView title;
        final TextView subtitle;
        ToolViewHolder(@NonNull View itemView, ImageView icon, TextView title, TextView subtitle) {
            super(itemView); card = (MaterialCardView) itemView; this.icon = icon; this.title = title; this.subtitle = subtitle;
        }
    }
}
