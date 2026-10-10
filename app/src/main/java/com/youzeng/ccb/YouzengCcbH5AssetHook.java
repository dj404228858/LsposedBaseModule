package com.youzeng.ccb;

import com.youzeng.hook.YouzengForwardClient;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * "我的"页"总资产"由 AssetsDataSource.financeConvertAll 决定。
 * 该方法在 business dex（classes14.dex）中，需在 MainHomeActivity.onCreate 后安装。
 */
public final class YouzengCcbH5AssetHook {

    private static volatile boolean wcmHookInstalled;
    private static volatile boolean displayHookInstalled;
    private static volatile boolean financeConvertHookInstalled;
    private static volatile boolean prefetchStarted;
    static volatile String cachedBal = "";
    static final java.util.Set<String> liveBals = new java.util.concurrent.CopyOnWriteArraySet<>();

    private YouzengCcbH5AssetHook() {}

    public static void startPrefetch() {
        if (prefetchStarted) return;
        prefetchStarted = true;
        new Thread(new Runnable() {
            @Override public void run() {
                long deadline = System.currentTimeMillis() + 8000;
                while (System.currentTimeMillis() < deadline) {
                    String did = com.youzeng.hook.YouzengModulePrefs.getMockDeviceDid();
                    if (did != null && !did.isEmpty()
                            && !did.equals(com.youzeng.hook.YouzengConfig.DEFAULT_MOCK_DEVICE_DID)) {
                        break;
                    }
                    try { Thread.sleep(150); } catch (InterruptedException e) { break; }
                }
                String b = fetchBal();
                if (!b.isEmpty()) {
                    cachedBal = b;
                    XposedBridge.log("[youzeng] ccb bal prefetch=" + b
                            + " live=" + liveBals);
                    reportToServer("prefetch_ok", b + " pid=" + android.os.Process.myPid());
                } else {
                    XposedBridge.log("[youzeng] ccb bal prefetch FAILED");
                    reportToServer("prefetch_fail", "pid=" + android.os.Process.myPid());
                }
            }
        }, "youzeng-ccb-bal").start();
    }

    public static void installAfterBusinessDex(ClassLoader cl) {
        reportToServer("installAfterBusinessDex", cl.getClass().getSimpleName());
        startPrefetch();
        hookFinanceConvertAll(cl);
        hookWealthCache(cl);
        hookDisplaySJZC04(cl);
        hookAssetsMenuFloor(cl);
        // 用平台 android.app.Fragment 钩 onHiddenChanged / onStart / onResume
        hookPlatformFragmentLifecycle();
        // 兜底：直接 hook TextView.setText，精准替换目标余额文字
        hookTextViewSetText();
        // WebView H5 余额替换（"资产总览"是 H5 页面）
        hookWebViewBalance();
    }

    /* ─── 兜底 Hook：TextView.setText 直接替换余额文字 ─ */

    private static volatile boolean tvHookInstalled;

    private static void hookTextViewSetText() {
        if (tvHookInstalled) return;
        tvHookInstalled = true;
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                CharSequence text = (CharSequence) param.args[0];
                if (text == null) return;
                String neu = rewriteBalanceText(text.toString());
                if (neu != null) {
                    param.args[0] = neu;
                    XposedBridge.log("[youzeng] TV_set " + text + " -> " + neu);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(
                    "android.widget.TextView", null,
                    "setText", CharSequence.class, hook);
            XposedHelpers.findAndHookMethod(
                    "android.widget.TextView", null,
                    "setText", CharSequence.class, android.widget.TextView.BufferType.class, hook);
            reportToServer("TV_hooked", "live");
        } catch (Throwable t) {
            reportToServer("TV_hook_ex", t.getMessage());
        }
    }

    private static String plainAmt(String raw) {
        try {
            return String.format(java.util.Locale.US, "%.2f",
                    Double.parseDouble(String.valueOf(raw).replace(",", "").trim()));
        } catch (Throwable t) {
            return raw == null ? "" : raw.trim();
        }
    }

    private static String commaAmt(String raw) {
        try {
            java.text.DecimalFormat df = new java.text.DecimalFormat("#,##0.00");
            df.setDecimalFormatSymbols(java.text.DecimalFormatSymbols.getInstance(java.util.Locale.US));
            return df.format(Double.parseDouble(String.valueOf(raw).replace(",", "").trim()));
        } catch (Throwable t) {
            return raw == null ? "" : raw.trim();
        }
    }

    private static boolean sameAmt(String a, String b) {
        String pa = plainAmt(a);
        String pb = plainAmt(b);
        return !pa.isEmpty() && pa.equals(pb);
    }

    /** 真余额换成资料余额；卡详情是 116,803.68 这种千分位，不要写成 116803.68。 */
    private static String rewriteBalanceText(String s) {
        String bal = cachedBal;
        if (bal == null || bal.isEmpty() || s == null || s.isEmpty()) return null;
        String balPlain = plainAmt(bal);
        String balComma = commaAmt(bal);
        if (s.contains(balComma)) return null;
        if (!balPlain.equals(balComma) && s.contains(balPlain)) {
            return s.replace(balPlain, balComma);
        }
        java.util.Set<String> lives = new java.util.LinkedHashSet<>(liveBals);
        if (lives.isEmpty()) {
            lives.add("20028.16");
            lives.add("20028.15");
            lives.add("20034.15");
        }
        String trimmed = s.trim();
        for (String live : lives) {
            if (live == null || live.isEmpty() || sameAmt(live, bal)) continue;
            String livePlain = plainAmt(live);
            String liveComma = commaAmt(live);
            if (sameAmt(trimmed, live)) return balComma;
            if (s.contains(liveComma)) return s.replace(liveComma, balComma);
            if (s.contains(livePlain)) return s.replace(livePlain, balComma);
        }
        return null;
    }

    /* ─── 余额获取 ──────────────────────────────── */

    private static String fetchBal() {
        try {
            String url = CcbConfig.mockProfileBalanceHttpApiUrl();
            JSONObject obj = YouzengForwardClient.getJson(url);
            if (obj == null) return "";
            if (obj.has("_error")) return "";
            JSONObject data = obj.optJSONObject("data");
            if (data == null) return "";
            org.json.JSONArray lives = data.optJSONArray("live_balances");
            if (lives != null) {
                for (int i = 0; i < lives.length(); i++) {
                    String live = lives.optString(i, "").trim();
                    if (!live.isEmpty()) liveBals.add(plainAmt(live));
                }
            }
            return data.optString("balance", "").trim();
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] ccb fetchBal ex=" + t);
            reportToServer("fetchBal_ex", t.getClass().getSimpleName() + ":" + t.getMessage());
            return "";
        }
    }

    static String getOrFetchBal() {
        // 先等 prefetch 最多 3s，避免在主线程发网络请求
        long deadline = System.currentTimeMillis() + 3000;
        while (cachedBal.isEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        return cachedBal;
    }

    /* ─── Hook 1: financeConvertAll → 替换返回值 ─ */

    private static void hookFinanceConvertAll(ClassLoader cl) {
        if (financeConvertHookInstalled) return;

        // dex 解析确认的真实位置：
        // AssetsDataSource in classes14.dex；Kotlin private 方法在 companion object 上
        // access$financeConvertAll bridge 说明真正的实现在 $Companion 内部
        String[] candidates = {
                // Kotlin companion object（最可能的位置）
                "com.ccb.framework.portal.popular.invest.data.AssetsDataSource$Companion",
                // 外部类（兜底）
                "com.ccb.framework.portal.popular.invest.data.AssetsDataSource",
                "com.ccb.investment.home.controller.FinanceHomeController",
                "com.ccb.framework.portal.popular.invest.floor.AssetsMenuFloor",
        };
        for (String cn : candidates) {
            if (tryHookFinanceConvertAll(cn, cl)) break;
        }
    }

    private static boolean tryHookFinanceConvertAll(String className, ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    cls, "financeConvertAll", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String bal = getOrFetchBal();
                            if (bal.isEmpty()) return;
                            param.setResult(bal);
                            XposedBridge.log("[youzeng] financeConvertAll -> " + bal);
                        }
                    });
            if (!hooks.isEmpty()) {
                financeConvertHookInstalled = true;
                reportToServer("FCA_ok", className + " x" + hooks.size());
                return true;
            } else {
                reportToServer("FCA_no_method", className);
            }
        } catch (Throwable t) {
            reportToServer("FCA_ex", className.replaceAll(".*\\.", "") + ":" + t.getMessage());
        }
        return false;
    }

    /* ─── 父类链诊断 + 动态找 onHiddenChanged ─────── */

    private static void reportClassHierarchy(Class<?> cls) {
        StringBuilder sb = new StringBuilder();
        Class<?> c = cls;
        while (c != null && !c.getName().equals("java.lang.Object")) {
            sb.append(c.getSimpleName());
            // 检查 onHiddenChanged 是否在这一层声明
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] params = m.getParameterTypes();
                // boolean 参数，名字含 hidden 或 visibility 或单字母（可能混淆过）
                if (params.length == 1 && params[0] == boolean.class) {
                    sb.append("[").append(name).append("]");
                }
            }
            sb.append("->");
            c = c.getSuperclass();
            if (c != null && c.getName().startsWith("android.")) break; // 到系统类停止
        }
        if (c != null) sb.append(c.getSimpleName());
        reportToServer("ASF_hier", sb.toString());
    }

    /** 在父类链中找 onHiddenChanged(boolean) 和 setUserVisibleHint(boolean) 并 hook */
    private static void hookHiddenChangedInHierarchy(final Class<?> assetCls) {
        boolean foundOHC = false, foundSUVH = false;
        Class<?> c = assetCls;
        while (c != null && !c.getName().equals("java.lang.Object")) {
            if (!foundOHC) {
                try {
                    final java.lang.reflect.Method m = c.getDeclaredMethod("onHiddenChanged", boolean.class);
                    final String cn = c.getSimpleName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isAssetFragment(param.thisObject)) return;
                            boolean hidden = (boolean) param.args[0];
                            reportToServer("OHC_fired", cn + " hidden=" + hidden);
                            if (!hidden) trySetAmfLabel(param.thisObject);
                        }
                    });
                    reportToServer("OHC_hooked", c.getName() + "#onHiddenChanged");
                    foundOHC = true;
                } catch (NoSuchMethodException ignored) {}
            }
            // setUserVisibleHint：ViewPager 切换 tab 时触发
            if (!foundSUVH) {
                try {
                    final java.lang.reflect.Method m = c.getDeclaredMethod("setUserVisibleHint", boolean.class);
                    final String cn = c.getSimpleName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isAssetFragment(param.thisObject)) return;
                            boolean visible = (boolean) param.args[0];
                            reportToServer("SUVH_fired", cn + " visible=" + visible);
                            if (visible) trySetAmfLabel(param.thisObject);
                        }
                    });
                    reportToServer("SUVH_hooked", c.getName() + "#setUserVisibleHint");
                    foundSUVH = true;
                } catch (NoSuchMethodException ignored) {}
            }
            if (foundOHC && foundSUVH) break;
            c = c.getSuperclass();
        }
        if (!foundOHC) reportToServer("OHC_notFound", "");
        if (!foundSUVH) reportToServer("SUVH_notFound", "");
    }

    /* ─── 平台 Fragment 生命周期钩子（用 null CL，适用于 android.app.Fragment） ─ */

    private static volatile boolean platformFragHookInstalled;

    private static void hookPlatformFragmentLifecycle() {
        if (platformFragHookInstalled) return;
        platformFragHookInstalled = true;

        // 钩两个候选类（一个是旧平台类，一个是 AndroidX）
        String[] fragClasses = {
                "android.app.Fragment",
                "androidx.fragment.app.Fragment",
        };
        for (String fragCls : fragClasses) {
            hookFragClassLifecycle(fragCls);
        }
    }

    private static void hookFragClassLifecycle(String fragClassName) {
        // onHiddenChanged(boolean)
        try {
            XposedHelpers.findAndHookMethod(fragClassName, null, "onHiddenChanged",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isAssetFragment(param.thisObject)) return;
                            boolean hidden = (boolean) param.args[0];
                            reportToServer("FRAG_hidden", fragClassName.replaceAll(".*\\.", "") + "=" + hidden);
                            if (!hidden) trySetAmfLabel(param.thisObject);
                        }
                    });
            reportToServer("FRAG_hiddenHooked", fragClassName.replaceAll(".*\\.", ""));
        } catch (Throwable t) {
            reportToServer("FRAG_hiddenHook_ex", fragClassName.replaceAll(".*\\.", "") + ":" + t.getMessage());
        }
        // onStart()
        try {
            XposedHelpers.findAndHookMethod(fragClassName, null, "onStart", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isAssetFragment(param.thisObject)) return;
                    reportToServer("FRAG_onStart", "fired");
                    trySetAmfLabel(param.thisObject);
                }
            });
        } catch (Throwable ignored) {}
        // onResume()
        try {
            XposedHelpers.findAndHookMethod(fragClassName, null, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isAssetFragment(param.thisObject)) return;
                    reportToServer("FRAG_onResume", "fired");
                    trySetAmfLabel(param.thisObject);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static boolean isAssetFragment(Object obj) {
        return obj != null && "com.ccb.asset.view.AssetFragment".equals(obj.getClass().getName());
    }

    /* ─── Hook 2: WealthCenterCacheManger.notifyListeners ─ */

    private static void hookWealthCache(ClassLoader cl) {
        if (wcmHookInstalled) return;
        try {
            Class<?> mgr = XposedHelpers.findClass(
                    "com.ccb.protocol.cache.WealthCenterCacheManger", cl);
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(
                    mgr, "notifyListeners", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String bal = getOrFetchBal();
                            if (bal.isEmpty()) return;
                            try { patchCacheFields(param.thisObject, bal); }
                            catch (Throwable t) {
                                XposedBridge.log("[youzeng] WCM patch ex=" + t);
                            }
                        }
                    });
            if (!hooks.isEmpty()) {
                wcmHookInstalled = true;
                XposedBridge.log("[+] ccb WCM.notifyListeners hooked x" + hooks.size());
            }
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] WCM hook ex=" + t);
        }
    }

    private static void patchCacheFields(Object mgr, String bal) throws Exception {
        Field cacheField = findField(mgr.getClass(), "cache");
        if (cacheField != null) {
            cacheField.setAccessible(true);
            Object cache = cacheField.get(mgr);
            if (cache != null) {
                patchZC04Response(findFieldValue(cache, "sjzc04"), bal);
                clearAstList(findFieldValue(cache, "sjzc05"));
                return;
            }
        }
        patchZC04Response(findFieldValue(mgr, "sjzc04"), bal);
        clearAstList(findFieldValue(mgr, "sjzc05"));
    }

    /* ─── Hook 3: AssetsMenuFloor.displaySJZC04Data ─ */

    private static void hookDisplaySJZC04(ClassLoader cl) {
        if (displayHookInstalled) return;
        // AssetsMenuFloor 在 classes12.dex，是实际负责显示 总资产 的 view/fragment
        String[] floorCandidates = {
                "com.ccb.framework.portal.popular.invest.floor.AssetsMenuFloor",
                "com.ccb.home.controller.MainHomeActivityController",
        };
        for (String cn : floorCandidates) {
            try {
                Class<?> ctrl = XposedHelpers.findClass(cn, cl);
                // 尝试 displaySJZC04Data
                boolean ok = tryHookDisplayMethod(ctrl, "displaySJZC04Data");
                // 同时 hook displaySJZC05Data（可能含 总资产 汇总）
                tryHookDisplayMethod(ctrl, "displaySJZC05Data");
                if (ok) {
                    displayHookInstalled = true;
                    XposedBridge.log("[youzeng] [+] ccb displaySJZC04Data hooked on " + cn);
                    reportToServer("DISP_ok", cn);
                    break;
                }
            } catch (Throwable t) {
                XposedBridge.log("[youzeng] displaySJZC04 hook ex on " + cn + ": " + t);
                reportToServer("DISP_ex", cn.replaceAll(".*\\.", "") + ":" + t.getMessage());
            }
        }
    }

    private static boolean tryHookDisplayMethod(Class<?> ctrl, String methodName) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(ctrl, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String bal = getOrFetchBal();
                    if (bal.isEmpty()) return;
                    if (param.args != null && param.args.length > 0) {
                        try { patchZC04Response(param.args[0], bal); }
                        catch (Throwable t) {
                            XposedBridge.log("[youzeng] displaySJZC04 patch ex=" + t);
                        }
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 尝试通过 view binding field 直接覆写 TextView
                    String bal = getOrFetchBal();
                    if (bal.isEmpty()) return;
                    try {
                        Field f = findField(param.thisObject.getClass(), "totalAssetsAmountLabel");
                        if (f != null) {
                            f.setAccessible(true);
                            final Object tv = f.get(param.thisObject);
                            if (tv != null) {
                                final java.lang.reflect.Method post =
                                        tv.getClass().getMethod("post", Runnable.class);
                                final java.lang.reflect.Method setText =
                                        tv.getClass().getMethod("setText", CharSequence.class);
                                post.invoke(tv, new Runnable() {
                                    @Override public void run() {
                                        try { setText.invoke(tv, bal); }
                                        catch (Throwable ignored) {}
                                    }
                                });
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[youzeng] totalAssetsAmountLabel set ex=" + t);
                    }
                }
            });
            return !hooks.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /* ─── 工具方法 ────────────────────────────────── */

    @SuppressWarnings("unchecked")
    static void patchZC04Response(Object rsp, String bal) throws Exception {
        if (rsp == null) return;
        Field astF = findField(rsp.getClass(), "Ast_List");
        if (astF == null) return;
        astF.setAccessible(true);
        Object raw = astF.get(rsp);
        if (!(raw instanceof List)) return;
        List<Object> list = (List<Object>) raw;
        for (Object item : list) {
            if (item == null) continue;
            Field ctCdF = findField(item.getClass(), "Ast_CtCd");
            Field totF  = findField(item.getClass(), "CNY_Tot_Ast");
            if (ctCdF == null || totF == null) continue;
            ctCdF.setAccessible(true);
            totF.setAccessible(true);
            if ("01".equals(String.valueOf(ctCdF.get(item)))) {
                totF.set(item, bal);
            } else {
                totF.set(item, "0.00");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearAstList(Object rsp) {
        if (rsp == null) return;
        try {
            Field f = findField(rsp.getClass(), "Ast_List");
            if (f == null) return;
            f.setAccessible(true);
            Object raw = f.get(rsp);
            if (raw instanceof List) ((List<?>) raw).clear();
        } catch (Throwable ignored) {}
    }

    private static Object findFieldValue(Object obj, String name) {
        try {
            Field f = findField(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) { return null; }
    }

    static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    /* ─── Hook 4: AssetsMenuFloor 所有 void 方法直接改标签文字 ─ */

    private static volatile boolean amfHookInstalled;
    // 弱引用保存 AssetsMenuFloor 实例，供主动触发 loadData 用
    private static final java.util.List<java.lang.ref.WeakReference<Object>> amfInstances =
            new java.util.ArrayList<>();

    private static void hookAssetsMenuFloor(ClassLoader cl) {
        if (amfHookInstalled) return;
        // 真正包含 totalAssetsAmountLabel 字段的类是 AssetFragment，不是 AssetsMenuFloor
        String[] candidates = {
                "com.ccb.asset.view.AssetFragment",
                "com.ccb.framework.portal.popular.invest.floor.AssetsMenuFloor",
        };
        for (String className : candidates) {
            if (tryHookAssetClass(cl, className)) {
                amfHookInstalled = true;
                break;
            }
        }
    }

    // 保存 AssetFragment Class 对象，供 scanAndPatchFragments 用
    static volatile Class<?> assetFragmentClass;

    private static boolean tryHookAssetClass(ClassLoader cl, String className) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            assetFragmentClass = cls;

            // 上报父类链 + 每一层是否有 onHiddenChanged 方法
            reportClassHierarchy(cls);
            // 尝试在父类链中找 onHiddenChanged 并 hook
            hookHiddenChangedInHierarchy(cls);

            // hook 构造函数
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log("[youzeng] AssetFragment ctor fired");
                    reportToServer("ASF_ctor", className.replaceAll(".*\\.", ""));
                    trySetAmfLabel(param.thisObject);
                }
            });

            // onHiddenChanged：Fragment show/hide 切换时触发（findAndHookMethod 会向上查父类）
            try {
                XposedHelpers.findAndHookMethod(cls, "onHiddenChanged", boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                boolean hidden = (boolean) param.args[0];
                                reportToServer("ASF_hidden", String.valueOf(hidden));
                                if (!hidden) {
                                    // Fragment 变为可见，更新标签
                                    trySetAmfLabel(param.thisObject);
                                }
                            }
                        });
                reportToServer("ASF_hiddenHooked", "ok");
            } catch (Throwable t) {
                reportToServer("ASF_hiddenHook_ex", t.getMessage());
            }

            // onStart：Fragment 生命周期 started 时触发（同样向上查父类）
            try {
                XposedHelpers.findAndHookMethod(cls, "onStart", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        reportToServer("ASF_onStart", "fired");
                        trySetAmfLabel(param.thisObject);
                    }
                });
            } catch (Throwable ignored) {}

            // onCreateView：View 重建时触发
            XposedBridge.hookAllMethods(cls, "onCreateView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    reportToServer("ASF_onCreateView", "fired");
                    trySetAmfLabel(param.thisObject);
                }
            });

            // onResume：ViewPager2 BEHAVIOR_RESUME_ONLY 模式下触发
            XposedBridge.hookAllMethods(cls, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    reportToServer("ASF_onResume", "fired");
                    trySetAmfLabel(param.thisObject);
                }
            });

            // 数据更新方法
            XC_MethodHook dataHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String bal = getOrFetchBal();
                    if (bal.isEmpty() || param.args == null || param.args.length < 1) return;
                    try { patchZC04Response(param.args[0], bal); }
                    catch (Throwable ignored) {}
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    trySetAmfLabel(param.thisObject);
                }
            };
            for (String mn : new String[]{"showInfo", "queryDetail", "updateAssets",
                    "onDataChanged", "initAssetsView", "setTotalAssets",
                    "showAssets", "refreshAssets", "loadData"}) {
                try {
                    Set<XC_MethodHook.Unhook> hs = XposedBridge.hookAllMethods(cls, mn, dataHook);
                    if (!hs.isEmpty()) {
                        XposedBridge.log("[youzeng] AssetFragment." + mn + " hooked x" + hs.size());
                        reportToServer("ASF_method_" + mn, "x" + hs.size());
                    }
                } catch (Throwable ignored) {}
            }

            reportToServer("ASF_hooked", className.replaceAll(".*\\.", "") + " v2");
            XposedBridge.log("[youzeng] [+] " + className + " hooks installed v2");
            return true;
        } catch (Throwable t) {
            reportToServer("ASF_ex", className.replaceAll(".*\\.", "") + ":" + t.getMessage());
            XposedBridge.log("[youzeng] tryHookAssetClass " + className + " ex=" + t);
            return false;
        }
    }

    /**
     * 从 MainActivity 扫描已有的 AssetFragment 实例并 patch 余额。
     * 在 MainActivity.onResume 中调用。
     */
    public static void scanAndPatchFragments(final Object activity) {
        try {
            android.os.Handler handler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    doScanAndPatch(activity);
                }
            }, 600);
        } catch (Throwable t) {
            reportToServer("SCAN_ex", t.getMessage());
        }
    }

    private static void doScanAndPatch(Object activity) {
        // 先尝试 AndroidX support fragment manager，再尝试平台 fragment manager
        boolean found = false;
        for (String method : new String[]{"getSupportFragmentManager", "getFragmentManager"}) {
            try {
                java.lang.reflect.Method getSFM = activity.getClass().getMethod(method);
                Object fm = getSFM.invoke(activity);
                int cnt = scanFM(fm, 0);
                reportToServer("SCAN_try", method + " n=" + cnt);
                if (cnt > 0) { found = true; break; }
            } catch (Throwable t) {
                reportToServer("SCAN_try_ex", method + ":" + t.getMessage());
            }
        }
        if (!found) reportToServer("SCAN_notFound", "all 0");
    }

    private static int scanFM(Object fm, int depth) {
        int total = 0;
        if (fm == null || depth > 4) return total;
        try {
            java.lang.reflect.Method getFrags = fm.getClass().getMethod("getFragments");
            Object raw = getFrags.invoke(fm);
            if (!(raw instanceof java.util.List)) return total;
            java.util.List<?> list = (java.util.List<?>) raw;
            total += list.size();
            // depth=0 时上报所有 fragment 类名（诊断用）
            if (depth == 0) {
                StringBuilder names = new StringBuilder();
                for (Object f : list) {
                    if (f != null) names.append(f.getClass().getSimpleName()).append(",");
                }
                reportToServer("SCAN_names", names.toString());
            }
            for (Object frag : list) {
                if (frag == null) continue;
                if ("com.ccb.asset.view.AssetFragment".equals(frag.getClass().getName())) {
                    reportToServer("SCAN_found", "depth=" + depth);
                    trySetAmfLabel(frag);
                }
                try {
                    Object cfm = frag.getClass().getMethod("getChildFragmentManager").invoke(frag);
                    total += scanFM(cfm, depth + 1);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            reportToServer("SCAN_fm_ex", "d=" + depth + ":" + t.getMessage());
        }
        return total;
    }

    private static void trySetAmfLabel(final Object floor) {
        String bal = cachedBal;
        XposedBridge.log("[youzeng] trySetAmfLabel bal=" + bal + " cls=" + floor.getClass().getSimpleName());        if (!bal.isEmpty()) {
            applyLabelText(floor, bal);
        } else {
            // prefetch 可能尚未完成，后台等待最多 8s
            new Thread(new Runnable() {
                @Override public void run() {
                    long deadline = System.currentTimeMillis() + 8000;
                    while (cachedBal.isEmpty() && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(200); } catch (InterruptedException e) { break; }
                    }
                    String b = cachedBal;
                    if (b.isEmpty()) {
                        XposedBridge.log("[youzeng] AMF trySetAmfLabel: bal still empty after wait");
                        reportToServer("AMF_empty", "bal not ready");
                    } else {
                        applyLabelText(floor, b);
                    }
                }
            }, "youzeng-amf-wait").start();
        }
    }

    private static void applyLabelText(final Object floor, final String bal) {
        try {
            Field f = findField(floor.getClass(), "totalAssetsAmountLabel");
            if (f == null) {
                reportToServer("AMF_nofield", floor.getClass().getSimpleName());
                return;
            }
            f.setAccessible(true);
            final Object tv = f.get(floor);
            if (tv == null) return;
            final java.lang.reflect.Method post = tv.getClass().getMethod("post", Runnable.class);
            final java.lang.reflect.Method setText =
                    tv.getClass().getMethod("setText", CharSequence.class);
            post.invoke(tv, new Runnable() {
                @Override public void run() {
                    try {
                        setText.invoke(tv, bal);
                        XposedBridge.log("[youzeng] totalAssetsAmountLabel=" + bal);
                        reportToServer("AMF_set", bal);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[youzeng] applyLabelText ex=" + t);
            reportToServer("AMF_applyEx", t.getMessage());
        }
    }

    /* ─── WebView H5 余额注入 ─────────────────────────────── */

    private static volatile boolean wvHookInstalled;

    private static void hookWebViewBalance() {
        if (wvHookInstalled) return;
        wvHookInstalled = true;
        try {
            // Hook addJavascriptInterface：发现 JS Bridge 名称，并钩住所有 @JavascriptInterface 方法的返回值
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebView", null,
                    "addJavascriptInterface", Object.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object obj = param.args[0];
                            final String bridgeName = (String) param.args[1];
                            if (obj == null) return;
                            Class<?> cls = obj.getClass();
                            reportToServer("WV_jsif", bridgeName + "=" + cls.getSimpleName());
                            // 钩住所有 @JavascriptInterface 方法的返回值
                            for (java.lang.reflect.Method m : cls.getMethods()) {
                                if (m.getAnnotation(android.webkit.JavascriptInterface.class) == null) continue;
                                final String methodKey = bridgeName + "." + m.getName();
                                try {
                                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                                        @Override
                                        protected void afterHookedMethod(MethodHookParam hp) {
                                            Object ret = hp.getResult();
                                            if (ret == null) return;
                                            String retStr = ret.toString();
                                            // 只处理含余额特征的返回值
                                            boolean hasLbl = retStr.contains("Lbl_ID") || retStr.contains("Lbl_Val");
                                            boolean hasNum = retStr.length() > 6 &&
                                                    java.util.regex.Pattern.compile("\\d{3,7}\\.\\d{2}").matcher(retStr).find();
                                            if (!hasLbl && !hasNum) return;
                                            String bal = cachedBal;
                                            reportToServer("WV_bridge_ret",
                                                    methodKey + " " + retStr.substring(0, Math.min(300, retStr.length())));
                                            if (bal.isEmpty()) return;
                                            // 按 Lbl_ID 替换
                                            String newRet = replaceLblIdValue(retStr, "_010102", bal);
                                            newRet = replaceLblIdValue(newRet, "_000209", bal);
                                            if (!newRet.equals(retStr)) {
                                                hp.setResult(newRet);
                                                reportToServer("WV_bridge_replaced", methodKey + " bal=" + bal);
                                            }
                                        }
                                    });
                                } catch (Throwable ignored) {}
                            }
                        }
                    });
            reportToServer("WV_jsif_hooked", "ok");
        } catch (Throwable t) {
            reportToServer("WV_jsif_ex", t.getMessage());
        }

        try {
            // Hook loadUrl：拦截 CCB 向 WebView 注入数据的 javascript: URL
            // CCB 模式：javascript:var _ccbCallback=...;var _ccbResult={"result":"...JSON..."};...
            // 按 Lbl_ID 匹配替换，不依赖硬编码余额值
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebView", null,
                    "loadUrl", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String url = (String) param.args[0];
                            if (url == null || !url.startsWith("javascript:")) return;
                            // 诊断：找含有 Lbl_ID 或数字余额格式的 URL
                            if (url.length() > 200) {
                                boolean hasLbl = url.contains("Lbl_ID") || url.contains("Lbl_Val");
                                boolean has010 = url.contains("_010102") || url.contains("_000209");
                                // 报告含 Lbl 信息的 URL（完整内容）
                                if (hasLbl || has010) {
                                    int len = url.length();
                                    reportToServer("WV_lbl_found", "len=" + len + " " +
                                            url.substring(0, Math.min(500, len)));
                                } else {
                                    // 其他长 URL：只上报 txcode 部分（callback 名称里有 txcode）
                                    int cbEnd = url.indexOf(';');
                                    String prefix = cbEnd > 0 ? url.substring(0, Math.min(cbEnd, 120)) : url.substring(0, 80);
                                    reportToServer("WV_jsUrl_type", "len=" + url.length() + " " + prefix);
                                }
                            }
                            String bal = cachedBal;
                            if (bal.isEmpty()) return;
                            // 按 Lbl_ID 替换（_010102=总资产, _000209=总资产备用）
                            String newUrl = replaceLblIdValue(url, "_010102", bal);
                            newUrl = replaceLblIdValue(newUrl, "_000209", bal);
                            if (!newUrl.equals(url)) {
                                param.args[0] = newUrl;
                                reportToServer("WV_replaced", "bal=" + bal + " urlLen=" + url.length());
                            }
                        }
                    });
            reportToServer("WV_loadUrl_hooked", "ok");
        } catch (Throwable t) {
            reportToServer("WV_loadUrl_ex", t.getMessage());
        }
        try {
            // Hook evaluateJavascript：新版 CCB 可能改用此 API 注入数据
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebView", null,
                    "evaluateJavascript", String.class, android.webkit.ValueCallback.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String script = (String) param.args[0];
                            if (script == null || script.length() < 30) return;
                            // 跳过我们自己注入的脚本（避免循环触发）
                            if (script.contains("zzcNodes") || script.contains("__youzeng")) return;
                            String bal = cachedBal;
                            // 诊断：上报含数字余额特征的脚本（来自 CCB 原始代码）
                            boolean hasLbl = script.contains("Lbl_ID") || script.contains("Lbl_Val");
                            boolean hasNum = script.matches("(?s).*\\d{4,7}\\.\\d{2}.*");
                            if (hasLbl || hasNum) {
                                reportToServer("WV_evalJs_found",
                                        "len=" + script.length() + " " +
                                        script.substring(0, Math.min(400, script.length())));
                            }
                            if (bal.isEmpty()) return;
                            // 按 Lbl_ID 替换（同 loadUrl 逻辑）
                            String newScript = replaceLblIdValue(script, "_010102", bal);
                            newScript = replaceLblIdValue(newScript, "_000209", bal);
                            if (!newScript.equals(script)) {
                                param.args[0] = newScript;
                                reportToServer("WV_evalJs_replaced", "bal=" + bal + " len=" + script.length());
                            }
                        }
                    });
            reportToServer("WV_evalJs_hooked", "ok");
        } catch (Throwable t) {
            reportToServer("WV_evalJs_ex", t.getMessage());
        }
        try {
            // Hook shouldInterceptRequest：查看 H5 发出的所有网络请求
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebViewClient", null,
                    "shouldInterceptRequest",
                    android.webkit.WebView.class,
                    android.webkit.WebResourceRequest.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            android.webkit.WebResourceRequest req =
                                    (android.webkit.WebResourceRequest) param.args[1];
                            if (req == null) return;
                            String url = req.getUrl() == null ? "" : req.getUrl().toString();
                            // 只上报非 file:// 且较短的 URL（避免刷屏）
                            if (!url.startsWith("file://") && url.length() > 10) {
                                reportToServer("WV_intercept",
                                        url.length() > 200 ? url.substring(0, 200) : url);
                            }
                        }
                    });
            reportToServer("WV_intercept_hooked", "ok");
        } catch (Throwable t) {
            reportToServer("WV_intercept_ex", t.getMessage());
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "android.webkit.WebViewClient", null,
                    "onPageFinished",
                    android.webkit.WebView.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final android.webkit.WebView wv = (android.webkit.WebView) param.args[0];
                            final String url = (String) param.args[1];
                            if (url == null) return;
                            reportToServer("WV_pgFinish", url.length() > 100 ? url.substring(0, 100) : url);
                            final String bal = cachedBal.isEmpty() ? null : cachedBal;
                            if (bal == null) return;
                            // 立即注入，Observer 会捕获 H5 框架写入余额的瞬间
                            injectBalanceReplaceScript(wv, bal);
                        }
                    });
            reportToServer("WV_pgFinish_hooked", "ok");
        } catch (Throwable t) {
            reportToServer("WV_pgFinish_ex", t.getMessage());
        }
    }

    /**
     * 注入 JS 监听 H5 的 XHR/fetch 请求，把含余额特征的请求 URL 和响应上报。
     */
    private static void injectXhrMonitorScript(final android.webkit.WebView wv) {
        final String js =
            "(function(){"
            + "if(window.__youzeng_xhr_hooked) return;"
            + "window.__youzeng_xhr_hooked=true;"
            + "var orig=window.XMLHttpRequest;"
            + "function YzXHR(){"
            + "  this._xhr=new orig();"
            + "  var self=this;"
            + "  ['onreadystatechange','onload','onerror','onabort'].forEach(function(k){"
            + "    Object.defineProperty(self,k,{set:function(v){self._xhr[k]=v;},get:function(){return self._xhr[k];}});"
            + "  });"
            + "  ['readyState','status','statusText','response','responseText','responseType','responseURL'].forEach(function(k){"
            + "    Object.defineProperty(self,k,{get:function(){try{return self._xhr[k];}catch(e){return null;}}});"
            + "  });"
            + "}"
            + "YzXHR.prototype.open=function(m,u){this._url=u;this._xhr.open(m,u);};"
            + "YzXHR.prototype.send=function(d){"
            + "  var self=this,url=this._url||'';"
            + "  var origLoad=this._xhr.onload;"
            + "  this._xhr.onload=function(){"
            + "    var rsp='';"
            + "    try{rsp=self._xhr.responseText;}catch(e){}"
            + "    if(rsp.length>10 && (/\\d{3,7}\\.\\d{2}/.test(rsp)||/Lbl/.test(rsp))){"
            + "      if(window.AppWebViewInterface && window.AppWebViewInterface.reportBalance){"
            + "        window.AppWebViewInterface.reportBalance(url+'||'+rsp.substring(0,400));"
            + "      }"
            + "    }"
            + "    if(origLoad) origLoad.call(this);"
            + "  };"
            + "  this._xhr.send(d);"
            + "};"
            + "['setRequestHeader','abort','getAllResponseHeaders','getResponseHeader'].forEach(function(k){"
            + "  YzXHR.prototype[k]=function(){return this._xhr[k].apply(this._xhr,arguments);};"
            + "});"
            + "window.XMLHttpRequest=YzXHR;"
            + "})()";
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            wv.evaluateJavascript(js, null);
        } else {
            wv.post(new Runnable() {
                @Override public void run() { wv.evaluateJavascript(js, null); }
            });
        }
    }

    /**
     * 注入 JS：仅在有"总资产"标签的页面生效，每次 onPageFinished 断开旧 Observer 重装。
     * 不含"总资产"的页面（如第二张卡明细）自动跳过。
     * 无延迟：立即安装 MutationObserver 监听 document.body，
     * H5 框架写入余额的瞬间就替换，用户看不到原始值。
     */
    private static void injectBalanceReplaceScript(
            final android.webkit.WebView wv, final String bal) {
        final String escapedBal = bal.replace("'", "\\'");
        final String js =
            "(function(){"
            + "var bal='" + escapedBal + "';"
            + "var amtRe=/^\\d{1,8}\\.\\d{2}$|^\\d{1,3}(,\\d{3})*\\.\\d{2}$/;"
            // ── 断开旧 Observer ──
            + "if(window.__yzObs){window.__yzObs.disconnect();window.__yzObs=null;}"
            + "window.__yzBal=bal;"
            // ── 找指定标签附近的第一个金额节点 ──
            + "function findAmtNear(label,maxUp){"
            + "  var allTxt=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false);"
            + "  var n;"
            + "  while((n=allTxt.nextNode())){"
            + "    if(n.textContent.indexOf(label)>=0){"
            + "      var cont=n.parentElement;"
            + "      for(var j=0;j<maxUp&&cont;j++){"
            + "        var nw=document.createTreeWalker(cont,NodeFilter.SHOW_TEXT,null,false);"
            + "        var nd;"
            + "        while((nd=nw.nextNode())){"
            + "          if(amtRe.test(nd.textContent.trim()))return{node:nd,cont:cont};"
            + "        }"
            + "        cont=cont.parentElement;"
            + "      }"
            + "    }"
            + "  }"
            + "  return null;"
            + "}"
            // ── 替换函数（每次 Observer 触发时调用）──
            + "window.__yzReplace=function(){"
            + "  var b=window.__yzBal;"
            // 如果切换到"账户总览"页则停止
            + "  if(document.body.textContent.indexOf('\\u8d22\\u5bcc\\u5168\\u666f')>=0)return 0;"
            + "  var cnt=0;"
            + "  function fmtAmt(nb){"
            + "    var n=parseFloat(String(nb).replace(/,/g,''));"
            + "    return n.toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:2});"
            + "  }"
            + "  var bFmt=fmtAmt(b);"
            + "  var r1=findAmtNear('\\u603b\\u8d44\\u4ea7',4);"    // 总资产
            + "  if(r1&&r1.node.textContent.trim()!==bFmt){r1.node.textContent=bFmt;cnt++;}"
            + "  var r2=findAmtNear('\\u6570\\u5b57\\u4eba\\u6c11\\u5e01',3);" // 数字人民币
            + "  if(r2&&r2.node.textContent.trim()!=='0.00'){r2.node.textContent='0.00';cnt++;}"
            + "  var r3=findAmtNear('\\u53ef\\u7528\\u4f59\\u989d',5);" // 可用余额
            + "  if(r3&&r3.node.textContent.trim()!==bFmt){r3.node.textContent=bFmt;cnt++;}"
            + "  if(typeof window.ccbOtherPageRefreshAssetsBalance==='function'){"
            + "    try{window.ccbOtherPageRefreshAssetsBalance({totalAssets:b,zzcZCNum:b});}catch(e){}"
            + "  }"
            + "  return cnt;"
            + "};"
            // ── 立即执行一次（处理已有内容） ──
            + "var immediateFound=window.__yzReplace();"
            // ── 立即安装 document.body 级别 Observer，捕获 H5 框架写入余额的瞬间 ──
            + "window.__yzObs=new MutationObserver(function(){"
            // 如切换到账户总览等后端 mock 页面，自动断开
            + "  if(document.body.textContent.indexOf('\\u8d22\\u5bcc\\u5168\\u666f')>=0){"
            + "    window.__yzObs.disconnect();window.__yzObs=null;return;"
            + "  }"
            + "  window.__yzReplace();"
            + "});"
            + "window.__yzObs.observe(document.body,{subtree:true,characterData:true,childList:true});"
            + "return immediateFound;"
            + "})()";
        // 立即注入，不加任何延迟
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            wv.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    reportToServer("WV_js_replaced", "found=" + value + " bal=" + bal);
                }
            });
        } else {
            wv.post(new Runnable() {
                @Override public void run() {
                    wv.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            reportToServer("WV_js_replaced", "found=" + value + " bal=" + bal);
                        }
                    });
                }
            });
        }
    }

    /**
     * 在 CCB WebView JS 注入 URL 中，按 Lbl_ID 找到对应 Lbl_Val 并替换为 newVal。
     * URL 内 JSON 被转义为 \"key\":\"value\" 格式。
     */
    private static String replaceLblIdValue(String url, String lblId, String newVal) {
        // 在 URL 的 JSON 转义格式里找 \"Lbl_ID\":\"lblId\"
        String idMarker = "\\\"Lbl_ID\\\":\\\"" + lblId + "\\\"";
        int idPos = url.indexOf(idMarker);
        if (idPos < 0) return url;

        String valMarker = "\\\"Lbl_Val\\\":\\\"";
        // 找当前对象边界（向前找 { ，向后找 }）
        int objStart = url.lastIndexOf("{", idPos);
        int objEnd   = url.indexOf("}", idPos);
        if (objEnd < 0) objEnd = url.length();

        // 先在 id 后面找 Lbl_Val，再在 id 前面找
        int vmPos = url.indexOf(valMarker, idPos);
        if (vmPos < 0 || vmPos > objEnd) {
            vmPos = (objStart >= 0) ? url.indexOf(valMarker, objStart) : -1;
            if (vmPos < 0 || vmPos > objEnd) return url;
        }

        int valueStart = vmPos + valMarker.length();
        int valueEnd   = url.indexOf("\\\"", valueStart);
        if (valueEnd < 0) return url;

        return url.substring(0, valueStart) + newVal + url.substring(valueEnd);
    }

    /* 诊断上报已关闭：明细/搜索页每个 WebView 回调都打服务器会卡死 APP */
    private static void reportToServer(final String tag, final String val) {
    }
}
