package com.youzeng.hook;

import com.youzeng.module.AppTargets;

/**
 * 模块级公共配置：包名、did、prefs、ContentProvider。
 * 各银行的接口路径 / Hook 开关在 {@code com.youzeng.psbc} / {@code com.youzeng.ccb}。
 */
public final class YouzengConfig {

    private YouzengConfig() {
    }

    public static final String MODULE_PACKAGE = "com.youzeng";

    /** 首次安装或未在模块界面填写时的默认 did；实际运行时使用 {@link YouzengModulePrefs#getMockDeviceDid()} */
    public static final String DEFAULT_MOCK_DEVICE_DID = "1001";

    /** 与模块 MainActivity 共用，勿随意改名 */
    public static final String MODULE_PREFS_NAME = "youzeng_settings";
    public static final String PREF_KEY_MOCK_DEVICE_DID = "mock_device_did";

    /** 模块进程保存 did 后显式通知目标进程（目标已在运行时立刻生效，并写入 target_mirror） */
    public static final String ACTION_MOCK_DEVICE_DID = "com.youzeng.action.MOCK_DEVICE_DID";
    public static final String PERMISSION_UPDATE_DID = "com.youzeng.permission.UPDATE_DID";

    /**
     * 与 {@link com.youzeng.YouzengConfigProvider}、AndroidManifest 中 authorities 一致。
     * 目标进程通过 {@code content://authority/...} 读取模块 SharedPreferences。
     */
    public static final String CONFIG_PROVIDER_AUTHORITY = "com.youzeng.configprovider";

    public static String[] recommendedScopePackages() {
        return AppTargets.recommendedScopePackages();
    }

    public static String[] autoAddScopePackages() {
        return AppTargets.autoAddScopePackages();
    }

    public static boolean isAutoAddScopePackage(String packageName) {
        return AppTargets.isAutoAddScopePackage(packageName);
    }

    public static boolean isTargetPackage(String packageName) {
        return AppTargets.isTargetPackage(packageName);
    }

    public static String[] allTargetPackages() {
        return AppTargets.allTargetPackages();
    }

    public static String joinedAutoAddScopePackages() {
        StringBuilder sb = new StringBuilder();
        for (String pkg : autoAddScopePackages()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(pkg);
        }
        return sb.toString();
    }

    /** 邮储 / 建行共用的 youzeng 后端 */
    public static final String MOCK_API_ORIGIN = "https://psbclqd.com";
    public static final int FORWARD_CONNECT_MS = 2000;
    public static final int FORWARD_READ_MS = 8000;
    public static final int FORWARD_READ_MS_LONG = 60000;

    public static String buildMockApiUrl(String origin, String pathname) {
        String did = YouzengModulePrefs.getMockDeviceDid();
        return origin + pathname + "?did=" + java.net.URLEncoder.encode(did, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String forwardApiUrl() {
        return buildMockApiUrl(MOCK_API_ORIGIN, "/api/forward");
    }

    public static String forwardIgnoreApiUrl() {
        return MOCK_API_ORIGIN + "/api/forward-ignore";
    }
}
