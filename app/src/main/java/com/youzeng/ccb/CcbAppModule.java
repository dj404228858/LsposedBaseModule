package com.youzeng.ccb;

import com.youzeng.module.AppModule;
import com.youzeng.hook.YouzengForwardClient;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 建行业务入口。具体 Hook 实现后在 {@link #install} 里按邮储同样方式挂上即可。
 */
public final class CcbAppModule implements AppModule {

    @Override
    public String id() {
        return "ccb";
    }

    @Override
    public String displayName() {
        return "建设银行";
    }

    @Override
    public String[] targetPackages() {
        return CcbConfig.TARGET_PACKAGES;
    }

    @Override
    public String[] autoAddScopePackages() {
        return CcbConfig.AUTO_ADD_SCOPE_PACKAGES;
    }

    @Override
    public boolean handles(String packageName) {
        return CcbConfig.isTargetPackage(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("youzeng: install module=" + id() + " pkg=" + lpparam.packageName);
        YouzengForwardClient.prefetchIgnoreList();
        CcbNativeBridge.loadAndInstall();
        YouzengCcbRootBypassHook.install(lpparam);
        YouzengCcbScreenCaptureHook.install(lpparam);
        YouzengCcbNetworkHook.install(lpparam);
        YouzengCcbStatementHook.install(lpparam.classLoader);
    }
}
