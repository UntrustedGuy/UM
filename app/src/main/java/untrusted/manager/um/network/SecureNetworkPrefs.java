package untrusted.manager.um.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small encrypted preference helper for network credentials. Non-secret connection metadata stays in normal prefs. */
public final class SecureNetworkPrefs {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "untrusted.manager.network.credentials.v1";
    private static final String ENC_PREFIX = "enc:v1:";
    private SecureNetworkPrefs() {}

    public static String get(Context context, SharedPreferences prefs, String key, String fallback) {
        String value = prefs.getString(key, null);
        if (value == null || value.isEmpty()) return fallback;
        if (!value.startsWith(ENC_PREFIX)) return value; // one-time migration of legacy plaintext values
        try {
            byte[] packed = Base64.decode(value.substring(ENC_PREFIX.length()), Base64.NO_WRAP);
            if (packed.length < 13) return fallback;
            byte[] iv = new byte[12];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            byte[] cipherText = new byte[packed.length - iv.length];
            System.arraycopy(packed, iv.length, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static void put(SharedPreferences.Editor editor, String key, String value) {
        if (value == null) value = "";
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            editor.putString(key, ENC_PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot protect network credential", e);
        }
    }

    public static void migrate(Context context, SharedPreferences prefs, String... keys) {
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : keys) {
            String raw = prefs.getString(key, null);
            if (raw != null && !raw.isEmpty() && !raw.startsWith(ENC_PREFIX)) {
                put(editor, key, raw);
                changed = true;
            }
        }
        if (changed) editor.apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
