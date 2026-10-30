package com.quant.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 幣安「新版 WebSocket API」現貨 User Data Stream（見架構文件 §6.1、§9.2）。
 * 流程：連 WS API → session.logon（Ed25519 簽名認證）→ userDataStream.subscribe
 *       → 於同一連線接收 executionReport 等事件 → 轉 OrderEvent 發 Kafka。
 * 取代已停用的 listenKey 版 UDS。需 Ed25519 API 金鑰（HMAC 不可用於 session.logon）。
 * 由 binance.user-data-stream-enabled 控制。開了卻缺金鑰 → 啟動失敗（以前只 warn 一行，成交回報靜默遺失）。
 * 訂閱成功才向 UdsRegistry 回報「已連線」；logon / subscribe 失敗、斷線都會退避重連並觸發看門狗告警。
 */
@Service
public class BinanceWsApiUserDataStream extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceWsApiUserDataStream.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spot-uds"); t.setDaemon(true); return t;
    });

    @Value("${binance.user-data-stream-enabled:false}") private boolean enabled;
    @Value("${binance.ws-api-url:wss://ws-api.testnet.binance.vision/ws-api/v3}") private String wsApiUrl;
    @Value("${binance.mainnet-ws-api-url:wss://ws-api.binance.com:443/ws-api/v3}") private String wsApiMainnetUrl;
    @Value("${binance.ed25519-api-key:}") private String apiKey;
    @Value("${binance.ed25519-key-path:}") private String keyPath;

    private final KafkaTemplate<String, Object> kafka;
    private final OrderRepository orders;
    private final BinanceOrderGateway gateway;
    private final UdsRegistry registry;
    private final OrderService orderService;
    private PrivateKey privateKey;
    private volatile WebSocketSession session;
    private volatile String logonId, subscribeId;
    private volatile int backoffSec = 5;

    public BinanceWsApiUserDataStream(KafkaTemplate<String, Object> kafka, OrderRepository orders,
                                      BinanceOrderGateway gateway, UdsRegistry registry,
                                      @Lazy OrderService orderService) {
        this.kafka = kafka; this.orders = orders; this.gateway = gateway; this.registry = registry; this.orderService = orderService;
    }

    @PostConstruct
    public void init() {
        if (!enabled || gateway.isDryRun()) {
            log.info("現貨 WS API UDS 未啟用（enabled={} dry-run={}）；成交以下單回應(RESULT)解析 + 對帳排程補記", enabled, gateway.isDryRun());
            return;
        }
        if (apiKey.isBlank() || keyPath.isBlank())
            throw new IllegalStateException("BINANCE_UDS_ENABLED=true 但缺 Ed25519 金鑰（BINANCE_ED25519_API_KEY / BINANCE_ED25519_KEY_PATH）。"
                    + "拒絕以「成交回報靜默遺失」的狀態啟動；請補金鑰（docker: 把 pem 放 ./secrets/ 並設 KEY_PATH=/run/secrets/<檔名>）或關閉 UDS");
        try {
            this.privateKey = loadEd25519PrivateKey(keyPath);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 私鑰讀取失敗 " + keyPath + ": " + e.getMessage(), e);
        }
        if ("mainnet".equalsIgnoreCase(gateway.currentEnv())) wsApiUrl = wsApiMainnetUrl;
        registry.expect(UdsRegistry.SPOT);
        scheduler.execute(this::connect);
        log.info("現貨 WS API UDS 啟動中… env={} url={}", gateway.currentEnv(), wsApiUrl);
    }

    private void connect() {
        try {
            new StandardWebSocketClient()
                    .execute(this, new WebSocketHttpHeaders(), URI.create(wsApiUrl))
                    .whenComplete((s, ex) -> { if (ex != null) reconnect("connect error: " + ex.getMessage()); });
        } catch (Exception e) { reconnect("connect exception: " + e.getMessage()); }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) throws Exception {
        this.session = s;
        // 1) session.logon（Ed25519 簽名）
        long ts = gateway.clock().now(gateway.spotRestUrl());   // 校正後的時間；本機漂移會讓 logon 回 -1021
        String payload = "apiKey=" + apiKey + "&timestamp=" + ts;   // 參數依字母序
        String signature = ed25519Sign(payload);

        logonId = UUID.randomUUID().toString();
        ObjectNode req = mapper.createObjectNode();
        req.put("id", logonId);
        req.put("method", "session.logon");
        ObjectNode params = req.putObject("params");
        params.put("apiKey", apiKey);
        params.put("timestamp", ts);
        params.put("signature", signature);
        send(req);
        log.info("已送出 session.logon");
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage message) {
        try {
            JsonNode msg = mapper.readTree(message.getPayload());

            // 使用者資料事件（訂閱後推送）
            if (msg.has("event")) { handleUserEvent(msg.get("event")); return; }
            if (msg.has("e")) { handleUserEvent(msg); return; }

            // 請求回應
            if (msg.has("id") && msg.has("status")) {
                String id = msg.get("id").asText();
                int status = msg.get("status").asInt();
                if (id.equals(logonId)) {
                    if (status == 200) {
                        log.info("session.logon 成功，訂閱 userDataStream…");
                        subscribe();
                    } else {
                        // 金鑰錯 / 時間偏移 / IP 白名單：不能停在這裡不動，關連線走退避重連，看門狗會告警
                        log.error("session.logon 失敗: {}", msg);
                        registry.markDisconnected(UdsRegistry.SPOT, "logon 失敗: " + msg.path("error").path("msg").asText(msg.toString()));
                        closeQuietly();
                    }
                } else if (id.equals(subscribeId)) {
                    if (status == 200) {
                        log.info("userDataStream.subscribe 成功，開始接收成交回報");
                        backoffSec = 5;
                        registry.markConnected(UdsRegistry.SPOT);
                        // 補回斷線期間的成交：未完成現貨單回查一次（增量發布，已記過的不重複）
                        try { orderService.reconcileOpenOrders(false); } catch (Exception e) { log.warn("現貨開單對帳失敗: {}", e.getMessage()); }
                    } else {
                        log.error("subscribe 失敗: {}", msg);
                        registry.markDisconnected(UdsRegistry.SPOT, "subscribe 失敗");
                        closeQuietly();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("WS API 訊息解析失敗: {}", ex.getMessage());
        }
    }

    private void subscribe() throws Exception {
        subscribeId = UUID.randomUUID().toString();
        ObjectNode req = mapper.createObjectNode();
        req.put("id", subscribeId);
        req.put("method", "userDataStream.subscribe");
        send(req);
    }

    private void handleUserEvent(JsonNode e) {
        if (!"executionReport".equals(e.path("e").asText())) return;
        registry.onEvent(UdsRegistry.SPOT);
        String symbol = e.get("s").asText();
        String clientOrderId = e.path("c").asText();
        OrderStatus status = mapStatus(e.get("X").asText());
        double lastQty = e.path("l").asDouble();
        double lastPx = e.path("L").asDouble();
        String account = orders.findByClientOrderId(clientOrderId)
                .map(o -> o.getAccount() == null ? "main" : o.getAccount()).orElse("main");
        OrderSide side = "SELL".equals(e.path("S").asText()) ? OrderSide.SELL : OrderSide.BUY;
        // t = tradeId（每筆成交唯一）；非成交事件用 X+E 區分
        String eventId = "uds|" + clientOrderId + "|" + e.path("t").asLong(-1) + "|" + e.path("X").asText() + "|" + e.path("E").asLong(0);
        kafka.send(Topics.ORDER_EVENTS, symbol, new OrderEvent(
                clientOrderId, e.path("i").asText(), symbol, side, status,
                lastQty > 0 ? lastQty : 0, lastPx, System.currentTimeMillis(), account, eventId));
        log.info("UDS(WS API) 成交回報 {} {} l={} @ {}", symbol, status, lastQty, lastPx);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        session = null;
        reconnect("connection closed: " + status);
    }

    @Override
    public void handleTransportError(WebSocketSession s, Throwable ex) {
        log.warn("現貨 UDS 傳輸錯誤: {}", ex.getMessage());
        closeQuietly();
    }

    private void closeQuietly() {
        WebSocketSession s = session;
        if (s != null) { try { s.close(); } catch (Exception ignore) {} }
    }

    private void reconnect(String reason) {
        registry.markDisconnected(UdsRegistry.SPOT, reason);
        int delay = backoffSec;
        backoffSec = Math.min(backoffSec * 2, 60);
        log.warn("WS API UDS {} — {}s 後重連", reason, delay);
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    private synchronized void send(ObjectNode node) throws Exception {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) throw new IllegalStateException("WS session 未開啟");
        s.sendMessage(new TextMessage(mapper.writeValueAsString(node)));
    }

    // ---- Ed25519 ----
    private PrivateKey loadEd25519PrivateKey(String path) throws Exception {
        String pem = Files.readString(Path.of(path));
        String base64 = pem.replaceAll("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String ed25519Sign(String payload) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(privateKey);
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private OrderStatus mapStatus(String x) {
        return switch (x) {
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELED" -> OrderStatus.CANCELED;
            case "REJECTED" -> OrderStatus.REJECTED;
            case "EXPIRED", "EXPIRED_IN_MATCH" -> OrderStatus.EXPIRED;
            default -> OrderStatus.SUBMITTED;
        };
    }
}
