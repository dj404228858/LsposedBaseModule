package com.youzeng.ccb;

import android.view.Window;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 绕过建行 APP 防录屏限制。
 *
 * <p>建行在以下地方调用了 {@code getWindow().addFlags(8192)}（即
 * {@code WindowManager.LayoutParams.FLAG_SECURE = 0x2000}），导致
 * scrcpy / 录屏工具黑屏：
 *
 * <ul>
 *   <li>com.ccb.framework.security.login.internal.view.BaseLoginActivity（手势/密码登录页）
 *   <li>com.ccb.framework.ui.widget.webview.CcbWebViewActivity / CcbBankeWebViewActivity
 *   <li>com.ccb.microesafe.SAFE.MicroESafeLib（安全键盘）
 *   <li>com.ccb.loan.quickloan.view.QuickLoanApplyConfirmActivity2 / InputActivity2
 *   <li>com.ccb.myaccount.qrcode.QrCodeHelper / CcbAiOneWordQrCodeHelper
 *   <li>com.finogeeks.lib.applet.* 小程序容器
 * </ul>
 *
 * <p>最简单的整体修复：Hook {@link Window#addFlags(int)}，在 before 里把
 * {@code FLAG_SECURE} 位从参数中抹去，这样所有 Activity/Dialog/WebView
 * 都不会设上防录屏标志，不需要逐类枚举。
 */
public final class YouzengCcbScreenCaptureHook {

    /** WindowManager.LayoutParams.FLAG_SECURE = 0x00002000 */
    private static final int FLAG_SECURE = 0x2000;

    private static volatile boolean installed = false;

    private YouzengCcbScreenCaptureHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!CcbConfig.isTargetPackage(lpparam.packageName)) {
            return;
        }
        if (installed) {
            return;
        }
        installed = true;

        try {
            // Hook android.view.Window.addFlags(int)
            // 这是系统类，直接用类名即可，不依赖 lpparam.classLoader
            XposedHelpers.findAndHookMethod(Window.class, "addFlags", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int flags = (int) param.args[0];
                            if ((flags & FLAG_SECURE) != 0) {
                                // 去掉 FLAG_SECURE 位，保留其余 flag
                                param.args[0] = flags & ~FLAG_SECURE;
                                XposedBridge.log("[youzeng][ccb] Window.addFlags: stripped FLAG_SECURE, original=0x"
                                        + Integer.toHexString(flags)
                                        + " new=0x" + Integer.toHexString((int) param.args[0]));
                            }
                        }
                    });
            XposedBridge.log("[youzeng][ccb] ScreenCaptureHook: Window.addFlags hooked (FLAG_SECURE bypass)");
        } catch (Throwable t) {
            XposedBridge.log("[youzeng][ccb] ScreenCaptureHook: addFlags hook failed: " + t);
        }

        try {
            // 保险起见同时 hook Window.setFlags(int, int)
            // 某些路径调用 setFlags(FLAG_SECURE, FLAG_SECURE) 而不是 addFlags
            XposedHelpers.findAndHookMethod(Window.class, "setFlags", int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int flags = (int) param.args[0];
                            int mask  = (int) param.args[1];
                            if ((flags & FLAG_SECURE) != 0 || (mask & FLAG_SECURE) != 0) {
                                param.args[0] = flags & ~FLAG_SECURE;
                                param.args[1] = mask  & ~FLAG_SECURE;
                                XposedBridge.log("[youzeng][ccb] Window.setFlags: stripped FLAG_SECURE");
                            }
                        }
                    });
            XposedBridge.log("[youzeng][ccb] ScreenCaptureHook: Window.setFlags hooked");
        } catch (Throwable t) {
            XposedBridge.log("[youzeng][ccb] ScreenCaptureHook: setFlags hook failed: " + t);
        }
    }
}
