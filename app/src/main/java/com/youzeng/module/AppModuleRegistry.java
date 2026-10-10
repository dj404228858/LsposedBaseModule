package com.youzeng.module;

import com.youzeng.ccb.CcbAppModule;
import com.youzeng.psbc.PsbcAppModule;

/**
 * Hook 总表，只在 Xposed 入口使用。新增银行：实现 {@link AppModule}，在 {@link #MODULES} 登记一行。
 */
public final class AppModuleRegistry {

    private static final AppModule[] MODULES = {
            new PsbcAppModule(),
            new CcbAppModule(),
    };

    private AppModuleRegistry() {
    }

    public static AppModule[] all() {
        return MODULES;
    }

    public static AppModule findByPackage(String packageName) {
        if (packageName == null) {
            return null;
        }
        for (AppModule module : MODULES) {
            if (module.handles(packageName)) {
                return module;
            }
        }
        return null;
    }
}
