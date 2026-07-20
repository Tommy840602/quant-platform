package com.quant.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易所交易規則（filters）快取：LOT_SIZE(stepSize/minQty)、PRICE_FILTER(tickSize)、MIN_NOTIONAL。
 * 下單前把數量對齊 stepSize、價格對齊 tickSize，並檢查最小數量/最小名目，避免 -1013 Filter failure。
 */
@Service
public class SymbolFilterService {

    private static final Logger log = LoggerFactory.getLogger(SymbolFilterService.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    // key = "S:BTCUSDT" / "F:BTCUSDT" -> [stepSize, tickSize, minQty, minNotional]
    private final Map<String, double[]> cache = new ConcurrentHashMap<>();

    /** 取得該 symbol 的 filters；失敗回 {0,0,0,0}（代表未知，呼叫端不強制 round）。 */
    public double[] filters(String base, String symbol, boolean futures) {
        String key = (futures ? "F:" : "S:") + symbol.toUpperCase();
        double[] c = cache.get(key);
        if (c != null) return c;
        double[] f = fetch(base, symbol, futures);
        if (f != null) { cache.put(key, f); return f; }
        return new double[]{0, 0, 0, 0};
    }

    private double[] fetch(String base, String symbol, boolean futures) {
        try {
            String path = futures ? "/fapi/v1/exchangeInfo"
                    : "/api/v3/exchangeInfo?symbol=" + symbol.toUpperCase();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(base + path))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            JsonNode root = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            for (JsonNode s : root.path("symbols")) {
                if (!symbol.equalsIgnoreCase(s.path("symbol").asText())) continue;
                double step = 0, tick = 0, minQty = 0, minNotional = 0;
                for (JsonNode f : s.path("filters")) {
                    switch (f.path("filterType").asText()) {
                        case "LOT_SIZE" -> { step = f.path("stepSize").asDouble(0); minQty = f.path("minQty").asDouble(0); }
                        case "PRICE_FILTER" -> tick = f.path("tickSize").asDouble(0);
                        case "MIN_NOTIONAL" -> minNotional = f.path("minNotional").asDouble(f.path("notional").asDouble(0));
                        case "NOTIONAL" -> minNotional = f.path("minNotional").asDouble(0);
                        default -> { }
                    }
                }
                log.info("filters {} step={} tick={} minQty={} minNotional={}", symbol, step, tick, minQty, minNotional);
                return new double[]{step, tick, minQty, minNotional};
            }
            log.warn("exchangeInfo 找不到 symbol {}", symbol);
        } catch (Exception e) {
            log.warn("取 {} filters 失敗: {}", symbol, e.getMessage());
        }
        return null;
    }

    /** 向下對齊到步進（數量用；避免超出可用）。step<=0 時原值回傳。 */
    public static double floorToStep(double value, double step) {
        if (step <= 0) return value;
        BigDecimal v = BigDecimal.valueOf(value), s = BigDecimal.valueOf(step);
        return v.divide(s, 0, RoundingMode.FLOOR).multiply(s).stripTrailingZeros().doubleValue();
    }
}
