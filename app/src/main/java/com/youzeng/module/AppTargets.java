package com.youzeng.module;

import com.youzeng.ccb.CcbConfig;
import com.youzeng.psbc.PsbcConfig;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 各业务模块的包名目录。不引用 Xposed，可供模块 App 进程和 {@code app_process} 使用。
 */
public final class AppTargets {

    private AppTargets() {
    }

    public static boolean isTargetPackage(String packageName) {
        return PsbcConfig.isTargetPackage(packageName) || CcbConfig.isTargetPackage(packageName);
    }

    public static boolean isAutoAddScopePackage(String packageName) {
        return PsbcConfig.isAutoAddScopePackage(packageName) || CcbConfig.isAutoAddScopePackage(packageName);
    }

    public static String[] autoAddScopePackages() {
        return merge(PsbcConfig.AUTO_ADD_SCOPE_PACKAGES, CcbConfig.AUTO_ADD_SCOPE_PACKAGES);
    }

    public static String[] recommendedScopePackages() {
        return autoAddScopePackages();
    }

    public static String[] allTargetPackages() {
        return merge(PsbcConfig.TARGET_PACKAGES, CcbConfig.TARGET_PACKAGES);
    }

    private static String[] merge(String[]... groups) {
        Set<String> out = new LinkedHashSet<>();
        for (String[] group : groups) {
            for (String pkg : group) {
                if (pkg != null && !pkg.isEmpty()) {
                    out.add(pkg);
                }
            }
        }
        return out.toArray(new String[0]);
    }
}
