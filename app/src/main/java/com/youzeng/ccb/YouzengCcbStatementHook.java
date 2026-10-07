package com.youzeng.ccb;

import com.youzeng.hook.YouzengConfig;
import com.youzeng.hook.YouzengForwardClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 建行「流水打印」走小程序 {@code /jhdj_wyy/P1161W955}，请求体在 JS 里已经加密。
 * 在加密前把交易改道到 {@code FinChatJSCore.invokeHandler("youzengCcbPrint")}，
 * 由这里转发 youzeng，回包就是页面直接使用的明文。
 */
public final class YouzengCcbStatementHook {

    private static final String MARK = "youzengCcbPrint";
    private static final String EVENT = "youzengCcbPrint";
    /** 正确注入：prelude 在 function(){ 之后。旧版打在属性名之前，小程序解析失败卡在加载页。 */
    private static final String HEALTHY = "function(){try{var __yz=FinChatJSCore.invokeHandler(\"" + EVENT + "\"";
    private static volatile boolean bridgeHooked = false;
    private static volatile boolean fosHooked = false;
    private static volatile boolean watcherStarted = false;
    private static volatile boolean jsPatched = false;
    private static volatile long lastScanMs = 0;
    private static final Map<FileOutputStream, File> FOS_FILES =
            Collections.synchronizedMap(new WeakHashMap<FileOutputStream, File>());
    private static final ThreadLocal<Boolean> WRITING_PATCH = new ThreadLocal<Boolean>();

    private YouzengCcbStatementHook() {
    }

    public static void install(ClassLoader cl) {
        hookBridge(cl);
        hookAppJsWrites();
        patchAppJs();
        startWatcher();
    }

    private static void hookBridge(ClassLoader cl) {
        if (bridgeHooked || cl == null) {
            return;
        }
        String[] names = new String[]{
                "com.finogeeks.lib.applet.jsbridge.c",
                "com.finogeeks.lib.applet.jsbridge.JSInterface",
        };
        for (int i = 0; i < names.length; i++) {
            try {
                Class<?> cls = XposedHelpers.findClass(names[i], cl);
                XposedHelpers.findAndHookMethod(
                        cls,
                        "invokeHandler",
                        String.class,
                        String.class,
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                maybeRescan();
                                onInvoke(param);
                            }
                        });
                bridgeHooked = true;
                XposedBridge.log("[youzeng] ccb statement bridge hooked " + names[i]);
                return;
            } catch (Throwable t) {
                XposedBridge.log("[youzeng] ccb statement bridge skip " + names[i] + " " + t.getClass().getSimpleName());
            }
        }
    }

    private static void onInvoke(XC_MethodHook.MethodHookParam param) {
        Object ev = param.args[0];
        if (ev == null || !EVENT.equals(String.valueOf(ev))) {
            return;
        }
        String params = param.args[1] == null ? "" : String.valueOf(param.args[1]);
        try {
            JSONObject in = new JSONObject(params);
            String tx = in.optString("transaction_id", "");
            Object data = in.opt("jsonData");
            String req = data == null ? "{}" : String.valueOf(data);
            if (req.isEmpty() || "null".equals(req)) {
                req = "{}";
            }
            // 注意：invokeHandler 是 JS 线程同步调用，Java 层阻塞即 JS 线程阻塞。
            // 用短超时（5 s）而不是 FORWARD_READ_MS_LONG（60 s），防止小程序卡在加载页。
            JSONObject fw = YouzengForwardClient.forward(
                    "ccb",
                    "jhdj_wyy/" + tx,
                    tx,
                    req,
                    "{}",
                    tx,
                    5000);
            if (fw != null && "replace".equals(fw.optString("action", ""))) {
                String body = fw.optString("body", "");
                if (!body.isEmpty()) {
                    param.setResult(body);
                    XposedBridge.log("[youzeng] ccb statement replace tx=" + tx + " bodyLen=" + body.length());
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] ccb statement invoke: " + t);
        }
        // 返回 null 而不是 ""，避免 JS 层 JSON.parse("") 抛异常
        param.setResult(null);
    }

    private static void maybeRescan() {
        if (jsPatched) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastScanMs < 1000L) {
            return;
        }
        lastScanMs = now;
        patchAppJs();
    }

    private static void startWatcher() {
        if (watcherStarted) {
            return;
        }
        watcherStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 90; i++) {
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    patchAppJs();
                    if (jsPatched && i >= 15) {
                        XposedBridge.log("[youzeng] ccb statement watcher done patched=1");
                        return;
                    }
                }
                XposedBridge.log("[youzeng] ccb statement watcher timeout patched=" + (jsPatched ? 1 : 0));
            }
        }, "youzeng-ccb-js-patch");
        t.setDaemon(true);
        t.start();
    }

    private static void hookAppJsWrites() {
        if (fosHooked) {
            return;
        }
        fosHooked = true;
        XC_MethodHook remember = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                File f = fosFileArg(param.args[0]);
                if (isRuntimeAppJs(f)) {
                    FOS_FILES.put((FileOutputStream) param.thisObject, f);
                }
            }
        };
        try {
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, remember);
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, File.class, boolean.class, remember);
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class, remember);
            XposedHelpers.findAndHookConstructor(FileOutputStream.class, String.class, boolean.class, remember);
            XposedHelpers.findAndHookMethod(FileOutputStream.class, "close", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(WRITING_PATCH.get())) {
                        return;
                    }
                    File f = FOS_FILES.remove(param.thisObject);
                    if (f != null) {
                        patchFile(f);
                    }
                }
            });
            XposedBridge.log("[youzeng] ccb statement fos hook installed");
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] ccb statement fos hook: " + t);
        }
    }

    private static File fosFileArg(Object arg) {
        if (arg instanceof File) {
            return (File) arg;
        }
        if (arg instanceof String) {
            return new File((String) arg);
        }
        return null;
    }

    private static void patchAppJs() {
        File files = new File("/data/data/" + CcbConfig.TARGET_PACKAGE + "/files");
        int n = walk(new File(files, "Jump"), 0);
        n += walk(new File(files, "MiniProgram"), 0);
        if (n > 0) {
            jsPatched = true;
        }
        XposedBridge.log("[youzeng] ccb statement js patched=" + n);
    }

    private static int walk(File dir, int depth) {
        if (dir == null || depth > 8 || !dir.isDirectory()) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < children.length; i++) {
            File f = children[i];
            if (f.isDirectory()) {
                n += walk(f, depth + 1);
            } else if (isRuntimeAppJs(f)) {
                if (patchFile(f)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean isRuntimeAppJs(File f) {
        if (f == null || !"__APP__.js".equals(f.getName())) {
            return false;
        }
        String p = f.getAbsolutePath().replace('\\', '/');
        return p.contains("/source/");
    }

    /** 旧版把 prelude 插在对象属性名之前，例如 }catch(__e){}getTransactions: */
    private static boolean hasPreludeBeforeKey(String text) {
        return text != null && (
                text.contains("}catch(__e){}getTransactions:")
                        || text.contains("}catch(__e){}submitApplication")
                        || text.contains("}catch(__e){}getOrderList:")
                        || text.contains("}catch(__e){}sumbmitJD10:"));
    }

    private static boolean isHealthy(String text) {
        return text != null && text.contains(HEALTHY) && !hasPreludeBeforeKey(text);
    }

    /** 去掉所有 youzeng prelude，不论插在函数内还是属性名之前。 */
    private static String stripAllPreludes(String text) {
        String mark = "try{var __yz=FinChatJSCore.invokeHandler(\"" + EVENT + "\"";
        String end = "}catch(__e){}";
        String next = text;
        int guard = 0;
        while (guard++ < 20) {
            int idx = next.indexOf(mark);
            if (idx < 0) {
                break;
            }
            int endPos = next.indexOf(end, idx);
            if (endPos < 0) {
                break;
            }
            next = next.substring(0, idx) + next.substring(endPos + end.length());
        }
        return next;
    }

    private static boolean extractAppJs(File zipFile, File dest) {
        ZipFile z = null;
        try {
            z = new ZipFile(zipFile);
            ZipEntry hit = null;
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                int slash = name.lastIndexOf('/');
                String base = slash >= 0 ? name.substring(slash + 1) : name;
                if ("__APP__.js".equals(base)) {
                    hit = e;
                    break;
                }
            }
            if (hit == null) {
                return false;
            }
            InputStream in = z.getInputStream(hit);
            WRITING_PATCH.set(Boolean.TRUE);
            FileOutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                in.close();
                out.close();
                WRITING_PATCH.remove();
            }
            XposedBridge.log("[youzeng] ccb statement extracted " + dest.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] ccb statement unzip: " + t);
            return false;
        } finally {
            if (z != null) {
                try {
                    z.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String[][] needles() {
        return new String[][]{
                {"getTransactions:function(){var n=arguments.length>0", "P11048004"},
                {"submitApplication:function(){var t=arguments.length>1", "P1161W955"},
                {"submitApplicationnewECD:function(){var t=arguments.length>1", "P1161W955ECD"},
                {"submitApplicationnew:function(){var t=arguments.length>1", "P1161W955-ccvh5"},
                {"sumbmitJD10:function(){var n=arguments;", "A0152DJ10"},
                {"getOrderList:function(){var t=arguments.length>0", "P1161W941"},
        };
    }

    private static boolean patchFile(File file) {
        try {
            String text = readUtf8(file);
            if (isHealthy(text)) {
                return true;
            }
            String next = text;
            if (next.contains(MARK) || hasPreludeBeforeKey(next)) {
                next = stripAllPreludes(next);
                XposedBridge.log("[youzeng] ccb statement stripped preludes " + file.getAbsolutePath());
            }
            if (hasPreludeBeforeKey(next) || (next.contains(MARK) && !isHealthy(next))) {
                File zip = new File(file.getParentFile(), "__APP__.zip");
                if (zip.isFile() && extractAppJs(zip, file)) {
                    next = readUtf8(file);
                }
            }
            String[][] rows = needles();
            int hits = 0;
            for (int i = 0; i < rows.length; i++) {
                String needle = rows[i][0];
                if (!next.contains(needle)) {
                    XposedBridge.log("[youzeng] ccb statement needle missing " + rows[i][1]);
                    continue;
                }
                // 把 prelude 插入函数体 "{" 之后，而不是属性名之前，
                // 否则会在对象字面量两属性之间产生非法语句，整个 JS 文件解析失败。
                int bracePos = needle.indexOf("{");
                if (bracePos < 0) {
                    next = next.replace(needle, prelude(rows[i][1]) + needle);
                } else {
                    String nBefore = needle.substring(0, bracePos + 1);
                    String nAfter = needle.substring(bracePos + 1);
                    next = next.replace(needle, nBefore + prelude(rows[i][1]) + nAfter);
                }
                hits++;
            }
            if (hits == 0 || next.equals(text)) {
                return isHealthy(next);
            }
            File tmp = new File(file.getParentFile(), "__APP__.js.youzeng");
            writeUtf8(tmp, next);
            if (!tmp.renameTo(file)) {
                writeUtf8(file, next);
                tmp.delete();
            }
            XposedBridge.log("[youzeng] ccb statement patched " + file.getAbsolutePath() + " hits=" + hits);
            return isHealthy(next);
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] ccb statement patch: " + t);
            return false;
        }
    }

    private static String readUtf8(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            int len = (int) file.length();
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static void writeUtf8(File file, String text) throws Exception {
        WRITING_PATCH.set(Boolean.TRUE);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
            WRITING_PATCH.remove();
        }
    }

    private static String prelude(String tx) {
        return "try{var __yz=FinChatJSCore.invokeHandler(\"" + EVENT + "\",JSON.stringify({transaction_id:\""
                + tx + "\",jsonData:arguments.length>0&&void 0!==arguments[0]?arguments[0]:{}}),0);"
                + "if(__yz&&\"string\"==typeof __yz&&__yz.length>0)__yz=JSON.parse(__yz);"
                + "if(__yz&&\"object\"==typeof __yz&&__yz[\"C-API-Status\"])return Promise.resolve(__yz)}catch(__e){}";
    }
}
