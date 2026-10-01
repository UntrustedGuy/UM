package untrusted.manager.um.ui.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import rikka.shizuku.ShizukuRemoteProcess;
import rikka.shizuku.ShizukuProcessFactory;
import untrusted.manager.um.R;
import untrusted.manager.um.UMManager.shizuku.ShizukuShell;
import untrusted.manager.um.utils.RootManager;

/**
 * Persistent interactive shell UI. Commands are sent to one long-lived shell
 * process, so shell state such as cd/export/aliases is retained between lines.
 */
public class TerminalActivity extends AppCompatActivity {
    private static final String PREFS = "um_terminal";
    private static final String HISTORY = "history";
    private static final int MAX_HISTORY = 200;
    private static final int MAX_OUTPUT = 4 * 1024 * 1024;

    private TextView output;
    private EditText input;
    private Spinner modeSpinner;
    private MaterialButton sendButton;
    private TextView status;
    private ShellSession session;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private SharedPreferences prefs;

    public static void open(Context context, @Nullable String workingDirectory) {
        Intent intent = new Intent(context, TerminalActivity.class);
        if (workingDirectory != null) intent.putExtra("working_directory", workingDirectory);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(buildView());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadHistory();
        String cwd = getIntent().getStringExtra("working_directory");
        if (cwd == null || cwd.trim().isEmpty() || !new File(cwd).isDirectory()) cwd = getDefaultDirectory();
        startSession(cwd, 0);
    }

    private View buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.terminal);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        toolbar.setNavigationOnClickListener(v -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeRow.setPadding(dp(8), dp(4), dp(8), dp(4));
        modeSpinner = new Spinner(this);
        String[] modes = {"Shell", "Shizuku shell", "Root shell"};
        modeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, modes));
        modeRow.addView(modeSpinner, new LinearLayout.LayoutParams(0, -2, 1f));
        MaterialButton clear = new MaterialButton(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> clearOutput());
        modeRow.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        MaterialButton info = new MaterialButton(this);
        info.setText(android.R.string.dialog_alert_title);
        info.setOnClickListener(v -> showTerminalInfo());
        modeRow.addView(info, new LinearLayout.LayoutParams(-2, -2));
        root.addView(modeRow);

        status = new TextView(this);
        status.setPadding(dp(12), dp(2), dp(12), dp(4));
        status.setTextSize(12);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setMovementMethod(new ScrollingMovementMethod());
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(13);
        output.setPadding(dp(10), dp(8), dp(10), dp(8));
        scroll.addView(output, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTypeface(Typeface.MONOSPACE);
        input.setHint("command");
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendCommand();
                return true;
            }
            return false;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { navigateHistory(-1); return true; }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { navigateHistory(1); return true; }
            return false;
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        sendButton = new MaterialButton(this);
        sendButton.setText(R.string.send);
        sendButton.setOnClickListener(v -> sendCommand());
        inputRow.addView(sendButton, new LinearLayout.LayoutParams(-2, -2));
        MaterialButton interrupt = new MaterialButton(this);
        interrupt.setText("Ctrl+C");
        interrupt.setOnClickListener(v -> sendControlC());
        inputRow.addView(interrupt, new LinearLayout.LayoutParams(-2, -2));
        root.addView(inputRow);

        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (session != null) startSession(session.getWorkingDirectory(), position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        return root;
    }

    private void startSession(String cwd, int mode) {
        stopSession();
        ShellSession newSession = null;
        try {
            if (mode == 1) newSession = new ShizukuSession(cwd);
            else if (mode == 2) newSession = new RootSession(this, cwd);
            else newSession = new LocalSession(cwd);
            session = newSession;
            session.start(new OutputSink() {
                @Override public void append(String text) { appendOutput(text); }
                @Override public void exited(int code) { main.post(() -> status.setText("Session exited (" + code + ")")); }
            });
            status.setText(mode == 0 ? "Shell • " + cwd : (mode == 1 ? "Shizuku shell • " + cwd : "Root shell • " + cwd));
        } catch (Exception e) {
            status.setText("Session failed");
            appendOutput("[terminal] " + message(e) + "\n");
        }
    }

    private void sendCommand() {
        String command = input.getText().toString();
        if (command.trim().isEmpty()) return;
        if (session == null || !session.isAlive()) {
            startSession(session == null ? getDefaultDirectory() : session.getWorkingDirectory(), modeSpinner.getSelectedItemPosition());
            if (session == null || !session.isAlive()) return;
        }
        remember(command);
        appendOutput("$ " + command + "\n");
        input.setText("");
        try {
            session.write(command + "\n");
        } catch (IOException e) {
            appendOutput("[write error] " + message(e) + "\n");
        }
    }

    private void sendControlC() {
        if (session == null || !session.isAlive()) return;
        try {
            session.write("\u0003");
            appendOutput("^C\n");
        } catch (IOException e) {
            appendOutput("[interrupt error] " + message(e) + "\n");
        }
    }

    private void appendOutput(String text) {
        main.post(() -> {
            if (output == null) return;
            String existing = output.getText().toString();
            if (existing.length() + text.length() > MAX_OUTPUT) {
                int keep = Math.max(0, MAX_OUTPUT - text.length());
                existing = existing.substring(Math.max(0, existing.length() - keep));
                output.setText(existing);
            }
            output.append(text);
            output.post(() -> ((ScrollView) output.getParent()).fullScroll(View.FOCUS_DOWN));
        });
    }

    private void clearOutput() { output.setText(""); }

    private void showTerminalInfo() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.terminal)
                .setMessage("UM Terminal keeps one shell process alive so cd, export, aliases and other shell state persist between commands.\n\nShell: app UID shell\nShizuku: shell UID through Shizuku\nRoot: su when a working su provider is available.\n\nCommand output is capped at 4 MiB per session to prevent an accidental noisy command from exhausting app memory.")
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void loadHistory() {
        String raw = prefs.getString(HISTORY, "");
        if (raw == null || raw.isEmpty()) return;
        Collections.addAll(history, raw.split("\\n", -1));
        while (history.size() > MAX_HISTORY) history.remove(0);
        historyIndex = history.size();
    }

    private void remember(String command) {
        history.remove(command);
        history.add(command);
        while (history.size() > MAX_HISTORY) history.remove(0);
        historyIndex = history.size();
        prefs.edit().putString(HISTORY, joinHistory()).apply();
    }

    private String joinHistory() {
        StringBuilder sb = new StringBuilder();
        for (String command : history) {
            if (command == null || command.indexOf('\n') >= 0) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(command);
        }
        return sb.toString();
    }

    private void navigateHistory(int delta) {
        if (history.isEmpty()) return;
        historyIndex = Math.max(0, Math.min(history.size(), historyIndex + delta));
        input.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
        input.setSelection(input.length());
    }

    private String getDefaultDirectory() {
        File external = getExternalFilesDir(null);
        if (external != null && external.isDirectory()) return external.getAbsolutePath();
        return getFilesDir().getAbsolutePath();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private static String message(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }

    @Override protected void onDestroy() {
        stopSession();
        super.onDestroy();
    }

    private void stopSession() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
    }

    private interface OutputSink {
        void append(String text);
        void exited(int code);
    }

    private abstract static class ShellSession implements AutoCloseable {
        protected final String cwd;
        protected BufferedWriter writer;
        protected Process process;
        protected Thread reader;
        protected OutputSink sink;
        ShellSession(String cwd) { this.cwd = cwd; }
        abstract Process createProcess() throws Exception;
        void start(OutputSink sink) throws Exception {
            this.sink = sink;
            process = createProcess();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new Thread(() -> readOutput(process.getInputStream()), "um-terminal-output");
            reader.setDaemon(true);
            reader.start();
            // Root shells do not inherit the requested working directory; explicitly align all backends.
            write("cd '" + cwd.replace("'", "'\\''") + "'\n");
        }
        void readOutput(InputStream stream) {
            int exit = -1;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                char[] buffer = new char[4096];
                int n;
                while ((n = r.read(buffer)) != -1) sink.append(new String(buffer, 0, n));
                process.waitFor();
                exit = process.exitValue();
            } catch (Exception e) {
                sink.append("[terminal] " + message(e) + "\n");
            } finally { sink.exited(exit); }
        }
        void write(String value) throws IOException { writer.write(value); writer.flush(); }
        boolean isAlive() {
            if (process == null) return false;
            try { process.exitValue(); return false; }
            catch (IllegalThreadStateException e) { return true; }
        }
        String getWorkingDirectory() { return cwd; }
        @Override public void close() {
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            if (process != null) {
                try {
                    process.destroy();
                    long deadline = System.currentTimeMillis() + 500;
                    while (System.currentTimeMillis() < deadline) {
                        try { process.exitValue(); break; } catch (IllegalThreadStateException ignored) { Thread.sleep(25); }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private static final class LocalSession extends ShellSession {
        LocalSession(String cwd) { super(cwd); }
        @Override Process createProcess() throws Exception {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh");
            pb.directory(new File(cwd));
            pb.redirectErrorStream(true);
            pb.environment().put("TERM", "dumb");
            pb.environment().put("HOME", cwd);
            return pb.start();
        }
    }

    private static final class RootSession extends ShellSession {
        private final Context context;
        RootSession(Context context, String cwd) { super(cwd); this.context = context; }
        @Override Process createProcess() throws Exception {
            RootManager manager = RootManager.getInstance(context);
            if (!manager.isRootAvailable()) throw new IOException("Root access is not available");
            Process p = Runtime.getRuntime().exec(new String[]{manager.suBinary()});
            return p;
        }
    }

    private static final class ShizukuSession extends ShellSession {
        ShizukuSession(String cwd) { super(cwd); }
        @Override Process createProcess() throws Exception {
            if (!ShizukuShell.isGranted()) throw new IOException("Shizuku permission is not granted");
            ShizukuRemoteProcess p = (ShizukuRemoteProcess) new ShizukuProcessFactory()
                    .newProcess(new String[]{"sh"}, null, cwd);
            return p;
        }
    }
}
