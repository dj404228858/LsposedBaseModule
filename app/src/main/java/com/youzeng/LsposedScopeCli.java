package com.youzeng;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.youzeng.hook.YouzengConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 启动时先向 Magisk / KernelSU 申请 su，成功后再用 LSPosed_mod CLI 勾选推荐作用域。
 */
public final class LsposedScopeCli {
    public interface ResultCallback {
        void onResult(boolean granted, String message);
    }

    private static final String TAG = "youzeng-scope";
    private static final String CLI = "/data/adb/lspd/bin/cli";
    private static final String WATCHER_NAME = "youzeng-lsposed-scope.sh";
    private static final String WATCHER_DST = "/data/adb/service.d/" + WATCHER_NAME;
    private static final String PREFS = "youzeng_root";
    private static final String PREF_SU_GRANTED = "su_granted";
    private static final long DEBOUNCE_MS = 3000L;
    private static final long SU_PROMPT_TIMEOUT_SEC = 120L;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicLong LAST_APPLY_AT = new AtomicLong(0L);
    private static final AtomicBoolean SU_IN_FLIGHT = new AtomicBoolean(false);

    private LsposedScopeCli() {
    }

    /**
     * 必须在 Activity 已获得窗口焦点后调用，Magisk 才会弹出「超级用户请求」而不是后台静默拒绝。
     */
    public static void requestSuThenApply(Context context, ResultCallback callback) {
        requestSuThenApply(context, false, callback);
    }

    public static void requestSuThenApply(Context context, boolean force, ResultCallback callback) {
        if (!SU_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                notify(callback, false, "正在申请 Root 权限，请在 Magisk / KernelSU 弹窗中点允许。");
                if (!requestSu()) {
                    saveSuGranted(app, false);
                    notify(callback, false,
                            "未获得 Root。请点允许，或到 Magisk / KernelSU 超级用户里给 uu168 永久授权后再点重试。");
                    return;
                }
                saveSuGranted(app, true);
                notify(callback, true, "Root 已授权，正在同步 LSPosed 作用域…");
                applyAfterSuGranted(app, force);
                notify(callback, true, "Root 已授权，已勾选 " + YouzengConfig.joinedAutoAddScopePackages() + "。");
            } finally {
                SU_IN_FLIGHT.set(false);
            }
        });
    }

    /**
     * 仅在用户已经通过前台授权过 su 之后调用（开机广播、装包广播）。
     * 未授权时不去调 su，避免 Magisk 把后台请求记成拒绝。
     */
    public static void applyRecommendedScopeAsync(Context context) {
        final Context app = context.getApplicationContext();
        if (!isSuGranted(app)) {
            Log.i(TAG, "skip cli: su not granted yet");
            return;
        }
        IO.execute(() -> applyAfterSuGranted(app, false));
    }

    /**
     * 目标 App 卸载再装时：只追加这一个包，并重试到 {@code scope ls} 能看到为止。
     * 不要用 {@code -i}：LSPosed 缓存未刷新时 CLI 会返回成功但实际没勾上。
     */
    public static void appendWatchedPackageAsync(Context context, String packageName) {
        if (!isWatchedTarget(packageName)) {
            return;
        }
        final Context app = context.getApplicationContext();
        if (!isSuGranted(app)) {
            Log.i(TAG, "skip append, su not granted: " + packageName);
            return;
        }
        IO.execute(() -> {
            ensureWatcherInstalled(app);
            appendPackageUntilConfirmed(packageName);
        });
    }

    static boolean isWatchedPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        return YouzengConfig.MODULE_PACKAGE.equals(packageName)
                || isWatchedTarget(packageName);
    }

    static boolean isWatchedTarget(String packageName) {
        return YouzengConfig.isAutoAddScopePackage(packageName);
    }

    private static void applyAfterSuGranted(Context app, boolean force) {
        long now = System.currentTimeMillis();
        long prev = LAST_APPLY_AT.get();
        if (!force && now - prev < DEBOUNCE_MS && prev > 0L) {
            return;
        }
        ensureWatcherInstalled(app);
        patchLsposedDb(app);
        installCliWrapper(app);
        if (applyRecommendedScope()) {
            LAST_APPLY_AT.set(now);
        }
        for (String pkg : YouzengConfig.autoAddScopePackages()) {
            appendPackageUntilConfirmed(pkg);
        }
    }

    /**
     * 打开交互式 {@code su} 会话，让 Magisk/KernelSU 弹出授权框。
     * 已永久授权时会立刻成功，不会再弹窗。
     */
    private static boolean requestSu() {
        Process process = null;
        try {
            process = new ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start();
            final Process running = process;
            Thread gobbler = new Thread(() -> drain(running.getInputStream()), "youzeng-su-out");
            gobbler.setDaemon(true);
            gobbler.start();
            try (OutputStream os = process.getOutputStream()) {
                os.write("id\nexit\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            boolean finished;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                finished = process.waitFor(SU_PROMPT_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (!finished) {
                    Log.w(TAG, "su prompt timed out");
                    process.destroy();
                    return false;
                }
                return process.exitValue() == 0;
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            Log.w(TAG, "request su failed", e);
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static boolean applyRecommendedScope() {
        StringBuilder scopes = new StringBuilder();
        for (String pkg : YouzengConfig.recommendedScopePackages()) {
            if (scopes.length() > 0) {
                scopes.append(' ');
            }
            scopes.append(pkg).append("/0");
        }
        String module = YouzengConfig.MODULE_PACKAGE;
        // -s 覆盖整份作用域，避免旧的 system/0 被 -a 追加留下。
        String cmd = CLI + " scope set -s " + module + " " + module + "/0 " + scopes
                + " && " + CLI + " modules set -e " + module;
        int code = su(cmd);
        Log.i(TAG, "cli apply scope exit=" + code + " cmd=" + cmd);
        return code == 0;
    }

    /**
     * 不加 {@code -i}：包刚装上时 LSPosed 缓存可能还没有它，忽略会让 CLI 直接成功但没写入作用域。
     */
    private static boolean appendPackageUntilConfirmed(String packageName) {
        if (!packageInstalled(packageName)) {
            Log.i(TAG, "skip append, not installed: " + packageName);
            return false;
        }
        String module = YouzengConfig.MODULE_PACKAGE;
        String arg = packageName + "/0";
        String append = CLI + " scope set -a " + module + " " + arg;
        String ls = CLI + " scope ls " + module;
        for (int i = 0; i < 15; i++) {
            try {
                Thread.sleep(i == 0 ? 1500 : 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!packageInstalled(packageName)) {
                return false;
            }
            int code = su(append);
            String listed = suOutput(ls);
            Log.i(TAG, "append " + packageName + " try=" + i + " exit=" + code + " ls=" + listed.replace('\n', ' '));
            if (listed.contains(packageName + "/")) {
                return true;
            }
        }
        Log.w(TAG, "failed to confirm scope for " + packageName);
        return false;
    }

    private static boolean packageInstalled(String packageName) {
        String out = suOutput("cmd package path " + packageName);
        return out.contains("package:");
    }

    private static void patchLsposedDb(Context context) {
        String apk = context.getPackageCodePath();
        int code = su("CLASSPATH='" + apk + "' /system/bin/app_process /system/bin "
                + LsposedDbPatch.class.getName());
        Log.i(TAG, "LsposedDbPatch exit=" + code);
    }

    private static void installCliWrapper(Context context) {
        String script = ""
                + "#!/system/bin/sh\n"
                + "DIR=/data/adb/modules/zygisk_lsposed\n"
                + "[ -f \"$DIR/daemon.apk\" ] || DIR=/debug_ramdisk/.magisk/modules/zygisk_lsposed\n"
                + "APK=\"$DIR/daemon.apk\"\n"
                + "[ -f \"$APK\" ] || { echo 'daemon.apk not found' >&2; exit 1; }\n"
                + "export CLASSPATH=\"$APK\"\n"
                + "exec /system/bin/app_process -Djava.class.path=\"$APK\" /system/bin "
                + "--nice-name=lsp-cli:264ba8aa-3ef0-4b28-a8eb-fda38cbed445 "
                + "org.lsposed.lspd.cli.Main \"$@\"\n";
        File local = new File(context.getFilesDir(), "lsposed-cli.sh");
        try {
            try (OutputStream os = new FileOutputStream(local)) {
                os.write(script.getBytes(StandardCharsets.UTF_8));
            }
            su("mkdir -p /data/adb/lspd/bin"
                    + " && cp '" + local.getAbsolutePath() + "' /data/adb/lspd/bin/cli"
                    + " && chmod 755 /data/adb/lspd/bin/cli");
        } catch (Exception e) {
            Log.w(TAG, "install cli wrapper failed", e);
        }
    }

    private static void ensureWatcherInstalled(Context context) {
        File local = new File(context.getFilesDir(), WATCHER_NAME);
        try {
            writeWatcherScript(local);
            su("mkdir -p /data/adb/service.d"
                    + " && if [ -f /data/adb/youzeng-lsposed-scope.pid ]; then"
                    + " kill \"$(cat /data/adb/youzeng-lsposed-scope.pid)\" 2>/dev/null; fi"
                    + " && cp '" + local.getAbsolutePath() + "' '" + WATCHER_DST + "'"
                    + " && chmod 755 '" + WATCHER_DST + "'"
                    + " && sh '" + WATCHER_DST + "'");
        } catch (Exception e) {
            Log.w(TAG, "install watcher failed", e);
        }
    }

    private static void writeWatcherScript(File dest) throws Exception {
        StringBuilder watchedPkgs = new StringBuilder();
        for (String pkg : YouzengConfig.autoAddScopePackages()) {
            if (watchedPkgs.length() > 0) {
                watchedPkgs.append(' ');
            }
            watchedPkgs.append(pkg);
        }
        String script = ""
                + "#!/system/bin/sh\n"
                + "MOD='" + YouzengConfig.MODULE_PACKAGE + "'\n"
                + "CLI='" + CLI + "'\n"
                + "WATCHED='" + watchedPkgs + "'\n"
                + "PIDFILE=/data/adb/youzeng-lsposed-scope.pid\n"
                + "LOG=/data/adb/youzeng-lsposed-scope.log\n"
                + "WASDIR=/data/adb/youzeng-scope-was\n"
                + "mkdir -p \"$WASDIR\"\n"
                + "pkg_path() { cmd package path \"$1\" 2>/dev/null; }\n"
                + "pkg_on() { pkg_path \"$1\" | grep -q .; }\n"
                + "scope_has() { \"$CLI\" scope ls \"$MOD\" 2>/dev/null | grep -q \"^$1/\"; }\n"
                + "append_until_ok() {\n"
                + "  pkg=\"$1\"\n"
                + "  i=0\n"
                + "  while [ \"$i\" -lt 15 ]; do\n"
                + "    [ -x \"$CLI\" ] || return 1\n"
                + "    pkg_on \"$pkg\" || return 1\n"
                + "    \"$CLI\" scope set -a \"$MOD\" \"$pkg/0\" >>\"$LOG\" 2>&1\n"
                + "    if scope_has \"$pkg\"; then\n"
                + "      echo \"$(date) scoped $pkg\" >>\"$LOG\"\n"
                + "      return 0\n"
                + "    fi\n"
                + "    i=$((i+1))\n"
                + "    sleep 1\n"
                + "  done\n"
                + "  echo \"$(date) FAILED $pkg\" >>\"$LOG\"\n"
                + "  return 1\n"
                + "}\n"
                + "if [ \"$1\" != daemon ]; then\n"
                + "  if [ -f \"$PIDFILE\" ]; then\n"
                + "    kill \"$(cat \"$PIDFILE\")\" 2>/dev/null\n"
                + "  fi\n"
                + "  if command -v setsid >/dev/null 2>&1; then\n"
                + "    setsid /system/bin/sh \"$0\" daemon </dev/null >>\"$LOG\" 2>&1 &\n"
                + "  else\n"
                + "    /system/bin/sh \"$0\" daemon </dev/null >>\"$LOG\" 2>&1 &\n"
                + "  fi\n"
                + "  exit 0\n"
                + "fi\n"
                + "echo $$ > \"$PIDFILE\"\n"
                + "trap '' HUP\n"
                + "i=0\n"
                + "while [ \"$i\" -lt 90 ]; do\n"
                + "  [ \"$(getprop sys.boot_completed)\" = \"1\" ] && [ -x \"$CLI\" ] && break\n"
                + "  sleep 2\n"
                + "  i=$((i+1))\n"
                + "done\n"
                + "echo \"$(date) daemon start\" >>\"$LOG\"\n"
                + "for p in $WATCHED; do\n"
                + "  pkg_on \"$p\" && append_until_ok \"$p\"\n"
                + "  echo 0 > \"$WASDIR/$p\"\n"
                + "  pkg_on \"$p\" && echo 1 > \"$WASDIR/$p\"\n"
                + "done\n"
                + "while true; do\n"
                + "  sleep 2\n"
                + "  [ -x \"$CLI\" ] || continue\n"
                + "  for p in $WATCHED; do\n"
                + "    now=0\n"
                + "    pkg_on \"$p\" && now=1\n"
                + "    old=$(cat \"$WASDIR/$p\" 2>/dev/null || echo 0)\n"
                + "    if [ \"$now\" -eq 1 ] && [ \"$old\" -eq 0 ]; then\n"
                + "      echo \"$(date) $p 0->1\" >>\"$LOG\"\n"
                + "      append_until_ok \"$p\"\n"
                + "    fi\n"
                + "    echo \"$now\" > \"$WASDIR/$p\"\n"
                + "  done\n"
                + "done\n";
        try (OutputStream os = new FileOutputStream(dest)) {
            os.write(script.getBytes(StandardCharsets.UTF_8));
        }
        dest.setExecutable(true, false);
    }

    private static int su(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            drain(process.getInputStream());
            return process.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "su failed: " + command, e);
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String suOutput(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            String out = drainToString(process.getInputStream());
            process.waitFor();
            return out;
        } catch (Exception e) {
            Log.w(TAG, "suOutput failed: " + command, e);
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String drainToString(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            byte[] buf = new byte[4096];
            StringBuilder out = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void drain(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            byte[] buf = new byte[4096];
            StringBuilder out = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            if (out.length() > 0) {
                Log.i(TAG, "su: " + out);
            }
        } catch (Exception ignored) {
        }
    }

    private static void notify(ResultCallback callback, boolean granted, String message) {
        Log.i(TAG, message);
        if (callback != null) {
            callback.onResult(granted, message);
        }
    }

    private static boolean isSuGranted(Context context) {
        return prefs(context).getBoolean(PREF_SU_GRANTED, false);
    }

    private static void saveSuGranted(Context context, boolean granted) {
        prefs(context).edit().putBoolean(PREF_SU_GRANTED, granted).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
