package untrusted.manager.um.plugin.api;

import android.app.Activity;
import android.os.Bundle;

/** Base activity for a plugin feature. The host launches the plugin as a normal feature. */
public abstract class UmPluginActivity extends Activity {
    protected final String pluginId() {
        return getIntent() == null ? null : getIntent().getStringExtra(UmPluginContract.EXTRA_PLUGIN_ID);
    }

    protected final String inputPath() {
        return getIntent() == null ? null : getIntent().getStringExtra(UmPluginContract.EXTRA_INPUT_PATH);
    }

    protected final android.net.Uri inputUri() {
        return getIntent() == null ? null : getIntent().getParcelableExtra(UmPluginContract.EXTRA_INPUT_URI);
    }

    protected final String inputMime() {
        return getIntent() == null ? null : getIntent().getStringExtra(UmPluginContract.EXTRA_INPUT_MIME);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
