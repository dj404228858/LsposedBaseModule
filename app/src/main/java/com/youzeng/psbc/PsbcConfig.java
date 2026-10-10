package com.youzeng.psbc;

import com.youzeng.hook.YouzengConfig;

/**
 * 邮储 App 专用开关、接口路径、mock URL。不要把建行字段放进来。
 */
public final class PsbcConfig {

    private PsbcConfig() {
    }

    public static final String TARGET_PACKAGE = "com.yitong.mbank.psbc";

    /** 仅 hook 用；不自动勾选作用域 */
    public static final String TARGET_PACKAGE_ALT = "com.yitong.mbankpsbc";

    public static final String[] TARGET_PACKAGES = {
            TARGET_PACKAGE,
            TARGET_PACKAGE_ALT,
    };

    public static final String[] AUTO_ADD_SCOPE_PACKAGES = {
            TARGET_PACKAGE,
    };

    public static boolean isTargetPackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName) || TARGET_PACKAGE_ALT.equals(packageName);
    }

    public static boolean isAutoAddScopePackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName);
    }

    /**
     * 与 frida/c.js 中 ClassLoader.loadClass 一致：当该全限定名被加载时，再挂钩 DECMsgDialog.onCreate 与
     * DeviceEnvironmentCheck$1$1.run。若你方 APK 混淆不同，请改下面两个常量。
     */
    /** 拦截启动时自动版本检查、「发现新版本」弹窗及静默下载 */
    public static final boolean ENABLE_BLOCK_APP_UPGRADE = true;

    public static final boolean ENABLE_CLASSLOADER_GUARD_HOOKS = true;
    public static final String GUARD_DEC_MSG_DIALOG_CLASS = "com.a.b.c.DECMsgDialog";
    public static final String GUARD_DEVICE_ENV_RUNNABLE_CLASS = "com.a.b.c.DeviceEnvironmentCheck$1$1";

    public static final boolean ENABLE_HTTP_HOOK_LOG_CONSOLE = true;
    public static final boolean ENABLE_HTTP_HOOK_LOG_FILE = false;

    /** 业务 API 解密后整包转发后端，由后端筛选 mock；不再在客户端按 URL 逐个采集 */
    public static final boolean ENABLE_FORWARD_ALL = true;

    public static final boolean ENABLE_XHX_HTTP_MOCK = true;
    public static final boolean ENABLE_TX_TRSF_HTTP_MOCK = true;
    /** qryTopDtlList/T080492：收支分析「Top 明细列表」 */
    public static final boolean ENABLE_QRY_TOP_DTL_LIST_HTTP_MOCK = true;
    /** qryTransDetail/T080245：账单页搜索（仅支出/仅收入/关键词等） */
    public static final boolean ENABLE_QRY_TRANS_DETAIL_HTTP_MOCK = true;
    public static final boolean ENABLE_TX_DETAIL_HTTP_MOCK = true;
    public static final boolean ENABLE_PROFILE_BALANCE_HTTP_MOCK = true;
    /** qryAccDtl/T080002：改余额与 openaccDate（字符串原位替换，避免 JSONObject 破坏 URL 斜杠） */
    public static final boolean ENABLE_QRY_ACC_DTL_HTTP_MOCK = true;
    /** pageDataQuery/T020104「我的」页：按资料星级覆盖 custLvl / custLvlCode / starLvlIcon */
    public static final boolean ENABLE_CUST_LVL_HTTP_MOCK = true;
    /** initQyzq/T070708 权益区：按资料星级覆盖 custCurStarLvl（1 起算，不改 custLvl） */
    public static final boolean ENABLE_INIT_QYZQ_HTTP_MOCK = true;
    /** qryTransAccBal/T080770：转发并同步 avalBal 为资料余额 */
    public static final boolean ENABLE_QRY_TRANS_ACC_BAL_HTTP_MOCK = true;
    public static final boolean ENABLE_HISTORY_TX_DETAIL_APPLY_HTTP_MOCK = true;
    public static final boolean ENABLE_QUERY_APPLY_SCHEDULE_HTTP_MOCK = true;
    public static final boolean ENABLE_INCM_EPN_ANALY_SUM_HTTP_MOCK = true;
    public static final boolean ENABLE_MY_INCM_EPN_HTTP_MOCK = true;
    public static final boolean ENABLE_TX_INCM_EPN_ANALY_SUM_HTTP_MOCK = true;
    /** qryImexSumData/T080764：收支分析页趋势/月度汇总（totIcmAmt/totExpnAmt） */
    public static final boolean ENABLE_IMEX_SUM_DATA_HTTP_MOCK = true;

    public static final String MOCK_XHX_API = "api/account/xhxTxDtlQry/T080232";
    public static final String MOCK_TX_TRSF_API = "api/account/txTrsfQry/T080233";
    public static final String MOCK_QRY_TOP_DTL_LIST_API = "api/account/qryTopDtlList/T080492";
    public static final String MOCK_QRY_TRANS_DETAIL_API = "api/account/qryTransDetail/T080245";
    public static final String MOCK_TX_DETAIL_API = "api/account/qryTxDtlDtlInfo/T080236";
    public static final String MOCK_HISTORY_TX_DETAIL_APPLY_API = "api/account/historyTransactionDetailApply/T080331";
    public static final String MOCK_QUERY_APPLY_SCHEDULE_API = "api/account/queryApplySchedule/T080332";
    public static final String MOCK_INCM_EPN_ANALY_SUM_API = "api/account/qryIncmEpnAnalySum/T080239";
    public static final String MOCK_MY_INCM_EPN_API = "api/account/qryMyIncmEpn/T080505";
    public static final String MOCK_TX_INCM_EPN_ANALY_SUM_API = "api/account/qryTxIncmEpnAnalySum/T080240";
    public static final String MOCK_IMEX_SUM_DATA_API = "api/account/qryImexSumData/T080764";
    public static final String MOCK_QRY_ACC_DTL_API = "api/account/qryAccDtl/T080002";
    public static final String MOCK_CUST_LVL_API = "api/public/pageDataQuery/T020104";
    public static final String MOCK_INIT_QYZQ_API = "api/life/initQyzq/T070708";
    public static final String MOCK_QRY_TRANS_ACC_BAL_API = "api/account/qryTransAccBal/T080770";

    // 与 youzeng 后端共用。整包转发走 {@link YouzengConfig#forwardApiUrl()}。
    public static final String MOCK_API_ORIGIN = YouzengConfig.MOCK_API_ORIGIN;

    public static final int MOCK_XHX_HTTP_TIMEOUT_MS = 3000;
    public static final int MOCK_HISTORY_TX_DETAIL_APPLY_HTTP_TIMEOUT_MS = 60000;

    public static final String[] HOOK_URL_FILTER_SUBSTRINGS = {"api/secmon/appsdk"};

    public static final int HOOK_ONCE_MAX = 500;

    public static String buildMockApiUrl(String pathname) {
        return YouzengConfig.buildMockApiUrl(MOCK_API_ORIGIN, pathname);
    }

    public static String mockXhxHttpApiUrl() {
        return buildMockApiUrl("/api/mock-xhx");
    }

    public static String mockTxTrsfHttpApiUrl() {
        return buildMockApiUrl("/api/mock-tx-trsf");
    }

    public static String mockQryTopDtlListHttpApiUrl() {
        return buildMockApiUrl("/api/mock-top-dtl-list");
    }

    public static String mockQryTransDetailHttpApiUrl() {
        return buildMockApiUrl("/api/mock-qry-trans-detail");
    }

    public static String mockTxDetailHttpApiUrl() {
        return buildMockApiUrl("/api/mock-tx-detail");
    }

    public static String mockHistoryTxApplyHttpApiUrl() {
        return buildMockApiUrl("/api/mock-history-tx-apply");
    }

    public static String mockProfileBalanceHttpApiUrl() {
        return buildMockApiUrl("/api/mock-profile-balance");
    }

    public static String mockQryAccDtlHttpApiUrl() {
        return buildMockApiUrl("/api/mock-qry-acc-dtl");
    }

    public static String mockCustLvlHttpApiUrl() {
        return buildMockApiUrl("/api/mock-cust-lvl");
    }

    public static String mockInitQyzqHttpApiUrl() {
        return buildMockApiUrl("/api/mock-init-qyzq");
    }

    public static String mockQryTransAccBalHttpApiUrl() {
        return buildMockApiUrl("/api/mock-qry-trans-acc-bal");
    }

    public static String mockQueryApplyScheduleHttpApiUrl() {
        return buildMockApiUrl("/api/mock-query-apply-schedule");
    }

    public static String mockIncmEpnAnalySumHttpApiUrl() {
        return buildMockApiUrl("/api/mock-incm-epn-analy-sum");
    }

    public static String mockTxIncmEpnAnalySumHttpApiUrl() {
        return buildMockApiUrl("/api/mock-tx-incm-epn-analy-sum");
    }

    public static String mockImexSumDataHttpApiUrl() {
        return buildMockApiUrl("/api/mock-imex-sum-data");
    }

    public static String mockMyIncmEpnHttpApiUrl() {
        return buildMockApiUrl("/api/mock-my-incm-epn");
    }

    public static String mockDeviceInfoHttpApiUrl() {
        return buildMockApiUrl("/api/device-info");
    }

    public static String mockCollectTxHttpApiUrl() {
        return buildMockApiUrl("/api/device-collect-transactions");
    }
}
