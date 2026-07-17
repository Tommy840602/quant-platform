package com.quant.marketdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.dto.Kline;
import com.quant.marketdata.config.BinanceProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 幣安行情 WebSocket 客戶端（見架構文件 §6）：kline + 訂單簿深度(partial depth)。
 * 含斷線指數退避重連。
 */
@Service
public class BinanceWebSocketClient extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceWebSocketClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService reconnect = Executors.newSingleThreadScheduledExecutor();

    private final BinanceProperties props;
    private final MarketDataService marketDataService;
    private int retry = 0;

    public BinanceWebSocketClient(BinanceProperties props, MarketDataService marketDataService) {
        this.props = props;
        this.marketDataService = marketDataService;
    }

    @PostConstruct
    public void connect() {
        String streams = String.join("/", props.getStreams());
        String url = props.getWsBaseUrl() + "/stream?streams=" + streams;
        try {
            new StandardWebSocketClient()
                    .execute(this, new WebSocketHttpHeaders(), URI.create(url))
                    .whenComplete((s, ex) -> { if (ex != null) scheduleReconnect(); });
        } catch (Exception e) { log.error("WS connect failed: {}", e.getMessage()); scheduleReconnect(); }
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        this.retry = 0; log.info("Binance WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = mapper.readTree(message.getPayload());
            String stream = root.path("stream").asText("");
            JsonNode data = root.path("data");
            if (stream.contains("@kline") && data.has("k")) {
                JsonNode k = data.get("k");
                Kline kline = new Kline(k.get("s").asText(), k.get("i").asText(), k.get("t").asLong(),
                        k.get("o").asDouble(), k.get("h").asDouble(), k.get("l").asDouble(),
                        k.get("c").asDouble(), k.get("v").asDouble(), k.get("T").asLong(), k.get("x").asBoolean());
                marketDataService.onKline(kline);
            } else if (stream.contains("@depth") && data.has("bids")) {
                // partial book depth: 從 stream 名取 symbol（btcusdt@depth10@1000ms）
                String symbol = stream.substring(0, stream.indexOf('@')).toUpperCase();
                marketDataService.onDepth(symbol, data.toString());
            }
        } catch (Exception e) { log.warn("parse WS msg failed: {}", e.getMessage()); }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("Binance WS closed: {} — reconnect", status); scheduleReconnect();
    }

    private void scheduleReconnect() {
        long delay = Math.min(60, (long) Math.pow(2, Math.min(retry++, 6)));
        reconnect.schedule(this::connect, delay, TimeUnit.SECONDS);
    }
}
