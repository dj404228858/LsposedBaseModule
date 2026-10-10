package com.youzeng;

import com.youzeng.hook.YouzengConfig;
import com.youzeng.hook.YouzengModulePrefs;
import com.youzeng.module.AppModule;
import com.youzeng.module.AppModuleRegistry;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedHookModule implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("youzeng: loadPackage " + lpparam.packageName);
        if (YouzengConfig.MODULE_PACKAGE.equals(lpparam.packageName)) {
            YouzengModulePrefs.installForModuleProcess();
            return;
        }
        AppModule module = AppModuleRegistry.findByPackage(lpparam.packageName);
        if (module == null) {
            return;
        }
        YouzengModulePrefs.installForTargetProcess(lpparam);
        module.install(lpparam);
    }
}
