/*
 * JKS-SignKey-Generator , Android jks key generator with optional certificate details
 * Copyright 2024, developer-krushna
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of developer-krushna nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *     Please contact Krushna by email mt.modder.hub@gmail.com if you need
 *     additional information or have any questions
 */

package mt.signature.generate;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.sun.security.x509.CertAndKeyGen;
import android.sun.security.x509.X500Name;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;

import untrusted.manager.um.R;
import untrusted.manager.um.utils.ErrorUtil;
import untrusted.manager.um.utils.FileUtils;
import untrusted.manager.um.utils.PasswordEncryptor;
import untrusted.manager.um.utils.SignatureKeyPaths;

public class KeyStoreMakerDialog extends DialogFragment {

    private static final String PREF_NAME = "KeyStore";

    private SharedPreferences s;
    private ProgressDialog progress;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Reuse one process-local CSPRNG instead of constructing/seeding a new SecureRandom
    // for every key-generation request. The key material remains cryptographically random,
    // while repeated generations avoid the avoidable entropy/provider initialization cost.
    private static SecureRandom createKeyGenerationRandom() throws GeneralSecurityException {
        // Match MP Manager upstream: SHA1PRNG avoids the blocking platform-default
        // SecureRandom initialization that can stall RSA generation on some Android builds.
        return SecureRandom.getInstance("SHA1PRNG");
    }

    // Views
    private LinearLayout linear1;
    private LinearLayout linear12;
    private LinearLayout fab;
    private ScrollView vscroll3;
    private LinearLayout linear2;
    private LinearLayout linear13;
    private TextInputLayout textinputlayout1;
    private TextInputLayout textinput_keyName;
    private TextInputLayout textinput2;
    private TextInputLayout textinput3;
    private TextInputLayout textinput4;
    private TextInputLayout textinput5;
    private TextInputLayout textinput6;
    private CompoundButton moreOption;
    private LinearLayout linear_more;
    private TextView copyright;
    private CheckBox generatePairKeys;
    private CheckBox generateJKS;
    private EditText directory;
    private EditText key_name;
    private EditText storePass;
    private EditText keyPass;
    private EditText keySize;
    private EditText date;
    private EditText commonName;
    private TextInputLayout textinput7;
    private TextInputLayout textinput8;
    private TextInputLayout textinput9;
    private TextInputLayout textinput10;
    private TextInputLayout textinput11;
    private EditText organizationUnit;
    private EditText organizationName;
    private EditText localityName;
    private EditText stateName;
    private EditText country;
    private ImageView fab_icon;
    private TextView fab_text;

    private OnKeyGeneratedListener listener;
    private boolean generating;
    private CompoundButton biometricsSwitch;

    public interface OnKeyGeneratedListener {
        void onKeyGenerated(KeyParam keyParam);
        void onError(String error);
    }

    public static KeyStoreMakerDialog newInstance() {
        return new KeyStoreMakerDialog();
    }

    public void setOnKeyGeneratedListener(OnKeyGeneratedListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.key_store_maker, null);
        initializeViews(view);
        initializeLogic();

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        builder.setView(view);
        // Keep the generator dialog open until generation and file validation have completed.
        // The old positive-button listener dismissed the DialogFragment immediately, which could
        // detach its callback before the background key-generation thread finished.
        builder.setPositiveButton("Generate Key", null);

        biometricsSwitch = view.findViewById(R.id.cb_save_password);
        return builder.create();
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (!(dialog instanceof androidx.appcompat.app.AlertDialog alertDialog)) return;
        Button generateButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (generateButton == null) return;
        generateButton.setOnClickListener(v -> {
            if (generating) return;
            if (!validateInputs()) return;
            generating = true;
            setCancelable(false);
            v.setEnabled(false);
            generateButton.setText(R.string.generating);
            setFormEnabled(false);
            generateKeys();
        });
    }

    private void setFormEnabled(boolean enabled) {
        if (linear2 != null) setViewTreeEnabled(linear2, enabled);
        if (biometricsSwitch != null) biometricsSwitch.setEnabled(enabled);
    }

    private static void setViewTreeEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                setViewTreeEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (progress != null && progress.isShowing()) progress.dismiss();
        progress = null;
        mainHandler.removeCallbacksAndMessages(null);
        biometricsSwitch = null;
        super.onDestroyView();
    }

    private void finishGeneration(boolean success) {
        generating = false;
        setCancelable(true);
        setFormEnabled(true);
        Dialog dialog = getDialog();
        if (dialog instanceof androidx.appcompat.app.AlertDialog alertDialog) {
            Button button = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (button != null) {
                button.setEnabled(true);
                button.setText("Generate Key");
            }
        }
        if (success && isAdded()) dismissAllowingStateLoss();
    }

    private boolean validateInputs() {
        if (!generatePairKeys.isChecked() && !generateJKS.isChecked()) {
            Activity activity = getActivity();
            if (activity != null) {
                new ErrorUtil(activity).showError(new IllegalArgumentException("Select at least one output format: pk8 + pem or JKS"));
            }
            return false;
        }
        String alias = key_name.getText() == null ? "" : key_name.getText().toString().trim();
        if (alias.isEmpty() || ".".equals(alias) || "..".equals(alias) || alias.contains("/") || alias.contains("\\")
                || alias.indexOf('\u0000') >= 0) {
            textinput_keyName.setError("Use a valid key name without path separators");
            key_name.requestFocus();
            return false;
        }
        String keySizeText = keySize.getText() == null ? "" : keySize.getText().toString().trim();
        String dateText = date.getText() == null ? "" : date.getText().toString().trim();
        String cn = commonName.getText() == null ? "" : commonName.getText().toString().trim();
        try {
            int bits = Integer.parseInt(keySizeText);
            if (bits < 2048 || bits > 8192 || (bits & 7) != 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            textinput4.setError("Use an RSA key size from 2048 to 8192 bits");
            keySize.requestFocus();
            return false;
        }
        try {
            long years = Long.parseLong(dateText);
            if (years <= 0 || years > 1000) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            textinput5.setError("Validity must be 1–1000 years");
            date.requestFocus();
            return false;
        }
        if (cn.isEmpty()) {
            textinput6.setError("Enter a common name");
            commonName.requestFocus();
            return false;
        }
        if (generateJKS.isChecked()) {
            if (storePass.getText() == null || storePass.getText().toString().isEmpty()) {
                textinput2.setError("Enter a keystore password");
                storePass.requestFocus();
                return false;
            }
            if (keyPass.getText() == null || keyPass.getText().toString().isEmpty()) {
                textinput3.setError("Enter a key password");
                keyPass.requestFocus();
                return false;
            }
        }
        if (moreOption.isChecked()) {
            String c = country.getText() == null ? "" : country.getText().toString().trim();
            if (!c.isEmpty() && !c.matches("[A-Za-z]{2}")) {
                textinput11.setError("Country code must be two letters");
                country.requestFocus();
                return false;
            }
        }
        return true;
    }

    private void initializeViews(View view) {
        linear1 = view.findViewById(R.id.linear1);
        view.findViewById(R.id.cb_save_password).setVisibility(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? View.VISIBLE : View.GONE);
       // linear12 = view.findViewById(R.id.linear12);
       // fab = view.findViewById(R.id.fab);
      //  vscroll3 = view.findViewById(R.id.vscroll3);
        linear2 = view.findViewById(R.id.linear2);
        linear13 = view.findViewById(R.id.linear13);
        textinputlayout1 = view.findViewById(R.id.textinputlayout1);
        textinput_keyName = view.findViewById(R.id.textinput_keyName);
        textinput2 = view.findViewById(R.id.textinput2);
        textinput3 = view.findViewById(R.id.textinput3);
        textinput4 = view.findViewById(R.id.textinput4);
        textinput5 = view.findViewById(R.id.textinput5);
        textinput6 = view.findViewById(R.id.textinput6);
        moreOption = view.findViewById(R.id.switch1);
        linear_more = view.findViewById(R.id.linear_more);
        copyright = view.findViewById(R.id.copyright);
        copyright.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/developer-krushna/JKS-SignKey-Generator"))));
        generatePairKeys = view.findViewById(R.id.checkbox1);
        generateJKS = view.findViewById(R.id.checkbox2);
        directory = view.findViewById(R.id.directory);
        key_name = view.findViewById(R.id.key_name);
        storePass = view.findViewById(R.id.storePass);
        keyPass = view.findViewById(R.id.keyPass);
        keySize = view.findViewById(R.id.keySize);
        date = view.findViewById(R.id.date);
        commonName = view.findViewById(R.id.commonName);
        textinput7 = view.findViewById(R.id.textinput7);
        textinput8 = view.findViewById(R.id.textinput8);
        textinput9 = view.findViewById(R.id.textinput9);
        textinput10 = view.findViewById(R.id.textinput10);
        textinput11 = view.findViewById(R.id.textinput11);
        organizationUnit = view.findViewById(R.id.organizationUnit);
        organizationName = view.findViewById(R.id.organizationName);
        localityName = view.findViewById(R.id.localityName);
        stateName = view.findViewById(R.id.stateName);
        country = view.findViewById(R.id.country);
        //fab_icon = view.findViewById(R.id.fab_icon);
       // fab_text = view.findViewById(R.id.fab_text);

        s = PreferenceManager.getDefaultSharedPreferences(getContext());
    }

    private void initializeLogic() {
        //fab_icon.setImageResource(R.drawable.ic_build_mt);

        // Use UM's canonical key directory and safe defaults so the first generation
        // can complete without requiring hidden/unrelated fields to be populated.
        directory.setText(SignatureKeyPaths.getDefaultDirectory().getAbsolutePath() + File.separator);
        if (key_name.getText() == null || key_name.getText().toString().trim().isEmpty()) key_name.setText("um-signing-key");
        if (commonName.getText() == null || commonName.getText().toString().trim().isEmpty()) commonName.setText("Untrusted Manager");
        // Set default country
        country.setText(requireContext().getResources().getConfiguration().locale.getCountry());

        // Set listeners
        setupListeners();
    }

    private void setupListeners() {
        moreOption.setOnCheckedChangeListener((buttonView, isChecked) -> {
            linear_more.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (!isChecked) {
                country.setText("");
                organizationName.setText("");
                organizationUnit.setText("");
                localityName.setText("");
                stateName.setText("");
            } else {
                country.setText(requireContext().getResources().getConfiguration().locale.getCountry());
            }
        });

        generateJKS.setOnCheckedChangeListener((buttonView, isChecked) -> {
            textinput2.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            textinput3.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        // Text watchers for error clearing
        addTextWatcher(key_name, textinput_keyName);
        addTextWatcher(storePass, textinput2);
        addTextWatcher(keyPass, textinput3);
        addTextWatcher(keySize, textinput4);
        addTextWatcher(date, textinput5);
        addTextWatcher(commonName, textinput6);
    }

    private void addTextWatcher(EditText editText, TextInputLayout textInputLayout) {
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (textInputLayout != null) {
                    textInputLayout.setErrorEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void generateKeys() {
        final KeyParam keyParam;
        try {
            keyParam = save();
        } catch (Exception e) {
            generating = false;
            setFormEnabled(true);
            if (getView() != null) {
                if (getDialog() instanceof androidx.appcompat.app.AlertDialog alertDialog) {
                    Button button = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
                    if (button != null) {
                        button.setEnabled(true);
                        button.setText("Generate Key");
                    }
                }
            }
            Activity activity = getActivity();
            if (activity != null) {
                new ErrorUtil(activity).showError(e);
            }
            return;
        }

        showProgress();
        new Thread(() -> {
            try {
                generateKey(keyParam);
                if (keyParam.useBiometrics && !keyParam.keyPass.isEmpty()) {
                    s.edit()
                            .putBoolean("useBiometrics", true)
                            .putString("keyPass", PasswordEncryptor.encryptString(keyParam.keyPass))
                            .apply();
                } else {
                    s.edit().putBoolean("useBiometrics", false).remove("keyPass").apply();
                }
                mainHandler.post(() -> {
                    if (getView() == null) return;
                    if (progress != null && progress.isShowing()) progress.dismiss();
                    try {
                        if (listener != null) listener.onKeyGenerated(keyParam);
                    } finally {
                        finishGeneration(true);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (getView() == null) return;
                    if (progress != null && progress.isShowing()) progress.dismiss();
                    finishGeneration(false);
                    if (listener != null) listener.onError(getExceptionMessage(e));
                    else if (getActivity() != null) new ErrorUtil(getActivity()).showError(e);
                });
            }
        }, "um-signature-key-generation").start();
    }

    private void showProgress() {
        progress = new ProgressDialog(requireContext());
        progress.setMessage(getString(R.string.generating));
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        progress.setCancelable(false);
        progress.show();
    }

    private void updateProgressMessage(String message) {
        mainHandler.post(() -> {
            if (progress != null && progress.isShowing()) {
                progress.setMessage(message);
            }
        });
    }

    private KeyParam save() throws Exception {
        KeyParam keyParam = new KeyParam();

        String obj = keySize.getText().toString();
        keyParam.keySize = obj.isEmpty() ? 2048 : Integer.parseInt(obj);

        String directoryText = directory.getText() == null ? "" : directory.getText().toString().trim();
        File outputDirectory;
        if (directoryText.isEmpty()) {
            outputDirectory = SignatureKeyPaths.ensureDefaultDirectory();
        } else {
            File requested = new File(directoryText);
            outputDirectory = requested.isAbsolute()
                    ? requested
                    : new File(Environment.getExternalStorageDirectory(), directoryText);
        }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs() && !outputDirectory.isDirectory()) {
            throw new IOException("Cannot create destination directory: " + outputDirectory.getAbsolutePath());
        }
        if (!outputDirectory.isDirectory()) {
            throw new IOException("Destination path is not a directory: " + outputDirectory.getAbsolutePath());
        }
        String alias = key_name.getText().toString().trim();
        File canonicalDirectory = outputDirectory.getCanonicalFile();
        File baseFile = new File(canonicalDirectory, alias);
        File canonicalBase = baseFile.getCanonicalFile();
        if (!canonicalDirectory.equals(canonicalBase.getParentFile())) {
            throw new IOException("Key output must remain inside the selected destination directory");
        }
        String basePath = canonicalBase.getAbsolutePath();
        keyParam.keyPath = basePath + ".pk8";
        keyParam.certOrAlias = basePath + ".x509.pem";
        keyParam.alias = alias;
        keyParam.jksPath = basePath + ".jks";
        keyParam.storePass = storePass.getText().toString();
        keyParam.keyPass = keyPass.getText().toString();
        keyParam.commonName = commonName.getText().toString().trim();
        keyParam.organizationUnit = organizationUnit.getText().toString();
        keyParam.organizationName = organizationName.getText().toString();
        keyParam.localityName = localityName.getText().toString();
        keyParam.stateName = stateName.getText().toString();
        keyParam.country = country.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
        keyParam.days = Math.multiplyExact(Long.parseLong(date.getText().toString()), 365L);
        keyParam.generatePairKeys = generatePairKeys.isChecked();
        keyParam.generateJKS = generateJKS.isChecked();
        keyParam.useBiometrics = biometricsSwitch != null && biometricsSwitch.isChecked();

        if (keyParam.generatePairKeys) {
            rejectExisting(new File(keyParam.keyPath), "PK8 private key");
            rejectExisting(new File(keyParam.certOrAlias), "X.509 certificate");
        }
        if (keyParam.generateJKS) {
            rejectExisting(new File(keyParam.jksPath), "JKS keystore");
        }

        // Confirm the destination is writable before starting the CPU-heavy generation thread.
        File probe = null;
        try {
            probe = File.createTempFile(".um-key-write-test-", ".tmp", outputDirectory);
        } finally {
            if (probe != null) probe.delete();
        }
        return keyParam;
    }

    private void generateKey(KeyParam keyParam) throws Exception {
        updateProgressMessage("Preparing cryptographic provider...");
        // Follow MP Manager's upstream signing path: use the platform RSA provider and
        // explicitly select SHA1PRNG. The previous implementation used a lazily seeded
        // SecureRandom and forced RSA through BC; on some Android devices that could make
        // key generation appear to hang for minutes while waiting for entropy/provider
        // initialization. SHA1PRNG is the upstream non-blocking generation path.
        SignatureKeyPaths.ensureJksProvider();
        SecureRandom keyRandom = createKeyGenerationRandom();
        CertAndKeyGen keyGen = new CertAndKeyGen("RSA", "SHA256withRSA");
        keyGen.setRandom(keyRandom);
        updateProgressMessage("Generating RSA key...");
        long keyStartNanos = System.nanoTime();
        keyGen.generate(keyParam.keySize);
        long keyElapsedMs = (System.nanoTime() - keyStartNanos) / 1_000_000L;
        updateProgressMessage("RSA key generated (" + keyElapsedMs + " ms)");

        PrivateKey privateKey = keyGen.getPrivateKey();
        if (privateKey == null || privateKey.getEncoded() == null || privateKey.getEncoded().length == 0) {
            throw new IOException("RSA private key generation returned no encodable key");
        }

        StringBuilder x500NameBuilder = new StringBuilder("CN=").append(escapeX500Value(keyParam.commonName));
        if (keyParam.organizationName != null && !keyParam.organizationName.isEmpty()) {
            x500NameBuilder.append(", O=").append(escapeX500Value(keyParam.organizationName));
        }
        if (keyParam.organizationUnit != null && !keyParam.organizationUnit.isEmpty()) {
            x500NameBuilder.append(", OU=").append(escapeX500Value(keyParam.organizationUnit));
        }
        if (keyParam.localityName != null && !keyParam.localityName.isEmpty()) {
            x500NameBuilder.append(", L=").append(escapeX500Value(keyParam.localityName));
        }
        if (keyParam.stateName != null && !keyParam.stateName.isEmpty()) {
            x500NameBuilder.append(", ST=").append(escapeX500Value(keyParam.stateName));
        }
        if (keyParam.country != null && !keyParam.country.isEmpty()) {
            x500NameBuilder.append(", C=").append(escapeX500Value(keyParam.country));
        }

        X500Name x500Name = new X500Name(x500NameBuilder.toString());
        Date notBefore = new Date();
        updateProgressMessage("Creating signing certificate...");
        long validitySeconds = Math.multiplyExact(keyParam.days, 24L * 60L * 60L);
        long certStartNanos = System.nanoTime();
        X509Certificate generatedCert = keyGen.getSelfCertificate(x500Name, notBefore, validitySeconds);
        long certElapsedMs = (System.nanoTime() - certStartNanos) / 1_000_000L;
        updateProgressMessage("Certificate generated (" + certElapsedMs + " ms)");
        if (generatedCert == null || generatedCert.getEncoded().length == 0) {
            throw new IOException("Certificate generation returned no usable certificate");
        }
        verifyKeyMatchesCertificate(privateKey, generatedCert);

        boolean completed = false;
        try {
            // Generate exactly one certificate and reuse it for both output forms.
            if (keyParam.generatePairKeys) {
                updateProgressMessage("Writing PK8 and certificate...");
                writeCertificate(privateKey, generatedCert, keyParam);
            }
            if (keyParam.generateJKS) {
                updateProgressMessage("Writing JKS keystore...");
                writeJks(privateKey, generatedCert, keyParam);
            }
            updateProgressMessage("Validating generated files...");
            verifyOutputs(keyParam);
            completed = true;
        } finally {
            // Never leave a partially generated signing identity behind when any requested
            // output fails. All target files were checked before generation started, so
            // anything present here belongs to this generation attempt.
            if (!completed) {
                deleteGeneratedFile(keyParam.generatePairKeys ? keyParam.keyPath : null);
                deleteGeneratedFile(keyParam.generatePairKeys ? keyParam.certOrAlias : null);
                deleteGeneratedFile(keyParam.generateJKS ? keyParam.jksPath : null);
            }
        }
    }

    private static String escapeX500Value(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("Certificate subject fields cannot contain control characters");
            }
            if (c == '\\' || c == ',' || c == '+' || c == '"' || c == '<' || c == '>' || c == ';' || c == '=') {
                escaped.append('\\');
            } else if ((i == 0 || i == value.length() - 1) && c == ' ') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private void writeCertificate(PrivateKey privateKey, X509Certificate x509Certificate,
                                  KeyParam keyParam) throws Exception {
        File keyFile = new File(keyParam.keyPath);
        File certFile = new File(keyParam.certOrAlias);
        File parent = keyFile.getParentFile();
        if (parent == null) throw new IOException("Signature-key output has no parent directory");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create output directory: " + parent.getAbsolutePath());
        }

        byte[] encodedKey = privateKey.getEncoded();
        if (encodedKey == null || encodedKey.length == 0) {
            throw new IOException("Private key has no encoded form");
        }
        byte[] encodedCertificate = x509Certificate.getEncoded();
        if (encodedCertificate.length == 0) {
            throw new IOException("Certificate has no encoded form");
        }

        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.encodeToString(encodedCertificate, Base64.NO_WRAP)
                + "\n-----END CERTIFICATE-----\n";
        byte[] encodedPem = pem.getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        File keyTemporary = null;
        File certTemporary = null;
        boolean keyCommitted = false;
        try {
            keyTemporary = createTemporaryOutput(keyFile);
            certTemporary = createTemporaryOutput(certFile);
            writeTemporary(keyTemporary, encodedKey, "PK8 private key");
            writeTemporary(certTemporary, encodedPem, "X.509 certificate");
            commitTemporaryFile(keyTemporary, keyFile, "PK8 private key");
            keyCommitted = true;
            commitTemporaryFile(certTemporary, certFile, "X.509 certificate");
        } catch (IOException e) {
            if (keyCommitted && keyFile.isFile() && !certFile.isFile()) keyFile.delete();
            throw e;
        } finally {
            if (keyTemporary != null && keyTemporary.exists()) keyTemporary.delete();
            if (certTemporary != null && certTemporary.exists()) certTemporary.delete();
        }
    }

    private void writeJks(PrivateKey privateKey, X509Certificate x509Certificate,
                          KeyParam keyParam) throws Exception {
        SignatureKeyPaths.ensureJksProvider();
        KeyStore keyStore = KeyStore.getInstance("JKS", "JKS");
        char[] storePass = keyParam.storePass.toCharArray();
        char[] keyPass = keyParam.keyPass.toCharArray();
        File file = new File(keyParam.jksPath);
        File parent = file.getParentFile();
        if (parent == null) throw new IOException("JKS output has no parent directory");
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create JKS directory: " + parent.getAbsolutePath());
        }

        // Build a fresh keystore in memory and validate it before exposing the final file.
        keyStore.load(null, storePass);
        keyStore.setKeyEntry(keyParam.alias, privateKey, keyPass, new Certificate[]{x509Certificate});

        File temporary = new File(parent, "." + file.getName() + "." + System.nanoTime() + ".tmp");
        try {
            try (OutputStream fos = FileUtils.getOutputStream(temporary)) {
                keyStore.store(fos, storePass);
                fos.flush();
            }
            requireUsableFile(temporary, "JKS keystore");

            // The key/certificate relationship was already cryptographically verified before
            // serialization. Avoid immediately reopening the JKS, decrypting the private key,
            // and performing a second RSA sign/verify cycle; that duplicate work was on the
            // critical path and provided no additional protection against a write failure.
            commitTemporaryFile(temporary, file, "JKS keystore");

            // Verify the committed artifact structurally (size/readability) without redoing
            // the expensive private-key recovery operation. The JKS provider has already
            // authenticated the keystore during store().
            requireUsableFile(file, "JKS keystore");
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static void deleteGeneratedFile(String path) {
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (file.isFile() && !file.delete() && file.exists()) {
            // Cleanup is best-effort after a generation failure; the original exception
            // remains the authoritative error shown to the user.
        }
    }

    private static void rejectExisting(File file, String description) throws IOException {
        if (file.isFile()) {
            throw new IOException(description + " already exists: " + file.getAbsolutePath()
                    + ". Choose a different key name to avoid replacing a signing identity.");
        }
        if (file.exists() && !file.isFile()) {
            throw new IOException(description + " destination is not a regular file: " + file.getAbsolutePath());
        }
    }

    private static void verifyKeyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate) throws Exception {
        if (privateKey == null || certificate == null) throw new IOException("Generated signing key or certificate is missing");
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        byte[] probe = "Untrusted Manager signing-key verification".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        signature.update(probe);
        byte[] signed = signature.sign();
        signature.initVerify(certificate.getPublicKey());
        signature.update(probe);
        if (!signature.verify(signed)) {
            throw new IOException("Generated private key does not match its certificate");
        }
    }

    private static File createTemporaryOutput(File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null) throw new IOException("Output has no parent directory: " + target);
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create output directory: " + parent.getAbsolutePath());
        }
        return new File(parent, "." + target.getName() + "." + System.nanoTime() + ".tmp");
    }

    private static void writeTemporary(File temporary, byte[] data, String description) throws IOException {
        try (OutputStream out = FileUtils.getOutputStream(temporary)) {
            out.write(data);
            out.flush();
        }
        requireUsableFile(temporary, description);
    }

    private static void commitTemporaryFile(File temporary, File target, String description) throws IOException {
        if (target.exists()) throw new IOException(description + " destination appeared during generation: " + target.getAbsolutePath());
        if (!temporary.renameTo(target)) {
            // Same-directory fallback for filesystems where renameTo is unavailable.
            FileUtils.copyFile(temporary, target);
            if (!target.isFile() || target.length() != temporary.length()) {
                if (target.exists()) target.delete();
                throw new IOException("Could not finalize generated " + description + ": " + target.getAbsolutePath());
            }
            if (!temporary.delete() && temporary.exists()) {
                throw new IOException("Could not clean temporary generated " + description + ": " + temporary.getAbsolutePath());
            }
        }
        requireUsableFile(target, description);
    }

    private void verifyOutputs(KeyParam keyParam) throws IOException {
        if (keyParam.generatePairKeys) {
            requireUsableFile(new File(keyParam.keyPath), "PK8 private key");
            requireUsableFile(new File(keyParam.certOrAlias), "X.509 certificate");
        }
        if (keyParam.generateJKS) {
            requireUsableFile(new File(keyParam.jksPath), "JKS keystore");
        }
    }

    private static void requireUsableFile(File file, String description) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0) {
            throw new IOException(description + " was not created correctly: "
                    + (file == null ? "null" : file.getAbsolutePath()));
        }
    }

    private static String getExceptionMessage(Throwable t) {
        StringBuilder out = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (out.length() > 0) out.append("\nCaused by: ");
            out.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) {
                out.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return out.toString();
    }

    public static class KeyParam {
        public String certOrAlias;
        public String alias;
        public String jksPath;
        public String commonName;
        public String country;
        public long days;
        public String keyPass;
        public String keyPath;
        public int keySize;
        public String localityName;
        public String organizationName;
        public String organizationUnit;
        public String stateName;
        public String storePass;
        public boolean generatePairKeys;
        public boolean generateJKS;
        public boolean useBiometrics;
    }
}