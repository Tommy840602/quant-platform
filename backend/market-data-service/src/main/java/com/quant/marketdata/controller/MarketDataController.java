package com.quant.marketdata.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import com.quant.marketdata.config.BinanceProperties;
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

    public MarketDataController(StringRedisTemplate redis, BinanceProperties props) {
        this.redis = redis; this.props = props;
    }

    /** 歷史 K 線：GET /api/market/klines?symbol=BTCUSDT&interval=1m&limit=200 （幣安 REST 直取，TODO：改由 TimescaleDB + 回補）。 */
    @GetMapping("/klines")
    public ApiResponse<List<Kline>> klines(@RequestParam String symbol,
                                           @RequestParam(defaultValue = "1m") String interval,
                                           @RequestParam(defaultValue = "200") int limit) {
        List<Kline> out = new ArrayList<>();
        try {
            String url = props.getRestBaseUrl() + "/api/v3/klines?symbol=" + symbol.toUpperCase()
                    + "&interval=" + interval + "&limit=" + Math.min(limit, 1000);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode arr = mapper.readTree(resp.body());
                for (JsonNode k : arr) {
                    out.add(new Kline(symbol.toUpperCase(), interval,
                            k.get(0).asLong(), k.get(1).asDouble(), k.get(2).asDouble(),
                            k.get(3).asDouble(), k.get(4).asDouble(), k.get(5).asDouble(),
                            k.get(6).asLong(), true));
                }
            }
        } catch (Exception ignore) {}
        return ApiResponse.ok(out);
    }

    /** 即時訂單簿深度快照：GET /api/market/depth?symbol=BTCUSDT */
    @GetMapping("/depth")
    public ApiResponse<Object> depth(@RequestParam String symbol) {
        String json = redis.opsForValue().get("depth:" + symbol.toUpperCase());
        return ApiResponse.ok(json == null ? "{}" : json);
    }
}
