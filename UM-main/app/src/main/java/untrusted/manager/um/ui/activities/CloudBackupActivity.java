package untrusted.manager.um.ui.activities;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import untrusted.manager.um.R;
import untrusted.manager.um.utils.CloudBackupManager;
import io.github.codehasan.colorpicker.extensions.Extensions;

public class CloudBackupActivity extends AppCompatActivity {
    private static final int PICK_BACKUP_FOLDER = 4201;
    private static final int PICK_BACKUP_FILE = 4202;
    private TextView status;
    private boolean exporting;

    @Override protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * getResources().getDisplayMetrics().density + .5f);
        root.setPadding(p, p, p, p);
        TextView title = new TextView(this);
        title.setText(R.string.cloud_backup);
        title.setTextSize(22);
        root.addView(title);
        TextView info = new TextView(this);
        info.setText(R.string.cloud_backup_description);
        info.setPadding(0, p / 2, 0, p);
        root.addView(info);
        Button backup = new Button(this);
        backup.setText(R.string.cloud_backup_export);
        backup.setOnClickListener(v -> chooseFolder());
        root.addView(backup);
        Button restore = new Button(this);
        restore.setText(R.string.cloud_backup_import);
        restore.setOnClickListener(v -> chooseFile());
        root.addView(restore);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(0, p, 0, 0);
        root.addView(status);
        setTitle(R.string.cloud_backup);
        setContentView(root);
    }

    private void chooseFolder() {
        exporting = true;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), PICK_BACKUP_FOLDER);
    }

    private void chooseFile() {
        exporting = false;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), PICK_BACKUP_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (flags != 0) getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {}
        if (requestCode == PICK_BACKUP_FOLDER && exporting) performExport(uri);
        else if (requestCode == PICK_BACKUP_FILE && !exporting) performRestore(uri);
    }

    private void performExport(Uri tree) {
        status.setText(R.string.cloud_backup_export);
        new Thread(() -> {
            try {
                Uri result = CloudBackupManager.createBackup(this, tree);
                runOnUiThread(() -> status.setText(getString(R.string.cloud_backup_success, String.valueOf(result))));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(getString(R.string.cloud_backup_failed, message(e))));
            }
        }, "um-cloud-backup").start();
    }

    private void performRestore(Uri file) {
        status.setText(R.string.cloud_backup_import);
        new Thread(() -> {
            try {
                int count = CloudBackupManager.restoreBackup(this, file);
                runOnUiThread(() -> {
                    status.setText(getString(R.string.cloud_restore_success, count));
                    Extensions.showMessage(this, getString(R.string.cloud_restore_success, count));
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText(getString(R.string.cloud_restore_failed, message(e))));
            }
        }, "um-cloud-restore").start();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
