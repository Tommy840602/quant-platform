package com.quant.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import com.quant.order.repository.OrderRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
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
 * USDT-M 合約 User Data Stream（listenKey 版；合約端點仍有效，與已停用的現貨 listenKey 不同）。
 * 流程：POST /fapi/v1/listenKey → 連 {futuresWs}/ws/{listenKey} → 每 30 分 PUT 續期（60 分過期）
 *       → 收 ORDER_TRADE_UPDATE 轉 OrderEvent 發 Kafka；收 listenKeyExpired 重新申請並重連。
 * 連上後觸發一次未完成合約單對帳，補回斷線期間漏掉的成交。
 * 啟用：binance.futures-user-data-stream-enabled=true 且非 dry-run。開了卻缺合約金鑰 → 啟動失敗（不靜默）。
 */
@Service
public class BinanceFuturesUserDataStream extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesUserDataStream.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "futures-uds"); t.setDaemon(true); return t;
    });

    @Value("${binance.futures-user-data-stream-enabled:false}") private boolean enabled;

    private final KafkaTemplate<String, Object> kafka;
    private final OrderRepository orders;
    private final BinanceOrderGateway gateway;
    private final UdsRegistry registry;
    private final OrderService orderService;

    private volatile String listenKey;
    private volatile WebSocketSession session;
    private volatile int backoffSec = 5;

    public BinanceFuturesUserDataStream(KafkaTemplate<String, Object> kafka, OrderRepository orders,
                                        BinanceOrderGateway gateway, UdsRegistry registry,
                                        @Lazy OrderService orderService) {
        this.kafka = kafka; this.orders = orders; this.gateway = gateway; this.registry = registry; this.orderService = orderService;
    }

    private final java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean(false);

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("合約 UDS 未啟用（BINANCE_FUTURES_UDS_ENABLED=false）；合約成交以下單回應(RESULT)解析 + 對帳排程補記");
            return;
        }
        if (gateway.futuresApiKey().isBlank())
            throw new IllegalStateException("BINANCE_FUTURES_UDS_ENABLED=true 但未設定合約 API 金鑰（BINANCE_FUTURES_API_KEY"
                    + ("mainnet".equalsIgnoreCase(gateway.currentEnv()) ? " / BINANCE_MAINNET_FUTURES_API_KEY" : "")
                    + "）。拒絕以「成交回報靜默遺失」的狀態啟動；請補金鑰或關閉 UDS");
        if (gateway.isDryRun()) {
            log.info("合約 UDS 已設定但目前 Dry-Run，先不連；關閉 Dry-Run / 上膛時自動啟動");
            return;
        }
        startIfNeeded("開機");
    }

    @org.springframework.context.event.EventListener
    public void onModeChanged(TradingModeChangedEvent e) {
        if (!enabled || e.effectiveDryRun() || gateway.futuresApiKey().isBlank()) return;
        startIfNeeded(e.by());
    }

    public synchronized void startIfNeeded(String by) {
        if (!started.compareAndSet(false, true)) return;
        registry.expect(UdsRegistry.FUTURES);
        scheduler.execute(this::start);
        scheduler.scheduleAtFixedRate(this::keepAlive, 30, 30, TimeUnit.MINUTES);
        log.info("合約 UDS 啟動中（{}）… env={} ws={}", by, gateway.currentEnv(), gateway.futuresWsUrl());
    }

    private void start() {
        try {
            String key = createListenKey();
            if (key == null) { reconnect("listenKey 申請失敗"); return; }
            listenKey = key;
            new StandardWebSocketClient()
                    .execute(this, new WebSocketHttpHeaders(), URI.create(gateway.futuresWsUrl() + "/ws/" + listenKey))
                    .whenComplete((s, ex) -> { if (ex != null) reconnect("WS 連線失敗: " + ex.getMessage()); });
        } catch (Exception e) {
            reconnect("啟動例外: " + e.getMessage());
        }
    }

    private String createListenKey() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(gateway.futuresRestUrl() + "/fapi/v1/listenKey"))
                .header("X-MBX-APIKEY", gateway.futuresApiKey())
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.error("合約 listenKey 申請失敗 HTTP {}: {}", resp.statusCode(), resp.body());
            return null;
        }
        return mapper.readTree(resp.body()).path("listenKey").asText(null);
    }

    private void keepAlive() {
        if (listenKey == null) return;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(gateway.futuresRestUrl() + "/fapi/v1/listenKey"))
                    .header("X-MBX-APIKEY", gateway.futuresApiKey())
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) log.warn("合約 listenKey 續期失敗 HTTP {}: {}", resp.statusCode(), resp.body());
        } catch (Exception e) { log.warn("合約 listenKey 續期失敗: {}", e.getMessage()); }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
        this.session = s;
        backoffSec = 5;
        registry.markConnected(UdsRegistry.FUTURES);
        // 補回斷線期間的成交：對所有未完成合約單回查一次（增量發布，已記過的不重複）
        try { orderService.reconcileOpenOrders(true); } catch (Exception e) { log.warn("合約開單對帳失敗: {}", e.getMessage()); }
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
        try {
            JsonNode e = mapper.readTree(message.getPayload());
            String type = e.path("e").asText();
            if ("listenKeyExpired".equals(type)) {
                log.warn("合約 listenKey 已過期，重新申請並重連");
                closeQuietly();
                return;   // afterConnectionClosed 會排程重連（start() 會重新申請 key）
            }
            if (!"ORDER_TRADE_UPDATE".equals(type)) return;
            registry.onEvent(UdsRegistry.FUTURES);
            OrderEvent ev = toEvent(e, orders);
            if (ev == null) return;
            kafka.send(Topics.ORDER_EVENTS, ev.symbol(), ev);
            log.info("UDS(合約) 回報 {} {} l={} @ {}", ev.symbol(), ev.status(), ev.filledQty(), ev.avgPrice());
        } catch (Exception ex) {
            log.warn("解析 ORDER_TRADE_UPDATE 失敗: {}", ex.getMessage());
        }
    }

    /**
     * ORDER_TRADE_UPDATE → OrderEvent。欄位在 o 底下：s symbol, c clientOrderId, S side, X 狀態,
     * l 本次成交量, L 本次成交價, t tradeId, i orderId。filledQty 是「本次」增量（與現貨 executionReport 一致）。
     * eventId 綁 tradeId；非成交事件以 X+E 區分，重送不會重複入帳。
     */
    static OrderEvent toEvent(JsonNode root, OrderRepository orders) {
        JsonNode o = root.path("o");
        if (o.isMissingNode()) return null;
        String symbol = o.path("s").asText();
        String clientOrderId = o.path("c").asText();
        if (symbol.isBlank() || clientOrderId.isBlank()) return null;
        OrderSide side = "SELL".equals(o.path("S").asText()) ? OrderSide.SELL : OrderSide.BUY;
        OrderStatus status = mapStatus(o.path("X").asText());
        double lastQty = o.path("l").asDouble(0);
        double lastPx = o.path("L").asDouble(0);
        String account = orders == null ? "main" : orders.findByClientOrderId(clientOrderId)
                .map(r -> r.getAccount() == null ? "main" : r.getAccount()).orElse("main");
        String eventId = "uds-fut|" + clientOrderId + "|" + o.path("t").asLong(-1) + "|" + o.path("X").asText()
                + "|" + root.path("E").asLong(0);
        return new OrderEvent(clientOrderId, o.path("i").asText(null), symbol, side, status,
                lastQty > 0 ? lastQty : 0, lastPx, System.currentTimeMillis(), account, eventId);
    }

    static OrderStatus mapStatus(String x) {
        return switch (x) {
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELED" -> OrderStatus.CANCELED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "EXPIRED", "EXPIRED_IN_MATCH" -> OrderStatus.EXPIRED;
            default -> OrderStatus.SUBMITTED;
        };
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        session = null;
        reconnect("connection closed: " + status);
    }

    @Override
    public void handleTransportError(WebSocketSession s, Throwable ex) {
        log.warn("合約 UDS 傳輸錯誤: {}", ex.getMessage());
        closeQuietly();
    }

    private void closeQuietly() {
        WebSocketSession s = session;
        if (s != null) { try { s.close(); } catch (Exception ignore) {} }
    }

    private void reconnect(String reason) {
        registry.markDisconnected(UdsRegistry.FUTURES, reason);
        int delay = backoffSec;
        backoffSec = Math.min(backoffSec * 2, 60);
        log.warn("合約 UDS {} — {}s 後重連", reason, delay);
        scheduler.schedule(this::start, delay, TimeUnit.SECONDS);
    }
}
