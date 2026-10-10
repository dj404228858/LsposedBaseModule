package com.youzeng.psbc;

import android.app.Application;
import android.content.Context;
import android.util.Base64;

import com.youzeng.hook.YouzengConfig;
import com.youzeng.hook.YouzengForwardClient;
import com.youzeng.hook.YouzengModulePrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 将 c.js 中 Application.attach 之后的 OkHttp 注入、拦截器逻辑移植为 Xposed Java 实现。
 */
public final class YouzengPsbcNetworkHook {

    private static volatile boolean networkHooksInstalled = false;
    private static volatile boolean appGuardHooksInstalled = false;

    private static volatile boolean deviceConfigLoaded = false;
    private static volatile long deviceConfigLastFetchMs = 0;
    /** 与 {@link #deviceConfigLoaded} 配对，did 变更时必须重新拉设备信息 */
    private static volatile String deviceConfigCachedDid = "";

    private static JSONObject mockXhxData = null;
    private static volatile int lastMockValidCount = 0;
    private static JSONObject mockTxTrsfData = null;
    private static volatile int lastMockTxTrsfValidCount = 0;
    private static JSONObject mockQryTopDtlListData = null;
    private static volatile int lastMockQryTopDtlListValidCount = 0;
    private static JSONObject mockQryTransDetailData = null;
    private static volatile int lastMockQryTransDetailValidCount = 0;

    private static final ConcurrentHashMap<String, Object> hookReqLogOnce = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> hookRspProcessOnce = new ConcurrentHashMap<>();

    private static Object interceptorSingleton = null;

    private static volatile boolean classLoaderGuardHookInstalled = false;
    private static volatile boolean decMsgDialogHookInstalled = false;
    private static volatile boolean deviceEnvRunnableHookInstalled = false;

    private YouzengPsbcNetworkHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!PsbcConfig.isTargetPackage(lpparam.packageName)) {
            return;
        }
        ClassLoader cl = lpparam.classLoader;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    installAppGuardHooks(cl);
                    if (networkHooksInstalled) {
                        return;
                    }
                    networkHooksInstalled = true;
                    installOkHttpAndInterceptor(cl);
                } catch (Throwable t) {
                    XposedBridge.log("[youzeng] install failed: " + t);
                }
            }
        });
    }

    private static void installAppGuardHooks(ClassLoader cl) {
        if (appGuardHooksInstalled) {
            return;
        }
        appGuardHooksInstalled = true;
        try {
            XposedHelpers.findAndHookMethod("java.lang.System", cl, "exit", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    YouzengPsbcHelpers.log("[*] System.exit intercepted code=" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] System.exit hook: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.os.Process", cl, "killProcess", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    YouzengPsbcHelpers.log("[*] Process.killProcess intercepted pid=" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] killProcess hook: " + t);
        }
        installClassLoaderGuardHooks();
    }

    /**
     * 对齐 c.js：hook ClassLoader.loadClass(String)，在加载到 DECMsgDialog 时再挂钩 onCreate / Runnable.run。
     */
    private static void installClassLoaderGuardHooks() {
        if (!PsbcConfig.ENABLE_CLASSLOADER_GUARD_HOOKS) {
            return;
        }
        if (classLoaderGuardHookInstalled) {
            return;
        }
        classLoaderGuardHookInstalled = true;
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.hasThrowable()) {
                        return;
                    }
                    String className = (String) param.args[0];
                    ClassLoader loader = (ClassLoader) param.thisObject;
                    if (PsbcConfig.GUARD_DEC_MSG_DIALOG_CLASS.equals(className)) {
                        installDecDialogHooks(loader);
                        installDeviceEnvRunnableHooks(loader);
                    } else if (PsbcConfig.GUARD_DEVICE_ENV_RUNNABLE_CLASS.equals(className)) {
                        installDeviceEnvRunnableHooks(loader);
                    }
                }
            });
            YouzengPsbcHelpers.log("[+] ClassLoader.loadClass(String) guard (DECMsgDialog / DeviceEnv runnable)");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] ClassLoader.loadClass guard: " + t);
        }
    }

    private static synchronized void installDecDialogHooks(ClassLoader loader) {
        if (decMsgDialogHookInstalled) {
            return;
        }
        try {
            Class<?> decCls = XposedHelpers.findClass(PsbcConfig.GUARD_DEC_MSG_DIALOG_CLASS, loader);
            XposedHelpers.findAndHookMethod(decCls, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    YouzengPsbcHelpers.log("DECMsgDialog.onCreate called, finishing");
                    XposedHelpers.callMethod(param.thisObject, "finish");
                }
            });
            decMsgDialogHookInstalled = true;
            YouzengPsbcHelpers.log("[+] Hooked " + PsbcConfig.GUARD_DEC_MSG_DIALOG_CLASS + ".onCreate");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] DECMsgDialog hook: " + t);
        }
    }

    private static synchronized void installDeviceEnvRunnableHooks(ClassLoader loader) {
        if (deviceEnvRunnableHookInstalled) {
            return;
        }
        try {
            Class<?> runCls = XposedHelpers.findClass(PsbcConfig.GUARD_DEVICE_ENV_RUNNABLE_CLASS, loader);
            XposedHelpers.findAndHookMethod(runCls, "run", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    YouzengPsbcHelpers.log("DeviceEnvironmentCheck$1$1.run intercepted (no-op)");
                    return null;
                }
            });
            deviceEnvRunnableHookInstalled = true;
            YouzengPsbcHelpers.log("[+] Hooked " + PsbcConfig.GUARD_DEVICE_ENV_RUNNABLE_CLASS + ".run");
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] DeviceEnvironmentCheck$1$1 hook: " + t);
        }
    }

    private static boolean ensureDeviceConfigLoadedSync(ClassLoader cl, boolean forceReload) {
        long now = System.currentTimeMillis();
        String did = YouzengModulePrefs.getMockDeviceDid();
        boolean sameDidAsCache = did.equals(deviceConfigCachedDid);

        if (!forceReload && deviceConfigLoaded && sameDidAsCache) {
            return true;
        }
        // did 切换后必须立刻重新拉配置；仅在同 did 下做 15s 节流
        if (!forceReload && deviceConfigLoaded && sameDidAsCache
                && deviceConfigLastFetchMs > 0
                && (now - deviceConfigLastFetchMs) < 15000L) {
            return true;
        }
        deviceConfigLastFetchMs = now;
        if (did.isEmpty()) {
            deviceConfigLoaded = false;
            deviceConfigCachedDid = "";
            YouzengPsbcHelpers.log("[-] 设备配置加载失败: 设备 did 为空");
            return false;
        }
        try {
            JSONObject q = new JSONObject();
            q.put("did", did);
            String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockDeviceInfoHttpApiUrl(), q);
            JSONObject obj = YouzengPsbcHelpers.httpGetJson(url, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                    PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, null);
            if (obj.has("_error")) {
                deviceConfigLoaded = false;
                YouzengPsbcHelpers.log("[-] 设备配置加载失败 did=" + did + " err=" + obj.optString("_error"));
                return false;
            }
            if (obj.optBoolean("ok", true) && obj.has("data")) {
                deviceConfigLoaded = true;
                deviceConfigCachedDid = did;
                YouzengPsbcHelpers.log("[*] 设备配置加载成功 did=" + did);
                return true;
            }
        } catch (Throwable t) {
            deviceConfigLoaded = false;
            YouzengPsbcHelpers.log("[-] 设备配置加载异常: " + t);
        }
        deviceConfigLoaded = false;
        return false;
    }

    private static String resolveSm4KeyFromBaseApi(ClassLoader cl, Object baseApiObj) {
        if (baseApiObj == null) return null;
        String[] names = {"getEncryptKey4SM4", "getSm4Key", "getMSm4Key"};
        for (String n : names) {
            try {
                Method m = baseApiObj.getClass().getMethod(n);
                Object k = m.invoke(baseApiObj);
                if (k != null && String.valueOf(k).length() > 0) {
                    return String.valueOf(k);
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String resolveSm4KeyFromRequest(ClassLoader cl, Object req) throws Throwable {
        if (req == null) return null;
        Class<?> baseApiClass = XposedHelpers.findClass("com.yitong.mbank.psbc.net.http.api.BaseApi", cl);
        Method tagMethod = req.getClass().getMethod("tag", Class.class);
        Object o1 = tagMethod.invoke(req, baseApiClass);
        if (o1 != null) {
            String k1 = resolveSm4KeyFromBaseApi(cl, o1);
            if (k1 != null && !k1.isEmpty()) return k1;
        }
        try {
            ClassLoader ctxLoader = Thread.currentThread().getContextClassLoader();
            if (ctxLoader != null) {
                Class<?> cls = ctxLoader.loadClass("com.yitong.mbank.psbc.net.http.api.BaseApi");
                Object o2 = tagMethod.invoke(req, cls);
                if (o2 != null) {
                    String k2 = resolveSm4KeyFromBaseApi(cl, o2);
                    if (k2 != null && !k2.isEmpty()) return k2;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readOkHttpRequestBodyText(ClassLoader cl, Object req) {
        try {
            Method bodyMethod = req.getClass().getMethod("body");
            Object rb = bodyMethod.invoke(req);
            if (rb == null) return "";
            try {
                Method isOneShot = rb.getClass().getMethod("isOneShot");
                if (Boolean.TRUE.equals(isOneShot.invoke(rb))) {
                    return "";
                }
            } catch (Throwable ignored) {
            }
            Class<?> bufferCls = XposedHelpers.findClass("okio.Buffer", cl);
            Object buf = bufferCls.getConstructor().newInstance();
            Method writeTo = rb.getClass().getMethod("writeTo", XposedHelpers.findClass("okio.BufferedSink", cl));
            writeTo.invoke(rb, buf);
            Method readByteArray = bufferCls.getMethod("readByteArray");
            byte[] bytes = (byte[]) readByteArray.invoke(buf);
            String charsetName = "UTF-8";
            try {
                Method contentType = rb.getClass().getMethod("contentType");
                Object ct = contentType.invoke(rb);
                if (ct != null) {
                    Method charsetM = null;
                    try {
                        charsetM = ct.getClass().getMethod("charset");
                    } catch (NoSuchMethodException e) {
                        charsetM = ct.getClass().getMethod("charset", Charset.class);
                    }
                    Object cs;
                    if (charsetM.getParameterTypes().length == 0) {
                        cs = charsetM.invoke(ct);
                    } else {
                        cs = charsetM.invoke(ct, Charset.forName("UTF-8"));
                    }
                    if (cs != null) {
                        charsetName = cs.toString();
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                return new String(bytes, charsetName);
            } catch (Throwable e) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject resolvePlainReqFromReq(ClassLoader cl, Object req, String sm4Key) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", false);
            out.put("plain", "");
            out.put("note", "");
        } catch (org.json.JSONException e) {
            return out;
        }
        try {
            if (sm4Key == null || sm4Key.isEmpty()) {
                out.put("note", "(sm4Key 为空)");
                return out;
            }
            String reqBodyText = readOkHttpRequestBodyText(cl, req);
            if (reqBodyText == null || reqBodyText.isEmpty()) {
                out.put("note", "(无可读请求体)");
                return out;
            }
            JSONObject wrap = new JSONObject(reqBodyText);
            if (!wrap.has("data")) {
                out.put("note", "(请求体不是加密包装)");
                return out;
            }
            String plain = YouzengPsbcHelpers.decryptDataBySm4Key(cl, sm4Key, wrap.optString("data"));
            if (plain == null || plain.isEmpty()) {
                out.put("note", "(SM4 解密失败)");
                return out;
            }
            out.put("ok", true);
            out.put("plain", plain);
            return out;
        } catch (Throwable e) {
            try {
                out.put("note", "(请求体解密异常) " + e);
            } catch (org.json.JSONException ignored) {
            }
            return out;
        }
    }

    private static JSONObject resolveXhxQueryFromReq(ClassLoader cl, Object req, String sm4Key) {
        try {
            JSONObject ctx = resolvePlainReqFromReq(cl, req, sm4Key);
            if (!ctx.optBoolean("ok")) return null;
            return YouzengPsbcHelpers.parseXhxQueryFromPlainRequest(ctx.optString("plain"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject resolveTxTrsfQueryFromReq(ClassLoader cl, Object req, String sm4Key) {
        try {
            JSONObject ctx = resolvePlainReqFromReq(cl, req, sm4Key);
            if (!ctx.optBoolean("ok")) return null;
            return YouzengPsbcHelpers.parseTxTrsfQueryFromPlainRequest(ctx.optString("plain"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject resolveQryTopDtlListQueryFromReq(ClassLoader cl, Object req, String sm4Key) {
        try {
            JSONObject ctx = resolvePlainReqFromReq(cl, req, sm4Key);
            if (!ctx.optBoolean("ok")) return null;
            return YouzengPsbcHelpers.parseQryTopDtlListFromPlainRequest(ctx.optString("plain"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject resolveQryTransDetailQueryFromReq(ClassLoader cl, Object req, String sm4Key) {
        try {
            JSONObject ctx = resolvePlainReqFromReq(cl, req, sm4Key);
            if (!ctx.optBoolean("ok")) return null;
            return YouzengPsbcHelpers.parseQryTransDetailFromPlainRequest(ctx.optString("plain"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject resolveTxDetailQueryFromReq(ClassLoader cl, Object req, String sm4Key) {
        try {
            JSONObject ctx = resolvePlainReqFromReq(cl, req, sm4Key);
            if (!ctx.optBoolean("ok")) return null;
            return YouzengPsbcHelpers.parseTxDetailQueryFromPlainRequest(ctx.optString("plain"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject fetchMockXhx(ClassLoader cl, String reqMsgId, JSONObject xhxQuery, String hostRspPlain)
            throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        if (xhxQuery != null) {
            if (xhxQuery.has("beginDate")) body.put("beginDate", xhxQuery.optString("beginDate"));
            if (xhxQuery.has("dlineDate")) body.put("dlineDate", xhxQuery.optString("dlineDate"));
            if (xhxQuery.has("ebankQryTypeFlagCd")) body.put("ebankQryTypeFlagCd", xhxQuery.optString("ebankQryTypeFlagCd"));
            if (xhxQuery.has("txTpCdList")) {
                JSONArray arr = xhxQuery.optJSONArray("txTpCdList");
                if (arr != null && arr.length() > 0) {
                    body.put("txTpCdList", arr);
                }
            }
        }
        String url = PsbcConfig.mockXhxHttpApiUrl();
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.optBoolean("collect_transactions_enabled", false)
                || obj.optInt("collect_transactions_enabled", 0) == 1) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", "collect_mode");
            return r;
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data.has("fieldItemInfo")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_xhx_payload");
        return r;
    }

    private static JSONObject fetchMockTxTrsf(ClassLoader cl, String reqMsgId, JSONObject trsfQuery,
            String reqPlain, String hostRspPlain) throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        if (reqPlain != null && !reqPlain.isEmpty()) {
            body.put("hostReqPlain", reqPlain);
        }
        JSONObject urlQuery = new JSONObject();
        if (trsfQuery != null) {
            if (trsfQuery.has("beginDate")) {
                String v = trsfQuery.optString("beginDate");
                body.put("beginDate", v);
                urlQuery.put("beginDate", v);
            }
            if (trsfQuery.has("dlineDate")) {
                String v = trsfQuery.optString("dlineDate");
                body.put("dlineDate", v);
                urlQuery.put("dlineDate", v);
            }
            if (trsfQuery.has("txOpsAccno")) {
                String v = trsfQuery.optString("txOpsAccno");
                body.put("txOpsAccno", v);
                urlQuery.put("txOpsAccno", v);
            }
            if (trsfQuery.has("txOpsName")) {
                String v = trsfQuery.optString("txOpsName");
                body.put("txOpsName", v);
                urlQuery.put("txOpsName", v);
            }
            if (trsfQuery.has("ebankQryTypeFlagCd")) {
                body.put("ebankQryTypeFlagCd", trsfQuery.optString("ebankQryTypeFlagCd"));
            }
            if (trsfQuery.has("bgnIndexNo")) {
                String v = trsfQuery.optString("bgnIndexNo");
                body.put("bgnIndexNo", v);
                urlQuery.put("bgnIndexNo", v);
            }
            if (trsfQuery.has("curQryReqNum")) {
                String v = trsfQuery.optString("curQryReqNum");
                body.put("curQryReqNum", v);
                urlQuery.put("curQryReqNum", v);
            }
            if (trsfQuery.has("txTpCdList")) {
                JSONArray arr = trsfQuery.optJSONArray("txTpCdList");
                if (arr != null && arr.length() > 0) {
                    body.put("txTpCdList", arr);
                }
            }
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockTxTrsfHttpApiUrl(), urlQuery);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] TX_TRSF POST: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.optBoolean("collect_transactions_enabled", false)
                || obj.optInt("collect_transactions_enabled", 0) == 1) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", "collect_mode");
            return r;
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data.has("fieldItemInfo")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_tx_trsf_payload");
        return r;
    }

    private static JSONObject fetchMockQryTopDtlList(ClassLoader cl, String reqMsgId, JSONObject queryObj,
            String reqPlain, String hostRspPlain) throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        if (reqPlain != null && !reqPlain.isEmpty()) {
            body.put("hostReqPlain", reqPlain);
        }
        JSONObject urlQuery = new JSONObject();
        if (queryObj != null) {
            JSONArray names = queryObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    Object v = queryObj.opt(k);
                    if (v == null) continue;
                    if (v instanceof JSONArray) {
                        body.put(k, v);
                        continue;
                    }
                    String sv = String.valueOf(v).trim();
                    if (sv.isEmpty()) continue;
                    body.put(k, sv);
                    if (!"ebankQryTypeFlagCd".equals(k) && !(v instanceof JSONObject)) {
                        urlQuery.put(k, sv);
                    }
                }
            }
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockQryTopDtlListHttpApiUrl(), urlQuery);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] QRY_TOP_DTL_LIST POST: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.optBoolean("collect_transactions_enabled", false)
                || obj.optInt("collect_transactions_enabled", 0) == 1) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", "collect_mode");
            return r;
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && (data.has("detailList") || data.has("fieldItemInfo"))) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_qry_top_dtl_list_payload");
        return r;
    }

    private static JSONObject fetchMockQryTransDetail(ClassLoader cl, String reqMsgId, JSONObject queryObj,
            String reqPlain, String hostRspPlain) throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        if (reqPlain != null && !reqPlain.isEmpty()) {
            body.put("hostReqPlain", reqPlain);
        }
        JSONObject urlQuery = new JSONObject();
        if (queryObj != null) {
            JSONArray names = queryObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    Object v = queryObj.opt(k);
                    if (v == null) continue;
                    if (v instanceof JSONArray) {
                        body.put(k, v);
                        continue;
                    }
                    String sv = String.valueOf(v).trim();
                    if (sv.isEmpty()) continue;
                    body.put(k, sv);
                    if (!(v instanceof JSONObject)) {
                        urlQuery.put(k, sv);
                    }
                }
            }
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockQryTransDetailHttpApiUrl(), urlQuery);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] QRY_TRANS_DETAIL POST: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data.has("fieldItemInfo")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_qry_trans_detail_payload");
        return r;
    }

    private static JSONObject fetchMockTxDetail(ClassLoader cl, String reqMsgId, JSONObject detailQuery, String hostRspPlain) throws org.json.JSONException {
        JSONObject query = new JSONObject();
        if (detailQuery != null) {
            JSONArray names = detailQuery.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    query.put(k, detailQuery.optString(k));
                }
            }
        }
        if (hostRspPlain != null && !hostRspPlain.isEmpty()) {
            String b64 = Base64.encodeToString(hostRspPlain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            query.put("hostPayloadB64", b64);
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockTxDetailHttpApiUrl(), query);
        JSONObject obj = YouzengPsbcHelpers.httpGetJson(url, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_tx_detail_payload");
        return r;
    }

    private static JSONObject fetchMockHistoryApply(ClassLoader cl, String reqMsgId, JSONObject applyQuery) throws org.json.JSONException {
        JSONObject query = new JSONObject();
        if (applyQuery != null) {
            JSONArray names = applyQuery.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    query.put(k, applyQuery.optString(k));
                }
            }
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockHistoryTxApplyHttpApiUrl(), query);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] HISTORY_TX_DETAIL_APPLY 请求: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpGetJson(url, PsbcConfig.MOCK_HISTORY_TX_DETAIL_APPLY_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_HISTORY_TX_DETAIL_APPLY_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        JSONObject d = obj.optJSONObject("data");
        if (obj.has("code") && d != null && d.optString("genFileName").length() > 0) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_history_tx_apply_payload");
        return r;
    }

    private static JSONObject fetchMockQryAccDtl(ClassLoader cl, String reqMsgId, String hostRspPlain)
            throws org.json.JSONException {
        String url = PsbcConfig.mockQryAccDtlHttpApiUrl();
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        YouzengPsbcHelpers.log("[MOCK] QRY_ACC_DTL POST: " + url + " plainLen=" + (hostRspPlain == null ? 0 : hostRspPlain.length()));
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code") && "000000".equals(obj.optString("code")) && obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_qry_acc_dtl_payload code=" + obj.optString("code"));
        return r;
    }

    private static JSONObject fetchMockCustLvl(ClassLoader cl, String reqMsgId, String hostRspPlain)
            throws org.json.JSONException {
        String url = PsbcConfig.mockCustLvlHttpApiUrl();
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        YouzengPsbcHelpers.log("[MOCK] CUST_LVL POST: " + url
                + " plainLen=" + (hostRspPlain == null ? 0 : hostRspPlain.length()));
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code") && "000000".equals(obj.optString("code")) && obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_cust_lvl_payload code=" + obj.optString("code"));
        return r;
    }

    private static JSONObject fetchMockInitQyzq(ClassLoader cl, String reqMsgId, String hostRspPlain)
            throws org.json.JSONException {
        String url = PsbcConfig.mockInitQyzqHttpApiUrl();
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        YouzengPsbcHelpers.log("[MOCK] INIT_QYZQ POST: " + url
                + " plainLen=" + (hostRspPlain == null ? 0 : hostRspPlain.length()));
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code") && "000000".equals(obj.optString("code")) && obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_init_qyzq_payload code=" + obj.optString("code"));
        return r;
    }

    private static JSONObject fetchMockQryTransAccBal(ClassLoader cl, String reqMsgId, String hostRspPlain)
            throws org.json.JSONException {
        String url = PsbcConfig.mockQryTransAccBalHttpApiUrl();
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
        YouzengPsbcHelpers.log("[MOCK] QRY_TRANS_ACC_BAL POST: " + url
                + " plainLen=" + (hostRspPlain == null ? 0 : hostRspPlain.length()));
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code") && "000000".equals(obj.optString("code")) && obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_qry_trans_acc_bal_payload code=" + obj.optString("code"));
        return r;
    }

    private static JSONObject fetchMockProfileBalance(ClassLoader cl, String reqMsgId) throws org.json.JSONException {
        String url = PsbcConfig.mockProfileBalanceHttpApiUrl();
        YouzengPsbcHelpers.log("[MOCK] PROFILE_BALANCE 请求: " + url);
        JSONObject obj = YouzengPsbcHelpers.httpGetJson(url,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("data") && obj.getJSONObject("data").has("balance")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_profile_balance_payload");
        return r;
    }

    private static JSONObject fetchMockQueryApplySchedule(ClassLoader cl, String reqMsgId, JSONObject scheduleQuery, String hostRspPlain)
            throws org.json.JSONException {
        JSONObject query = new JSONObject();
        if (scheduleQuery != null) {
            JSONArray names = scheduleQuery.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    query.put(k, scheduleQuery.optString(k));
                }
            }
        }
        if (hostRspPlain != null && !hostRspPlain.isEmpty()) {
            String b64 = Base64.encodeToString(hostRspPlain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            query.put("hostPayloadB64", b64);
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockQueryApplyScheduleHttpApiUrl(), query);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] QUERY_APPLY_SCHEDULE 请求: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpGetJson(url, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        // 兼容两种形态：直接返回 {code,...} 或 {data:{...}} 包装
        if (obj.has("code") || obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_query_apply_schedule_payload");
        return r;
    }

    private static JSONObject fetchMockIncmEpnAnalySum(ClassLoader cl, String reqMsgId, JSONObject queryObj, String hostRspPlain)
            throws org.json.JSONException {
        JSONObject query = new JSONObject();
        if (queryObj != null) {
            JSONArray names = queryObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    query.put(k, queryObj.optString(k));
                }
            }
        }
        if (hostRspPlain != null && !hostRspPlain.isEmpty()) {
            String b64 = Base64.encodeToString(hostRspPlain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            query.put("hostPayloadB64", b64);
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockIncmEpnAnalySumHttpApiUrl(), query);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] INCM_EPN_ANALY_SUM 请求: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpGetJson(url, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code") || obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_incm_epn_analy_sum_payload");
        return r;
    }

    private static JSONObject fetchMockTxIncmEpnAnalySum(ClassLoader cl, String reqMsgId, JSONObject queryObj,
            String reqPlain, String hostRspPlain) throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        if (hostRspPlain != null && !hostRspPlain.isEmpty()) {
            body.put("hostRspPlain", hostRspPlain);
        }
        if (reqPlain != null && !reqPlain.isEmpty()) {
            body.put("hostReqPlain", reqPlain);
        }
        JSONObject urlQuery = new JSONObject();
        if (queryObj != null) {
            JSONArray names = queryObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    Object v = queryObj.opt(k);
                    if (v == null) continue;
                    String sv = String.valueOf(v).trim();
                    if (sv.isEmpty()) continue;
                    body.put(k, sv);
                    urlQuery.put(k, sv);
                }
            }
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockTxIncmEpnAnalySumHttpApiUrl(), urlQuery);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] TX_INCM_EPN_ANALY_SUM POST: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code") || obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_tx_incm_epn_analy_sum_payload");
        return r;
    }

    private static JSONObject fetchMockImexSumData(ClassLoader cl, String reqMsgId, JSONObject queryObj,
            String reqPlain, String hostRspPlain) throws org.json.JSONException {
        JSONObject body = new JSONObject();
        body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        if (hostRspPlain != null && !hostRspPlain.isEmpty()) {
            body.put("hostRspPlain", hostRspPlain);
        }
        if (reqPlain != null && !reqPlain.isEmpty()) {
            body.put("hostReqPlain", reqPlain);
        }
        JSONObject urlQuery = new JSONObject();
        if (queryObj != null) {
            JSONArray names = queryObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    Object v = queryObj.opt(k);
                    if (v == null) continue;
                    String sv = String.valueOf(v).trim();
                    if (sv.isEmpty()) continue;
                    body.put(k, sv);
                    urlQuery.put(k, sv);
                }
            }
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockImexSumDataHttpApiUrl(), urlQuery);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] IMEX_SUM_DATA POST: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(url, body.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data.has("fieldItemInfo")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        if (obj.has("code") || obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_imex_sum_data_payload");
        return r;
    }

    private static JSONObject fetchMockMyIncmEpn(ClassLoader cl, String reqMsgId, JSONObject queryObj, String hostRspPlain)
            throws org.json.JSONException {
        JSONObject query = new JSONObject();
        if (queryObj != null) {
            JSONArray names = queryObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String k = names.optString(i);
                    query.put(k, queryObj.optString(k));
                }
            }
        }
        if (hostRspPlain != null && !hostRspPlain.isEmpty()) {
            String b64 = Base64.encodeToString(hostRspPlain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            query.put("hostPayloadB64", b64);
        }
        String url = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockMyIncmEpnHttpApiUrl(), query);
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            YouzengPsbcHelpers.log("[MOCK] MY_INCM_EPN 请求: " + url);
        }
        JSONObject obj = YouzengPsbcHelpers.httpGetJson(url, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS,
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.has("code") || obj.has("data")) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj);
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_my_incm_epn_payload");
        return r;
    }

    private static JSONObject uploadCollectedTransactions(ClassLoader cl, String did, String reqMsgId,
                                                          String decRspPlain, JSONObject xhxQuery) throws org.json.JSONException {
        if (decRspPlain == null || decRspPlain.isEmpty()) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", "empty_plain_payload");
            return r;
        }
        new JSONObject(decRspPlain);
        JSONObject reqBodyObj = new JSONObject();
        reqBodyObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        reqBodyObj.put("query", xhxQuery);
        reqBodyObj.put("payload", new JSONObject(decRspPlain));
        String finalUrl = YouzengPsbcHelpers.appendQueryString(PsbcConfig.mockCollectTxHttpApiUrl(),
                new JSONObject().put("did", did == null ? "" : did.trim()));
        JSONObject obj = YouzengPsbcHelpers.httpPostJson(finalUrl, reqBodyObj.toString(),
                PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, PsbcConfig.MOCK_XHX_HTTP_TIMEOUT_MS, reqMsgId);
        if (obj.has("_error")) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("error", obj.optString("_error"));
            return r;
        }
        if (obj.optBoolean("ok", true)) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            r.put("data", obj.opt("data"));
            return r;
        }
        JSONObject r = new JSONObject();
        r.put("ok", false);
        r.put("error", "invalid_collect_response");
        return r;
    }

    private static String buildMockXhxPlain(String reqMsgId) throws org.json.JSONException {
        JSONObject plainObj = mockXhxData;
        if (plainObj == null || !plainObj.has("data") || !plainObj.getJSONObject("data").has("fieldItemInfo")) {
            lastMockValidCount = 0;
            JSONObject empty = new JSONObject();
            empty.put("code", "000000");
            JSONObject data = new JSONObject();
            data.put("curQryReturnNum", "0");
            data.put("fieldItemInfo", new JSONArray());
            data.put("haveNextDataFlag", "0");
            data.put("qryResultTnum", "0");
            data.put("qryTime", "");
            empty.put("data", data);
            empty.put("msg", "交易成功");
            empty.put("showType", "0");
            empty.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
            return empty.toString();
        }
        JSONObject d = plainObj.getJSONObject("data");
        int n = 0;
        try {
            n = Integer.parseInt(d.optString("curQryReturnNum", d.optString("qryResultTnum", "0")));
        } catch (NumberFormatException ignored) {
        }
        lastMockValidCount = n;
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockTxTrsfPlain(String reqMsgId) throws org.json.JSONException {
        JSONObject plainObj = mockTxTrsfData;
        if (plainObj == null || !plainObj.has("data") || !plainObj.getJSONObject("data").has("fieldItemInfo")) {
            lastMockTxTrsfValidCount = 0;
            JSONObject empty = new JSONObject();
            empty.put("code", "000000");
            JSONObject data = new JSONObject();
            data.put("curQryReturnNum", "0");
            data.put("fieldItemInfo", new JSONArray());
            data.put("haveNextDataFlag", "0");
            empty.put("data", data);
            empty.put("msg", "交易成功");
            empty.put("showType", "0");
            empty.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
            return empty.toString();
        }
        JSONObject d = plainObj.getJSONObject("data");
        int n = 0;
        try {
            n = Integer.parseInt(d.optString("curQryReturnNum", "0"));
        } catch (NumberFormatException ignored) {
        }
        lastMockTxTrsfValidCount = n;
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockQryTopDtlListPlain(String reqMsgId) throws org.json.JSONException {
        JSONObject plainObj = mockQryTopDtlListData;
        JSONObject data = plainObj != null ? plainObj.optJSONObject("data") : null;
        if (data == null || !data.has("detailList")) {
            lastMockQryTopDtlListValidCount = 0;
            JSONObject empty = new JSONObject();
            empty.put("code", "000000");
            JSONObject emptyData = new JSONObject();
            emptyData.put("detailList", new JSONArray());
            emptyData.put("incmEpnMonth", "");
            emptyData.put("qryTime", "");
            emptyData.put("tnum", "0");
            emptyData.put("totalAmt", "0.00");
            empty.put("data", emptyData);
            empty.put("msg", "交易成功");
            empty.put("showType", "0");
            empty.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
            return empty.toString();
        }
        int n = data.optJSONArray("detailList") != null ? data.optJSONArray("detailList").length() : 0;
        if (n <= 0) {
            try {
                n = Integer.parseInt(data.optString("tnum", "0"));
            } catch (NumberFormatException ignored) {
                n = 0;
            }
        }
        lastMockQryTopDtlListValidCount = n;
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockQryTransDetailPlain(String reqMsgId) throws org.json.JSONException {
        JSONObject plainObj = mockQryTransDetailData;
        JSONObject data = plainObj != null ? plainObj.optJSONObject("data") : null;
        if (data == null || !data.has("fieldItemInfo")) {
            lastMockQryTransDetailValidCount = 0;
            JSONObject empty = new JSONObject();
            empty.put("code", "000000");
            JSONObject emptyData = new JSONObject();
            emptyData.put("curQryReturnNum", "0");
            emptyData.put("expnTotAmt", "0.00");
            emptyData.put("fieldItemInfo", new JSONArray());
            emptyData.put("haveNextDataFlag", "0");
            emptyData.put("incomeTotalAmt", "0.00");
            emptyData.put("qryResultTnum", "0");
            emptyData.put("qryTimeTotalExpnNum", "0");
            emptyData.put("qryTimeTotalIncomeNum", "0");
            empty.put("data", emptyData);
            empty.put("msg", "交易成功");
            empty.put("showType", "0");
            empty.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
            return empty.toString();
        }
        int n = 0;
        JSONArray fi = data.optJSONArray("fieldItemInfo");
        if (fi != null) {
            n = fi.length();
        }
        if (n <= 0) {
            try {
                n = Integer.parseInt(data.optString("curQryReturnNum", data.optString("qryResultTnum", "0")));
            } catch (NumberFormatException ignored) {
                n = 0;
            }
        }
        lastMockQryTransDetailValidCount = n;
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockTxDetailPlain(String reqMsgId, JSONObject detailObj) throws org.json.JSONException {
        JSONObject plainObj = detailObj != null ? detailObj : new JSONObject();
        if (plainObj.length() == 0) {
            plainObj = new JSONObject();
            plainObj.put("code", "000016");
            plainObj.put("msg", "暂时无法处理您的请求，请返回或退出后重试");
            plainObj.put("showType", "2");
        }
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockHistoryApplyPlain(String reqMsgId, JSONObject hostObj) throws org.json.JSONException {
        JSONObject plainObj = hostObj != null ? hostObj : new JSONObject();
        if (plainObj.length() == 0) {
            plainObj = new JSONObject();
            plainObj.put("code", "000016");
            plainObj.put("msg", "暂时无法处理您的请求，请返回或退出后重试");
            plainObj.put("showType", "2");
        }
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockQueryApplySchedulePlain(String reqMsgId, JSONObject hostObj) throws org.json.JSONException {
        JSONObject plainObj = hostObj != null ? hostObj : new JSONObject();
        if (plainObj.length() == 0) {
            plainObj = new JSONObject();
            plainObj.put("code", "000016");
            plainObj.put("msg", "暂时无法处理您的请求，请返回或退出后重试");
            plainObj.put("showType", "2");
        }
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockIncmEpnAnalySumPlain(String reqMsgId, JSONObject hostObj) throws org.json.JSONException {
        JSONObject plainObj = hostObj != null ? hostObj : new JSONObject();
        if (plainObj.length() == 0) {
            plainObj = new JSONObject();
            plainObj.put("code", "000016");
            plainObj.put("msg", "暂时无法处理您的请求，请返回或退出后重试");
            plainObj.put("showType", "2");
        }
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockTxIncmEpnAnalySumPlain(String reqMsgId, JSONObject hostObj) throws org.json.JSONException {
        JSONObject plainObj = hostObj != null ? hostObj : new JSONObject();
        if (plainObj.length() == 0) {
            plainObj = new JSONObject();
            plainObj.put("code", "000016");
            plainObj.put("msg", "暂时无法处理您的请求，请返回或退出后重试");
            plainObj.put("showType", "2");
        }
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockImexSumDataPlain(String reqMsgId, JSONObject hostObj) throws org.json.JSONException {
        JSONObject plainObj = hostObj != null ? hostObj : new JSONObject();
        if (plainObj.length() == 0) {
            plainObj = new JSONObject();
            plainObj.put("code", "000016");
            plainObj.put("msg", "暂时无法处理您的请求，请返回或退出后重试");
            plainObj.put("showType", "2");
        }
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static String buildMockMyIncmEpnPlain(String reqMsgId, JSONObject hostObj) throws org.json.JSONException {
        JSONObject plainObj = hostObj != null ? hostObj : new JSONObject();
        if (plainObj.length() == 0) {
            plainObj = new JSONObject();
            plainObj.put("code", "000016");
            plainObj.put("msg", "暂时无法处理您的请求，请返回或退出后重试");
            plainObj.put("showType", "2");
        }
        plainObj.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        return plainObj.toString();
    }

    private static Object responseBodyCreate(ClassLoader cl, String jsonStr) throws Throwable {
        Class<?> responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
        Class<?> mediaTypeCls = XposedHelpers.findClass("okhttp3.MediaType", cl);
        Object mt = mediaTypeCls.getMethod("parse", String.class).invoke(null, "application/json; charset=utf-8");
        for (Method m : responseBodyCls.getDeclaredMethods()) {
            if (!"create".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2) continue;
            if (p[0] == mediaTypeCls && p[1] == String.class) {
                m.setAccessible(true);
                return m.invoke(null, mt, jsonStr);
            }
            if (p[0] == String.class && p[1] == mediaTypeCls) {
                m.setAccessible(true);
                return m.invoke(null, jsonStr, mt);
            }
        }
        throw new NoSuchMethodException("ResponseBody.create");
    }

    private static Object rebuildOkHttpJsonResponse(ClassLoader cl, Object response, String jsonStr) throws Throwable {
        Object newBody = responseBodyCreate(cl, jsonStr);
        Method newBuilder = response.getClass().getMethod("newBuilder");
        Object builder = newBuilder.invoke(response);
        Method body = builder.getClass().getMethod("body", XposedHelpers.findClass("okhttp3.ResponseBody", cl));
        body.invoke(builder, newBody);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static Object buildSyntheticOkHttpJsonResponse(ClassLoader cl, Object request, String jsonStr) throws Throwable {
        Object newBody = responseBodyCreate(cl, jsonStr);
        Class<?> responseBuilderCls = XposedHelpers.findClass("okhttp3.Response$Builder", cl);
        Object b = responseBuilderCls.getConstructor().newInstance();
        Method requestM = responseBuilderCls.getMethod("request", XposedHelpers.findClass("okhttp3.Request", cl));
        requestM.invoke(b, request);
        Class<?> protocolCls = XposedHelpers.findClass("okhttp3.Protocol", cl);
        Object protoHttp11 = null;
        try {
            protoHttp11 = Enum.valueOf((Class<Enum>) protocolCls, "HTTP_1_1");
        } catch (Throwable ignored) {
        }
        if (protoHttp11 == null) {
            Object[] constants = protocolCls.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    if ("HTTP_1_1".equals(String.valueOf(c))) {
                        protoHttp11 = c;
                        break;
                    }
                }
            }
        }
        if (protoHttp11 == null) {
            throw new IllegalStateException("HTTP_1_1");
        }
        responseBuilderCls.getMethod("protocol", protocolCls).invoke(b, protoHttp11);
        responseBuilderCls.getMethod("code", int.class).invoke(b, 200);
        responseBuilderCls.getMethod("message", String.class).invoke(b, "OK");
        long now = System.currentTimeMillis();
        try {
            responseBuilderCls.getMethod("sentRequestAtMillis", long.class).invoke(b, now);
        } catch (Throwable ignored) {
        }
        try {
            responseBuilderCls.getMethod("receivedResponseAtMillis", long.class).invoke(b, now);
        } catch (Throwable ignored) {
        }
        responseBuilderCls.getMethod("body", XposedHelpers.findClass("okhttp3.ResponseBody", cl)).invoke(b, newBody);
        return responseBuilderCls.getMethod("build").invoke(b);
    }

    private static boolean builderHasOurInterceptor(ClassLoader cl, Object builder, Object singleton) {
        try {
            java.lang.reflect.Field f = builder.getClass().getDeclaredField("interceptors");
            f.setAccessible(true);
            java.util.List<?> list = (java.util.List<?>) f.get(builder);
            for (Object el : list) {
                if (el == singleton) {
                    return true;
                }
            }
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] builderHasOurInterceptor: " + t);
        }
        return false;
    }

    private static void builderAppendInterceptor(ClassLoader cl, Object builder, Object singleton) {
        try {
            java.lang.reflect.Field f = builder.getClass().getDeclaredField("interceptors");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) f.get(builder);
            list.add(singleton);
        } catch (Throwable t) {
            YouzengPsbcHelpers.log("[-] builderAppendInterceptor: " + t);
        }
    }

    /**
     * 反射调用会把目标方法的 IOException 包成 InvocationTargetException。
     * OkHttp Interceptor.intercept 只声明了 throws IOException；若不拆开，
     * Proxy 会再包成 UndeclaredThrowableException，Dispatcher 线程 FATAL 杀进程。
     */
    private static Object invokeUnwrapping(Method method, Object target, Object... args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    private static Throwable unwrapInvocation(Throwable t) {
        Throwable cur = t;
        while (cur instanceof InvocationTargetException) {
            Throwable cause = cur.getCause();
            if (cause == null || cause == cur) {
                break;
            }
            cur = cause;
        }
        return cur;
    }

    private static Object interceptChain(ClassLoader cl, Object chain) throws Throwable {
        Method requestMethod = chain.getClass().getMethod("request");
        Object req = requestMethod.invoke(chain);
        String url = "";
        String reqMsgId = "";
        String sm4Key = null;
        JSONObject reqPlainCtx = null;

        try {
            ensureDeviceConfigLoadedSync(cl, false);
            try {
                Object httpUrl = req.getClass().getMethod("url").invoke(req);
                url = String.valueOf(httpUrl.getClass().getMethod("toString").invoke(httpUrl));
            } catch (Throwable ignored) {
            }
            try {
                Object h = req.getClass().getMethod("header", String.class).invoke(req, "reqMsgId");
                reqMsgId = h != null ? String.valueOf(h) : "";
            } catch (Throwable ignored) {
            }
            sm4Key = resolveSm4KeyFromRequest(cl, req);
            if (sm4Key == null || sm4Key.isEmpty()) {
                if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
                    YouzengPsbcHelpers.log("[-] SM4 跳过: Request 上取不到 BaseApi 或密钥为空");
                }
                Method skipProceed = chain.getClass().getMethod("proceed", XposedHelpers.findClass("okhttp3.Request", cl));
                return invokeUnwrapping(skipProceed, chain, req);
            }
            reqPlainCtx = resolvePlainReqFromReq(cl, req, sm4Key);
            // 请求明文解析失败仍继续走响应 mock（如 qryAccDtl 仅改响应）

            if (YouzengPsbcHelpers.hookHttpLogAnyEnabled() && !YouzengPsbcHelpers.isHookUrlFiltered("", url)) {
                String keyReq = reqMsgId.isEmpty() ? url : reqMsgId + "|" + url;
                if (!keyReq.isEmpty() && !hookReqLogOnce.containsKey(keyReq)) {
                    YouzengPsbcHelpers.markOnceAndPrune(hookReqLogOnce, keyReq, PsbcConfig.HOOK_ONCE_MAX);
                    StringBuilder sb = new StringBuilder();
                    sb.append("\n╔══════════════ [REQ okhttp3.Interceptor] ══════════════\n");
                    sb.append("║ [PAIR] reqMsgId=").append(reqMsgId.isEmpty() ? "(无)" : reqMsgId).append("\n");
                    sb.append("║ [REQ] URL(okhttp3): ").append(url).append("\n");
                    sb.append("║ [REQ] Body(plainBySM4): ").append(reqPlainCtx.optString("plain")).append("\n");
                    if (YouzengPsbcHelpers.isXhxTargetUrl(url) && !reqMsgId.isEmpty()) {
                        JSONObject q = YouzengPsbcHelpers.parseXhxQueryFromPlainRequest(reqPlainCtx.optString("plain"));
                        if (q != null) {
                            sb.append("║ [XHX] QueryRange: beginDate=").append(q.optString("beginDate", "(无)"))
                                    .append(", dlineDate=").append(q.optString("dlineDate", "(无)")).append("\n");
                        }
                    }
                    if (YouzengPsbcHelpers.isTxTrsfTargetUrl(url) && !reqMsgId.isEmpty()) {
                        JSONObject tq = YouzengPsbcHelpers.parseTxTrsfQueryFromPlainRequest(reqPlainCtx.optString("plain"));
                        if (tq != null) {
                            sb.append("║ [TX_TRSF] QueryRange: beginDate=").append(tq.optString("beginDate", "(无)"))
                                    .append(", dlineDate=").append(tq.optString("dlineDate", "(无)"))
                                    .append(", txOpsAccno=").append(tq.optString("txOpsAccno", "(无)"))
                                    .append(", txOpsName=").append(tq.optString("txOpsName", "(无)"))
                                    .append(", bgnIndexNo=").append(tq.optString("bgnIndexNo", "(无)"))
                                    .append(", curQryReqNum=").append(tq.optString("curQryReqNum", "(无)")).append("\n");
                        }
                    }
                    if (YouzengPsbcHelpers.isQryTopDtlListTargetUrl(url) && !reqMsgId.isEmpty()) {
                        JSONObject tq = YouzengPsbcHelpers.parseQryTopDtlListFromPlainRequest(reqPlainCtx.optString("plain"));
                        if (tq != null) {
                            sb.append("║ [QRY_TOP_DTL_LIST] Query: beginDate=").append(tq.optString("beginDate", "(无)"))
                                    .append(", dlineDate=").append(tq.optString("dlineDate", "(无)"))
                                    .append(", incmEpnMonth=").append(tq.optString("incmEpnMonth", "(无)"))
                                    .append(", incmEpnTpCd=").append(tq.optString("incmEpnTpCd", "(无)"))
                                    .append(", incmEpnTxTpCd=").append(tq.optString("incmEpnTxTpCd", "(无)"))
                                    .append(", bgnIndexNo=").append(tq.optString("bgnIndexNo", "(无)"))
                                    .append(", curQryReqNum=").append(tq.optString("curQryReqNum", "(无)")).append("\n");
                        }
                    }
                    if (YouzengPsbcHelpers.isQryTransDetailTargetUrl(url) && !reqMsgId.isEmpty()) {
                        JSONObject tq = YouzengPsbcHelpers.parseQryTransDetailFromPlainRequest(reqPlainCtx.optString("plain"));
                        if (tq != null) {
                            sb.append("║ [QRY_TRANS_DETAIL] Query: beginDate=").append(tq.optString("beginDate", "(无)"))
                                    .append(", dlineDate=").append(tq.optString("dlineDate", "(无)"))
                                    .append(", incmEpnTpCd=").append(tq.optString("incmEpnTpCd", "(无)"))
                                    .append(", qryCond=").append(tq.optString("qryCond", "(无)"))
                                    .append(", bgnIndexNo=").append(tq.optString("bgnIndexNo", "(无)"))
                                    .append(", curQryReqNum=").append(tq.optString("curQryReqNum", "(无)")).append("\n");
                        }
                    }
                    if (YouzengPsbcHelpers.isTxDetailTargetUrl(url) && !reqMsgId.isEmpty()) {
                        JSONObject d = YouzengPsbcHelpers.parseTxDetailQueryFromPlainRequest(reqPlainCtx.optString("plain"));
                        if (d != null) {
                            sb.append("║ [TX_DETAIL] Query: ").append(d.toString()).append("\n");
                        }
                    }
                    sb.append("╚════════════════════════════════════════════\n");
                    YouzengPsbcHelpers.log(sb.toString());
                }
            }
        } catch (Throwable eReq) {
            YouzengPsbcHelpers.log("[-] 请求日志异常: " + eReq);
        }

        Method proceed = chain.getClass().getMethod("proceed", XposedHelpers.findClass("okhttp3.Request", cl));
        String reqPlain = reqPlainCtx != null ? reqPlainCtx.optString("plain") : "";

        if (PsbcConfig.ENABLE_FORWARD_ALL
                && YouzengPsbcHelpers.isHistoryTransactionDetailApplyTargetUrl(url) && sm4Key != null) {
            try {
                JSONObject fw = YouzengForwardClient.forward(
                        "psbc", url, "", reqPlain, "", reqMsgId, YouzengConfig.FORWARD_READ_MS_LONG);
                Object syn = wrapForwardPlainAsOkHttpResponse(cl, req, null, null, sm4Key, reqMsgId, fw);
                if (syn != null) {
                    if (YouzengPsbcHelpers.hookHttpLogAnyEnabled() && !YouzengPsbcHelpers.isHookUrlFiltered("", url)) {
                        StringBuilder rspEarly = new StringBuilder();
                        rspEarly.append("\n╔══════════════ [RSP okhttp3.Interceptor] (FORWARD SYNTH) ══════════════\n");
                        rspEarly.append("║ [PAIR] reqMsgId=").append(reqMsgId.isEmpty() ? "(无)" : reqMsgId).append("\n");
                        rspEarly.append("║ [RSP] URL(okhttp3): ").append(url).append("\n");
                        rspEarly.append("║ [FORWARD] HISTORY_TX_DETAIL_APPLY replace\n");
                        rspEarly.append("╚════════════════════════════════════════════\n");
                        YouzengPsbcHelpers.log(rspEarly.toString());
                    }
                    return syn;
                }
            } catch (Throwable t) {
                YouzengPsbcHelpers.log("[-] HISTORY apply forward: " + t);
            }
        }

        Object resp = invokeUnwrapping(proceed, chain, req);

        String decRspPlain = "";
        JSONObject rspObj = null;
        String rawRsp = "";
        try {
            if (sm4Key != null) {
                Method peekBody = resp.getClass().getMethod("peekBody", long.class);
                Object peeked = peekBody.invoke(resp, 10L * 1024 * 1024);
                if (peeked != null) {
                    rawRsp = String.valueOf(peeked.getClass().getMethod("string").invoke(peeked));
                }
                try {
                    rspObj = new JSONObject(rawRsp);
                } catch (Exception ignored) {
                }
                if (rspObj != null) {
                    decRspPlain = YouzengPsbcHelpers.resolveResponsePlainFromWire(cl, sm4Key, rspObj);
                }
            }
        } catch (Throwable tPeek) {
            YouzengPsbcHelpers.log("[-] 响应体读取/解密异常: " + tPeek);
        }

        if ((YouzengPsbcHelpers.isXhxTargetUrl(url) || YouzengPsbcHelpers.isQryTransDetailTargetUrl(url))
                && decRspPlain != null && !decRspPlain.isEmpty()) {
            YouzengPsbcHelpers.reportBillListProbeAsync(url, decRspPlain);
        }

        StringBuilder mockLog = new StringBuilder();
        try {
            if (PsbcConfig.ENABLE_FORWARD_ALL && sm4Key != null) {
                JSONObject fw = YouzengForwardClient.forward("psbc", url, "", reqPlain, decRspPlain, reqMsgId);
                String action = fw.optString("action", "passthrough");
                mockLog.append("║ [FORWARD] action=").append(action).append("\n");
                if ("replace".equals(action)) {
                    Object rebuilt = wrapForwardPlainAsOkHttpResponse(cl, req, resp, rspObj, sm4Key, reqMsgId, fw);
                    if (rebuilt != null) {
                        resp = rebuilt;
                        decRspPlain = fw.optString("body", decRspPlain);
                        mockLog.append("║ [FORWARD] replace ok bodyLen=").append(decRspPlain.length()).append("\n");
                    } else {
                        mockLog.append("║ [FORWARD] replace skip: empty/encrypt fail\n");
                    }
                }
            }
        } catch (Throwable eMock) {
            YouzengPsbcHelpers.log("[-] 响应forward异常: " + eMock);
        }

        try {
            if (!YouzengPsbcHelpers.hookHttpLogAnyEnabled() || YouzengPsbcHelpers.isHookUrlFiltered("", url)) {
                return resp;
            }
            String rspOnceKey = reqMsgId.isEmpty() ? url : reqMsgId + "|" + url;
            if (!rspOnceKey.isEmpty() && hookRspProcessOnce.containsKey(rspOnceKey)) {
                return resp;
            }
            if (!rspOnceKey.isEmpty()) {
                YouzengPsbcHelpers.markOnceAndPrune(hookRspProcessOnce, rspOnceKey, PsbcConfig.HOOK_ONCE_MAX);
            }
            StringBuilder rspLines = new StringBuilder();
            rspLines.append("\n╔══════════════ [RSP okhttp3.Interceptor] ══════════════\n");
            rspLines.append("║ [PAIR] reqMsgId=").append(reqMsgId.isEmpty() ? "(无)" : reqMsgId).append("\n");
            rspLines.append("║ [RSP] URL(okhttp3): ").append(url).append("\n");
            if (!decRspPlain.isEmpty()) {
                rspLines.append("║ [RSP] Body(plainBySM4): ").append(decRspPlain).append("\n");
            } else if (rawRsp.length() > 0) {
                rspLines.append("║ [RSP] Body(raw): ").append(rawRsp).append("\n");
            } else {
                rspLines.append("║ [RSP] Body(plainBySM4): (响应体为空)\n");
            }
            if (mockLog.length() > 0) {
                rspLines.append(mockLog);
            }
            rspLines.append("╚════════════════════════════════════════════\n");
            YouzengPsbcHelpers.log(rspLines.toString());
        } catch (Throwable eRsp) {
            YouzengPsbcHelpers.log("[-] 响应日志异常: " + eRsp);
        }
        return resp;
    }


    private static Object wrapForwardPlainAsOkHttpResponse(
            ClassLoader cl,
            Object request,
            Object response,
            JSONObject existingWire,
            String sm4Key,
            String reqMsgId,
            JSONObject fw) throws Throwable {
        if (fw == null || !"replace".equals(fw.optString("action"))) {
            return null;
        }
        String mockPlain = fw.optString("body", "");
        if (mockPlain.isEmpty()) {
            return null;
        }
        String mockEnc = YouzengPsbcHelpers.encryptPlainBySm4KeyToHex(cl, sm4Key, mockPlain);
        if (mockEnc == null || mockEnc.isEmpty()) {
            return null;
        }
        JSONObject inner;
        try {
            inner = new JSONObject(mockPlain);
        } catch (Exception e) {
            inner = new JSONObject();
        }
        JSONObject wire = existingWire != null ? existingWire : new JSONObject();
        if (inner.has("code")) {
            wire.put("code", inner.optString("code", "000000"));
        }
        if (inner.has("msg")) {
            wire.put("msg", inner.optString("msg", "交易成功"));
        }
        if (inner.has("showType")) {
            wire.put("showType", inner.optString("showType", "0"));
        }
        wire.put("data", mockEnc);
        wire.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
        if (response != null) {
            return rebuildOkHttpJsonResponse(cl, response, wire.toString());
        }
        return buildSyntheticOkHttpJsonResponse(cl, request, wire.toString());
    }

    private static void installOkHttpAndInterceptor(ClassLoader cl) throws Throwable {
        Class<?> interceptorIface = XposedHelpers.findClass("okhttp3.Interceptor", cl);
        if (interceptorSingleton == null) {
            interceptorSingleton = Proxy.newProxyInstance(cl, new Class[]{interceptorIface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("intercept".equals(method.getName()) && args != null && args.length == 1) {
                                try {
                                    return interceptChain(cl, args[0]);
                                } catch (Throwable t) {
                                    throw unwrapInvocation(t);
                                }
                            }
                            return null;
                        }
                    });
        }

        Class<?> builderCls = XposedHelpers.findClass("okhttp3.OkHttpClient$Builder", cl);
        Method addInterceptor = null;
        for (Method m : builderCls.getDeclaredMethods()) {
            if (!"addInterceptor".equals(m.getName())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == interceptorIface) {
                addInterceptor = m;
                break;
            }
        }
        if (addInterceptor == null) {
            throw new NoSuchMethodException("addInterceptor");
        }
        addInterceptor.setAccessible(true);
        final Method addInterceptorFinal = addInterceptor;
        final Object singleton = interceptorSingleton;

        XposedHelpers.findAndHookMethod(builderCls, "addInterceptor", interceptorIface, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object builder = param.thisObject;
                Object interceptorArg = param.args[0];
                if (interceptorArg == singleton) {
                    return;
                }
                if (!builderHasOurInterceptor(cl, builder, singleton)) {
                    addInterceptorFinal.invoke(builder, singleton);
                }
            }
        });

        XposedHelpers.findAndHookMethod(builderCls, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object builder = param.thisObject;
                if (!builderHasOurInterceptor(cl, builder, singleton)) {
                    builderAppendInterceptor(cl, builder, singleton);
                }
            }
        });

        YouzengPsbcHelpers.log("[+] okhttp3.OkHttpClient$Builder hooked (youzeng mock interceptor)");
    }
}
