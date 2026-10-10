package com.youzeng;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.system.Os;
import android.util.Log;

import com.youzeng.hook.YouzengConfig;

import java.io.File;

/**
 * 模块 App 进程内使用，不依赖 Xposed API。
 * <p>
 * 必须用 {@link Context#MODE_WORLD_READABLE} 打开（配合 Manifest 中 {@code xposedsharedprefs}），
 * LSPosed 才会把 prefs 写到目标进程里 {@code XSharedPreferences} 能读的路径。
 * 旧版用 {@link Context#MODE_PRIVATE} 写的值会在首次打开时迁移过来。
 */
public final class YouzengModulePrefsStore {

    private static final String TAG = "YouzengPrefsStore";

    private YouzengModulePrefsStore() {
    }

    @SuppressWarnings("deprecation")
    public static SharedPreferences openExportable(Context context) {
        try {
            return context.getSharedPreferences(YouzengConfig.MODULE_PREFS_NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            return context.getSharedPreferences(YouzengConfig.MODULE_PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public static SharedPreferences openLegacy(Context context) {
        return context.getSharedPreferences(YouzengConfig.MODULE_PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String loadDid(Context context) {
        String exported = trimToNull(openExportable(context).getString(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, null));
        String legacy = trimToNull(openLegacy(context).getString(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, null));
        String chosen = exported != null ? exported : legacy;
        if (chosen == null) {
            return YouzengConfig.DEFAULT_MOCK_DEVICE_DID;
        }
        if (exported == null && legacy != null) {
            saveDid(context, chosen, false);
        }
        return chosen;
    }

    public static void saveDid(Context context, String did) {
        saveDid(context, did, true);
    }

    public static void saveDid(Context context, String did, boolean notifyTarget) {
        String value = did == null ? "" : did.trim();
        SharedPreferences exported = openExportable(context);
        exported.edit()
                .putString(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, value)
                .commit();
        // 同时写入 legacy 文件，避免部分 LSPosed 只 hook WORLD_READABLE、XSP 仍读 dataDir
        try {
            SharedPreferences legacy = openLegacy(context);
            if (legacy != exported) {
                legacy.edit().putString(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, value).commit();
            }
        } catch (Throwable ignored) {
        }
        ensureWorldReadable(context);
        if (notifyTarget) {
            notifyTargetProcess(context, value);
        }
    }

    public static void ensureWorldReadable(Context context) {
        if (context == null) {
            return;
        }
        try {
            File dataDir = new File(context.getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, YouzengConfig.MODULE_PREFS_NAME + ".xml");
            chmod(dataDir, 0751);
            dataDir.setExecutable(true, false);
            if (prefsDir.exists() || prefsDir.mkdirs()) {
                chmod(prefsDir, 0755);
                prefsDir.setExecutable(true, false);
                prefsDir.setReadable(true, false);
            }
            if (prefsFile.exists()) {
                chmod(prefsFile, 0644);
                prefsFile.setReadable(true, false);
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensureWorldReadable failed", t);
        }
    }

    private static void notifyTargetProcess(Context context, String did) {
        for (String pkg : YouzengConfig.allTargetPackages()) {
            try {
                Intent intent = new Intent(YouzengConfig.ACTION_MOCK_DEVICE_DID);
                intent.setPackage(pkg);
                intent.putExtra(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, did);
                context.sendBroadcast(intent, YouzengConfig.PERMISSION_UPDATE_DID);
            } catch (Throwable t) {
                Log.w(TAG, "notifyTargetProcess failed pkg=" + pkg, t);
            }
        }
    }

    private static void chmod(File file, int mode) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Os.chmod(file.getAbsolutePath(), mode);
        } catch (Throwable ignored) {
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }
}
