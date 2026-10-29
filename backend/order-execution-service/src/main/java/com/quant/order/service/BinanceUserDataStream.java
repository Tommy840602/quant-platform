package com.quant.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 幣安 User Data Stream（見架構文件 §6.1、§9.2）。
 * ⚠️ 幣安已停用現貨 listenKey 版 UDS（POST /api/v3/userDataStream 回 HTTP 410）。
 *    本類別保留為參考；偵測到端點停用時會優雅略過，改由 OrderService 解析下單回應取得真實成交。
 *    若要即時追蹤「掛單後才成交」，請改接新版 WebSocket API user data stream（session.logon + Ed25519）。
 */
@Service
public class BinanceUserDataStream extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceUserDataStream.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Value("${trading.dry-run:true}") private boolean dryRun;
    @Value("${binance.rest-base-url:https://testnet.binance.vision}") private String restBaseUrl;
    @Value("${binance.ws-base-url:wss://stream.testnet.binance.vision}") private String wsBaseUrl;
    @Value("${binance.api-key:}") private String apiKey;
    @Value("${binance.uds-listenkey-enabled:false}") private boolean enabled;

    private final KafkaTemplate<String, Object> kafka;
    private String listenKey;

    public BinanceUserDataStream(KafkaTemplate<String, Object> kafka) { this.kafka = kafka; }

    @PostConstruct
    public void init() {
        if (dryRun || apiKey.isBlank() || !enabled) {
            log.info("User Data Stream 未啟用（成交回報改由訂單回應解析；listenKey 版端點幣安已停用）");
            return;
        }
        try {
            String key = createListenKey();
            if (key == null) {
                log.warn("listenKey 端點不可用（可能已停用）— 略過 UDS，改用訂單回應解析成交");
                return;
            }
            this.listenKey = key;
            connect();
            scheduler.scheduleAtFixedRate(this::keepAlive, 30, 30, TimeUnit.MINUTES);
            log.info("User Data Stream 已啟用");
        } catch (Exception e) {
            log.warn("User Data Stream 啟動略過: {}", e.getMessage());
        }
    }

    private String createListenKey() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(restBaseUrl + "/api/v3/userDataStream"))
                .header("X-MBX-APIKEY", apiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 || !resp.body().trim().startsWith("{")) return null;
        return mapper.readTree(resp.body()).get("listenKey").asText();
    }

    private void keepAlive() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(restBaseUrl + "/api/v3/userDataStream?listenKey=" + listenKey))
                    .header("X-MBX-APIKEY", apiKey)
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10)).build();
            http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) { log.warn("keepalive 失敗: {}", e.getMessage()); }
    }

    private void connect() {
        new StandardWebSocketClient()
                .execute(this, new WebSocketHttpHeaders(), URI.create(wsBaseUrl + "/ws/" + listenKey))
                .whenComplete((s, ex) -> { if (ex != null) log.warn("UDS WS 連線失敗: {}", ex.getMessage()); });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("User Data Stream WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode e = mapper.readTree(message.getPayload());
            if (!"executionReport".equals(e.path("e").asText())) return;
            String symbol = e.get("s").asText();
            OrderStatus status = mapStatus(e.get("X").asText());
            double lastQty = e.path("l").asDouble();
            double lastPx = e.path("L").asDouble();
            com.quant.common.enums.OrderSide side = "SELL".equals(e.path("S").asText()) ? com.quant.common.enums.OrderSide.SELL : com.quant.common.enums.OrderSide.BUY;
            String eventId = "uds|" + e.get("c").asText() + "|" + e.path("t").asLong(-1) + "|" + e.path("X").asText() + "|" + e.path("E").asLong(0);
            kafka.send(Topics.ORDER_EVENTS, symbol, new OrderEvent(
                    e.get("c").asText(), e.get("i").asText(), symbol, side, status,
                    lastQty > 0 ? lastQty : 0, lastPx, System.currentTimeMillis(), "main", eventId));
            log.info("UDS 成交回報 {} {} l={} @ {}", symbol, status, lastQty, lastPx);
        } catch (Exception ex) { log.warn("解析 executionReport 失敗: {}", ex.getMessage()); }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (listenKey != null) scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private OrderStatus mapStatus(String x) {
        return switch (x) {
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELED" -> OrderStatus.CANCELED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "EXPIRED" -> OrderStatus.EXPIRED;
            default -> OrderStatus.SUBMITTED;
        };
    }
}
