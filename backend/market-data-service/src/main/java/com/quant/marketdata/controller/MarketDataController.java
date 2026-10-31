package com.quant.marketdata.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import com.quant.marketdata.config.BinanceProperties;
import com.quant.marketdata.service.KlineHistoryLoader;
import com.quant.marketdata.service.KlinePersister;
import com.quant.marketdata.service.KlineRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 行情查詢 API（見架構文件 §5.2.2）：歷史 K 線（幣安 REST）+ 訂單簿深度快照。 */
@RestController
@RequestMapping("/")
public class MarketDataController {

    private final StringRedisTemplate redis;
    private final BinanceProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final KlineRepository klines;
    private final KlinePersister persister;
    private final KlineHistoryLoader loader;

    public MarketDataController(StringRedisTemplate redis, BinanceProperties props,
                                KlineRepository klines, KlinePersister persister, KlineHistoryLoader loader) {
        this.redis = redis; this.props = props; this.klines = klines; this.persister = persister; this.loader = loader;
    }

    /**
     * 歷史 K 線：GET /api/market/klines?symbol=BTCUSDT&interval=1m&limit=200
     * 先讀 TimescaleDB；庫裡不夠（沒訂閱的幣 / interval、剛開機）才打幣安 REST，抓到的順手落庫。
     */
    @GetMapping("/klines")
    public ApiResponse<List<Kline>> klines(@RequestParam String symbol,
                                           @RequestParam(defaultValue = "1m") String interval,
                                           @RequestParam(defaultValue = "200") int limit) {
        int lim = Math.min(Math.max(limit, 1), 1000);
        String sym = symbol.toUpperCase();
        try {
            List<Kline> fromDb = klines.latest(sym, interval, lim);
            if (fromDb.size() >= lim) return ApiResponse.ok(fromDb);
        } catch (Exception e) { /* DB 掛了退回 REST */ }
        List<Kline> out = fetchFromBinance(sym, interval, lim);
        try { if (!out.isEmpty()) klines.upsertAll(out.stream().filter(Kline::closed).toList()); } catch (Exception ignore) {}
        return ApiResponse.ok(out);
    }

    /** 區間查詢（回測用）：GET /api/market/klines/range?symbol=&interval=&from=ms&to=ms&limit=5000 */
    @GetMapping("/klines/range")
    public ApiResponse<List<Kline>> range(@RequestParam String symbol, @RequestParam(defaultValue = "1m") String interval,
                                          @RequestParam long from, @RequestParam long to,
                                          @RequestParam(defaultValue = "5000") int limit) {
        return ApiResponse.ok(klines.range(symbol.toUpperCase(), interval, from, to, Math.min(limit, 50_000)));
    }

    /** 落庫狀態：GET /api/market/klines/status */
    @GetMapping("/klines/status")
    public ApiResponse<Object> status() {
        return ApiResponse.ok(java.util.Map.of("persister", persister.stats(), "loader", loader.stats(), "coverage", klines.coverage()));
    }

    /** 手動觸發歷史暖機：POST /api/market/klines/warmup */
    @PostMapping("/klines/warmup")
    public ApiResponse<Object> warmup() {
        new Thread(loader::loadAll, "kline-warmup-manual").start();
        return ApiResponse.ok("started");
    }

    private List<Kline> fetchFromBinance(String symbol, String interval, int limit) {
        List<Kline> out = new ArrayList<>();
        try {
            String url = props.getRestBaseUrl() + "/api/v3/klines?symbol=" + symbol.toUpperCase()
                    + "&interval=" + interval + "&limit=" + Math.min(limit, 1000);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode arr = mapper.readTree(resp.body());
                long now = System.currentTimeMillis();
                for (JsonNode k : arr) {
                    long closeTime = k.get(6).asLong();
                    out.add(new Kline(symbol, interval,
                            k.get(0).asLong(), k.get(1).asDouble(), k.get(2).asDouble(),
                            k.get(3).asDouble(), k.get(4).asDouble(), k.get(5).asDouble(),
                            closeTime, closeTime < now));   // 最後一根通常未收盤，以前一律標 true 是錯的
                }
            }
        } catch (Exception ignore) {}
        return out;
    }

    /** 即時訂單簿深度快照：GET /api/market/depth?symbol=BTCUSDT */
    @GetMapping("/depth")
    public ApiResponse<Object> depth(@RequestParam String symbol) {
        String json = redis.opsForValue().get("depth:" + symbol.toUpperCase());
        return ApiResponse.ok(json == null ? "{}" : json);
    }
}
