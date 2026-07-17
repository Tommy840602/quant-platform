package com.quant.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import com.quant.order.repository.OrderRepository;
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
 * 幣安「新版 WebSocket API」User Data Stream（見架構文件 §6.1、§9.2）。
 * 流程：連 WS API → session.logon（Ed25519 簽名認證）→ userDataStream.subscribe
 *       → 於同一連線接收 executionReport 等事件 → 轉 OrderEvent 發 Kafka。
 * 取代已停用的 listenKey 版 UDS。需 Ed25519 API 金鑰（HMAC 不可用於 session.logon）。
 * 由 binance.user-data-stream-enabled 控制；關閉時成交回報改由 OrderService 解析下單回應。
 */
@Service
public class BinanceWsApiUserDataStream extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BinanceWsApiUserDataStream.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Value("${trading.dry-run:true}") private boolean dryRun;
    @Value("${binance.user-data-stream-enabled:false}") private boolean enabled;
    @Value("${binance.ws-api-url:wss://ws-api.testnet.binance.vision/ws-api/v3}") private String wsApiUrl;
    @Value("${binance.ed25519-api-key:}") private String apiKey;
    @Value("${binance.ed25519-key-path:}") private String keyPath;

    private final KafkaTemplate<String, Object> kafka;
    private final OrderRepository orders;
    private PrivateKey privateKey;
    private WebSocketSession session;
    private String logonId, subscribeId;

    public BinanceWsApiUserDataStream(KafkaTemplate<String, Object> kafka, OrderRepository orders) {
        this.kafka = kafka; this.orders = orders;
    }

    @PostConstruct
    public void init() {
        if (dryRun || !enabled) {
            log.info("WS API User Data Stream 未啟用（dry-run={} enabled={}）", dryRun, enabled);
            return;
        }
        if (apiKey.isBlank() || keyPath.isBlank()) {
            log.warn("WS API UDS 已開啟但缺 Ed25519 金鑰（ed25519-api-key / ed25519-key-path）— 略過");
            return;
        }
        try {
            this.privateKey = loadEd25519PrivateKey(keyPath);
            connect();
            log.info("WS API User Data Stream 啟動中…");
        } catch (Exception e) {
            log.error("WS API UDS 啟動失敗: {}", e.getMessage());
        }
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
        long ts = System.currentTimeMillis();
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
                        log.error("session.logon 失敗: {}", msg.toString());
                    }
                } else if (id.equals(subscribeId)) {
                    log.info(status == 200 ? "userDataStream.subscribe 成功，開始接收成交回報"
                            : "subscribe 失敗: " + msg);
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
        String symbol = e.get("s").asText();
        String clientOrderId = e.path("c").asText();
        OrderStatus status = mapStatus(e.get("X").asText());
        double lastQty = e.path("l").asDouble();
        double lastPx = e.path("L").asDouble();
        String account = orders.findByClientOrderId(clientOrderId)
                .map(o -> o.getAccount() == null ? "main" : o.getAccount()).orElse("main");
        kafka.send(Topics.ORDER_EVENTS, symbol, new OrderEvent(
                clientOrderId, e.path("i").asText(), symbol, status,
                lastQty > 0 ? lastQty : 0, lastPx, System.currentTimeMillis(), account));
        log.info("UDS(WS API) 成交回報 {} {} l={} @ {}", symbol, status, lastQty, lastPx);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        reconnect("connection closed: " + status);
    }

    private void reconnect(String reason) {
        log.warn("WS API UDS {} — 5s 後重連", reason);
        scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    private synchronized void send(ObjectNode node) throws Exception {
        session.sendMessage(new TextMessage(mapper.writeValueAsString(node)));
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
            case "EXPIRED" -> OrderStatus.EXPIRED;
            default -> OrderStatus.SUBMITTED;
        };
    }
}
