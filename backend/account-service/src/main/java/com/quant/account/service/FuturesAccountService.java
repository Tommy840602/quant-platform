package com.quant.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * USDT-M 合約錢包（簽名 /fapi/v2/account）：保證金餘額 = 錢包 + 未實現盈虧，以及每個持倉的名目與盈虧。
 * 權益要把它加進去，否則把 USDT 轉進合約錢包會被當成虧損、開空單的曝險也看不到。
 *
 * 防誤判：合約 API 暫時失敗時沿用上一份（最多 STALE_MS），不讓權益突然少一截觸發回撤熔斷；
 * 超過時限仍拿不到 → 回 unavailable，呼叫端要把權益標成 partial。
 */
@Service
public class FuturesAccountService {
    private static final Logger log = LoggerFactory.getLogger(FuturesAccountService.class);
    static final long CACHE_MS = 10_000, STALE_MS = 10 * 60_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final com.quant.common.binance.BinanceClock clock = new com.quant.common.binance.BinanceClock();
    private final BinanceAccountService spot;
    private final LongSupplier now;

    @Value("${binance.futures-rest-url:https://testnet.binancefuture.com}") String testUrl;
    @Value("${binance.futures-api-key:}") String testKey;
    @Value("${binance.futures-api-secret:}") String testSecret;
    @Value("${binance.mainnet-futures-rest-url:https://fapi.binance.com}") String mainUrl;
    @Value("${binance.mainnet-futures-api-key:}") String mainKey;
    @Value("${binance.mainnet-futures-api-secret:}") String mainSecret;

    private volatile Snapshot last;          // 最後一份成功的
    private volatile String lastEnv;
    private volatile String lastError;

    @org.springframework.beans.factory.annotation.Autowired
    public FuturesAccountService(BinanceAccountService spot) { this(spot, System::currentTimeMillis); }
    FuturesAccountService(BinanceAccountService spot, LongSupplier now) { this.spot = spot; this.now = now; }

    /** 合約錢包快照。positions 只含非 0 持倉。 */
    public record Snapshot(double walletBalance, double unrealizedPnl, double marginBalance, double availableBalance,
                           double grossNotional, List<Map<String, Object>> positions, long fetchedAt) {}

    /** 連線設定頁貼的金鑰（依 env 分開記，空 = 用 .env）。 */
    private final Map<String, String[]> uiKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRedis(org.springframework.data.redis.core.StringRedisTemplate r) { this.redis = r; }

    /** 連線設定頁：套用合約金鑰到目前 env（空字串 = 不變更）。 */
    public synchronized void applyConfig(String key, String secret) {
        boolean k = key != null && !key.isBlank(), s = secret != null && !secret.isBlank();
        if (!k && !s) return;
        String[] cur = uiKeys.getOrDefault(spot.env(), new String[]{key(), secret()});
        uiKeys.put(spot.env(), new String[]{k ? key.trim() : cur[0], s ? secret.trim() : cur[1]});
        last = null;   // 換金鑰 = 可能換帳戶，舊快照作廢
    }

    /** 合約總開關（與下單服務共用 Redis trading:futures-enabled；沒設過 = 開）。 */
    public boolean enabled() {
        try { return redis == null || !"0".equals(redis.opsForValue().get("trading:futures-enabled")); } catch (Exception e) { return true; }
    }

    /** 這個 env 有沒有設合約金鑰且合約開關打開（跟下單閘道同規則：正式網用 MAINNET_FUTURES 金鑰，沒設才退回 FUTURES）。 */
    public boolean configured() { return enabled() && !key().isBlank() && !secret().isBlank(); }
    public boolean keySet() { return !key().isBlank() && !secret().isBlank(); }

    private boolean mainnet() { return "mainnet".equalsIgnoreCase(spot.env()); }
    private String url()    { return mainnet() ? mainUrl : testUrl; }
    private String key()    { String[] u = uiKeys.get(spot.env()); if (u != null && !u[0].isBlank()) return u[0];
                              String k = mainnet() && mainKey != null && !mainKey.isBlank() ? mainKey : testKey;       return k == null ? "" : k.trim(); }
    private String secret() { String[] u = uiKeys.get(spot.env()); if (u != null && !u[1].isBlank()) return u[1];
                              String k = mainnet() && mainSecret != null && !mainSecret.isBlank() ? mainSecret : testSecret; return k == null ? "" : k.trim(); }

    /**
     * 取快照：10 秒內重用；失敗時沿用 10 分鐘內的舊值（stale=true）。
     * 沒設金鑰回 empty；設了但拿不到（且無可用舊值）也回 empty，用 {@link #unavailable()} 區分。
     */
    public synchronized Optional<Snapshot> snapshot() {
        if (!configured()) return Optional.empty();
        String env = spot.env();
        if (!env.equals(lastEnv)) { last = null; lastEnv = env; }   // 換 env：舊網路的數字不能用
        long t = now.getAsLong();
        if (last != null && t - last.fetchedAt() < CACHE_MS) return Optional.of(last);
        try {
            last = parse(fetch(), t);
            lastError = null;
            return Optional.of(last);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("查合約帳戶失敗{}: {}", last != null && t - last.fetchedAt() < STALE_MS ? "（沿用舊值）" : "", e.getMessage());
            return last != null && t - last.fetchedAt() < STALE_MS ? Optional.of(last) : Optional.empty();
        }
    }

    /** 有設金鑰、但目前（含舊值）拿不到 → 權益不完整。 */
    public boolean unavailable() {
        Snapshot s = last;
        return configured() && (s == null || now.getAsLong() - s.fetchedAt() >= STALE_MS);
    }
    public boolean stale() { Snapshot s = last; return s != null && now.getAsLong() - s.fetchedAt() >= CACHE_MS * 3; }
    public String lastError() { return lastError; }

    static Snapshot parse(String body, long t) throws Exception {
        JsonNode a = new ObjectMapper().readTree(body);
        if (!a.has("totalMarginBalance")) throw new IllegalStateException("回應缺 totalMarginBalance: " + body.substring(0, Math.min(200, body.length())));
        List<Map<String, Object>> pos = new ArrayList<>();
        double gross = 0;
        for (JsonNode p : a.path("positions")) {
            double amt = p.path("positionAmt").asDouble();
            if (amt == 0) continue;
            double notional = p.path("notional").asDouble();
            gross += Math.abs(notional);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", p.path("symbol").asText());
            m.put("side", amt > 0 ? "LONG" : "SHORT");
            m.put("positionAmt", amt);
            m.put("entryPrice", p.path("entryPrice").asDouble());
            m.put("notional", r2(notional));
            m.put("unrealizedPnl", r2(p.path("unrealizedProfit").asDouble()));
            m.put("leverage", p.path("leverage").asInt(1));
            pos.add(m);
        }
        pos.sort((x, y) -> Double.compare(Math.abs((double) y.get("notional")), Math.abs((double) x.get("notional"))));
        return new Snapshot(a.path("totalWalletBalance").asDouble(), a.path("totalUnrealizedProfit").asDouble(),
                a.path("totalMarginBalance").asDouble(), a.path("availableBalance").asDouble(), r2(gross), pos, t);
    }

    /** 給 /balance 用的摘要。 */
    public static Map<String, Object> toMap(Snapshot s, boolean stale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("walletBalance", r2(s.walletBalance())); m.put("unrealizedPnl", r2(s.unrealizedPnl()));
        m.put("marginBalance", r2(s.marginBalance())); m.put("availableBalance", r2(s.availableBalance()));
        m.put("grossNotional", s.grossNotional()); m.put("positions", s.positions()); m.put("stale", stale);
        return m;
    }

    /** 打幣安取原始 JSON（測試會覆寫）。 */
    String fetch() throws Exception {
        String base = url();
        HttpResponse<String> r = get(base);
        if (r.statusCode() != 200 && com.quant.common.binance.BinanceClock.isTimestampError(0, r.body())) {
            clock.trySync(base);
            r = get(base);
        }
        if (r.statusCode() != 200) throw new RuntimeException("HTTP " + r.statusCode() + ": " + r.body());
        return r.body();
    }

    private HttpResponse<String> get(String base) throws Exception {
        String q = "recvWindow=5000&timestamp=" + clock.now(base);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/fapi/v2/account?" + q + "&signature=" + hmac(q, secret())))
                .header("X-MBX-APIKEY", key()).timeout(Duration.ofSeconds(10)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static double r2(double d) { return Math.round(d * 100.0) / 100.0; }

    private static String hmac(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder sb = new StringBuilder();
        for (byte x : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
