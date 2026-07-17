package com.quant.order.service;

import com.quant.common.dto.OrderIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 幣安下單閘道（見架構文件 §6、§9）：支援現貨(SPOT)與 USDT-M 永續(USDT_M)。
 * 依 OrderIntent.market 路由到對應 base url / 端點 / 金鑰。HMAC-SHA256 簽名。
 * dry-run=true 僅記錄/模擬。⚠️ 合約 testnet 金鑰與現貨不同（testnet.binancefuture.com）。
 */
@Service
public class BinanceOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(BinanceOrderGateway.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final NotifyClient notifyClient;
    private volatile long lastAuthAlert = 0;

    public BinanceOrderGateway(NotifyClient notifyClient) { this.notifyClient = notifyClient; }

    private static boolean isAuthOrIpError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("-2015") || m.contains("-2014") || m.contains("-1022")
                || m.contains("invalid api-key") || m.contains("api-key format")
                || m.contains("http 401") || m.contains("http 403")
                || (m.contains("ip") && (m.contains("not") || m.contains("white") || m.contains("allow")));
    }

    private void alertAuth(String action, String detail) {
        long now = System.currentTimeMillis();
        if (now - lastAuthAlert < 600_000) return;
        lastAuthAlert = now;
        try {
            notifyClient.send(Map.of("text",
                ":lock: 幣安金鑰/IP 告警（" + action + "）｜ env=" + env + " ｜ " + detail
                + " ｜ 多為 IP 白名單未涵蓋目前對外 IP，或金鑰交易權限/簽名問題，請至幣安 API 管理確認"));
        } catch (Exception ignore) {}
    }

    @Value("${trading.dry-run:true}") private boolean dryRun;
    // 現貨
    @Value("${binance.rest-base-url:https://testnet.binance.vision}") private String spotUrl;
    @Value("${binance.api-key:}") private String spotKey;
    @Value("${binance.api-secret:}") private String spotSecret;
    // 合約 USDT-M
    @Value("${binance.futures-rest-url:https://testnet.binancefuture.com}") private String futUrl;
    @Value("${binance.futures-api-key:}") private String futKey;
    @Value("${binance.futures-api-secret:}") private String futSecret;
    @Value("${binance.recv-window:5000}") private long recvWindow;
    // 環境切換：testnet(預設,安全) / mainnet(真錢)
    @Value("${binance.env:testnet}") private String env;
    @Value("${binance.mainnet-rest-url:https://api.binance.com}") private String spotMainnetUrl;
    @Value("${binance.mainnet-api-key:}") private String spotMainnetKey;
    @Value("${binance.mainnet-api-secret:}") private String spotMainnetSecret;
    @Value("${binance.mainnet-futures-rest-url:https://fapi.binance.com}") private String futMainnetUrl;
    @Value("${binance.mainnet-futures-api-key:}") private String futMainnetKey;
    @Value("${binance.mainnet-futures-api-secret:}") private String futMainnetSecret;

    private String testnetSpotUrl, testnetFutUrl;   // 保留測試網位址以便切回

    @PostConstruct
    void resolveEnv() {
        testnetSpotUrl = spotUrl;  testnetFutUrl = futUrl;   // 先記住 testnet 位址
        if ("mainnet".equalsIgnoreCase(env)) {
            spotUrl = spotMainnetUrl;  futUrl = futMainnetUrl;
            if (!spotMainnetKey.isBlank())    spotKey = spotMainnetKey;
            if (!spotMainnetSecret.isBlank()) spotSecret = spotMainnetSecret;
            if (!futMainnetKey.isBlank())     futKey = futMainnetKey;
            if (!futMainnetSecret.isBlank())  futSecret = futMainnetSecret;
            log.warn("⚠️ BINANCE_ENV=mainnet ｜【正式網真錢下單】spot={} fut={} ｜ dry-run={} ｜ 唯有 dry-run=false 才會真送單",
                    spotUrl, futUrl, dryRun);
        } else {
            log.info("BINANCE_ENV=testnet ｜ 測試網下單 spot={}（安全）", spotUrl);
        }
    }

    /** 執行期套用連線設定（登入頁推送）。keys 空字串代表不變更；dryRun 為 null 代表不變更。 */
    public synchronized Map<String, Object> applyConfig(String newEnv, Boolean newDryRun, String newKey, String newSecret) {
        if (newEnv != null && !newEnv.isBlank()) {
            env = newEnv.trim().toLowerCase();
            boolean m = "mainnet".equals(env);
            spotUrl = m ? spotMainnetUrl : testnetSpotUrl;
            futUrl  = m ? futMainnetUrl  : testnetFutUrl;
        }
        if (newDryRun != null) dryRun = newDryRun;
        if (newKey != null && !newKey.isBlank())   { spotKey = newKey.trim(); }
        if (newSecret != null && !newSecret.isBlank()) { spotSecret = newSecret.trim(); }
        log.warn("套用下單設定：env={} dryRun={} spot={} keySet={}", env, dryRun, spotUrl, !spotKey.isBlank());
        return configStatus();
    }

    public Map<String, Object> configStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("env", env); m.put("dryRun", dryRun); m.put("spotUrl", spotUrl);
        m.put("keySet", !spotKey.isBlank() && !spotSecret.isBlank());
        m.put("mainnet", "mainnet".equalsIgnoreCase(env));
        return m;
    }

    private String baseUrl(OrderIntent i) { return i.isFutures() ? futUrl : spotUrl; }
    private String orderPath(OrderIntent i) { return i.isFutures() ? "/fapi/v1/order" : "/api/v3/order"; }
    private String key(boolean futures) { return futures ? futKey : spotKey; }
    private String secret(boolean futures) { return futures ? futSecret : spotSecret; }

    // ---------- 下單 ----------
    public String placeOrder(OrderIntent intent) {
        if (dryRun) {
            log.info("[DRY-RUN] {} place {} {} {} qty={}", intent.isFutures() ? "合約" : "現貨",
                    intent.symbol(), intent.side(), intent.type(), intent.quantity());
            return "DRYRUN-" + intent.clientOrderId();
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", intent.symbol());
        p.put("side", intent.side().name());
        p.put("type", intent.type().name());
        p.put("quantity", trim(intent.quantity()));
        if (intent.type().name().equals("LIMIT")) {
            p.put("timeInForce", "GTC");
            p.put("price", trim(intent.price()));
        }
        if (intent.isFutures() && intent.reduceOnly()) p.put("reduceOnly", "true");
        // 現貨用 newClientOrderId；合約亦支援
        p.put("newClientOrderId", intent.clientOrderId());
        return signed("POST", baseUrl(intent), orderPath(intent), p, intent.isFutures(), "下單");
    }

    // ---------- 查單 ----------
    public String queryOrder(String symbol, String clientOrderId, boolean futures) {
        if (dryRun) return "{\"symbol\":\"" + symbol + "\",\"status\":\"FILLED\",\"dryRun\":true}";
        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("origClientOrderId", clientOrderId);
        String base = futures ? futUrl : spotUrl;
        String path = futures ? "/fapi/v1/order" : "/api/v3/order";
        return signed("GET", base, path, p, futures, "查單");
    }

    // ---------- 撤單 ----------
    public String cancelOrder(String symbol, String clientOrderId, boolean futures) {
        if (dryRun) return "{\"symbol\":\"" + symbol + "\",\"status\":\"CANCELED\",\"dryRun\":true}";
        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("origClientOrderId", clientOrderId);
        String base = futures ? futUrl : spotUrl;
        String path = futures ? "/fapi/v1/order" : "/api/v3/order";
        return signed("DELETE", base, path, p, futures, "撤單");
    }

    // ---------- 設定合約槓桿 ----------
    public String setLeverage(String symbol, int leverage) {
        if (dryRun) return "{\"symbol\":\"" + symbol + "\",\"leverage\":" + leverage + ",\"dryRun\":true}";
        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("leverage", String.valueOf(leverage));
        return signed("POST", futUrl, "/fapi/v1/leverage", p, true, "設定槓桿");
    }


    // ---------- OCO（One-Cancels-Other，現貨）----------
    public String placeOco(String symbol, String side, double quantity,
                           double price, double stopPrice, double stopLimitPrice, String listClientOrderId) {
        if (dryRun) return "{\"symbol\":\"" + symbol + "\",\"listClientOrderId\":\"" + listClientOrderId
                + "\",\"listOrderStatus\":\"EXECUTING\",\"dryRun\":true}";
        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("side", side);
        p.put("quantity", trim(quantity));
        p.put("price", trim(price));                 // 限價腿
        p.put("stopPrice", trim(stopPrice));         // 觸發價
        p.put("stopLimitPrice", trim(stopLimitPrice));
        p.put("stopLimitTimeInForce", "GTC");
        p.put("listClientOrderId", listClientOrderId);
        return signed("POST", spotUrl, "/api/v3/order/oco", p, false, "OCO下單");
    }

    // ---------- 通用簽名請求 ----------
    private String signed(String method, String base, String path, Map<String, String> params,
                          boolean futures, String action) {
        String k = key(futures), sec = secret(futures);
        if (k.isBlank() || sec.isBlank())
            throw new IllegalStateException("未設定 " + (futures ? "合約" : "現貨") + " API 金鑰");
        try {
            params.put("recvWindow", String.valueOf(recvWindow));
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            String query = params.entrySet().stream()
                    .map(e -> e.getKey() + "=" + enc(e.getValue()))
                    .collect(Collectors.joining("&"));
            String sig = hmac(query, sec);
            String full = query + "&signature=" + sig;

            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .header("X-MBX-APIKEY", k).timeout(Duration.ofSeconds(10));
            if (method.equals("GET")) {
                b.uri(URI.create(base + path + "?" + full)).GET();
            } else {
                b.uri(URI.create(base + path))
                 .header("Content-Type", "application/x-www-form-urlencoded")
                 .method(method, HttpRequest.BodyPublishers.ofString(full));
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("幣安{}成功: {}", action, resp.body());
                return resp.body();
            }
            String err = "HTTP " + resp.statusCode() + ": " + resp.body();
            if (isAuthOrIpError(err)) alertAuth(action, err);
            throw new RuntimeException("幣安" + action + "失敗 " + err);
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(action + "例外: " + e.getMessage(), e); }
    }

    private static String hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : raw) sb.append(String.format("%02x", x));
        return sb.toString();
    }
    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
    private static String trim(double d) { return trim(Double.valueOf(d)); }
    private static String trim(Double d) {
        if (d == null) return "0";
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
