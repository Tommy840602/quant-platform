package com.quant.marketdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.dto.Kline;
import com.quant.marketdata.config.BinanceProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 幣安行情 WebSocket 客戶端（見架構文件 §6）：kline + 訂單簿深度(partial depth)。
 * - 斷線指數退避重連
 * - 重連後先用 REST 補抓斷線期間漏掉的已收盤 K 線，再處理即時訊息（同一執行緒，順序有保證）
 * - 看門狗：超過 60 秒沒收到任何訊息（NAT 斷線不會送 close frame）→ 主動關閉觸發重連
 */
@Service
public class BinanceWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketClient.class);
    static final long STALE_MS = 60_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "market-ws-reconnect"); t.setDaemon(true); return t;
    });

    private final BinanceProperties props;
    private final MarketDataService marketDataService;
    private final KlineBackfillService backfill;
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private volatile int retry = 0;
    private volatile WebSocketSession session;
    private volatile long lastMessageAt = 0;
    private volatile long disconnectedAt = 0;

    /** 每個 "SYMBOL|interval" 最後一根「已收盤」K 線的 openTime；重連補抓從這之後開始。 */
    private final Map<String, Long> lastClosedOpenTime = new ConcurrentHashMap<>();

    public BinanceWebSocketClient(BinanceProperties props, MarketDataService marketDataService, KlineBackfillService backfill) {
        this.props = props;
        this.marketDataService = marketDataService;
        this.backfill = backfill;
    }

    @PostConstruct
    public void connect() {
        reconnectPending.set(false);
        String streams = String.join("/", props.getStreams());
        String url = props.getWsBaseUrl() + "/stream?streams=" + streams;
        try {
            new StandardWebSocketClient()
                    .execute(this, new WebSocketHttpHeaders(), URI.create(url))
                    .whenComplete((s, ex) -> { if (ex != null) { log.warn("WS connect error: {}", ex.getMessage()); scheduleReconnect(); } });
        } catch (Exception e) { log.error("WS connect failed: {}", e.getMessage()); scheduleReconnect(); }
    }

    @Override public void afterConnectionEstablished(WebSocketSession s) {
        this.session = s;
        this.retry = 0;
        this.lastMessageAt = System.currentTimeMillis();
        log.info("Binance WS connected: {}", s.getId());
        // 在 onOpen 內同步補抓：容器在 onOpen 回傳前不會派送訊息，補的舊 K 線一定先於即時 K 線
        backfillGaps();
    }

    /** 對每條 kline 串流補抓上次已收盤 K 線之後、現在之前的 K 線。首次連線（沒有記錄）不補。 */
    void backfillGaps() {
        if (lastClosedOpenTime.isEmpty()) return;
        long now = System.currentTimeMillis();
        long gapMs = disconnectedAt > 0 ? now - disconnectedAt : 0;
        int total = 0;
        for (String stream : props.getStreams()) {
            int at = stream.indexOf("@kline_");
            if (at < 0) continue;
            String symbol = stream.substring(0, at).toUpperCase();
            String interval = stream.substring(at + "@kline_".length());
            Long since = lastClosedOpenTime.get(key(symbol, interval));
            if (since == null) continue;
            List<Kline> missed = backfill.fetchClosedSince(symbol, interval, since, now);
            for (Kline k : missed) {
                marketDataService.onKline(k);
                lastClosedOpenTime.put(key(symbol, interval), k.openTime());
            }
            total += missed.size();
        }
        log.info("WS 重連補抓完成：斷線 {}s，補 {} 根已收盤 K 線", gapMs / 1000, total);
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
        lastMessageAt = System.currentTimeMillis();
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            String stream = root.path("stream").asText("");
            JsonNode data = root.path("data");
            if (stream.contains("@kline") && data.has("k")) {
                JsonNode k = data.get("k");
                Kline kline = new Kline(k.get("s").asText(), k.get("i").asText(), k.get("t").asLong(),
                        k.get("o").asDouble(), k.get("h").asDouble(), k.get("l").asDouble(),
                        k.get("c").asDouble(), k.get("v").asDouble(), k.get("T").asLong(), k.get("x").asBoolean());
                if (kline.closed()) {
                    // 已收盤 K 線重複（補抓後串流又送一次同一根）直接略過
                    Long last = lastClosedOpenTime.get(key(kline.symbol(), kline.interval()));
                    if (last != null && kline.openTime() <= last) return;
                    lastClosedOpenTime.put(key(kline.symbol(), kline.interval()), kline.openTime());
                }
                marketDataService.onKline(kline);
            } else if (stream.contains("@depth") && data.has("bids")) {
                // partial book depth: 從 stream 名取 symbol（btcusdt@depth10@1000ms）
                String symbol = stream.substring(0, stream.indexOf('@')).toUpperCase();
                marketDataService.onDepth(symbol, data.toString());
            }
        } catch (Exception e) { log.warn("parse WS msg failed: {}", e.getMessage()); }
    }

    @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        session = null;
        disconnectedAt = System.currentTimeMillis();
        log.warn("Binance WS closed: {} — reconnect", status);
        scheduleReconnect();
    }

    @Override public void handleTransportError(WebSocketSession s, Throwable ex) {
        log.warn("Binance WS transport error: {}", ex.getMessage());
        closeQuietly();
    }

    /** 看門狗：連線還在但 60 秒沒任何訊息（20 個 1m kline 串流正常每秒都有）→ 視為殭屍連線，關掉觸發重連。 */
    @Scheduled(initialDelay = 30_000, fixedDelay = 15_000)
    public void watchdog() {
        WebSocketSession s = session;
        if (s == null || lastMessageAt == 0) return;
        long silent = System.currentTimeMillis() - lastMessageAt;
        if (silent > STALE_MS) {
            log.warn("Binance WS {}s 無訊息，判定殭屍連線，主動重連", silent / 1000);
            closeQuietly();
        }
    }

    private void closeQuietly() {
        WebSocketSession s = session;
        if (s != null) { try { s.close(CloseStatus.GOING_AWAY); } catch (Exception ignore) {} }
    }

    private void scheduleReconnect() {
        if (!reconnectPending.compareAndSet(false, true)) return;   // close + error 同時觸發只排一次
        long delay = Math.min(60, (long) Math.pow(2, Math.min(retry++, 6)));
        reconnect.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    static String key(String symbol, String interval) { return symbol + "|" + interval; }

    /** 給監控用：最後收到訊息的時間。 */
    public long lastMessageAt() { return lastMessageAt; }
    public boolean connected() { WebSocketSession s = session; return s != null && s.isOpen(); }
}
