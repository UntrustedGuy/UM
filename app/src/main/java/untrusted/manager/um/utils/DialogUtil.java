package untrusted.manager.um.utils;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class DialogUtil {
    private final Activity context;

    public DialogUtil(Activity c) {
        this.context = c;
    }

    public MaterialAlertDialogBuilder getDialogBuilder() {
        return new MaterialAlertDialogBuilder(context);
    }

    public void styleAlertDialog(AlertDialog ad) {
        context.runOnUiThread(ad::show);
    }
}