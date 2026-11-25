package com.youzeng.icbc;

import android.app.Application;
import android.content.Context;

import com.youzeng.hook.YouzengForwardClient;
import com.youzeng.hook.YouzengModulePrefs;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 工商银行网络请求拦截与转发。
 *
 * <p>工商银行使用 React Native 框架，底层走 OkHttp3。
 * 在 Application.attach 后 hook OkHttpClient$Builder，注入我们的拦截器，
 * 把每条业务请求的 URL + 请求体 + 响应体整包 POST 到 youzeng 后端 /api/forward。
 * 后端根据 bank=icbc 路由，当前为数据采集模式（passthrough），
 * 后续可扩展为 mock 注入（replace）。
 * </p>
 */
public final class YouzengIcbcNetworkHook {

    private static volatile boolean networkHooksInstalled = false;
    private static volatile boolean appGuardHooksInstalled = false;
    private static Object interceptorSingleton = null;

    private YouzengIcbcNetworkHook() {
    }

    // ─────────────────────────────────────────────────────────────
    // 入口
    // ─────────────────────────────────────────────────────────────

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!IcbcConfig.isTargetPackage(lpparam.packageName)) {
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
                    installOkHttpInterceptor(cl);
                } catch (Throwable t) {
                    log("install failed: " + t);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // AppGuard：拦截 System.exit / Process.killProcess
    // ─────────────────────────────────────────────────────────────

    private static void installAppGuardHooks(ClassLoader cl) {
        if (appGuardHooksInstalled) {
            return;
        }
        appGuardHooksInstalled = true;
        try {
            XposedHelpers.findAndHookMethod("java.lang.System", cl, "exit", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("[guard] System.exit intercepted code=" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            log("[-] System.exit hook: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("android.os.Process", cl, "killProcess", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("[guard] Process.killProcess intercepted pid=" + param.args[0]);
                }
            });
        } catch (Throwable t) {
            log("[-] Process.killProcess hook: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // OkHttp 读取辅助
    // ─────────────────────────────────────────────────────────────

    private static String readBodyAsString(ClassLoader cl, Object body) {
        try {
            if (body == null) {
                return "";
            }
            try {
                Method isOneShot = body.getClass().getMethod("isOneShot");
                if (Boolean.TRUE.equals(isOneShot.invoke(body))) {
                    return "";
                }
            } catch (Throwable ignored) {
            }
            Class<?> bufferCls = XposedHelpers.findClass("okio.Buffer", cl);
            Class<?> sinkCls = XposedHelpers.findClass("okio.BufferedSink", cl);
            Object buf = bufferCls.getConstructor().newInstance();
            Method writeTo = body.getClass().getMethod("writeTo", sinkCls);
            writeTo.invoke(body, buf);
            byte[] bytes = (byte[]) bufferCls.getMethod("readByteArray").invoke(buf);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String peekResponseBody(ClassLoader cl, Object response) {
        try {
            Method peekBody = response.getClass().getMethod("peekBody", long.class);
            Object peeked = peekBody.invoke(response, 4L * 1024 * 1024);
            if (peeked == null) {
                return "";
            }
            return String.valueOf(peeked.getClass().getMethod("string").invoke(peeked));
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 合成/替换 OkHttp 响应体
    // ─────────────────────────────────────────────────────────────

    private static Object responseBodyCreate(ClassLoader cl, String jsonStr) throws Throwable {
        Class<?> responseBodyCls = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
        Class<?> mediaTypeCls = XposedHelpers.findClass("okhttp3.MediaType", cl);
        Object mt = mediaTypeCls.getMethod("parse", String.class).invoke(null, "application/json; charset=utf-8");
        for (Method m : responseBodyCls.getDeclaredMethods()) {
            if (!"create".equals(m.getName())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 2) {
                continue;
            }
            if (p[0] == mediaTypeCls && p[1] == String.class) {
                m.setAccessible(true);
                return m.invoke(null, mt, jsonStr);
            }
            if (p[0] == String.class && p[1] == mediaTypeCls) {
                m.setAccessible(true);
                return m.invoke(null, jsonStr, mt);
            }
        }
        throw new NoSuchMethodException("ResponseBody.create(MediaType, String)");
    }

    /** 在已有 Response 上替换 body */
    private static Object rebuildResponse(ClassLoader cl, Object response, String body) throws Throwable {
        Object newBody = responseBodyCreate(cl, body);
        Method newBuilder = response.getClass().getMethod("newBuilder");
        Object builder = newBuilder.invoke(response);
        Method bodyM = builder.getClass().getMethod("body", XposedHelpers.findClass("okhttp3.ResponseBody", cl));
        bodyM.invoke(builder, newBody);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    /** 构造全新的 200 OK JSON 合成响应（短路场景，不发出实际网络请求） */
    private static Object buildSyntheticResponse(ClassLoader cl, Object request, String jsonStr) throws Throwable {
        Object newBody = responseBodyCreate(cl, jsonStr);
        Class<?> responseBuilderCls = XposedHelpers.findClass("okhttp3.Response$Builder", cl);
        Object b = responseBuilderCls.getConstructor().newInstance();
        responseBuilderCls.getMethod("request", XposedHelpers.findClass("okhttp3.Request", cl)).invoke(b, request);
        Class<?> protocolCls = XposedHelpers.findClass("okhttp3.Protocol", cl);
        Object protoHttp11 = null;
        try {
            protoHttp11 = Enum.valueOf((Class<Enum>) protocolCls, "HTTP_1_1");
        } catch (Throwable ignored) {
        }
        if (protoHttp11 == null) {
            for (Object c : protocolCls.getEnumConstants()) {
                if ("HTTP_1_1".equals(String.valueOf(c))) {
                    protoHttp11 = c;
                    break;
                }
            }
        }
        if (protoHttp11 == null) {
            throw new IllegalStateException("HTTP_1_1 not found in Protocol");
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

    // ─────────────────────────────────────────────────────────────
    // 拦截器核心逻辑
    // ─────────────────────────────────────────────────────────────

    private static Object interceptChain(ClassLoader cl, Object chain) throws Throwable {
        Method requestMethod = chain.getClass().getMethod("request");
        Object req = requestMethod.invoke(chain);

        // 读 URL
        String url = "";
        try {
            Object httpUrl = req.getClass().getMethod("url").invoke(req);
            url = String.valueOf(httpUrl.getClass().getMethod("toString").invoke(httpUrl));
        } catch (Throwable ignored) {
        }

        // 读请求头中的 reqMsgId（若有）
        String reqMsgId = "";
        try {
            for (String hName : new String[]{"reqMsgId", "X-Req-Msg-Id", "x-req-msg-id"}) {
                Object h = req.getClass().getMethod("header", String.class).invoke(req, hName);
                if (h != null && !String.valueOf(h).isEmpty()) {
                    reqMsgId = String.valueOf(h);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }

        // 读请求体
        String reqBody = "";
        try {
            Object rb = req.getClass().getMethod("body").invoke(req);
            reqBody = readBodyAsString(cl, rb);
        } catch (Throwable ignored) {
        }

        // 短路：发出请求前先询问后端（如果后端已有 mock 可以直接返回，不占用银行服务器）
        if (IcbcConfig.ENABLE_FORWARD_ALL && !url.isEmpty()) {
            try {
                JSONObject fw = YouzengForwardClient.forward(
                        "icbc", url, extractTxCode(url, reqBody), reqBody, "", reqMsgId);
                if ("replace".equals(fw.optString("action"))) {
                    String mockBody = fw.optString("body", "");
                    if (!mockBody.isEmpty()) {
                        log("[FORWARD] short-circuit url=…" + tail(url, 60) + " bodyLen=" + mockBody.length());
                        return buildSyntheticResponse(cl, req, mockBody);
                    }
                }
            } catch (Throwable t) {
                log("[-] short-circuit: " + t);
            }
        }

        // 正常发出请求
        Method proceed = chain.getClass().getMethod("proceed", XposedHelpers.findClass("okhttp3.Request", cl));
        Object resp;
        try {
            resp = proceed.invoke(chain, req);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }

        // 读响应体（peek 不消耗流）
        String rspBody = peekResponseBody(cl, resp);

        // 转发请求+响应到后端
        if (IcbcConfig.ENABLE_FORWARD_ALL && !url.isEmpty()) {
            try {
                JSONObject fw = YouzengForwardClient.forward(
                        "icbc", url, extractTxCode(url, reqBody), reqBody, rspBody, reqMsgId);
                String action = fw.optString("action", "passthrough");
                if ("replace".equals(action)) {
                    String mockBody = fw.optString("body", "");
                    if (!mockBody.isEmpty()) {
                        resp = rebuildResponse(cl, resp, mockBody);
                        log("[FORWARD] replace url=…" + tail(url, 60) + " bodyLen=" + mockBody.length());
                    }
                }
            } catch (Throwable t) {
                log("[-] forward: " + t);
            }
        }

        // 控制台日志（仅调试用，上线可关 ENABLE_HTTP_HOOK_LOG_CONSOLE）
        if (IcbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            log("[HTTP] url=…" + tail(url, 80)
                    + " reqLen=" + reqBody.length()
                    + " rspLen=" + rspBody.length()
                    + (reqMsgId.isEmpty() ? "" : " msgId=" + reqMsgId));
        }

        return resp;
    }

    /**
     * 从 URL 或请求体中尝试提取工商银行 txCode（如 SV800020）。
     * 工商 URL 格式通常为 /servlet/XXX?txcode=SV800020 或 path 中含交易码。
     */
    private static String extractTxCode(String url, String reqBody) {
        try {
            String u = url == null ? "" : url.toLowerCase();
            // URL query 参数 txcode=
            int idx = u.indexOf("txcode=");
            if (idx >= 0) {
                String sub = url.substring(idx + 7);
                int end = sub.indexOf('&');
                return end >= 0 ? sub.substring(0, end) : sub;
            }
            // 请求体 JSON 中的 txcode 字段
            if (reqBody != null && reqBody.contains("txcode")) {
                JSONObject obj = new JSONObject(reqBody);
                String tx = obj.optString("txcode", "");
                if (!tx.isEmpty()) {
                    return tx;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ─────────────────────────────────────────────────────────────
    // OkHttp Builder 注入
    // ─────────────────────────────────────────────────────────────

    private static void installOkHttpInterceptor(ClassLoader cl) throws Throwable {
        Class<?> interceptorIface = XposedHelpers.findClass("okhttp3.Interceptor", cl);
        if (interceptorSingleton == null) {
            interceptorSingleton = Proxy.newProxyInstance(
                    cl,
                    new Class[]{interceptorIface},
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
            if ("addInterceptor".equals(m.getName())) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == interceptorIface) {
                    addInterceptor = m;
                    break;
                }
            }
        }
        if (addInterceptor == null) {
            throw new NoSuchMethodException("OkHttpClient$Builder.addInterceptor(Interceptor)");
        }
        addInterceptor.setAccessible(true);

        final Method addInterceptorFinal = addInterceptor;
        final Object singleton = interceptorSingleton;

        // 在每次 addInterceptor 调用后追加我们的拦截器（幂等）
        XposedHelpers.findAndHookMethod(builderCls, "addInterceptor", interceptorIface, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object builder = param.thisObject;
                if (param.args[0] == singleton) {
                    return;
                }
                if (!hasInterceptor(builder, singleton)) {
                    addInterceptorFinal.invoke(builder, singleton);
                }
            }
        });

        // build() 前兜底：确保一定注入
        XposedHelpers.findAndHookMethod(builderCls, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object builder = param.thisObject;
                if (!hasInterceptor(builder, singleton)) {
                    appendInterceptor(builder, singleton);
                }
            }
        });

        log("[+] okhttp3.OkHttpClient$Builder hooked (icbc forward interceptor)");
    }

    private static boolean hasInterceptor(Object builder, Object singleton) {
        try {
            Field f = builder.getClass().getDeclaredField("interceptors");
            f.setAccessible(true);
            List<?> list = (List<?>) f.get(builder);
            if (list != null) {
                for (Object el : list) {
                    if (el == singleton) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            log("[-] hasInterceptor: " + t);
        }
        return false;
    }

    private static void appendInterceptor(Object builder, Object singleton) {
        try {
            Field f = builder.getClass().getDeclaredField("interceptors");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) f.get(builder);
            if (list != null) {
                list.add(singleton);
            }
        } catch (Throwable t) {
            log("[-] appendInterceptor: " + t);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────────

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

    private static String tail(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    static void log(String msg) {
        XposedBridge.log("[youzeng-icbc] " + msg);
    }
}
