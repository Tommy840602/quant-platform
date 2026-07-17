package com.quant.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 幣安真實現貨帳戶（簽名 /api/v3/account）。以行情估值過濾雜幣、計算總資產(USDT)。
 */
@Service
public class BinanceAccountService {

    private static final Logger log = LoggerFactory.getLogger(BinanceAccountService.class);
    private static final Set<String> STABLES = Set.of("USDT", "USDC", "TUSD", "FDUSD", "BUSD", "DAI", "USD", "USDP", "FRAX");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final NotifyClient notifyClient;
    private volatile long lastAuthAlert = 0;   // 節流：金鑰/IP 告警冷卻

    public BinanceAccountService(NotifyClient notifyClient) { this.notifyClient = notifyClient; }

    /** 偵測幣安金鑰/IP/權限類錯誤（-2015/-1022/-2014/401/403/IP…）。 */
    static boolean isAuthOrIpError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase();
        return m.contains("-2015") || m.contains("-2014") || m.contains("-1022")
                || m.contains("invalid api-key") || m.contains("api-key format")
                || m.contains("http 401") || m.contains("http 403")
                || (m.contains("ip") && (m.contains("not") || m.contains("white") || m.contains("allow")));
    }

    /** 節流推 Slack（同類告警 10 分鐘內只發一次）。 */
    private void alertAuth(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastAuthAlert < 600_000) return;
        lastAuthAlert = now;
        try {
            notifyClient.send(Map.of("text",
                ":lock: 幣安金鑰/IP 告警（帳戶查詢）｜ env=" + env + " ｜ " + detail
                + " ｜ 多為 IP 白名單未涵蓋目前對外 IP，或金鑰權限/簽名問題，請至幣安 API 管理確認"));
        } catch (Exception ignore) {}
    }

    @Value("${binance.rest-base-url:https://testnet.binance.vision}") private String restUrl;
    @Value("${binance.market-rest-url:https://api.binance.com}") private String marketRestUrl;
    @Value("${binance.api-key:}") private String apiKey;
    @Value("${binance.api-secret:}") private String apiSecret;
    @Value("${binance.env:testnet}") private String env;
    @Value("${binance.mainnet-rest-url:https://api.binance.com}") private String mainnetRestUrl;
    @Value("${binance.mainnet-api-key:}") private String mainnetApiKey;
    @Value("${binance.mainnet-api-secret:}") private String mainnetApiSecret;
    private String testnetRestUrl;   // 保留測試網位址以便切回

    @PostConstruct
    void resolveEnv() {
        testnetRestUrl = restUrl;   // 記住原始 testnet 位址
        if ("mainnet".equalsIgnoreCase(env)) {
            restUrl = mainnetRestUrl;
            if (!mainnetApiKey.isBlank()) apiKey = mainnetApiKey;
            if (!mainnetApiSecret.isBlank()) apiSecret = mainnetApiSecret;
            log.warn("BINANCE_ENV=mainnet ｜ 正在使用【正式網真實帳戶】REST={} ｜ 下單/查詢皆為真錢，請謹慎", restUrl);
        } else {
            log.info("BINANCE_ENV=testnet ｜ 使用測試網 REST={}（安全，不動真錢）", restUrl);
        }
    }

    /** 執行期套用連線設定（登入頁推送）。keys 空字串代表不變更。 */
    public synchronized void applyConfig(String newEnv, String newKey, String newSecret) {
        if (newEnv != null && !newEnv.isBlank()) {
            env = newEnv.trim().toLowerCase();
            restUrl = "mainnet".equals(env) ? mainnetRestUrl : testnetRestUrl;
        }
        if (newKey != null && !newKey.isBlank()) apiKey = newKey.trim();
        if (newSecret != null && !newSecret.isBlank()) apiSecret = newSecret.trim();
        log.warn("套用連線設定：env={} REST={} keySet={}", env, restUrl, live());
    }

    /** 目前連線狀態（不外洩金鑰內容）。 */
    public Map<String, Object> configStatus() {
        return Map.of("env", env, "restUrl", restUrl, "keySet", live(),
                "mainnet", "mainnet".equalsIgnoreCase(env));
    }
    @Value("${account.min-asset-value:1.0}") private double minValue;   // 估值門檻(USDT)
    @Value("${account.max-assets:50}") private int maxAssets;

    public boolean live() { return !apiKey.isBlank() && !apiSecret.isBlank(); }

    public Map<String, Object> balance() {
        if (!live()) return Map.of("asset", "USDT", "free", 10000.0, "locked", 0.0,
                "real", false, "totalUsdt", 10000.0, "balances", List.of());
        try {
            Map<String, Double> px = allPrices();   // SYMBOL -> price（正式網公開行情估值）
            String query = "recvWindow=5000&timestamp=" + System.currentTimeMillis();
            String sig = hmac(query, apiSecret);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(restUrl + "/api/v3/account?" + query + "&signature=" + sig))
                    .header("X-MBX-APIKEY", apiKey).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());

            JsonNode acc = mapper.readTree(resp.body());
            double usdtFree = 0, usdtLocked = 0, totalUsdt = 0;
            List<Map<String, Object>> assets = new ArrayList<>();
            for (JsonNode b : acc.path("balances")) {
                double free = b.path("free").asDouble(), locked = b.path("locked").asDouble();
                double qty = free + locked;
                if (qty <= 0) continue;
                String asset = b.get("asset").asText();
                double price = STABLES.contains(asset) ? 1.0 : px.getOrDefault(asset + "USDT", 0.0);
                double value = qty * price;
                totalUsdt += value;
                if ("USDT".equals(asset)) { usdtFree = free; usdtLocked = locked; }
                if (value >= minValue) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asset", asset); m.put("free", free); m.put("locked", locked);
                    m.put("price", round(price)); m.put("valueUsdt", round(value));
                    assets.add(m);
                }
            }
            assets.sort((a, c) -> Double.compare(
                    ((Number) c.get("valueUsdt")).doubleValue(), ((Number) a.get("valueUsdt")).doubleValue()));
            if (assets.size() > maxAssets) assets = assets.subList(0, maxAssets);
            final double tot = totalUsdt;
            for (Map<String, Object> m : assets)
                m.put("weight", tot > 0 ? round(((Number) m.get("valueUsdt")).doubleValue() / tot * 100) : 0.0);
            return Map.of("asset", "USDT", "free", usdtFree, "locked", usdtLocked, "real", true,
                    "totalUsdt", round(totalUsdt), "assetCount", assets.size(), "balances", assets);
        } catch (Exception e) {
            log.warn("查真實餘額失敗,回退示意: {}", e.getMessage());
            if (isAuthOrIpError(e.getMessage())) alertAuth(e.getMessage());
            return Map.of("asset", "USDT", "free", 10000.0, "locked", 0.0,
                    "real", false, "totalUsdt", 10000.0, "balances", List.of());
        }
    }

    private Map<String, Double> allPrices() {
        Map<String, Double> map = new HashMap<>();
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(marketRestUrl + "/api/v3/ticker/price")).timeout(Duration.ofSeconds(8)).GET().build();
            JsonNode arr = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            for (JsonNode t : arr) map.put(t.get("symbol").asText(), t.get("price").asDouble());
        } catch (Exception e) { log.warn("取行情估值失敗: {}", e.getMessage()); }
        return map;
    }

    private double round(double d) { return Math.round(d * 100.0) / 100.0; }

    private static String hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte x : raw) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
