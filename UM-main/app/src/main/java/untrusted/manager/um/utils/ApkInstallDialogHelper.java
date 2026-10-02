package untrusted.manager.um.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import untrusted.manager.um.R;
import io.github.codehasan.colorpicker.extensions.Extensions;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.faith.apkinstaller.APKInstallService;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApkInstallDialogHelper {

    public static final String ACTION_INSTALL_RESULT = "untrusted.manager.um.APK_INSTALL_RESULT";
    public static final String EXTRA_INSTALL_SUCCESS = "extra_install_success";

    private final Activity activity;

    public ApkInstallDialogHelper(Activity activity) {
        this.activity = activity;
    }

    public void installSingleApk(File apkFile) {

        if (apkFile == null || !apkFile.isFile() || !apkFile.canRead()) {
            new ErrorUtil(activity).showError(new IllegalArgumentException("APK file is missing or unreadable"));
            return;
        }
        final File firstApk = apkFile;
        RootManager rm = RootManager.getInstance(activity);
        if (rm.isSilentInstallEnabled() && rm.isRootAvailable()) {
            CharSequence appName = getAppNameForApk(apkFile);
            ProgressManager pm = showProgress("Installing " + appName + "...");
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                try {
                    rm.installSilent(apkFile.getAbsolutePath());
                    activity.runOnUiThread(() -> {
                        dismissProgress(pm);
                        showInstallCompleteDialog(appName, apkFile);
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        dismissProgress(pm);
                        cleanupSplitInstallTemp(firstApk);
                        new ErrorUtil(activity).showError(e);
                    });
                }
            });
            executor.shutdown();
        } else {
            InstallUtil.installApk(activity, apkFile);
            //installWithIntent(apkFile, pm);
        }
    }

    public void installSplitApks(List<File> apkFiles, String archiveName) {
        if (apkFiles == null || apkFiles.isEmpty()) {
            new ErrorUtil(activity).showError(new IllegalArgumentException("No split APK files were supplied"));
            return;
        }
        for (File file : apkFiles) {
            if (file == null || !file.isFile() || !file.canRead()) {
                new ErrorUtil(activity).showError(new IllegalArgumentException("Split APK is missing or unreadable"));
                return;
            }
        }
        String appName = archiveName != null ? archiveName : "Split APK";
        ProgressManager pm = showProgress("Installing " + appName + "...");
        File firstApk = apkFiles.isEmpty() ? null : apkFiles.get(0);

        RootManager rm = RootManager.getInstance(activity);
        if (rm.isSilentInstallEnabled() && rm.isRootAvailable()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                try {
                    List<String> paths = new ArrayList<>();
                    for (File f : apkFiles) paths.add(f.getAbsolutePath());
                    rm.installSplitSilent(paths);
                    activity.runOnUiThread(() -> {
                        dismissProgress(pm);
                        cleanupSplitInstallTemp(firstApk);
                        showInstallCompleteDialog(appName, firstApk);
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        dismissProgress(pm);
                        new ErrorUtil(activity).showError(e);
                    });
                }
            });
            executor.shutdown();
        } else {
            installSplitWithPackageInstaller(apkFiles, appName, pm, firstApk);
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void installWithIntent(File apkFile, ProgressManager pm) {
        int sessionId = -1;
        PackageInstaller pi = null;
        try {
            pi = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(apkFile.length());

            sessionId = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(sessionId);
                 OutputStream out = session.openWrite("apk", 0, apkFile.length());
                 InputStream in = new FileInputStream(apkFile)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                session.fsync(out);
            }

            Intent callbackIntent = new Intent(activity, APKInstallService.class);
            PendingIntent pendingIntent = PendingIntent.getService(
                    activity, 0, callbackIntent, PendingIntent.FLAG_MUTABLE);

            try (PackageInstaller.Session commitSession = pi.openSession(sessionId)) {
                commitSession.commit(pendingIntent.getIntentSender());
            }
            dismissProgress(pm);
        } catch (Exception e) {
            if (pi != null && sessionId >= 0) {
                try { pi.abandonSession(sessionId); } catch (Exception ignored) {}
            }
            dismissProgress(pm);
            new ErrorUtil(activity).showError(e);
        }
    }

    @SuppressLint("RequestInstallPackagesPolicy")
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void installSplitWithPackageInstaller(List<File> apkFiles, String appName, ProgressManager pm, File firstApk) {
        BroadcastReceiver resultReceiver = null;
        int sessionId = -1;
        PackageInstaller pi = null;
        try {
            pi = activity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);

            long totalSize = 0;
            for (File f : apkFiles) totalSize += f.length();
            params.setSize(totalSize);

            sessionId = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(sessionId)) {
                int idx = 0;
                for (File apkFile : apkFiles) {
                    try (OutputStream out = session.openWrite("split_" + idx, 0, apkFile.length());
                         InputStream in = new FileInputStream(apkFile)) {
                        byte[] buffer = new byte[65536];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        session.fsync(out);
                    }
                    idx++;
                }
            }

            Intent callbackIntent = new Intent(activity, APKInstallService.class);
            PendingIntent pendingIntent = PendingIntent.getService(activity, 0, callbackIntent, PendingIntent.FLAG_MUTABLE);

            resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try { activity.unregisterReceiver(this); } catch (Exception ignored) {}
                    dismissProgress(pm);
                    cleanupSplitInstallTemp(firstApk);
                    if (intent.getBooleanExtra(EXTRA_INSTALL_SUCCESS, false)) {
                        showInstallCompleteDialog(appName, firstApk);
                    } else {
                        Extensions.showMessage(activity, activity.getString(R.string.installation_failed));
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_INSTALL_RESULT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                activity.registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else activity.registerReceiver(resultReceiver, filter);

            try (PackageInstaller.Session commitSession = pi.openSession(sessionId)) {
                commitSession.commit(pendingIntent.getIntentSender());
            }
        } catch (Exception e) {
            if (resultReceiver != null) { try { activity.unregisterReceiver(resultReceiver); } catch (Exception ignored) {} }
            if (pi != null && sessionId >= 0) {
                try { pi.abandonSession(sessionId); } catch (Exception ignored) {}
            }
            dismissProgress(pm);
            cleanupSplitInstallTemp(firstApk);
            new ErrorUtil(activity).showError(e);
        }
    }

    /** Remove only the private cache directory created by the split-archive installer. */
    private void cleanupSplitInstallTemp(File firstApk) {
        if (firstApk == null) return;
        try {
            File dir = firstApk.getParentFile();
            File cache = activity.getCacheDir();
            if (dir == null || cache == null) return;
            String name = dir.getName();
            String dirPath = dir.getCanonicalPath();
            String cachePath = cache.getCanonicalPath();
            if (!name.startsWith("split_install_") || !dirPath.startsWith(cachePath + File.separator)) return;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        deleteTempTree(f);
                    } else {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        } catch (Exception ignored) {
        }
    }

    private void deleteTempTree(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteTempTree(f);
                else {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private ProgressManager showProgress(String text) {
        if (activity instanceof AppCompatActivity) {
            ProgressManager pm = new ProgressManager((AppCompatActivity) activity, true);
            pm.setText(text);
            pm.show();
            return pm;
        }
        return null;
    }

    private void dismissProgress(ProgressManager pm) {
        if (pm != null) pm.dismiss();
    }

    private void showInstallCompleteDialog(CharSequence appName, File apkFile) {
        String packageName = getPackageNameForApk(apkFile);

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.installation_complete)
            .setMessage(activity.getString(R.string.installed_successfully, appName))
            .setPositiveButton(R.string.launch, (d, w) -> {
                if (packageName != null) {
                    Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage(packageName);
                    if (launchIntent != null) {
                        activity.startActivity(launchIntent);
                    } else {
                        Extensions.showMessage(activity, activity.getString(R.string.install_cannot_launch));
                    }
                }
            })
            .setNegativeButton(android.R.string.ok, null);
        activity.runOnUiThread(b::show);
    }

    private CharSequence getAppNameForApk(File apkFile) {
        if (apkFile == null) return "APK";
        try {
            PackageManager pm = activity.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null) {
                if (info.applicationInfo != null) {
                    return info.applicationInfo.loadLabel(pm);
                }
            }
        } catch (Exception ignored) {}
        return apkFile.getName();
    }

    private String getPackageNameForApk(File apkFile) {
        if (apkFile == null) return null;
        try {
            PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null) return info.packageName;
        } catch (Exception ignored) {}
        return null;
    }

    private int dp(int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
