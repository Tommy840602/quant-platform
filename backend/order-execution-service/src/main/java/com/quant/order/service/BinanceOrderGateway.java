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
    private final SymbolFilterService filters;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;
    private volatile long lastAuthAlert = 0;

    public BinanceOrderGateway(NotifyClient notifyClient, SymbolFilterService filters,
                               org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.notifyClient = notifyClient; this.filters = filters; this.redis = redis;
    }

    private double lastPrice(String symbol) {
        try { String v = redis.opsForValue().get("price:" + symbol); return v == null ? 0 : Double.parseDouble(v); }
        catch (Exception e) { return 0; }
    }

    /**
     * 參考價：Redis 行情快取 → 交易所公開 ticker。回 0 代表「拿不到」，呼叫端必須拒單而不是放行。
     * 以前只看 Redis，20 個串流以外的幣 / 合約 / Redis 剛重啟都會是 0，名目上限因此 fail-open。
     */
    public double referencePrice(String symbol, boolean futures) {
        double p = lastPrice(symbol);
        if (p > 0) return p;
        try {
            String base = futures ? futUrl : spotUrl;
            String path = futures ? "/fapi/v1/ticker/price" : "/api/v3/ticker/price";
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path + "?symbol=" + symbol))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"price\"\\s*:\\s*\"([0-9.]+)\"").matcher(resp.body());
                if (m.find()) return Double.parseDouble(m.group(1));
            }
        } catch (Exception e) {
            log.warn("參考價查詢失敗 {}: {}", symbol, e.getMessage());
        }
        return 0;
    }

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

    // 合約 User Data Stream（listenKey 版，合約仍支援）
    @Value("${binance.futures-ws-url:wss://stream.binancefuture.com}") private String futWsUrl;
    @Value("${binance.mainnet-futures-ws-url:wss://fstream.binance.com}") private String futMainnetWsUrl;

    private String testnetSpotUrl, testnetFutUrl, testnetFutWsUrl;   // 保留測試網位址以便切回

    @PostConstruct
    void resolveEnv() {
        testnetSpotUrl = spotUrl;  testnetFutUrl = futUrl;  testnetFutWsUrl = futWsUrl;   // 先記住 testnet 位址
        if ("mainnet".equalsIgnoreCase(env)) {
            spotUrl = spotMainnetUrl;  futUrl = futMainnetUrl;  futWsUrl = futMainnetWsUrl;
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
            futWsUrl = m ? futMainnetWsUrl : testnetFutWsUrl;
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

    /** 唯一的 dry-run 真相來源：OrderService / UDS 必須讀這裡，不可各自持有 @Value 副本。 */
    public boolean isDryRun() { return dryRun; }

    /** 給合約 UDS 用：依目前 env 解析後的合約 REST / WS 位址與 API key（listenKey 只需 key，不需簽名）。 */
    public String futuresRestUrl() { return futUrl; }
    public String futuresWsUrl()   { return futWsUrl; }
    public String futuresApiKey()  { return futKey == null ? "" : futKey; }
    public String currentEnv()     { return env; }

    /** 傳輸層失敗（逾時 / 斷線 / 5xx）：交易所「可能」已收到單，呼叫端必須回查而非直接標 REJECTED。 */
    public static class TransportException extends RuntimeException {
        public TransportException(String m, Throwable c) { super(m, c); }
    }
    /** 交易所明確拒絕（4xx，含 -2010 餘額不足 / -1013 filter 等）：單子確定沒進簿。 */
    public static class ExchangeRejectedException extends RuntimeException {
        public final int httpStatus; public final int code;
        public ExchangeRejectedException(int httpStatus, int code, String m) { super(m); this.httpStatus = httpStatus; this.code = code; }
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
        // ── 交易所 filter 對齊：數量→stepSize、價格→tickSize、檢查 minQty/minNotional ──
        double[] f = filters.filters(baseUrl(intent), intent.symbol(), intent.isFutures());
        if (f == null || (f[0] == 0 && f[1] == 0 && f[3] == 0))
            throw new IllegalStateException("取不到 " + intent.symbol() + " 的交易規則（exchangeInfo），拒絕送未對齊的真單");
        double step = f[0], tick = f[1], minQty = f[2], minNotional = f[3];
        double qty = step > 0 ? SymbolFilterService.floorToStep(intent.quantity(), step) : intent.quantity();
        if (minQty > 0 && qty < minQty)
            throw new RuntimeException("數量 " + trim(qty) + " 低於 minQty " + trim(minQty) + " (" + intent.symbol() + ")");
        Double px = intent.price();
        boolean isLimit = intent.type().name().equals("LIMIT");
        if (isLimit && tick > 0 && px != null) px = SymbolFilterService.floorToStep(px, tick);
        double ref = (px != null && px > 0) ? px : referencePrice(intent.symbol(), intent.isFutures());
        if (ref <= 0) throw new IllegalStateException("取不到 " + intent.symbol() + " 參考價，無法檢查 minNotional，拒絕送單");
        if (minNotional > 0 && qty * ref < minNotional)
            throw new RuntimeException("名目 " + trim(qty * ref) + " 低於 minNotional " + trim(minNotional)
                    + " (" + intent.symbol() + ")，請加大下單量");

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", intent.symbol());
        p.put("side", intent.side().name());
        p.put("type", intent.type().name());
        p.put("quantity", trim(qty));
        if (isLimit) {
            p.put("timeInForce", "GTC");
            p.put("price", trim(px));
        }
        if (intent.isFutures() && intent.reduceOnly()) p.put("reduceOnly", "true");
        // 現貨用 newClientOrderId；合約亦支援
        p.put("newClientOrderId", intent.clientOrderId());
        // RESULT：回應帶 status / executedQty / avgPrice(合約) / cummulativeQuoteQty(現貨)。
        // 合約預設 ACK 只回 executedQty=0，市價單立刻成交也記不到 → 一律要求 RESULT。
        p.put("newOrderRespType", "RESULT");
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
        double[] f = filters.filters(spotUrl, symbol, false);
        if (f == null || (f[0] == 0 && f[1] == 0 && f[3] == 0))
            throw new IllegalStateException("取不到 " + symbol + " 的交易規則，拒絕送 OCO");
        double step = f[0], tick = f[1], minQty = f[2], minNotional = f[3];
        double qty = step > 0 ? SymbolFilterService.floorToStep(quantity, step) : quantity;
        if (minQty > 0 && qty < minQty) throw new IllegalStateException("數量 " + trim(qty) + " 低於 minQty " + trim(minQty));
        double px = tick > 0 ? SymbolFilterService.floorToStep(price, tick) : price;
        double sp = tick > 0 ? SymbolFilterService.floorToStep(stopPrice, tick) : stopPrice;
        double slp = tick > 0 ? SymbolFilterService.floorToStep(stopLimitPrice, tick) : stopLimitPrice;
        if (minNotional > 0 && Math.min(qty * px, qty * slp) < minNotional)
            throw new IllegalStateException("OCO 任一腿名目低於 minNotional " + trim(minNotional));

        Map<String, String> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("side", side);
        p.put("quantity", trim(qty));
        p.put("price", trim(px));                   // 限價腿
        p.put("stopPrice", trim(sp));               // 觸發價
        p.put("stopLimitPrice", trim(slp));
        p.put("stopLimitTimeInForce", "GTC");
        p.put("listClientOrderId", listClientOrderId);
        p.put("limitClientOrderId", listClientOrderId + "-L");   // 讓 UDS executionReport 能對回我們的訂單列
        p.put("stopClientOrderId", listClientOrderId + "-S");
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
            HttpResponse<String> resp;
            try {
                resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // 逾時 / 連線中斷：請求可能已到交易所 → 由呼叫端回查
                throw new TransportException(action + "傳輸失敗: " + e.getMessage(), e);
            }
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("幣安{}成功: {}", action, resp.body());
                return resp.body();
            }
            String err = "HTTP " + resp.statusCode() + ": " + resp.body();
            if (isAuthOrIpError(err)) alertAuth(action, err);
            if (resp.statusCode() >= 500 || resp.statusCode() == 408 || resp.statusCode() == 429)
                throw new TransportException("幣安" + action + "失敗 " + err, null);   // 伺服器端狀態不明
            int code = 0;
            try {
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)").matcher(resp.body());
                if (mm.find()) code = Integer.parseInt(mm.group(1));
            } catch (Exception ignore) {}
            throw new ExchangeRejectedException(resp.statusCode(), code, "幣安" + action + "失敗 " + err);
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
