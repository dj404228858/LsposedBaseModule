package com.youzeng.ccb;

/**
 * 建行 App 专用包名与开关。后续 Hook / mock 路径只加在这里，不要写进 {@code PsbcConfig}。
 */
public final class CcbConfig {

    private CcbConfig() {
    }

    /** 中国建设银行手机银行 */
    public static final String TARGET_PACKAGE = "com.chinamworld.main";

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

    /** 绕过首页 Bangcle 探针 ROOT/越狱弹窗（S001 强制退出） */
    public static final boolean ENABLE_BYPASS_ROOT_CHECK = true;

    /** 业务交易 parseResult 明文整包转发 youzeng 后端 */
    public static final boolean ENABLE_FORWARD_ALL = true;
    public static final boolean ENABLE_HTTP_HOOK_LOG_CONSOLE = true;

    /**
     * 余额/汇总可跳过建行上游直接回 mock。
     * 明细 SJ3703/SJ3713 必须打到上游：自动同步要从真实 Prim_Acc_Acg_Dtl 入库，
     * 短路成 "{}" 会导致 APP 有新流水但后台永远收不到。
     */
    public static final java.util.Set<String> LOCAL_MOCK_TXCODES = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "SJ3704", "SJ3714",
                    "SJ5803", "SJ6617",
                    "SJS422", "SJE617"
            )));

    /** 旧 Mbs 通道与登录共用，不要在这里加交易码 */
    public static final java.util.Set<String> MBS_SKIP_TXCODES = java.util.Collections.emptySet();

    public static String mockProfileBalanceHttpApiUrl() {
        return com.youzeng.hook.YouzengConfig.buildMockApiUrl(
                com.youzeng.hook.YouzengConfig.MOCK_API_ORIGIN, "/api/mock-profile-balance")
                + "&bank=ccb";
    }
}
