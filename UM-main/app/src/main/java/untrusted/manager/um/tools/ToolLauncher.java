package untrusted.manager.um.tools;

import android.app.Activity;
import androidx.appcompat.app.AppCompatActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import untrusted.manager.um.patcher.PatcherActivity;
import untrusted.manager.um.gameanalysis.GameAnalysisActivity;
import untrusted.manager.um.gameanalysis.Il2CppEditorActivity;
import untrusted.manager.um.gameanalysis.FridaToolkitActivity;
import untrusted.manager.um.network.NetworkStorageActivity;
import untrusted.manager.um.network.NetworkTransferActivity;
import untrusted.manager.um.network.S3Activity;
import untrusted.manager.um.network.SftpActivity;
import untrusted.manager.um.network.SmbActivity;
import untrusted.manager.um.network.WebDavActivity;
import untrusted.manager.um.remote.HttpRemoteActivity;
import untrusted.manager.um.tools.DllEditorActivity;
import untrusted.manager.um.tools.RootManagerActivity;
import untrusted.manager.um.tools.StorageManagerActivity;
import untrusted.manager.um.ui.activities.TerminalActivity;
import untrusted.manager.um.ui.activities.TextEditorActivity;
import untrusted.manager.um.utils.SignatureKeyDialog;
import untrusted.manager.um.tools.WifiManagerActivity;
import untrusted.manager.um.plugin.api.UmPluginContract;

/**
 * Single routing point for registered Tools Kit entries.
 * Sidebar and Tools Kit therefore open the exact same implementation for every
 * registered feature instead of maintaining two independent routing switches.
 */
public final class ToolLauncher {
    private ToolLauncher() {}

    public static void open(Context context, ToolRegistry.ToolItem item) {
        if (context == null || item == null) return;
        if (item.isPlugin()) {
            openPlugin(context, item);
            return;
        }

        Intent intent;
        switch (item.id()) {
            case "wifimanager" -> intent = new Intent(context, WifiManagerActivity.class);
            case "storagemanager" -> intent = new Intent(context, StorageManagerActivity.class);
            case "patcher", "luckypatcher" -> intent = new Intent(context, PatcherActivity.class);
            case "rootmanager" -> intent = new Intent(context, RootManagerActivity.class);
            case "gameanalyzer", "metadata", "encryption", "gamemodding" ->
                    intent = new Intent(context, GameAnalysisActivity.class);
            case "il2cpp" -> intent = new Intent(context, GameAnalysisActivity.class).putExtra("mode", "il2cpp");
            case "il2cppeditor" -> intent = new Intent(context, Il2CppEditorActivity.class);
            case "frida" -> intent = new Intent(context, FridaToolkitActivity.class);
            case "dlleditor" -> intent = new Intent(context, DllEditorActivity.class);
            case "terminal" -> intent = new Intent(context, TerminalActivity.class);
            case "texteditor" -> intent = new Intent(context, TextEditorActivity.class);
            case "plugins" -> intent = new Intent(context, PluginManagerActivity.class);
            case "signaturekey" -> {
                if (context instanceof AppCompatActivity activity) {
                    SignatureKeyDialog.show(activity);
                } else {
                    Toast.makeText(context, "Signature key management requires an Activity", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            case "mcp" -> intent = new Intent(context, HttpRemoteActivity.class).putExtra("mode", "mcp");
            case "httpremote" -> intent = new Intent(context, HttpRemoteActivity.class);
            case "networkstorage" -> intent = new Intent(context, NetworkStorageActivity.class);
            case "networktransfers" -> intent = new Intent(context, NetworkTransferActivity.class);
            case "webdav" -> intent = new Intent(context, WebDavActivity.class);
            case "s3" -> intent = new Intent(context, S3Activity.class);
            case "smb" -> intent = new Intent(context, SmbActivity.class);
            case "sftp" -> intent = new Intent(context, SftpActivity.class);
            default -> {
                intent = new Intent(context, ToolRunnerActivity.class);
                intent.putExtra("tool_id", item.id());
                intent.putExtra("tool_title", item.title());
            }
        }
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Toast.makeText(context, "Unable to open " + item.title() + ": " + message(e), Toast.LENGTH_LONG).show();
        }
    }

    public static void open(Context context, String id) {
        ToolRegistry.ToolItem item = ToolRegistry.findById(context, id);
        if (item != null) open(context, item);
    }

    private static void openPlugin(Context context, ToolRegistry.ToolItem item) {
        Intent pluginIntent = new Intent(UmPluginContract.ACTION_PLUGIN_ENTRY);
        pluginIntent.setComponent(new ComponentName(item.pluginPackage(), item.pluginActivity()));
        String prefix = "plugin:" + item.pluginPackage() + ":";
        String pluginId = item.id().startsWith(prefix) ? item.id().substring(prefix.length()) : item.id();
        pluginIntent.putExtra(UmPluginContract.EXTRA_PLUGIN_ID, pluginId);
        if (!(context instanceof Activity)) pluginIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (context.getPackageManager().resolveActivity(pluginIntent, 0) == null) {
            Toast.makeText(context, "Plugin is no longer installed", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            context.startActivity(pluginIntent);
        } catch (RuntimeException e) {
            Toast.makeText(context, "Unable to open plugin: " + message(e), Toast.LENGTH_LONG).show();
        }
    }

    private static String message(Throwable t) {
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
