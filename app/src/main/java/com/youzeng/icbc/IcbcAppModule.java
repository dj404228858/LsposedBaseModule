package com.youzeng.icbc;

import com.youzeng.hook.YouzengBankBypassHook;
import com.youzeng.hook.YouzengForwardClient;
import com.youzeng.module.AppModule;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 工商银行业务入口。
 * <ul>
 *   <li>native Bangcle/SecNeo DexHelper bypass 与其他银行共用 {@link YouzengBankBypassHook}。</li>
 *   <li>网络请求通过 {@link YouzengIcbcNetworkHook} 整包转发至 youzeng 后端。</li>
 * </ul>
 */
public final class IcbcAppModule implements AppModule {

    @Override
    public String id() {
        return "icbc";
    }

    @Override
    public String displayName() {
        return "工商银行";
    }

    @Override
    public String[] targetPackages() {
        return IcbcConfig.TARGET_PACKAGES;
    }

    @Override
    public String[] autoAddScopePackages() {
        return IcbcConfig.AUTO_ADD_SCOPE_PACKAGES;
    }

    @Override
    public boolean handles(String packageName) {
        return IcbcConfig.isTargetPackage(packageName);
    }

    @Override
    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("youzeng: install module=" + id() + " pkg=" + lpparam.packageName);
        // 1. Native bypass（Bangcle / SecNeo SIGSEGV 检测）
        if (IcbcConfig.ENABLE_BYPASS_NATIVE) {
            YouzengBankBypassHook.install(lpparam);
        }
        // 2. 网络转发（OkHttp 拦截器）
        if (IcbcConfig.ENABLE_FORWARD_ALL) {
            YouzengForwardClient.prefetchIgnoreList();
            YouzengIcbcNetworkHook.install(lpparam);
        }
    }
}
