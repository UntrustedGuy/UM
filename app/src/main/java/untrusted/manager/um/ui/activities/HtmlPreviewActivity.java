package untrusted.manager.um.ui.activities;

import untrusted.manager.um.utils.EdgeToEdgeUtil;
import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;

/**
 * Sandboxed local HTML preview used by the file manager.
 *
 * The preview intentionally does not grant universal file access or clear-text
 * network access to file:// pages. Local subresources remain available so an
 * HTML project can preview its sibling CSS/images, while JavaScript cannot use
 * the file origin to reach arbitrary network origins.
 */
public class HtmlPreviewActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_CLEANUP = "cleanup";

    private WebView webView;
    private String previewPath;
    private boolean cleanup;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        previewPath = getIntent().getStringExtra(EXTRA_PATH);
        cleanup = getIntent().getBooleanExtra(EXTRA_CLEANUP, false);

        if (previewPath == null || previewPath.trim().isEmpty()) {
            finish();
            return;
        }
        File html = new File(previewPath);
        if (!html.isFile() || !html.canRead()) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.TRANSPARENT);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(html.getName());
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadsImagesAutomatically(true);
        webView.setOverScrollMode(WebView.OVER_SCROLL_IF_CONTENT_SCROLLS);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root); EdgeToEdgeUtil.applyContentInsets(this);

        webView.loadUrl(html.toURI().toString());
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        if (cleanup && previewPath != null) {
            try {
                File staged = new File(previewPath);
                // Only temporary preview files are ever marked for cleanup.
                if (staged.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath() + File.separator)) {
                    //noinspection ResultOfMethodCallIgnored
                    staged.delete();
                }
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }
}
