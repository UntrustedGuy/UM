package untrusted.manager.um.UMManager.ftp;

import android.content.Context;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App-private AES-GCM protection for saved FTP/FTPS profiles. */
final class FtpProfileCrypto {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "untrusted-manager-ftp-profiles";
    private static final String PREFIX = "v1:";
    private static final int GCM_BITS = 128;

    private FtpProfileCrypto() {}

    static String encrypt(Context context, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(data, Base64.NO_WRAP);
    }

    static String decrypt(Context context, String encoded) throws Exception {
        if (encoded == null || !encoded.startsWith(PREFIX)) throw new IllegalArgumentException("Not encrypted");
        String[] parts = encoded.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Invalid encrypted profile data");
        byte[] iv = Base64.decode(parts[0], Base64.DEFAULT);
        byte[] data = Base64.decode(parts[1], Base64.DEFAULT);
        if (iv.length < 12) throw new IllegalArgumentException("Invalid IV");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_BITS, iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance("AES", KEYSTORE);
        kg.init(256);
        return kg.generateKey();
    }

    static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }
}
