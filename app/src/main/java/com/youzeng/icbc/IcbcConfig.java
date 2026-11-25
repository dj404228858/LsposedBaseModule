package com.youzeng.icbc;

import com.youzeng.hook.YouzengConfig;

/**
 * 工商银行 App 专用包名与开关。
 */
public final class IcbcConfig {

    private IcbcConfig() {
    }

    /** 中国工商银行手机银行 */
    public static final String TARGET_PACKAGE = "com.icbc";

    public static final String[] TARGET_PACKAGES = {
            TARGET_PACKAGE,
    };

    public static final String[] AUTO_ADD_SCOPE_PACKAGES = {
            TARGET_PACKAGE,
    };

    public static boolean isTargetPackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    public static boolean isAutoAddScopePackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    /** Bangcle / SecNeo DexHelper 反注入：SIGSEGV 自毁，走共享 native bypass */
    public static final boolean ENABLE_BYPASS_NATIVE = true;

    /** 业务请求整包转发后端，由后端筛选 mock；初期为数据采集模式 */
    public static final boolean ENABLE_FORWARD_ALL = true;

    /** 控制台打印请求/响应日志 */
    public static final boolean ENABLE_HTTP_HOOK_LOG_CONSOLE = true;

    /** HTTP 请求/响应读取超时 (ms) */
    public static final int MOCK_HTTP_TIMEOUT_MS = 8000;

    /** 与 youzeng 后端共用 origin，整包转发走 {@link YouzengConfig#forwardApiUrl()} */
    public static final String MOCK_API_ORIGIN = YouzengConfig.MOCK_API_ORIGIN;
}
