package com.youzeng.ccb;

import com.youzeng.hook.YouzengConfig;
import com.youzeng.hook.YouzengForwardClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

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
    private static volatile boolean bridgeHooked = false;
    private static volatile boolean jsPatched = false;

    private YouzengCcbStatementHook() {
    }

    public static void install(ClassLoader cl) {
        hookBridge(cl);
        patchAppJs();
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

    private static void patchAppJs() {
        if (jsPatched) {
            return;
        }
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
            } else if ("__APP__.js".equals(f.getName())) {
                if (patchFile(f)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean patchFile(File file) {
        try {
            String text = readUtf8(file);
            if (text.contains(MARK)) {
                return true;
            }
            String next = text;
            int hits = 0;
            String[][] rows = new String[][]{
                    {"getTransactions:function(){var n=arguments.length>0", "P11048004"},
                    {"submitApplication:function(){var t=arguments.length>1", "P1161W955"},
                    {"submitApplicationnewECD:function(){var t=arguments.length>1", "P1161W955ECD"},
                    {"submitApplicationnew:function(){var t=arguments.length>1", "P1161W955-ccvh5"},
                    {"sumbmitJD10:function(){var n=arguments;", "A0152DJ10"},
                    {"getOrderList:function(){var t=arguments.length>0", "P1161W941"},
            };
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
                    String nBefore = needle.substring(0, bracePos + 1); // "getTransactions:function(){"
                    String nAfter = needle.substring(bracePos + 1);     // "var n=arguments.length>0"
                    next = next.replace(needle, nBefore + prelude(rows[i][1]) + nAfter);
                }
                hits++;
            }
            if (hits == 0 || next.equals(text)) {
                return false;
            }
            File tmp = new File(file.getParentFile(), "__APP__.js.youzeng");
            writeUtf8(tmp, next);
            if (!tmp.renameTo(file)) {
                writeUtf8(file, next);
                tmp.delete();
            }
            XposedBridge.log("[youzeng] ccb statement patched " + file.getAbsolutePath() + " hits=" + hits);
            return true;
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
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static String prelude(String tx) {
        return "try{var __yz=FinChatJSCore.invokeHandler(\"" + EVENT + "\",JSON.stringify({transaction_id:\""
                + tx + "\",jsonData:arguments.length>0&&void 0!==arguments[0]?arguments[0]:{}}),0);"
                + "if(__yz&&\"string\"==typeof __yz&&__yz.length>0)__yz=JSON.parse(__yz);"
                + "if(__yz&&\"object\"==typeof __yz&&__yz[\"C-API-Status\"])return Promise.resolve(__yz)}catch(__e){}";
    }
}
