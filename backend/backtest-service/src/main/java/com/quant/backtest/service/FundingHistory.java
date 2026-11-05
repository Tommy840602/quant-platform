package com.quant.backtest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 歷史資金費率（幣安公開 GET /fapi/v1/fundingRate，正式網資料）：合約回測每 8 小時結算一次資金費。
 * 依 (symbol, 天) 快取，同一段期間重跑不重抓。
 */
@Service
public class FundingHistory {
    private static final Logger log = LoggerFactory.getLogger(FundingHistory.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, NavigableMap<Long, Double>> cache = new ConcurrentHashMap<>();

    @Value("${backtest.funding-rest-url:https://fapi.binance.com}") String base;

    /** fundingTime(ms) → rate（小數）；拿不到回空 map（回測照跑，只是不扣資金費）。 */
    public NavigableMap<Long, Double> range(String symbol, long fromMs, long toMs) {
        String key = symbol + "|" + fromMs / 86_400_000 + "|" + toMs / 86_400_000;
        return cache.computeIfAbsent(key, k -> fetch(symbol, fromMs, toMs));
    }

    NavigableMap<Long, Double> fetch(String symbol, long fromMs, long toMs) {
        NavigableMap<Long, Double> out = new TreeMap<>();
        long start = fromMs - 8 * 3_600_000L;
        try {
            for (int page = 0; page < 20 && start < toMs; page++) {
                String url = base + "/fapi/v1/fundingRate?symbol=" + symbol + "&startTime=" + start + "&endTime=" + toMs + "&limit=1000";
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) { log.warn("資金費率歷史 {} HTTP {}", symbol, r.statusCode()); break; }
                JsonNode arr = mapper.readTree(r.body());
                if (!arr.isArray() || arr.isEmpty()) break;
                long last = start;
                for (JsonNode n : arr) { long t = n.path("fundingTime").asLong(); out.put(t, n.path("fundingRate").asDouble()); last = Math.max(last, t); }
                if (arr.size() < 1000) break;
                start = last + 1;
            }
        } catch (Exception e) { log.warn("資金費率歷史 {} 取得失敗：{}（回測不扣資金費）", symbol, e.getMessage()); }
        return out;
    }
}
