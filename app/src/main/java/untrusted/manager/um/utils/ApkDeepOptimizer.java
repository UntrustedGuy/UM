package untrusted.manager.um.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.reandroid.apk.APKLogger;

import untrusted.manager.um.utils.deepopt.DeepOptimizer;

import java.io.File;
import java.util.Set;

public class ApkDeepOptimizer {

    public static File optimize(Context context, File inputApk, APKLogger logger) throws Exception {
        return optimize(context, inputApk, null, null, logger);
    }

    public static File optimize(Context context, File inputApk, Set<String> filesToDelete,
                                SharedPreferences settings, APKLogger logger) throws Exception {
        return DeepOptimizer.optimize(context, inputApk, filesToDelete, settings, logger);
    }
}