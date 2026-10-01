package untrusted.manager.um.utils;

import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.security.Provider;
import java.security.Security;

import android.sun.security.provider.JavaKeyStoreProvider;

/**
 * Shared storage and provider handling for APK-signing keys.
 *
 * The old source used two different directories (UM and MT2) depending on
 * which signing screen was opened. Keep the MT2 directory as a read-only
 * legacy location for discovering/importing old keys, but use UM's directory
 * for all new keys and imports.
 */
public final class SignatureKeyPaths {
    private static final String DEFAULT_DIR = "Untrusted Manager/keys";
    private static final String LEGACY_DIR = "MT2/keys";

    private SignatureKeyPaths() {
    }

    public static File getDefaultDirectory() {
        return new File(Environment.getExternalStorageDirectory(), DEFAULT_DIR);
    }

    public static File getLegacyDirectory() {
        return new File(Environment.getExternalStorageDirectory(), LEGACY_DIR);
    }

    public static File ensureDefaultDirectory() throws IOException {
        File dir = getDefaultDirectory();
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create signature-key directory: " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static synchronized void ensureJksProvider() {
        Provider provider = Security.getProvider("JKS");
        if (provider == null) {
            Security.addProvider(new JavaKeyStoreProvider());
        }
        if (Security.getProvider("JKS") == null) {
            throw new IllegalStateException("JKS security provider is unavailable");
        }
    }
}
