package com.youzeng.hook;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 在目标进程读取本模块（com.youzeng）保存的 SharedPreferences，供 Hook 使用 mock 设备 did。
 * <p>
 * 读取顺序：
 * <ol>
 *   <li>{@link XSharedPreferences}（LSPosed {@code xposedsharedprefs} 路径 + 模块 dataDir）</li>
 *   <li>ContentResolver 查询 {@link YouzengConfig#CONFIG_PROVIDER_AUTHORITY}</li>
 *   <li>目标进程内镜像 prefs（含模块 App 广播推送的 did）</li>
 *   <li>进程内内存缓存</li>
 *   <li>最后 {@link YouzengConfig#DEFAULT_MOCK_DEVICE_DID}</li>
 * </ol>
 */
public final class YouzengModulePrefs {

    /** 与 {@code applicationId} 一致 */
    private static final String MODULE_PACKAGE = "com.youzeng";

    /**
     * 写在<strong>目标包</strong>私有目录下的 prefs 名，与模块 {@link YouzengConfig#MODULE_PREFS_NAME} 无关，避免混用。
     */
    private static final String TARGET_MIRROR_PREFS_NAME = "youzeng_hook_device_mirror";

    private static final String MIRROR_KEY_MOCK_DEVICE_DID = "mirror_mock_device_did";

    /** 与参考工程 Uri.parse("content://.../config") 一致 */
    private static final String CONFIG_URI = "content://" + YouzengConfig.CONFIG_PROVIDER_AUTHORITY + "/config";

    private static volatile String lastLoggedDid = "";
    private static volatile String lastLoggedSource = "";

    /** 任意一次从磁盘/Provider 成功解析到的非空 did；用于双通道均失败时的兜底（模块进程被杀、IPC 异常等） */
    private static volatile String memoryCachedDid = null;

    /**
     * Application 尚未就绪时无法写入目标 prefs，先暂存 did，待 {@link #tryFlushPendingMirrorDid()} 落盘。
     */
    private static volatile String pendingMirrorDid = null;

    private static volatile boolean didReceiverRegistered = false;
    private static volatile boolean xspDiagLogged = false;

    private YouzengModulePrefs() {
    }

    /** 模块自身进程：把 prefs 打成 LSPosed / UNIX 可读取。 */
    public static void installForModuleProcess() {
        try {
            XSharedPreferences p = createPrefs();
            if (p != null) {
                invokeMakeWorldReadable(p);
                p.reload();
            }
            XposedBridge.log("[youzeng] module process prefs makeWorldReadable done");
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] installForModuleProcess failed: " + t);
        }
    }

    /**
     * 目标进程：对齐微信插件，在 {@code Application.attach} 里用 attach 下来的 Context 读 did。
     * 此时 {@code currentApplication()} 往往还是 null，必须把 Context 传进 ContentProvider 查询。
     */
    public static void installForTargetProcess(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = (Context) param.args[0];
                    String did = getMockDeviceDid(context);
                    XposedBridge.log("[youzeng] DeviceName:" + did);
                    registerDidReceiverIfPossible(context);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] hook Application.attach failed: " + t);
        }
    }

    /**
     * @return 非空字符串；未配置或读取失败时返回 {@link YouzengConfig#DEFAULT_MOCK_DEVICE_DID}
     */
    public static String getMockDeviceDid() {
        return getMockDeviceDid(null);
    }

    /**
     * @param context 目标进程 Context；微信插件在 {@code Application.attach} 里传入，可为 null
     */
    public static String getMockDeviceDid(Context context) {
        tryFlushPendingMirrorDid(context);
        String out = YouzengConfig.DEFAULT_MOCK_DEVICE_DID;
        String source = "default";
        try {
            String viaXsp = readDidFromXSharedPreferences();
            String viaProvider = readDidViaContentProvider(context);
            String chosen = firstNonEmpty(viaXsp, viaProvider);
            if (chosen != null) {
                out = chosen;
                source = chosen.equals(viaXsp) ? "xsharedprefs" : "provider";
                memoryCachedDid = chosen;
                persistMirrorDid(chosen, context);
            } else {
                String fromMirror = readMirrorDid(context);
                if (fromMirror != null) {
                    out = fromMirror;
                    source = "target_mirror";
                    memoryCachedDid = fromMirror;
                } else if (memoryCachedDid != null) {
                    String cached = memoryCachedDid.trim();
                    if (!cached.isEmpty()) {
                        out = cached;
                        source = "memory_cache";
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] YouzengModulePrefs.getMockDeviceDid failed: " + t);
            String fromMirror = readMirrorDid(context);
            if (fromMirror != null) {
                out = fromMirror;
                source = "target_mirror_after_error";
                memoryCachedDid = fromMirror;
            } else if (memoryCachedDid != null) {
                String cached = memoryCachedDid.trim();
                if (!cached.isEmpty()) {
                    out = cached;
                    source = "memory_cache_after_error";
                }
            }
        }
        if (!out.equals(lastLoggedDid) || !source.equals(lastLoggedSource)) {
            lastLoggedDid = out;
            lastLoggedSource = source;
            XposedBridge.log("[youzeng] mock_device_did(in hook)=" + out + " source=" + source);
        }
        return out;
    }

    private static void registerDidReceiverIfPossible() {
        registerDidReceiverIfPossible(null);
    }

    private static void registerDidReceiverIfPossible(Context context) {
        if (didReceiverRegistered) {
            return;
        }
        Context ctx = context != null ? context : currentApplicationContext();
        if (ctx == null) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    String did = intent.getStringExtra(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID);
                    if (did == null) {
                        return;
                    }
                    String t = did.trim();
                    if (t.isEmpty()) {
                        return;
                    }
                    memoryCachedDid = t;
                    persistMirrorDid(t, context);
                    lastLoggedDid = t;
                    lastLoggedSource = "broadcast";
                    XposedBridge.log("[youzeng] mock_device_did(in hook)=" + t + " source=broadcast");
                }
            };
            IntentFilter filter = new IntentFilter(YouzengConfig.ACTION_MOCK_DEVICE_DID);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(
                        receiver,
                        filter,
                        YouzengConfig.PERMISSION_UPDATE_DID,
                        null,
                        Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter, YouzengConfig.PERMISSION_UPDATE_DID, null);
            }
            didReceiverRegistered = true;
            XposedBridge.log("[youzeng] did broadcast receiver registered");
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] registerDidReceiver failed: " + t);
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }

    private static String readDidFromXSharedPreferences() {
        try {
            XSharedPreferences p = createPrefs();
            if (p == null) {
                logXspDiag("createPrefs returned null", null);
                return null;
            }
            invokeMakeWorldReadable(p);
            p.reload();
            String v = p.getString(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, null);
            if (v == null) {
                logXspDiag("key missing after reload", p);
                return null;
            }
            String t = v.trim();
            if (t.isEmpty()) {
                logXspDiag("key empty after reload", p);
                return null;
            }
            return t;
        } catch (Throwable t) {
            logXspDiag("exception: " + t, null);
            return null;
        }
    }

    private static void logXspDiag(String reason, XSharedPreferences prefs) {
        if (xspDiagLogged) {
            return;
        }
        xspDiagLogged = true;
        StringBuilder sb = new StringBuilder("[youzeng] XSharedPreferences miss: ").append(reason);
        try {
            File f = prefs != null ? extractPrefsFile(prefs) : null;
            if (f != null) {
                sb.append(" file=").append(f.getAbsolutePath())
                        .append(" exists=").append(f.exists())
                        .append(" canRead=").append(f.canRead())
                        .append(" len=").append(f.exists() ? f.length() : -1);
            }
        } catch (Throwable ignored) {
        }
        XposedBridge.log(sb.toString());
    }

    private static File extractPrefsFile(XSharedPreferences prefs) {
        if (prefs == null) {
            return null;
        }
        String[] names = {"mFile", "file"};
        for (String n : names) {
            try {
                Field f = XSharedPreferences.class.getDeclaredField(n);
                f.setAccessible(true);
                Object v = f.get(prefs);
                if (v instanceof File) {
                    return (File) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void invokeMakeWorldReadable(XSharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        try {
            Method m = XSharedPreferences.class.getMethod("makeWorldReadable");
            m.invoke(prefs);
        } catch (Throwable ignored) {
        }
    }

    /**
     * de.robv.android.xposed:api:82 的 compileOnly 包可能不含 {@code AndroidAppHelper}，故用系统 API 取 Application。
     */
    private static Context currentApplicationContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 将模块侧读到的 did 同步到目标进程私有 prefs，供下次冷启动在 XSP/Provider 均失败时使用。 */
    private static void persistMirrorDid(String did) {
        persistMirrorDid(did, null);
    }

    private static void persistMirrorDid(String did, Context context) {
        if (did == null) {
            return;
        }
        String v = did.trim();
        if (v.isEmpty()) {
            return;
        }
        Context ctx = context != null ? context : currentApplicationContext();
        if (ctx == null) {
            pendingMirrorDid = v;
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(TARGET_MIRROR_PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(MIRROR_KEY_MOCK_DEVICE_DID, v).commit();
            pendingMirrorDid = null;
        } catch (Throwable ignored) {
        }
    }

    private static void tryFlushPendingMirrorDid() {
        tryFlushPendingMirrorDid(null);
    }

    private static void tryFlushPendingMirrorDid(Context context) {
        String p = pendingMirrorDid;
        if (p == null) {
            return;
        }
        Context ctx = context != null ? context : currentApplicationContext();
        if (ctx == null) {
            return;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(TARGET_MIRROR_PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(MIRROR_KEY_MOCK_DEVICE_DID, p.trim()).commit();
            pendingMirrorDid = null;
        } catch (Throwable ignored) {
        }
    }

    private static String readMirrorDid() {
        return readMirrorDid(null);
    }

    private static String readMirrorDid(Context context) {
        Context ctx = context != null ? context : currentApplicationContext();
        if (ctx == null) {
            return null;
        }
        try {
            SharedPreferences sp = ctx.getSharedPreferences(TARGET_MIRROR_PREFS_NAME, Context.MODE_PRIVATE);
            String v = sp.getString(MIRROR_KEY_MOCK_DEVICE_DID, null);
            if (v == null) {
                return null;
            }
            String t = v.trim();
            return t.isEmpty() ? null : t;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readDidViaContentProvider() {
        return readDidViaContentProvider(null);
    }

    private static String readDidViaContentProvider(Context context) {
        Context ctx = context != null ? context : currentApplicationContext();
        if (ctx == null) {
            return null;
        }
        Uri uri = Uri.parse(CONFIG_URI);
        try (Cursor cursor = ctx.getContentResolver().query(
                uri,
                null,
                null,
                new String[]{YouzengConfig.PREF_KEY_MOCK_DEVICE_DID},
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex("value");
                if (idx >= 0) {
                    String raw = cursor.getString(idx);
                    if (raw != null) {
                        String t = raw.trim();
                        if (!t.isEmpty()) {
                            return t;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static XSharedPreferences createPrefs() throws Throwable {
        final String name = YouzengConfig.MODULE_PREFS_NAME;
        final int perUserRange = 100000;
        int userId = Process.myUid() / perUserRange;

        try {
            Constructor<XSharedPreferences> c = XSharedPreferences.class.getConstructor(String.class, String.class);
            XSharedPreferences p = c.newInstance(MODULE_PACKAGE, name);
            invokeMakeWorldReadable(p);
            p.reload();
            String v = p.getString(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, null);
            if (v != null && !v.trim().isEmpty()) {
                return p;
            }
        } catch (Throwable ignored) {
        }

        File[] candidates = new File[]{
                new File("/data/misc/lspd/prefs/" + userId + "/" + MODULE_PACKAGE + "/" + name + ".xml"),
                new File("/data/misc/lspd/prefs/0/" + MODULE_PACKAGE + "/" + name + ".xml"),
                new File("/data/misc/" + userId + "/prefs/" + MODULE_PACKAGE + "/" + name + ".xml"),
                new File("/data/user/" + userId + "/" + MODULE_PACKAGE + "/shared_prefs/" + name + ".xml"),
                new File("/data/user_de/" + userId + "/" + MODULE_PACKAGE + "/shared_prefs/" + name + ".xml"),
                new File("/data/user/0/" + MODULE_PACKAGE + "/shared_prefs/" + name + ".xml"),
                new File("/data/user_de/0/" + MODULE_PACKAGE + "/shared_prefs/" + name + ".xml"),
                new File("/data/data/" + MODULE_PACKAGE + "/shared_prefs/" + name + ".xml"),
        };
        Throwable last = null;
        for (File f : candidates) {
            try {
                XSharedPreferences p = new XSharedPreferences(f);
                invokeMakeWorldReadable(p);
                p.reload();
                String v = p.getString(YouzengConfig.PREF_KEY_MOCK_DEVICE_DID, null);
                if (v != null && !v.trim().isEmpty()) {
                    return p;
                }
            } catch (Throwable t) {
                last = t;
            }
        }
        try {
            Constructor<XSharedPreferences> c = XSharedPreferences.class.getConstructor(String.class, String.class);
            return c.newInstance(MODULE_PACKAGE, name);
        } catch (Throwable t) {
            if (last != null) {
                throw last;
            }
            throw t;
        }
    }
}
