package com.youzeng.ccb;

import de.robv.android.xposed.XposedBridge;

/** 建行进程里加载 native，补丁 Bangcle DexHelper / 南天 verifyApp。 */
public final class CcbNativeBridge {

    private static volatile boolean loaded;

    private CcbNativeBridge() {
    }

    public static native void installBangcleBypass();

    public static void loadAndInstall() {
        if (loaded) {
            return;
        }
        System.loadLibrary("youzengccb");
        installBangcleBypass();
        loaded = true;
        XposedBridge.log("youzeng: ccb bangcle native bypass loaded");
    }
}
