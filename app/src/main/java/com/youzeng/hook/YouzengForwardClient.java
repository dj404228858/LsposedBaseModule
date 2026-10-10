package com.youzeng.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 银行 App 把业务请求明文/响应明文整包 POST 到 youzeng {@code /api/forward}，
 * 由后端按 bank + url/txcode 筛选 mock 或放行。
 * 启动时拉取忽略名单，命中则不转发。
 */
public final class YouzengForwardClient {

    private static final class IgnoreRule {
        final String bank;
        final String kind;
        final String pattern;

        IgnoreRule(String bank, String kind, String pattern) {
            this.bank = bank == null ? "" : bank.trim().toLowerCase();
            this.kind = kind == null ? "" : kind.trim().toLowerCase();
            this.pattern = pattern == null ? "" : pattern.trim();
        }
    }

    private static final Object IGNORE_LOCK = new Object();
    private static volatile boolean ignoreLoaded = false;
    private static volatile List<IgnoreRule> ignoreRules = Collections.emptyList();

    private YouzengForwardClient() {
    }

    public static void prefetchIgnoreList() {
        new Thread(YouzengForwardClient::loadIgnoreList, "youzeng-fwd-ignore").start();
    }

    public static boolean shouldIgnore(String bank, String url, String txcode) {
        String u = url == null ? "" : url.toLowerCase();
        if (u.contains("adv.ccb.com")) {
            return true;
        }
        if (!ignoreLoaded) {
            loadIgnoreList();
        }
        List<IgnoreRule> rules = ignoreRules;
        if (rules.isEmpty()) {
            return false;
        }
        String b = bank == null ? "" : bank.trim().toLowerCase();
        String t = txcode == null ? "" : txcode.trim().toLowerCase();
        for (IgnoreRule r : rules) {
            if (!r.bank.isEmpty() && !"all".equals(r.bank) && !"*".equals(r.bank) && !r.bank.equals(b)) {
                continue;
            }
            if (r.pattern.isEmpty()) {
                continue;
            }
            if ("url".equals(r.kind)) {
                if (u.contains(r.pattern.toLowerCase())) {
                    return true;
                }
            } else if (!t.isEmpty() && t.equals(r.pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static void loadIgnoreList() {
        synchronized (IGNORE_LOCK) {
            if (ignoreLoaded) {
                return;
            }
            List<IgnoreRule> parsed = new ArrayList<>();
            try {
                JSONObject obj = httpGetJson(
                        YouzengConfig.forwardIgnoreApiUrl(),
                        YouzengConfig.FORWARD_CONNECT_MS,
                        YouzengConfig.FORWARD_READ_MS);
                if (obj.has("_error")) {
                    XposedBridge.log("[youzeng] forward ignore load fail: " + obj.optString("_error"));
                    return;
                }
                JSONArray arr = obj.optJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject row = arr.optJSONObject(i);
                        if (row == null) {
                            continue;
                        }
                        String pattern = row.optString("pattern", "").trim();
                        if (pattern.isEmpty()) {
                            continue;
                        }
                        parsed.add(new IgnoreRule(
                                row.optString("bank", ""),
                                row.optString("kind", ""),
                                pattern));
                    }
                }
                ignoreRules = parsed;
                ignoreLoaded = true;
                XposedBridge.log("[youzeng] forward ignore loaded n=" + parsed.size());
            } catch (Throwable t) {
                XposedBridge.log("[youzeng] forward ignore load: " + t);
            }
        }
    }

    public static JSONObject forward(
            String bank, String url, String txcode, String hostReqPlain, String hostRspPlain, String reqMsgId) {
        return forward(bank, url, txcode, hostReqPlain, hostRspPlain, reqMsgId, YouzengConfig.FORWARD_READ_MS);
    }

    public static JSONObject forward(
            String bank,
            String url,
            String txcode,
            String hostReqPlain,
            String hostRspPlain,
            String reqMsgId,
            int readMs) {
        if (shouldIgnore(bank, url, txcode)) {
            XposedBridge.log("[youzeng] skip forward ignored bank=" + bank + " tx=" + txcode + " url=" + url);
            return passthrough();
        }
        JSONObject fallback = passthrough();
        try {
            JSONObject body = new JSONObject();
            body.put("bank", bank == null ? "" : bank);
            body.put("url", url == null ? "" : url);
            body.put("txcode", txcode == null ? "" : txcode);
            body.put("hostReqPlain", hostReqPlain == null ? "" : hostReqPlain);
            body.put("hostRspPlain", hostRspPlain == null ? "" : hostRspPlain);
            body.put("reqMsgId", reqMsgId == null ? "" : reqMsgId);
            JSONObject obj = httpPostJson(
                    YouzengConfig.forwardApiUrl(),
                    body.toString(),
                    YouzengConfig.FORWARD_CONNECT_MS,
                    readMs,
                    reqMsgId);
            if (obj.has("_error")) {
                fallback.put("error", obj.optString("_error"));
                return fallback;
            }
            String action = obj.optString("action", "passthrough");
            if (action.isEmpty()) {
                action = "passthrough";
            }
            JSONObject r = new JSONObject();
            r.put("ok", obj.optBoolean("ok", true));
            r.put("action", action);
            r.put("body", obj.optString("body", ""));
            if (obj.has("error")) {
                r.put("error", obj.optString("error"));
            }
            return r;
        } catch (Throwable t) {
            try {
                fallback.put("error", String.valueOf(t));
            } catch (org.json.JSONException ignored) {
            }
            return fallback;
        }
    }

    private static JSONObject passthrough() {
        JSONObject r = new JSONObject();
        try {
            r.put("ok", true);
            r.put("action", "passthrough");
            r.put("body", "");
        } catch (org.json.JSONException ignored) {
        }
        return r;
    }

    public static JSONObject getJson(String urlStr) {
        return httpGetJson(urlStr, YouzengConfig.FORWARD_CONNECT_MS, YouzengConfig.FORWARD_READ_MS);
    }

    private static JSONObject httpGetJson(String urlStr, int connectMs, int readMs) {
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
            String did = YouzengModulePrefs.getMockDeviceDid();
            if (did != null && !did.isEmpty()) {
                conn.setRequestProperty("x-device-id", did);
            }
            conn.connect();
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                err.put("_error", "http_status_" + code + "_no_body");
                return err;
            }
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = stream.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            stream.close();
            String body = bos.toString("UTF-8");
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
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static JSONObject httpPostJson(String urlStr, String jsonBody, int connectMs, int readMs, String reqMsgId) {
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
            String did = YouzengModulePrefs.getMockDeviceDid();
            if (did != null && !did.isEmpty()) {
                conn.setRequestProperty("x-device-id", did);
            }
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
            byte[] buf = new byte[4096];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = stream.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            stream.close();
            String body = bos.toString("UTF-8");
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
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
