package com.youzeng.ccb;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.youzeng.hook.YouzengForwardClient;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 建行业务交易在明文落地后整包转发 youzeng {@code /api/forward}。
 * 国密 {@code encGmDat} 等 {@code WhiteListUtils.decrypt} 解出明文后再替换 {@code data}，不再回封密文。
 */
public final class YouzengCcbNetworkHook {

    private static final String TAG = "youzeng";
    private static volatile boolean parseHookLegacyInstalled = false;
    private static volatile boolean parseHookTxInstalled = false;
    private static volatile boolean gmDecryptHookInstalled = false;
    private static volatile boolean httpShortCircuitInstalled = false;
    private static volatile boolean mbsMenuSkipInstalled = false;

    private static final ThreadLocal<Boolean> LOCAL_MOCK = new ThreadLocal<>();
    private static final ThreadLocal<FwdCtx> FWD = new ThreadLocal<>();
    private static final ThreadLocal<String> REQ_GM_PLAIN = new ThreadLocal<>();
    // 跨线程备份：异步 HTTP 时 ThreadLocal 跨线程失效，用此 Map 兜底
    private static final java.util.concurrent.ConcurrentHashMap<String, String> GM_PLAIN_MAP =
            new java.util.concurrent.ConcurrentHashMap<>();
    // 最后一次捕获的明文（顺序请求兜底）
    private static volatile String LAST_GM_PLAIN = "";

    private static final class FwdCtx {
        final String txcode;
        final String url;
        final String reqPlain;

        FwdCtx(String txcode, String url, String reqPlain) {
            this.txcode = txcode == null ? "" : txcode;
            this.url = url == null ? "" : url;
            this.reqPlain = reqPlain == null ? "" : reqPlain;
        }
    }

    private YouzengCcbNetworkHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!CcbConfig.isTargetPackage(lpparam.packageName)) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                tryInstall(cl);
            }
        });
        XposedHelpers.findAndHookMethod(android.app.Activity.class, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object thiz = param.thisObject;
                if (thiz == null) {
                    return;
                }
                String cn = thiz.getClass().getName();
                ClassLoader acl = thiz.getClass().getClassLoader();
                if ("com.ccb.start.MainActivity".equals(cn)) {
                    tryInstall(acl);
                }
            }
        });
        // onResume：扫描并 patch 已有的 AssetFragment 实例
        XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object thiz = param.thisObject;
                if (thiz != null && "com.ccb.start.MainActivity".equals(thiz.getClass().getName())) {
                    YouzengCcbH5AssetHook.scanAndPatchFragments(thiz);
                }
            }
        });
        tryInstall(cl);
    }

    private static void tryInstall(ClassLoader cl) {
        if (CcbConfig.ENABLE_FORWARD_ALL) {
            // 尽早异步加载 ignore list，避免首次 forward() 同步等待 8s
            YouzengForwardClient.prefetchIgnoreList();
            hookLegacyParseResult(cl);
            hookTxParseResult(cl);
            hookGmDecrypt(cl);
            hookHttpShortCircuit(cl);
            YouzengCcbStatementHook.install(cl);
            // 尽早安装财富 hook（不依赖 MainActivity）
            YouzengCcbH5AssetHook.startPrefetch();
            YouzengCcbH5AssetHook.installAfterBusinessDex(cl);
        }
    }

    private static void hookLegacyParseResult(ClassLoader cl) {
        if (parseHookLegacyInstalled) {
            return;
        }
        try {
            Class<?> rspCls = XposedHelpers.findClass(
                    "com.ccb.framework.transaction.TransactionResponse", cl);
            XposedHelpers.findAndHookMethod(
                    rspCls,
                    "parseResult",
                    String.class,
                    java.io.InputStream.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rewritePlain(param);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            FWD.remove();
                            REQ_GM_PLAIN.remove();
                            LOCAL_MOCK.remove();
                        }
                    });
            parseHookLegacyInstalled = true;
            log("[+] ccb parseResult(String) hooked (after decrypt)");
        } catch (Throwable t) {
            log("[-] ccb parseResult(String) hook skip: " + t);
        }
    }

    private static void hookTxParseResult(ClassLoader cl) {
        if (parseHookTxInstalled) {
            return;
        }
        try {
            Class<?> rspCls = XposedHelpers.findClass(
                    "com.ccb.framework.tx.CcbBaseTransactionResponse", cl);
            Class<?> reqCls = XposedHelpers.findClass(
                    "com.ccb.framework.tx.TransactionRequest", cl);
            Class<?> httpRspCls = XposedHelpers.findClass(
                    "com.ccb.framework.tx.http.Response", cl);
            XposedHelpers.findAndHookMethod(
                    rspCls,
                    "parseResult",
                    reqCls,
                    httpRspCls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rewriteTx(param);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            FWD.remove();
                            REQ_GM_PLAIN.remove();
                            LOCAL_MOCK.remove();
                        }
                    });
            parseHookTxInstalled = true;
            log("[+] ccb parseResult hooked (framework.tx)");
        } catch (Throwable t) {
            log("[-] ccb tx parseResult hook skip: " + t);
        }
    }

    private static void hookHttpShortCircuit(ClassLoader cl) {
        if (httpShortCircuitInstalled) {
            return;
        }
        try {
            Class<?> httpCls = XposedHelpers.findClass(
                    "com.ccb.framework.tx.httpurlconnection.HttpConnectionClient", cl);
            Class<?> txReq = XposedHelpers.findClass("com.ccb.framework.tx.TransactionRequest", cl);
            Class<?> httpReq = XposedHelpers.findClass("com.ccb.framework.tx.http.Request", cl);
            final Class<?> httpRsp = XposedHelpers.findClass("com.ccb.framework.tx.http.Response", cl);
            XposedHelpers.findAndHookMethod(
                    httpCls,
                    "http4Result",
                    txReq,
                    httpReq,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            trySkipUpstream(param, httpRsp);
                        }
                    });
            XposedHelpers.findAndHookMethod(txReq, "isEbsResponseEncryptNeeded", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(LOCAL_MOCK.get())) {
                        param.setResult(false);
                    }
                }
            });
            httpShortCircuitInstalled = true;
            log("[+] ccb http4Result skip-upstream hooked");
        } catch (Throwable t) {
            log("[-] ccb http4Result hook skip: " + t);
        }
    }

    private static void trySkipUpstream(XC_MethodHook.MethodHookParam param, Class<?> httpRsp) {
        try {
            Object request = param.args[0];
            String tx = callString(request, "getTxCode");
            if (tx == null) {
                tx = "";
            }
            tx = tx.toUpperCase();
            if (!CcbConfig.LOCAL_MOCK_TXCODES.contains(tx)) {
                LOCAL_MOCK.remove();
                return;
            }
            String url = callString(request, "getUrl");
            String reqPlain = requestToJson(request);
            JSONObject fw = YouzengForwardClient.forward("ccb", url, tx, reqPlain, "{}", tx);
            if (fw == null || !"replace".equals(fw.optString("action", ""))) {
                return;
            }
            String body = fw.optString("body", "");
            if (body.isEmpty()) {
                return;
            }
            Object rsp = XposedHelpers.newInstance(httpRsp, true);
            XposedHelpers.callMethod(rsp, "setResponseCode", 200);
            XposedHelpers.callMethod(rsp, "setString", body);
            XposedHelpers.callMethod(rsp, "setStringResult", true);
            LOCAL_MOCK.set(Boolean.TRUE);
            param.setResult(rsp);
            log("[FORWARD] ccb skip-upstream tx=" + tx + " bodyLen=" + body.length());
        } catch (Throwable t) {
            LOCAL_MOCK.remove();
            log("[-] ccb skip-upstream: " + t);
        }
    }

    private static void hookMbsMenuSkip(ClassLoader cl) {
        if (mbsMenuSkipInstalled) {
            return;
        }
        try {
            Class<?> httpCls = XposedHelpers.findClass(
                    "com.ccb.common.net.httpconnection.MbsNewHttpConnection", cl);
            Class<?> mbsReq = XposedHelpers.findClass(
                    "com.ccb.common.net.httpconnection.MbsRequest", cl);
            final Class<?> mbsRsp = XposedHelpers.findClass(
                    "com.ccb.common.net.httpconnection.MbsResult", cl);
            XposedHelpers.findAndHookMethod(
                    httpCls,
                    "http4Result",
                    mbsReq,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            trySkipMbsMenu(param, mbsRsp);
                        }
                    });
            mbsMenuSkipInstalled = true;
            log("[+] ccb mbs PMENU3 skip-upstream hooked");
        } catch (Throwable t) {
            log("[-] ccb mbs menu hook skip: " + t);
        }
    }

    private static void trySkipMbsMenu(XC_MethodHook.MethodHookParam param, Class<?> mbsRsp) {
        try {
            Object request = param.args[0];
            String tx = callString(request, "getTxCode");
            if (tx == null) {
                tx = "";
            }
            tx = tx.toUpperCase();
            if (!CcbConfig.MBS_SKIP_TXCODES.contains(tx)) {
                return;
            }
            String url = callString(request, "getUrl");
            String reqPlain = requestToJson(request);
            JSONObject fw = YouzengForwardClient.forward("ccb", url, tx, reqPlain, "{}", tx);
            if (fw == null || !"replace".equals(fw.optString("action", ""))) {
                return;
            }
            String body = fw.optString("body", "");
            if (body.isEmpty()) {
                return;
            }
            Object rsp = XposedHelpers.newInstance(mbsRsp, true);
            XposedHelpers.callMethod(rsp, "setResponseCode", 200);
            XposedHelpers.callMethod(rsp, "setString", body);
            XposedHelpers.callMethod(rsp, "setStringResult", true);
            param.setResult(rsp);
            log("[FORWARD] ccb mbs skip-upstream tx=" + tx + " bodyLen=" + body.length());
        } catch (Throwable t) {
            log("[-] ccb mbs skip-upstream: " + t);
        }
    }

    private static void hookGmDecrypt(ClassLoader cl) {
        if (gmDecryptHookInstalled) {
            return;
        }
        try {
            Class<?> wl = XposedHelpers.findClass("com.ccb.framework.whitelist.WhiteListUtils", cl);
            XposedHelpers.findAndHookMethod(wl, "decrypt", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rewriteGmPlain(param);
                }
            });

            // ── Hook EbsSafeManager.getEncryptString(String) 捕获 ccbParam 明文 ──
            // JADX 分析确认：EbsP3Request.buildCcbParam() 调用此方法加密请求参数
            // ccbParam 明文为 URL 编码字符串，包含 TXCODE/StDt/EdDt/Qry_Cntnt 等字段
            try {
                Class<?> ebsSafe = XposedHelpers.findClass("com.ccb.common.safe.EbsSafeManager", cl);
                XposedHelpers.findAndHookMethod(ebsSafe, "getEncryptString", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // param.args[0] 就是 ccbParam 加密前的 URL 编码明文
                        captureReqGmPlain(param.args[0]);
                    }
                });
                log("[+] ccb EbsSafeManager.getEncryptString(String) hooked");
            } catch (Throwable t) {
                log("[-] ccb EbsSafeManager.getEncryptString hook skip: " + t);
            }
            // ── 同时 hook WhiteListUtils.smEnvelop(String) 兜底国密加密 ──
            try {
                XposedHelpers.findAndHookMethod(wl, "smEnvelop", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        captureReqGmPlain(param.args[0]);
                    }
                });
                log("[+] ccb WhiteListUtils.smEnvelop(String) hooked");
            } catch (Throwable t) {
                log("[-] ccb WL.smEnvelop hook skip: " + t);
            }
            gmDecryptHookInstalled = true;
            log("[+] ccb WhiteListUtils.decrypt hooked");
        } catch (Throwable t) {
            log("[-] ccb gm decrypt hook skip: " + t);
        }
    }

    private static void rewriteGmPlain(XC_MethodHook.MethodHookParam param) {
        Object dec = param.getResult();
        if (dec == null) {
            return;
        }
        String data;
        try {
            Object v = XposedHelpers.getObjectField(dec, "data");
            data = v != null ? String.valueOf(v) : "";
        } catch (Throwable t) {
            return;
        }
        if (data.isEmpty()) {
            return;
        }
        FwdCtx ctx = FWD.get();
        String txcode = ctx != null ? ctx.txcode : txcodeFromJson(data);
        String url = ctx != null ? ctx.url : "";
        String reqPlain = ctx != null ? ctx.reqPlain : "";
        JSONObject fw = YouzengForwardClient.forward("ccb", url, txcode, reqPlain, data, txcode);
        if (fw == null) {
            return;
        }
        String action = fw.optString("action", "passthrough");
        log("[FORWARD] ccb gm action=" + action + " tx=" + txcode);
        if (!"replace".equals(action)) {
            return;
        }
        String body = fw.optString("body", "");
        if (body.isEmpty()) {
            return;
        }
        XposedHelpers.setObjectField(dec, "data", body);
        log("[FORWARD] ccb gm replaced tx=" + txcode + " bodyLen=" + body.length());
    }

    private static String txcodeFromJson(String data) {
        try {
            return new JSONObject(data).optString("TXCODE", "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void rewritePlain(XC_MethodHook.MethodHookParam param) {
        if (Boolean.TRUE.equals(LOCAL_MOCK.get())) {
            return;
        }
        String content = param.args[0] != null ? String.valueOf(param.args[0]) : "";
        if (shouldSkip(content)) {
            return;
        }
        Object thiz = param.thisObject;
        String txcode = fieldString(thiz, "mTxcode");
        Object request = null;
        try {
            request = XposedHelpers.getObjectField(thiz, "mRequest");
        } catch (Throwable ignored) {
        }
        String url = callString(request, "getUrl");
        String reqPlain = requestToJson(request);
        FWD.set(new FwdCtx(txcode, url, reqPlain));
        if (isGmWrapped(content)) {
            return;
        }
        JSONObject fw = YouzengForwardClient.forward("ccb", url, txcode, reqPlain, content, txcode);
        applyReplaceArg0(param, fw, txcode, url);
    }

    private static void rewriteTx(XC_MethodHook.MethodHookParam param) {
        if (Boolean.TRUE.equals(LOCAL_MOCK.get())) {
            return;
        }
        Object request = param.args[0];
        Object response = param.args[1];
        String txcode = callString(request, "getTxCode");
        String content = callString(response, "getStrContent");
        if (shouldSkip(content)) {
            return;
        }
        String url = callString(request, "getUrl");
        String reqPlain = requestToJson(request);
        FWD.set(new FwdCtx(txcode, url, reqPlain));
        if (isGmWrapped(content)) {
            return;
        }
        JSONObject fw = YouzengForwardClient.forward("ccb", url, txcode, reqPlain, content, txcode);
        applyReplace(response, "setString", fw, txcode, url);
    }

    private static boolean isGmWrapped(String content) {
        return content != null && content.indexOf("encGmDat") >= 0;
    }

    private static void applyReplaceArg0(XC_MethodHook.MethodHookParam param, JSONObject fw, String txcode, String url) {
        if (fw == null) {
            return;
        }
        String action = fw.optString("action", "passthrough");
        if (CcbConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            log("[FORWARD] ccb action=" + action + " tx=" + txcode + " url=" + url);
        }
        if (!"replace".equals(action)) {
            return;
        }
        String body = fw.optString("body", "");
        if (body.isEmpty()) {
            return;
        }
        param.args[0] = body;
        if (CcbConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            log("[FORWARD] ccb replaced tx=" + txcode + " bodyLen=" + body.length());
        }
    }

    private static String fieldString(Object obj, String name) {
        if (obj == null) {
            return "";
        }
        try {
            Object v = XposedHelpers.getObjectField(obj, name);
            return v != null ? String.valueOf(v) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void applyReplace(Object target, String setter, JSONObject fw, String txcode, String url) {
        if (fw == null) {
            return;
        }
        String action = fw.optString("action", "passthrough");
        if (CcbConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            log("[FORWARD] ccb action=" + action + " tx=" + txcode + " url=" + url);
        }
        if (!"replace".equals(action)) {
            return;
        }
        String body = fw.optString("body", "");
        if (body.isEmpty() || target == null) {
            return;
        }
        try {
            Method m = target.getClass().getMethod(setter, String.class);
            m.invoke(target, body);
            if (CcbConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
                log("[FORWARD] ccb replaced tx=" + txcode + " bodyLen=" + body.length());
            }
        } catch (Throwable t) {
            log("[-] ccb setString: " + t);
        }
    }

    private static boolean shouldSkip(String content) {
        if (content == null || content.isEmpty()) {
            return true;
        }
        return content.indexOf("</") >= 0 || content.indexOf("/>") >= 0;
    }

    private static String callString(Object obj, String method) {
        if (obj == null) {
            return "";
        }
        try {
            Object v = obj.getClass().getMethod(method).invoke(obj);
            return v != null ? String.valueOf(v) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static String requestToJson(Object request) {
        if (request == null) {
            return "";
        }
        JSONObject o = new JSONObject();
        try {
            Object mapObj = request.getClass().getMethod("toMapforAll").invoke(request);
            if (mapObj instanceof Map) {
                o = new JSONObject((Map<String, Object>) mapObj);
            }
        } catch (Throwable ignored) {
        }
        if (o.length() == 0) {
            try {
                Object mapObj = request.getClass().getMethod("toMap").invoke(request);
                if (mapObj instanceof Map) {
                    o = new JSONObject((Map<String, Object>) mapObj);
                }
            } catch (Throwable ignored) {
            }
        }
        putRequestFields(o, request);
        mergeReqGmPlain(o);
        return o.toString();
    }

    private static void captureReqGmPlain(Object arg) {
        if (arg == null) {
            return;
        }
        String s = String.valueOf(arg);
        // 只要看起来像业务参数就保存（扩大捕获范围，包含关键词字段）
        if (s.indexOf("StDt") < 0 && s.indexOf("EdDt") < 0 && s.indexOf("FLAG") < 0
                && s.indexOf("TXCODE") < 0 && s.indexOf("Qry_Cntnt") < 0
                && s.indexOf("Cntrprt_Txn_AccNo_Nm") < 0 && s.indexOf("Enqr_Cntnt") < 0
                && s.indexOf("KEY_WORD") < 0
                // SJ3703 储蓄卡明细特有字段：Beg_Enqr_Dt/CtOf_Enqr_Dt 是日期
                && s.indexOf("Beg_Enqr_Dt") < 0 && s.indexOf("CtOf_Enqr_Dt") < 0
                && s.indexOf("Sel_Txn_Tp_Nm") < 0 && s.indexOf("CardNo") < 0) {
            return;
        }
        REQ_GM_PLAIN.set(s);
        LAST_GM_PLAIN = s;
        // 按 txcode 存储，兜底异步线程场景
        String txcode = parseTxcodeFromPlain(s);
        if (txcode != null && !txcode.isEmpty()) {
            GM_PLAIN_MAP.put(txcode, s);
        }
    }

    private static String parseTxcodeFromPlain(String s) {
        // 尝试 JSON 格式
        try {
            return new JSONObject(s).optString("TXCODE", null);
        } catch (Throwable ignored) {}
        // 尝试 URL 编码格式 (k1=v1&k2=v2)
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq).trim();
                if ("TXCODE".equals(k)) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    private static void mergeReqGmPlain(JSONObject o) {
        // 优先用同线程 ThreadLocal，其次按 txcode 查 Map，最后用全局最后一次
        String s = REQ_GM_PLAIN.get();
        if (s == null || s.isEmpty()) {
            String tx = o.optString("TXCODE", o.optString("txCode", ""));
            if (!tx.isEmpty()) {
                s = GM_PLAIN_MAP.get(tx.toUpperCase());
            }
        }
        if (s == null || s.isEmpty()) {
            s = LAST_GM_PLAIN;
        }
        if (s == null || s.isEmpty()) {
            return;
        }
        // 尝试 JSON 格式
        boolean merged = false;
        try {
            JSONObject extra = new JSONObject(s);
            Iterator<String> it = extra.keys();
            while (it.hasNext()) {
                String k = it.next();
                putReqVal(o, k, extra.opt(k));
            }
            merged = true;
        } catch (Throwable ignored) {
        }
        if (merged) return;
        // 尝试 URL 编码格式 (k1=v1&k2=v2)
        try {
            for (String pair : s.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq).trim();
                String v = java.net.URLDecoder.decode(pair.substring(eq + 1).trim(), "UTF-8");
                if (!k.isEmpty() && !v.isEmpty()) {
                    putReqVal(o, k, v);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putRequestFields(JSONObject o, Object request) {
        Class<?> c = request.getClass();
        while (c != null && !c.getName().startsWith("java.")) {
            Field[] fields = c.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                String n = f.getName();
                if (n.startsWith("this$") || n.startsWith("$")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(request);
                    putReqVal(o, n, v);
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        String[] keys = new String[]{
                "StDt", "EdDt", "St_Dt", "Ed_Dt", "Enqr_StDt", "Enqr_CODt",
                "FLAG", "TranFlag", "IcmEpd_TpCd", "MIN_AMT", "MAX_AMT",
                "NBPAGE_START", "NBPAGE_MAXROW", "NBPAGE_LOCSTR",
                "Cst_AccNo", "CardNo", "DbCrd_CardNo", "Qry_Cntnt", "Enqr_Cntnt",
                "KEY_WORD", "Cntrprt_Txn_AccNo_Nm", "Dep_TxnAmt", "Amt_Lwrlmt", "Amt_Uprlmt"
        };
        for (int i = 0; i < keys.length; i++) {
            try {
                putReqVal(o, keys[i], XposedHelpers.getObjectField(request, keys[i]));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void putReqVal(JSONObject o, String n, Object v) {
        if (v == null) {
            return;
        }
        if (!(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)) {
            return;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return;
        }
        if (!o.has(n) || o.optString(n).isEmpty()) {
            try {
                o.put(n, v);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void log(String msg) {
        if (CcbConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            Log.i(TAG, msg);
        }
        XposedBridge.log("[youzeng] " + msg);
    }
}
