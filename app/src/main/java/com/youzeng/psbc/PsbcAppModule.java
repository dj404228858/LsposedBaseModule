package com.youzeng.psbc;

import com.youzeng.module.AppModule;
import com.youzeng.hook.YouzengForwardClient;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class PsbcAppModule implements AppModule {

    @Override
    public String id() {
        return "psbc";
    }

    @Override
    public String displayName() {
        return "邮储银行";
    }

    @Override
    public String[] targetPackages() {
        return PsbcConfig.TARGET_PACKAGES;
    }

    @Override
    public String[] autoAddScopePackages() {
        return PsbcConfig.AUTO_ADD_SCOPE_PACKAGES;
    }

    @Override
    public boolean handles(String packageName) {
        return PsbcConfig.isTargetPackage(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("youzeng: install module=" + id() + " pkg=" + lpparam.packageName);
        YouzengForwardClient.prefetchIgnoreList();
        YouzengPsbcUpgradeBlockHook.install(lpparam);
        YouzengPsbcNetworkHook.install(lpparam);
    }
}
