package com.youzeng.psbc;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

import com.youzeng.hook.YouzengConfig;

import de.robv.android.xposed.XposedHelpers;

/**
 * 对应 c.js 中的 JSON 解析、URL 判断、HttpURLConnection 拉 mock、余额字段改写等纯 Java 逻辑
 * （交易列表 XHX / 交易详情 TX_DETAIL 的 query 解析与脚本一致）。
 */
public final class YouzengPsbcHelpers {

    private static final String TAG = "youzeng";

    private YouzengPsbcHelpers() {
    }

    public static void log(String msg) {
        if (PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE) {
            Log.i(TAG, msg);
        }
    }

    public static boolean hookHttpLogAnyEnabled() {
        return PsbcConfig.ENABLE_HTTP_HOOK_LOG_CONSOLE || PsbcConfig.ENABLE_HTTP_HOOK_LOG_FILE;
    }

    public static boolean isHookUrlFiltered(String urlApi, String urlOk) {
        String a = urlApi != null ? urlApi : "";
        String b = urlOk != null ? urlOk : "";
        for (String s : PsbcConfig.HOOK_URL_FILTER_SUBSTRINGS) {
            if (s == null || s.isEmpty()) continue;
            if (a.contains(s) || b.contains(s)) return true;
        }
        return false;
    }

    public static boolean isXhxTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_XHX_API)
                || (s.contains("xhxTxDtlQry") && s.contains("T080232"));
    }

    public static boolean isTxTrsfTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_TX_TRSF_API)
                || (s.contains("txTrsfQry") && s.contains("T080233"));
    }

    public static boolean isQryTopDtlListTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_QRY_TOP_DTL_LIST_API)
                || (s.contains("qryTopDtlList") && s.contains("T080492"));
    }

    /** 账单页搜索 qryTransDetail / T080245（仅支出/仅收入/关键词等） */
    public static boolean isQryTransDetailTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_QRY_TRANS_DETAIL_API)
                || (s.contains("qryTransDetail") && s.contains("T080245"));
    }

    public static boolean isTxDetailTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_TX_DETAIL_API)
                || (s.contains("qryTxDtlDtlInfo") && s.contains("T080236"));
    }

    public static boolean isProfileBalanceTargetUrl(String u) {
        String s = u != null ? u : "";
        // 零钱宝/活期 + 账户基本信息（qryAccDtl 走独立 /api/mock-qry-acc-dtl）
        return s.contains("/api/account/queryTreasure/T080214")
                || s.contains("/api/account/currentDepositQuery/T080215")
                || s.contains("/api/account/qryAccBasInfo/T080025")
                || (s.contains("qryAccBasInfo") && s.contains("T080025"))
                || s.contains("/api/finance/custPftDtlSumInfoQry/T030930")
                || (s.contains("custPftDtlSumInfoQry") && s.contains("T030930"));
    }

    /** 账户基本信息 qryAccBasInfo / T080025 */
    public static boolean isQryAccBasInfoT080025Url(String u) {
        String s = u != null ? u : "";
        return s.contains("/api/account/qryAccBasInfo/T080025")
                || (s.contains("qryAccBasInfo") && s.contains("T080025"));
    }

    /** 账户明细 qryAccDtl / T080002 */
    public static boolean isQryAccDtlT080002Url(String u) {
        String s = u != null ? u : "";
        return s.contains("/api/account/qryAccDtl/T080002")
                || (s.contains("qryAccDtl") && s.contains("T080002"));
    }

    /** 「我的」页 pageDataQuery / T020104（custLvl / custLvlCode 星级） */
    public static boolean isPageDataQueryT020104Url(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_CUST_LVL_API)
                || (s.contains("pageDataQuery") && s.contains("T020104"));
    }

    /** 权益区 initQyzq / T070708（custCurStarLvl 星级，1 起算） */
    public static boolean isInitQyzqT070708Url(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_INIT_QYZQ_API)
                || (s.contains("initQyzq") && s.contains("T070708"));
    }

    /** 转账可用余额 qryTransAccBal / T080770 */
    public static boolean isQryTransAccBalT080770Url(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_QRY_TRANS_ACC_BAL_API)
                || (s.contains("qryTransAccBal") && s.contains("T080770"));
    }

    /**
     * qryAccDtl/T080002：{@code data.openaccDate} 在原值基础上年份减 {@code yearsBack}（如 2026/03/24 → 2023/03/24）。
     *
     * @return 是否写入
     */
    public static boolean applyQryAccDtlOpenaccDateShiftYears(JSONObject plainRoot, int yearsBack) {
        if (plainRoot == null || yearsBack <= 0) return false;
        try {
            JSONObject data = plainRoot.optJSONObject("data");
            if (data == null) return false;
            boolean changed = false;
            for (String key : new String[]{"openaccDate", "openAccDate"}) {
                if (!data.has(key)) continue;
                String raw = data.optString(key, "").trim();
                if (raw.isEmpty() || "--".equals(raw)) continue;
                String shifted = shiftOpenaccDateBackYears(raw, yearsBack);
                if (shifted == null || shifted.equals(raw)) continue;
                data.put(key, shifted);
                changed = true;
            }
            return changed;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 支持 yyyy/MM/dd、yyyy-MM-dd、yyyyMMdd；其它格式尝试提取四位年份。 */
    static String shiftOpenaccDateBackYears(String raw, int yearsBack) {
        if (raw == null || raw.isEmpty() || yearsBack <= 0) return null;
        String s = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(\\d{4})([/\\-]?)(\\d{1,2})([/\\-]?)(\\d{1,2})$")
                .matcher(s);
        if (m.matches()) {
            int y = Integer.parseInt(m.group(1)) - yearsBack;
            if (y < 1) return null;
            String sep = m.group(2);
            if (sep == null || sep.isEmpty()) {
                return String.format("%04d%02d%02d", y,
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(5)));
            }
            String sep2 = m.group(4);
            if (sep2 == null || sep2.isEmpty()) {
                sep2 = sep;
            }
            return String.format("%04d%s%02d%s%02d", y, sep,
                    Integer.parseInt(m.group(3)), sep2, Integer.parseInt(m.group(5)));
        }
        java.util.regex.Matcher yOnly = java.util.regex.Pattern.compile("(\\d{4})").matcher(s);
        if (yOnly.find()) {
            int y = Integer.parseInt(yOnly.group(1)) - yearsBack;
            if (y < 1) return null;
            return yOnly.replaceFirst(String.format("%04d", y));
        }
        return null;
    }

    /**
     * 将响应明文 {@code data.accLevel} 改为指定值（如 II 类户展示需要设为 {@code "1"}）。
     *
     * @return 是否写入（原值已为目标则 false）
     */
    public static boolean applyAccBasInfoAccLevel(JSONObject plainRoot, String accLevel) {
        if (plainRoot == null || accLevel == null || accLevel.isEmpty()) return false;
        try {
            JSONObject data = plainRoot.optJSONObject("data");
            if (data == null) return false;
            if (accLevel.equals(data.optString("accLevel"))) return false;
            data.put("accLevel", accLevel);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isHistoryTransactionDetailApplyTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_HISTORY_TX_DETAIL_APPLY_API)
                || (s.contains("historyTransactionDetailApply") && s.contains("T080331"));
    }

    public static boolean isQueryApplyScheduleTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_QUERY_APPLY_SCHEDULE_API)
                || (s.contains("queryApplySchedule") && s.contains("T080332"));
    }

    public static boolean isIncmEpnAnalySumTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_INCM_EPN_ANALY_SUM_API)
                || (s.contains("qryIncmEpnAnalySum") && s.contains("T080239"));
    }

    public static boolean isTxIncmEpnAnalySumTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_TX_INCM_EPN_ANALY_SUM_API)
                || (s.contains("qryTxIncmEpnAnalySum") && s.contains("T080240"));
    }

    public static boolean isImexSumDataTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_IMEX_SUM_DATA_API)
                || (s.contains("qryImexSumData") && s.contains("T080764"));
    }

    public static boolean isMyIncmEpnTargetUrl(String u) {
        String s = u != null ? u : "";
        return s.contains(PsbcConfig.MOCK_MY_INCM_EPN_API)
                || (s.contains("qryMyIncmEpn") && s.contains("T080505"));
    }

    public static void markOnceAndPrune(ConcurrentHashMap<String, Object> store, String key, int maxSize) {
        if (store == null || key == null || key.isEmpty()) return;
        store.put(key, Boolean.TRUE);
        while (store.size() > maxSize) {
            Iterator<String> it = store.keySet().iterator();
            if (!it.hasNext()) break;
            store.remove(it.next());
        }
    }

    public static String normalizeYmd8(Object v) {
        if (v == null) return "";
        String raw = String.valueOf(v).trim();
        if (raw.isEmpty()) return "";
        String onlyDigits = raw.replaceAll("\\D", "");
        if (onlyDigits.length() >= 8) return onlyDigits.substring(0, 8);
        return "";
    }

    public static JSONObject parseXhxQueryFromPlainRequest(String plainText) {
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            String beginDate = normalizeYmd8(obj.opt("beginDate"));
            String dlineDate = normalizeYmd8(obj.opt("dlineDate"));
            String ebankQryTypeFlagCd = String.valueOf(obj.opt("ebankQryTypeFlagCd") == null ? "" : obj.opt("ebankQryTypeFlagCd")).trim();
            JSONArray rawList = obj.optJSONArray("txTpCdList");
            JSONArray outList = new JSONArray();
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject node = rawList.optJSONObject(i);
                    if (node == null) continue;
                    String code = node.optString("incmEpnTxTpCd", "").trim();
                    if (code.isEmpty()) continue;
                    boolean dup = false;
                    for (int j = 0; j < outList.length(); j++) {
                        if (code.equals(outList.optString(j))) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) outList.put(code);
                }
            }
            if (beginDate.isEmpty() && dlineDate.isEmpty() && outList.length() <= 0 && ebankQryTypeFlagCd.isEmpty()) return null;
            JSONObject out = new JSONObject();
            if (!beginDate.isEmpty()) out.put("beginDate", beginDate);
            if (!dlineDate.isEmpty()) out.put("dlineDate", dlineDate);
            if (outList.length() > 0) out.put("txTpCdList", outList);
            if (!ebankQryTypeFlagCd.isEmpty()) out.put("ebankQryTypeFlagCd", ebankQryTypeFlagCd);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** qryTopDtlList/T080492：收支 Top 明细列表，透传顶层简单字段并规范化日期/分页。 */
    public static JSONObject parseQryTopDtlListFromPlainRequest(String plainText) {
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            JSONObject out = parseQueryApplyScheduleFromPlainRequest(plainText);
            String beginDate = normalizeYmd8(obj.opt("beginDate"));
            String dlineDate = normalizeYmd8(obj.opt("dlineDate"));
            if (!beginDate.isEmpty()) out.put("beginDate", beginDate);
            if (!dlineDate.isEmpty()) out.put("dlineDate", dlineDate);
            String incmEpnMonth = normalizeYmd8(obj.opt("incmEpnMonth"));
            if (incmEpnMonth.length() >= 6) {
                out.put("incmEpnMonth", incmEpnMonth.substring(0, 6));
            }
            JSONArray rawList = obj.optJSONArray("txTpCdList");
            JSONArray outList = new JSONArray();
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject node = rawList.optJSONObject(i);
                    if (node == null) continue;
                    String code = node.optString("incmEpnTxTpCd", "").trim();
                    if (code.isEmpty()) continue;
                    boolean dup = false;
                    for (int j = 0; j < outList.length(); j++) {
                        if (code.equals(outList.optString(j))) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) outList.put(code);
                }
            }
            if (outList.length() > 0) out.put("txTpCdList", outList);
            if (obj.has("incmEpnTpCd")) {
                out.put("incmEpnTpCd", String.valueOf(obj.opt("incmEpnTpCd")).trim());
            }
            if (obj.has("incmEpnTxTpCd")) {
                out.put("incmEpnTxTpCd", String.valueOf(obj.opt("incmEpnTxTpCd")).trim());
            }
            if (obj.has("datasize")) {
                out.put("datasize", String.valueOf(obj.opt("datasize")).trim());
            } else if (obj.has("dataSize")) {
                out.put("datasize", String.valueOf(obj.opt("dataSize")).trim());
            }
            if (obj.has("bgnIndexNo")) out.put("bgnIndexNo", obj.optString("bgnIndexNo", "1"));
            if (obj.has("curQryReqNum")) out.put("curQryReqNum", obj.optString("curQryReqNum", "30"));
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** qryTransDetail/T080245：账单页搜索，透传日期/收支/关键词/分页/介质号。 */
    public static JSONObject parseQryTransDetailFromPlainRequest(String plainText) {
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            JSONObject out = parseQueryApplyScheduleFromPlainRequest(plainText);
            String beginDate = normalizeYmd8(obj.opt("beginDate"));
            String dlineDate = normalizeYmd8(obj.opt("dlineDate"));
            if (!beginDate.isEmpty()) out.put("beginDate", beginDate);
            if (!dlineDate.isEmpty()) out.put("dlineDate", dlineDate);
            if (obj.has("incmEpnTpCd")) {
                out.put("incmEpnTpCd", String.valueOf(obj.opt("incmEpnTpCd")).trim());
            }
            if (obj.has("incmEpnTxTpCd")) {
                out.put("incmEpnTxTpCd", String.valueOf(obj.opt("incmEpnTxTpCd")).trim());
            }
            if (obj.has("qryCond")) {
                out.put("qryCond", String.valueOf(obj.opt("qryCond")).trim());
            }
            if (obj.has("cfmFlag")) {
                out.put("cfmFlag", String.valueOf(obj.opt("cfmFlag")).trim());
            }
            if (obj.has("bgnIndexNo")) out.put("bgnIndexNo", obj.optString("bgnIndexNo", "1"));
            if (obj.has("curQryReqNum")) out.put("curQryReqNum", obj.optString("curQryReqNum", "30"));
            JSONArray mediumList = obj.optJSONArray("mediumNoList");
            if (mediumList != null && mediumList.length() > 0) {
                JSONObject first = mediumList.optJSONObject(0);
                if (first != null) {
                    if (first.has("mediumNo") && first.optString("mediumNo").trim().length() > 0) {
                        out.put("mediumNo", first.optString("mediumNo").trim());
                    }
                    if (first.has("persInnerAccno") && first.optString("persInnerAccno").trim().length() > 0) {
                        out.put("persInnerAccno", first.optString("persInnerAccno").trim());
                    }
                    if (first.has("saccnoSeqNo") && first.optString("saccnoSeqNo").trim().length() > 0) {
                        out.put("saccnoSeqNo", first.optString("saccnoSeqNo").trim());
                    }
                }
            }
            JSONArray rawList = obj.optJSONArray("txTpCdList");
            JSONArray outList = new JSONArray();
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject node = rawList.optJSONObject(i);
                    if (node == null) continue;
                    String code = node.optString("incmEpnTxTpCd", "").trim();
                    if (code.isEmpty()) continue;
                    boolean dup = false;
                    for (int j = 0; j < outList.length(); j++) {
                        if (code.equals(outList.optString(j))) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) outList.put(code);
                }
            }
            if (outList.length() > 0) out.put("txTpCdList", outList);
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** txTrsfQry/T080233（H5 trans-query）：日期、对手方账号/户名、分页。 */
    public static JSONObject parseTxTrsfQueryFromPlainRequest(String plainText) {
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            String beginDate = normalizeYmd8(obj.opt("beginDate"));
            String dlineDate = normalizeYmd8(obj.opt("dlineDate"));
            String ebankQryTypeFlagCd = String.valueOf(obj.opt("ebankQryTypeFlagCd") == null ? "" : obj.opt("ebankQryTypeFlagCd")).trim();
            JSONArray rawList = obj.optJSONArray("txTpCdList");
            JSONArray outList = new JSONArray();
            if (rawList != null) {
                for (int i = 0; i < rawList.length(); i++) {
                    JSONObject node = rawList.optJSONObject(i);
                    if (node == null) continue;
                    String code = node.optString("incmEpnTxTpCd", "").trim();
                    if (code.isEmpty()) continue;
                    boolean dup = false;
                    for (int j = 0; j < outList.length(); j++) {
                        if (code.equals(outList.optString(j))) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) outList.put(code);
                }
            }
            boolean hasRange = !beginDate.isEmpty() || !dlineDate.isEmpty();
            boolean hasPage = obj.has("bgnIndexNo") || obj.has("curQryReqNum");
            String txOpsAccno = obj.optString("txOpsAccno", "").trim();
            String txOpsName = obj.optString("txOpsName", "").trim();
            boolean hasOps = !txOpsAccno.isEmpty() || !txOpsName.isEmpty();
            if (!hasRange && outList.length() <= 0 && ebankQryTypeFlagCd.isEmpty() && !hasPage && !hasOps) {
                return null;
            }
            JSONObject out = new JSONObject();
            if (!beginDate.isEmpty()) out.put("beginDate", beginDate);
            if (!dlineDate.isEmpty()) out.put("dlineDate", dlineDate);
            if (outList.length() > 0) out.put("txTpCdList", outList);
            if (!ebankQryTypeFlagCd.isEmpty()) out.put("ebankQryTypeFlagCd", ebankQryTypeFlagCd);
            if (!txOpsAccno.isEmpty()) out.put("txOpsAccno", txOpsAccno);
            if (!txOpsName.isEmpty()) out.put("txOpsName", txOpsName);
            if (obj.has("bgnIndexNo")) out.put("bgnIndexNo", obj.optString("bgnIndexNo", "1"));
            if (obj.has("curQryReqNum")) out.put("curQryReqNum", obj.optString("curQryReqNum", "30"));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONObject parseTxDetailQueryFromPlainRequest(String plainText) {
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            JSONObject q = new JSONObject();
            if (obj.has("saccnoSeqNo") && obj.optString("saccnoSeqNo").length() > 0) {
                q.put("saccnoSeqNo", obj.optString("saccnoSeqNo"));
            }
            if (obj.has("dtlSeqNo") && obj.optString("dtlSeqNo").length() > 0) {
                q.put("dtlSeqNo", obj.optString("dtlSeqNo"));
            }
            if (obj.has("mediumNo") && obj.optString("mediumNo").length() > 0) {
                q.put("mediumNo", obj.optString("mediumNo"));
            }
            if (obj.has("globalBusiTrackNo") && obj.optString("globalBusiTrackNo").length() > 0) {
                q.put("globalBusiTrackNo", obj.optString("globalBusiTrackNo"));
            }
            if (obj.has("txAmt") && obj.optString("txAmt").length() > 0) {
                q.put("txAmt", obj.optString("txAmt"));
            }
            String txDate = normalizeYmd8(obj.opt("txDate"));
            if (!txDate.isEmpty()) q.put("txDate", txDate);
            if (!q.has("globalBusiTrackNo")) return null;
            return q;
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONObject parseHistoryTransactionDetailApplyFromPlainRequest(String plainText) {
        JSONObject q = new JSONObject();
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            String beginDate = normalizeYmd8(obj.opt("beginDate"));
            String dlineDate = normalizeYmd8(obj.opt("dlineDate"));
            if (!beginDate.isEmpty()) q.put("beginDate", beginDate);
            if (!dlineDate.isEmpty()) q.put("dlineDate", dlineDate);
            if (obj.has("drawNo") && !obj.optString("drawNo").trim().isEmpty()) {
                q.put("drawNo", obj.optString("drawNo").trim());
            }
            if (obj.has("email") && !obj.optString("email").trim().isEmpty()) {
                q.put("email", obj.optString("email").trim());
            }
            if (obj.has("accNo") && !obj.optString("accNo").trim().isEmpty()) {
                q.put("accNo", obj.optString("accNo").trim());
            }
            JSONObject ti = obj.optJSONObject("tokenInfo");
            if (ti != null && ti.has("custNo") && !ti.optString("custNo").trim().isEmpty()) {
                q.put("custNo", ti.optString("custNo").trim());
            }
        } catch (Exception ignored) {
        }
        return q;
    }

    /**
     * 尽量“保守”地把请求明文中的顶层简单字段转成 query 参数（用于 mock server 侧匹配）。
     * 遇到对象/数组则跳过，避免 URL 过长和不确定序列化。
     */
    public static JSONObject parseQueryApplyScheduleFromPlainRequest(String plainText) {
        JSONObject q = new JSONObject();
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (k == null || k.trim().isEmpty()) continue;
                Object v = obj.opt(k);
                if (v == null) continue;
                if (v instanceof JSONObject || v instanceof JSONArray) continue;
                String sv = String.valueOf(v).trim();
                if (sv.isEmpty()) continue;
                q.put(k, sv);
            }
        } catch (Exception ignored) {
        }
        return q;
    }

    public static JSONObject parseIncmEpnAnalySumFromPlainRequest(String plainText) {
        // 同上：先把顶层简单字段透传给后端（通常会包含 beginDate/dlineDate 等）
        return parseQueryApplyScheduleFromPlainRequest(plainText);
    }

    public static JSONObject parseTxIncmEpnAnalySumFromPlainRequest(String plainText) {
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            JSONObject out = parseQueryApplyScheduleFromPlainRequest(plainText);
            String incmEpnMonth = normalizeYmd8(obj.opt("incmEpnMonth"));
            if (incmEpnMonth.length() >= 6) {
                out.put("incmEpnMonth", incmEpnMonth.substring(0, 6));
            }
            if (obj.has("incmEpnTpCd")) {
                out.put("incmEpnTpCd", String.valueOf(obj.opt("incmEpnTpCd")).trim());
            }
            if (obj.has("incmEpnYear")) {
                String y = String.valueOf(obj.opt("incmEpnYear")).trim();
                if (!y.isEmpty()) out.put("incmEpnYear", y);
            }
            if (obj.has("measureUnitCode")) {
                out.put("measureUnitCode", String.valueOf(obj.opt("measureUnitCode")).trim());
            }
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** qryImexSumData/T080764：收支趋势汇总，透传 beginDate/dlineDate/measureUnitCode 等。 */
    public static JSONObject parseImexSumDataFromPlainRequest(String plainText) {
        try {
            JSONObject obj = new JSONObject(String.valueOf(plainText));
            JSONObject out = parseQueryApplyScheduleFromPlainRequest(plainText);
            String beginDate = normalizeYmd8(obj.opt("beginDate"));
            String dlineDate = normalizeYmd8(obj.opt("dlineDate"));
            if (!beginDate.isEmpty()) out.put("beginDate", beginDate);
            if (!dlineDate.isEmpty()) out.put("dlineDate", dlineDate);
            String incmEpnMonth = normalizeYmd8(obj.opt("incmEpnMonth"));
            if (incmEpnMonth.length() >= 6) {
                out.put("incmEpnMonth", incmEpnMonth.substring(0, 6));
            }
            if (obj.has("measureUnitCode")) {
                out.put("measureUnitCode", String.valueOf(obj.opt("measureUnitCode")).trim());
            }
            return out.length() > 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONObject parseMyIncmEpnFromPlainRequest(String plainText) {
        // 透传顶层简单字段（若请求包含月份/类型等筛选，后端可使用）
        return parseQueryApplyScheduleFromPlainRequest(plainText);
    }

    public static String appendQueryString(String baseUrl, JSONObject params) throws org.json.JSONException {
        if (params == null || params.length() == 0) return baseUrl;
        StringBuilder sb = new StringBuilder(baseUrl);
        sb.append(baseUrl.contains("?") ? "&" : "?");
        boolean first = true;
        Iterator<String> keys = params.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = params.opt(k);
            if (v == null) continue;
            String sv = String.valueOf(v).trim();
            if (sv.isEmpty()) continue;
            if (!first) sb.append("&");
            first = false;
            sb.append(java.net.URLEncoder.encode(k, StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(java.net.URLEncoder.encode(sv, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    public static String httpBodyToString(InputStream ins) throws Exception {
        BufferedInputStream bis = new BufferedInputStream(ins);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = bis.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        bis.close();
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static JSONObject httpGetJson(String urlStr, int connectMs, int readMs, String reqMsgId) {
        JSONObject err = new JSONObject();
        try {
            err.put("_ok", false);
            err.put("_error", "");
        } catch (org.json.JSONException e) {
            return err;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoInput(true);
            if (reqMsgId != null && !reqMsgId.isEmpty()) {
                conn.setRequestProperty("X-Req-Msg-Id", reqMsgId);
            }
            conn.connect();
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                err.put("_error", "http_status_" + code + "_no_body");
                return err;
            }
            String body = httpBodyToString(stream);
            stream.close();
            if (!(code >= 200 && code < 300)) {
                err.put("_error", "http_status_" + code + "_body_" + body);
                return err;
            }
            return new JSONObject(body);
        } catch (Exception e) {
            try {
                err.put("_error", String.valueOf(e));
            } catch (org.json.JSONException ignored) {
            }
            return err;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 账单列表真实响应（替换 mock 之前）上报，后台对照汇出记录的字段。不阻塞页面。 */
    public static void reportBillListProbeAsync(final String pageUrl, final String hostRspPlain) {
        if (hostRspPlain == null || hostRspPlain.isEmpty()) {
            return;
        }
        final String snap = hostRspPlain;
        final String page = pageUrl == null ? "" : pageUrl;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("url", page);
                    body.put("hostRspPlain", snap);
                    String api = YouzengConfig.buildMockApiUrl(YouzengConfig.MOCK_API_ORIGIN, "/api/psbc-bill-probe");
                    JSONObject rsp = httpPostJson(api, body.toString(), 2000, 8000, "");
                    if (rsp.has("_error") && !rsp.optString("_error").isEmpty()) {
                        log("[-] bill probe: " + rsp.optString("_error"));
                    } else {
                        log("[+] bill probe novel=" + rsp.opt("novelKeys") + " bank=" + rsp.opt("bankHits"));
                    }
                } catch (Throwable t) {
                    log("[-] bill probe: " + t);
                }
            }
        }, "youzeng-bill-probe").start();
    }

    public static JSONObject httpPostJson(String urlStr, String jsonBody, int connectMs, int readMs, String reqMsgId) {
        JSONObject err = new JSONObject();
        try {
            err.put("_ok", false);
            err.put("_error", "");
        } catch (org.json.JSONException e) {
            return err;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (reqMsgId != null && !reqMsgId.isEmpty()) {
                conn.setRequestProperty("X-Req-Msg-Id", reqMsgId);
            }
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                err.put("_error", "http_status_" + code + "_no_body");
                return err;
            }
            String body = httpBodyToString(stream);
            stream.close();
            if (!(code >= 200 && code < 300)) {
                err.put("_error", "http_status_" + code + "_body_" + body);
                return err;
            }
            return new JSONObject(body);
        } catch (Exception e) {
            try {
                err.put("_error", String.valueOf(e));
            } catch (org.json.JSONException ignored) {
            }
            return err;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void applyProfileBalanceToPayload(Object node, String balanceStr) {
        if (node == null) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                applyProfileBalanceToPayload(arr.opt(i), balanceStr);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        String[] keys = {
                "accBal", "avalBal", "accAvalBal", "curTotalAmt", "totalAmt", "totalAmount", "addAmt",
                "addCurTotalAmt", "availableAmount", "addAssetAmt", "addCurDepAmt", "addDepAmt",
                "addLocCurDepAmt", "curDepAmt", "depAmt", "locCurDepAmt", "totalAssetAmt"
        };
        for (String key : keys) {
            if (!o.has(key)) continue;
            Object val = o.opt(key);
            if (val != null && !(val instanceof JSONObject) && !(val instanceof JSONArray)) {
                try {
                    o.put(key, balanceStr);
                } catch (org.json.JSONException ignored) {
                }
            }
        }
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            applyProfileBalanceToPayload(o.opt(k), balanceStr);
        }
    }

    public static byte[] hexUtilDecode(ClassLoader cl, String hexText) throws Throwable {
        Class<?> hexCls = XposedHelpers.findClass("com.yitong.mbank.psbc.net.http.encrypt.HexUtil", cl);
        try {
            Method m = hexCls.getMethod("decode", String.class);
            Object r = m.invoke(null, hexText);
            return (byte[]) r;
        } catch (NoSuchMethodException e) {
            Object inst = XposedHelpers.getStaticObjectField(hexCls, "INSTANCE");
            Object inner = XposedHelpers.getObjectField(inst, "value");
            Method m = inner.getClass().getMethod("decode", String.class);
            return (byte[]) m.invoke(inner, hexText);
        }
    }

    public static String hexUtilEncode(ClassLoader cl, byte[] bytes) throws Throwable {
        Class<?> hexCls = XposedHelpers.findClass("com.yitong.mbank.psbc.net.http.encrypt.HexUtil", cl);
        try {
            Method m = hexCls.getMethod("encode", byte[].class);
            Object r = m.invoke(null, bytes);
            return String.valueOf(r);
        } catch (NoSuchMethodException e) {
            Object inst = XposedHelpers.getStaticObjectField(hexCls, "INSTANCE");
            Object inner = XposedHelpers.getObjectField(inst, "value");
            Method m = inner.getClass().getMethod("encode", byte[].class);
            return String.valueOf(m.invoke(inner, bytes));
        }
    }

    public static byte[] sm4Decrypt(ClassLoader cl, byte[] keyBytes, byte[] encBytes) throws Throwable {
        Class<?> sm4Cls = XposedHelpers.findClass("com.yitong.mbank.psbc.net.http.encrypt.SM4Util", cl);
        Method found = null;
        Object target = null;
        for (Method m : sm4Cls.getDeclaredMethods()) {
            if (!"decrypt_ECB_Padding".equals(m.getName())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == byte[].class && p[1] == byte[].class) {
                found = m;
                target = null;
                break;
            }
        }
        if (found == null) {
            Object inst = XposedHelpers.getStaticObjectField(sm4Cls, "INSTANCE");
            Object inner = XposedHelpers.getObjectField(inst, "value");
            for (Method m : inner.getClass().getDeclaredMethods()) {
                if (!"decrypt_ECB_Padding".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == byte[].class && p[1] == byte[].class) {
                    found = m;
                    target = inner;
                    break;
                }
            }
        }
        if (found == null) throw new NoSuchMethodException("decrypt_ECB_Padding");
        found.setAccessible(true);
        Object r = target == null ? found.invoke(null, keyBytes, encBytes) : found.invoke(target, keyBytes, encBytes);
        return (byte[]) r;
    }

    public static byte[] sm4Encrypt(ClassLoader cl, byte[] keyBytes, byte[] plainBytes) throws Throwable {
        Class<?> sm4Cls = XposedHelpers.findClass("com.yitong.mbank.psbc.net.http.encrypt.SM4Util", cl);
        Method found = null;
        Object target = null;
        for (Method m : sm4Cls.getDeclaredMethods()) {
            if (!"encrypt_ECB_Padding".equals(m.getName())) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == byte[].class && p[1] == byte[].class) {
                found = m;
                break;
            }
        }
        if (found == null) {
            Object inst = XposedHelpers.getStaticObjectField(sm4Cls, "INSTANCE");
            Object inner = XposedHelpers.getObjectField(inst, "value");
            for (Method m : inner.getClass().getDeclaredMethods()) {
                if (!"encrypt_ECB_Padding".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == byte[].class && p[1] == byte[].class) {
                    found = m;
                    target = inner;
                    break;
                }
            }
        }
        if (found == null) throw new NoSuchMethodException("encrypt_ECB_Padding");
        found.setAccessible(true);
        Object r = target == null ? found.invoke(null, keyBytes, plainBytes) : found.invoke(target, keyBytes, plainBytes);
        return (byte[]) r;
    }

    public static String decryptDataBySm4Key(ClassLoader cl, String sm4Key, String dataHex) {
        try {
            if (sm4Key == null || dataHex == null || sm4Key.isEmpty() || dataHex.isEmpty()) {
                return "";
            }
            byte[] keyBytes = sm4Key.getBytes(StandardCharsets.UTF_8);
            byte[] encBytes = hexUtilDecode(cl, dataHex);
            byte[] decBytes = sm4Decrypt(cl, keyBytes, encBytes);
            return new String(decBytes, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 解析邮储网关响应明文。兼容 {@code data} 为 SM4 十六进制、明文字符串或 JSONObject（H5 isNeedEncrypt=false 常见）。
     */
    public static String resolveResponsePlainFromWire(ClassLoader cl, String sm4Key, JSONObject rspObj) {
        if (rspObj == null) {
            return "";
        }
        if (!rspObj.has("data")) {
            return rspObj.toString();
        }
        Object dataField = rspObj.opt("data");
        if (dataField instanceof JSONObject) {
            return rspObj.toString();
        }
        String dataStr = String.valueOf(dataField).trim();
        if (dataStr.isEmpty()) {
            return "";
        }
        if (dataStr.startsWith("{") || dataStr.startsWith("[")) {
            return dataStr;
        }
        String dec = decryptDataBySm4Key(cl, sm4Key, dataStr);
        if (dec != null && !dec.isEmpty()) {
            return dec;
        }
        return "";
    }

    /** 将仅含业务字段的 JSON 包一层 code/data 外壳，供 openaccDate 等改写使用。 */
    public static JSONObject normalizeBankResponseRoot(String plain) throws org.json.JSONException {
        JSONObject root = new JSONObject(plain);
        if (root.has("data")) {
            return root;
        }
        if (root.has("accBal") || root.has("openaccDate") || root.has("openAccDate") || root.has("avalBal")) {
            JSONObject wrapped = new JSONObject();
            wrapped.put("code", "000000");
            wrapped.put("data", root);
            wrapped.put("msg", "交易成功");
            wrapped.put("showType", "0");
            return wrapped;
        }
        return root;
    }

    /**
     * 按原始响应 {@code data} 形态回写：明文 JSONObject 或 SM4 十六进制。
     */
    /**
     * 在原始 JSON 文本上替换字符串字段，避免 {@link JSONObject#toString()} 把 {@code /} 编成 {@code \/} 导致 H5 异常。
     */
    public static String replaceJsonStringValueLiteral(String json, String key, String newValue) {
        if (json == null || key == null || newValue == null) {
            return json;
        }
        String esc = newValue.replace("\\", "\\\\").replace("\"", "\\\"");
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\")((?:\\\\.|[^\"\\\\])*)(\")");
        java.util.regex.Matcher m = p.matcher(json);
        if (!m.find()) {
            return json;
        }
        StringBuffer sb = new StringBuffer();
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + esc + m.group(3)));
        m.appendTail(sb);
        return sb.toString();
    }

    public static String shiftOpenaccDateInRawJson(String json, int yearsBack) {
        if (json == null || yearsBack <= 0) {
            return json;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(\"openaccDate\"\\s*:\\s*\")([^\"]+)(\")");
        java.util.regex.Matcher m = p.matcher(json);
        if (!m.find()) {
            p = java.util.regex.Pattern.compile("(\"openAccDate\"\\s*:\\s*\")([^\"]+)(\")");
            m = p.matcher(json);
            if (!m.find()) {
                return json;
            }
        }
        String raw = m.group(2);
        String shifted = shiftOpenaccDateBackYears(raw, yearsBack);
        if (shifted == null || shifted.equals(raw)) {
            return json;
        }
        return json.substring(0, m.start(2)) + shifted + json.substring(m.end(2));
    }

    /** 仅改 accBal / avalBal / openaccDate，保持其余字段与斜杠格式不变。 */
    public static String patchQryAccDtlPlainLiteral(String plain, String balanceStr, int openaccYearsBack) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        String out = plain;
        if (balanceStr != null && !balanceStr.isEmpty()) {
            if (out.contains("\"accBal\"")) {
                out = replaceJsonStringValueLiteral(out, "accBal", balanceStr);
            }
            if (out.contains("\"avalBal\"")) {
                out = replaceJsonStringValueLiteral(out, "avalBal", balanceStr);
            }
        }
        return shiftOpenaccDateInRawJson(out, openaccYearsBack);
    }

    /** 仅改 custLvl / custLvlCode / starLvlIcon，保持其余字段与斜杠格式不变。 */
    public static String patchT020104CustLvlPlainLiteral(String plain,
            String custLvl, String custLvlCode, String starLvlIcon) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        String out = plain;
        if (custLvl != null && !custLvl.isEmpty() && out.contains("\"custLvl\"")) {
            out = replaceJsonStringValueLiteral(out, "custLvl", custLvl);
        }
        if (custLvlCode != null && !custLvlCode.isEmpty() && out.contains("\"custLvlCode\"")) {
            out = replaceJsonStringValueLiteral(out, "custLvlCode", custLvlCode);
        }
        if (starLvlIcon != null && !starLvlIcon.isEmpty() && out.contains("\"starLvlIcon\"")) {
            out = replaceJsonStringValueLiteral(out, "starLvlIcon", starLvlIcon);
        }
        return out;
    }

    /** 仅改 custCurStarLvl（1 起算），不改 custLvl，避免与 0 起算码叠成 4+3=7。 */
    public static String patchT070708CustLvlPlainLiteral(String plain, String custCurStarLvl) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (custCurStarLvl != null && !custCurStarLvl.isEmpty() && plain.contains("\"custCurStarLvl\"")) {
            return replaceJsonStringValueLiteral(plain, "custCurStarLvl", custCurStarLvl);
        }
        return plain;
    }

    /**
     * 按银行原始 wire 形态回写：data 为明文对象则直接改 rawRsp 文本；data 为 hex 则只重加密对应明文块。
     */
    public static String rebuildQryAccDtlWirePreservingFormat(ClassLoader cl, String sm4Key,
            JSONObject origWire, String rawRsp, String patchedPlain) throws org.json.JSONException {
        if (origWire == null || rawRsp == null || patchedPlain == null) {
            return "";
        }
        Object origData = origWire.opt("data");
        if (origData instanceof JSONObject) {
            String balance = "";
            try {
                JSONObject p = new JSONObject(patchedPlain);
                JSONObject data = p.optJSONObject("data");
                if (data != null) {
                    balance = data.optString("accBal", "");
                }
            } catch (Exception ignored) {
            }
            String out = rawRsp;
            if (balance != null && !balance.isEmpty()) {
                if (out.contains("\"accBal\"")) {
                    out = replaceJsonStringValueLiteral(out, "accBal", balance);
                }
                if (out.contains("\"avalBal\"")) {
                    out = replaceJsonStringValueLiteral(out, "avalBal", balance);
                }
            }
            return shiftOpenaccDateInRawJson(out, 3);
        }
        String origDec = decryptDataBySm4Key(cl, sm4Key, String.valueOf(origData));
        String balanceStr = "";
        try {
            JSONObject p = new JSONObject(patchedPlain);
            JSONObject data = p.optJSONObject("data");
            if (data != null) {
                balanceStr = data.optString("accBal", "");
            }
        } catch (Exception ignored) {
        }
        String encryptSrc;
        if (origDec != null && !origDec.isEmpty() && !origDec.trim().startsWith("{\"code\"")) {
            encryptSrc = patchQryAccDtlPlainLiteral(origDec, balanceStr, 3);
        } else {
            encryptSrc = patchedPlain;
        }
        String enc = encryptPlainBySm4KeyToHex(cl, sm4Key, encryptSrc);
        if (enc == null || enc.isEmpty()) {
            return "";
        }
        JSONObject outWire = new JSONObject(rawRsp);
        outWire.put("data", enc);
        return outWire.toString();
    }

    public static String rebuildWireJsonAfterPlainPatch(ClassLoader cl, String sm4Key, JSONObject origRsp, String patchedPlain)
            throws org.json.JSONException {
        JSONObject outWire = new JSONObject(origRsp.toString());
        JSONObject patchedRoot = normalizeBankResponseRoot(patchedPlain);
        Object origData = origRsp.opt("data");
        if (origData instanceof JSONObject) {
            if (patchedRoot.has("data")) {
                outWire.put("code", patchedRoot.optString("code", outWire.optString("code", "000000")));
                outWire.put("data", patchedRoot.opt("data"));
                if (patchedRoot.has("msg")) {
                    outWire.put("msg", patchedRoot.optString("msg"));
                }
                if (patchedRoot.has("showType")) {
                    outWire.put("showType", patchedRoot.optString("showType"));
                }
                if (patchedRoot.has("reqMsgId")) {
                    outWire.put("reqMsgId", patchedRoot.optString("reqMsgId"));
                }
            } else {
                outWire.put("data", patchedRoot);
            }
            return outWire.toString();
        }
        String encryptSrc;
        if (patchedRoot.has("data")) {
            Object inner = patchedRoot.opt("data");
            encryptSrc = inner instanceof JSONObject ? ((JSONObject) inner).toString() : String.valueOf(inner);
        } else {
            encryptSrc = patchedRoot.toString();
        }
        String enc = encryptPlainBySm4KeyToHex(cl, sm4Key, encryptSrc);
        if (enc == null || enc.isEmpty()) {
            return "";
        }
        outWire.put("data", enc);
        return outWire.toString();
    }

    public static String encryptPlainBySm4KeyToHex(ClassLoader cl, String sm4Key, String plainText) {
        try {
            if (sm4Key == null || plainText == null) return null;
            byte[] keyBytes = sm4Key.getBytes(StandardCharsets.UTF_8);
            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] enc = sm4Encrypt(cl, keyBytes, plainBytes);
            return hexUtilEncode(cl, enc);
        } catch (Throwable t) {
            log("[-] 响应重加密失败: " + t);
            return null;
        }
    }
}
