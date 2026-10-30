package com.quant.marketdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.dto.Kline;
import com.quant.marketdata.config.BinanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * WS 斷線期間漏掉的 K 線用 REST /api/v3/klines 補抓（只補「已收盤」的）。
 * 重連時由 BinanceWebSocketClient 在開始處理即時訊息前同步呼叫，保證補的舊 K 線先於新 K 線送出。
 */
@Service
public class KlineBackfillService {

    private static final Logger log = LoggerFactory.getLogger(KlineBackfillService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final BinanceProperties props;

    public KlineBackfillService(BinanceProperties props) { this.props = props; }

    /**
     * 抓 openTime > sinceOpenTime 且已收盤（closeTime < now）的 K 線，依時間排序。最多 1000 根（幣安單次上限）。
     * 失敗回空清單並記 log —— 補抓是盡力而為，不可擋住重連。
     */
    public List<Kline> fetchClosedSince(String symbol, String interval, long sinceOpenTime, long now) {
        try {
            String url = props.getRestBaseUrl() + "/api/v3/klines?symbol=" + symbol + "&interval=" + interval
                    + "&startTime=" + (sinceOpenTime + 1) + "&limit=1000";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("補抓 K 線失敗 {} {} HTTP {}: {}", symbol, interval, resp.statusCode(), resp.body());
                return List.of();
            }
            return parse(resp.body(), symbol, interval, sinceOpenTime, now);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("補抓 K 線失敗 {} {}: {}", symbol, interval, e.getMessage());
            return List.of();
        }
    }

    /** 幣安 klines 陣列：[openTime, o, h, l, c, v, closeTime, …]。只留 openTime > since 且 closeTime < now 的。 */
    static List<Kline> parse(String json, String symbol, String interval, long sinceOpenTime, long now) throws Exception {
        JsonNode arr = MAPPER.readTree(json);
        List<Kline> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode row : arr) {
            long openTime = row.get(0).asLong();
            long closeTime = row.get(6).asLong();
            if (openTime <= sinceOpenTime || closeTime >= now) continue;   // 未收盤的交給即時串流
            out.add(new Kline(symbol, interval, openTime,
                    row.get(1).asDouble(), row.get(2).asDouble(), row.get(3).asDouble(),
                    row.get(4).asDouble(), row.get(5).asDouble(), closeTime, true));
        }
        out.sort((a, b) -> Long.compare(a.openTime(), b.openTime()));
        return out;
    }
}
