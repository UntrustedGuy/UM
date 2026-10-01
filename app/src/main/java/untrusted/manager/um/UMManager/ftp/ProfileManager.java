package untrusted.manager.um.UMManager.ftp;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import untrusted.manager.um.R;

public class ProfileManager {
    private static final String PREFS_NAME = "FtpProfiles";
    private static final String PROFILES_KEY = "profiles";
    private static final String ENCRYPTED_PROFILES_KEY = "profiles_v2";
    private final String DEFAULT_SERVER_PROFILE;
    private final String DEFAULT_CLIENT_PROFILE;
    private final SharedPreferences prefs;
    private final Context context;
    private List<FtpProfile> profiles;
    private final Gson gson;

    public ProfileManager(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        DEFAULT_SERVER_PROFILE = context.getString(R.string.default_server);
        DEFAULT_CLIENT_PROFILE = context.getString(R.string.default_client);
        gson = new Gson();
        loadProfiles();
        ensureDefaultProfiles();
    }

    private void loadProfiles() {
        profiles = new ArrayList<>();
        try {
            String encrypted = prefs.getString(ENCRYPTED_PROFILES_KEY, null);
            if (encrypted != null && !encrypted.isEmpty()) {
                String json = FtpProfileCrypto.decrypt(context, encrypted);
                parseProfiles(json);
                return;
            }
        } catch (Exception ignored) {
            // A restored preference can outlive its Android Keystore key.
            // Fall back to legacy data if present rather than crashing the FTP UI.
        }

        String legacy = prefs.getString(PROFILES_KEY, null);
        if (legacy != null && !legacy.isEmpty()) {
            try {
                parseProfiles(legacy);
                saveProfiles(); // migrate plaintext profiles immediately
            } catch (Exception ignored) {
                profiles.clear();
            }
        }
    }

    private void parseProfiles(String json) {
        Type type = new TypeToken<List<FtpProfile>>(){}.getType();
        List<FtpProfile> loaded = gson.fromJson(json, type);
        profiles = loaded != null ? new ArrayList<>(loaded) : new ArrayList<>();
        for (int i = profiles.size() - 1; i >= 0; i--) {
            FtpProfile p = profiles.get(i);
            if (p == null || p.getName() == null || p.getName().trim().isEmpty()) profiles.remove(i);
            else if (p.getPort() < 1 || p.getPort() > 65535) p.setPort(2121);
            if (p != null && p.getSecurityType() < 0) p.setSecurityType(0);
        }
    }

    private void saveProfiles() {
        try {
            String json = gson.toJson(profiles);
            String encrypted = FtpProfileCrypto.encrypt(context, json);
            prefs.edit()
                    .putString(ENCRYPTED_PROFILES_KEY, encrypted)
                    .remove(PROFILES_KEY)
                    .apply();
        } catch (Exception e) {
            // Never silently write passwords back to plaintext if the Keystore is unavailable.
            throw new IllegalStateException("Unable to securely save FTP profiles", e);
        }
    }

    private void ensureDefaultProfiles() {
        if (profiles.isEmpty()) {
            // Add default server profile
            profiles.add(new FtpProfile(
                    DEFAULT_SERVER_PROFILE,
                    "192.168.1.1",
                    2121,
                    "admin",
                    "admin",
                    true
            ));

            // Add default client profile
            profiles.add(new FtpProfile(
                    DEFAULT_CLIENT_PROFILE,
                    "192.168.1.1",
                    2121,
                    "admin",
                    "admin",
                    false
            ));

            saveProfiles();
        }
    }

    public void addProfile(FtpProfile profile) {
        profiles.add(profile);
        saveProfiles();
    }

    public void updateProfile(int index, FtpProfile profile) {
        if (index >= 0 && index < profiles.size()) {
            profiles.set(index, profile);
            saveProfiles();
        }
    }

    public void deleteProfile(int index) {
        if (profiles.size() > 1) { // Don't allow deleting the last profile
            profiles.remove(index);
            saveProfiles();
        }
    }

    public List<FtpProfile> getProfiles() {
        return new ArrayList<>(profiles); // Return a copy to prevent external modification
    }

    public FtpProfile getProfile(int index) {
        if (index >= 0 && index < profiles.size()) {
            return profiles.get(index);
        }
        return null;
    }
}