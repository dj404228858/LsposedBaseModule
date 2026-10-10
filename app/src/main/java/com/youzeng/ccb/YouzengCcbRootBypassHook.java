package com.youzeng.ccb;

import android.app.Activity;
import android.os.Bundle;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 建行 9.6.0 首页 ROOT/越狱弹窗。
 * Bangcle 解密后 {@code MainActivity.onCreate} 时业务 ClassLoader 才有 {@code RiskManager}。
 */
public final class YouzengCcbRootBypassHook {

    private static volatile boolean installed;

    private YouzengCcbRootBypassHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!CcbConfig.ENABLE_BYPASS_ROOT_CHECK) {
            return;
        }
        if (!CcbConfig.isTargetPackage(lpparam.packageName)) {
            return;
        }
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (installed) {
                    return;
                }
                if (!"com.ccb.start.MainActivity".equals(param.thisObject.getClass().getName())) {
                    return;
                }
                installHooks(param.thisObject.getClass().getClassLoader());
                installed = true;
                XposedBridge.log("youzeng: ccb root bypass hooks installed");
            }
        });
    }

    private static void installHooks(ClassLoader cl) {
        XC_MethodReplacement nop = XC_MethodReplacement.DO_NOTHING;
        XposedHelpers.findAndHookMethod("com.ccb.start.utils.RiskManager", cl,
                "checkRoot", Activity.class, nop);
        XposedHelpers.findAndHookMethod("com.ccb.start.utils.RiskNewUtils", cl,
                "check", Activity.class, nop);
        XposedHelpers.findAndHookMethod("com.ccb.start.RiskUtils", cl,
                "check", Activity.class, nop);
    }
}
