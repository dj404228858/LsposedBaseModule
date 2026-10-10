package com.youzeng.module;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 单个目标 App 的业务模块。邮政、建行各自实现，互不引用对方的 Hook / 配置。
 */
public interface AppModule {

    /** 稳定标识，如 {@code psbc}、{@code ccb} */
    String id();

    String displayName();

    /** 本模块会注入的包名（含仅 hook、不自动勾选作用域的包） */
    String[] targetPackages();

    /** 卸载再装时自动追加的作用域，通常比 {@link #targetPackages()} 更窄 */
    String[] autoAddScopePackages();

    boolean handles(String packageName);

    void install(XC_LoadPackage.LoadPackageParam lpparam);
}
