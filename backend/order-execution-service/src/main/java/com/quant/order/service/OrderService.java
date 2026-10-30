package com.quant.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import com.quant.order.entity.OrderEntity;
import com.quant.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.quant.common.enums.OrderSide;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OMS 核心（見架構文件 §9）：冪等、風控核可、送單、查單、撤單、槓桿、訂單落地 DB。
 * 現貨/合約由 OrderIntent.market 路由。成交回報：dry-run 自造；live 由 WS API UDS 或下單回應解析。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final RiskClient riskClient;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, Object> kafka;
    private final BinanceOrderGateway gateway;
    private final OrderRepository orders;
    private final AccountClient accountClient;
    private final NotifyClient notifyClient;

    @Value("${binance.user-data-stream-enabled:false}") private boolean udsEnabled;
    @Value("${risk.max-notional-usdt:1000}") private double maxNotional;   // 單筆名目金額上限(USDT)
    @Value("${risk.allow-unlimited-notional:false}") private boolean allowUnlimitedNotional;   // 顯式允許 0=不限
    @Value("${risk.balance-check:false}") private boolean balanceCheck;    // BUY 前檢查可用餘額(會多打一次 account)

    public OrderService(RiskClient riskClient, StringRedisTemplate redis,
                        KafkaTemplate<String, Object> kafka, BinanceOrderGateway gateway,
                        OrderRepository orders, AccountClient accountClient, NotifyClient notifyClient) {
        this.riskClient = riskClient;
        this.redis = redis;
        this.kafka = kafka;
        this.gateway = gateway;
        this.orders = orders;
        this.accountClient = accountClient;
        this.notifyClient = notifyClient;
    }

    /** 下單被拒推 Slack（含原因）。失敗不影響主流程。 */
    private void notifyReject(OrderIntent intent, String reason) {
        try {
            notifyClient.send(Map.of("text", String.format(":x: 下單被拒 [%s] %s %s qty=%s ｜ %s",
                    intent.accountOrDefault(), intent.symbol(), intent.side(),
                    trimNum(intent.quantity()), reason)));
        } catch (Exception e) { log.debug("被拒通知發送失敗（忽略）: {}", e.getMessage()); }
    }
    private static String trimNum(double d) {
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    private double lastPrice(String symbol) {
        try { String v = redis.opsForValue().get("price:" + symbol); return v == null ? 0 : Double.parseDouble(v); }
        catch (Exception e) { return 0; }
    }

    public ApiResponse<String> submit(OrderIntent intent) {
        // 冪等：Redis 只是快路徑；DB 的 client_order_id UNIQUE 才是真相（Redis 無持久化、10 分鐘 TTL）
        Boolean first = redis.opsForValue()
                .setIfAbsent("idem:" + intent.clientOrderId(), "1", 10, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(first)) return ApiResponse.error(409, "duplicate clientOrderId");

        // 送單前先落地；落地失敗就不送（崩潰安全：DB 裡沒有的單，交易所也不該有）
        try {
            persistNew(intent);
        } catch (DataIntegrityViolationException dup) {
            return ApiResponse.error(409, "duplicate clientOrderId (db)");
        } catch (Exception ex) {
            log.error("訂單落地失敗，拒絕送單 {}: {}", intent.clientOrderId(), ex.getMessage());
            return ApiResponse.error(503, "訂單落地失敗，未送單: " + ex.getMessage());
        }

        RiskClient.RiskDecision decision = riskClient.check(intent);
        if (!decision.approved()) {
            updateStatus(intent.clientOrderId(), OrderStatus.REJECTED, 0);
            publish(intent, OrderStatus.REJECTED, 0, 0);
            notifyReject(intent, "風控拒絕：" + decision.reason());
            return ApiResponse.error(403, "risk rejected: " + decision.reason());
        }
        // ── 名目金額上限 + 餘額檢查（事中風控補強）。拿不到參考價 = 拒單，不是放行 ──
        double ref = intent.price() != null && intent.price() > 0 ? intent.price()
                : gateway.referencePrice(intent.symbol(), intent.isFutures());
        double notional = intent.quantity() * ref;
        String preReject = preTradeCheck(intent, ref, notional);
        if (preReject != null) {
            updateStatus(intent.clientOrderId(), OrderStatus.REJECTED, 0);
            publish(intent, OrderStatus.REJECTED, 0, 0);
            notifyReject(intent, preReject);
            return ApiResponse.error(403, preReject);
        }

        publish(intent, OrderStatus.RISK_APPROVED, 0, 0);
        updateStatus(intent.clientOrderId(), OrderStatus.RISK_APPROVED, 0);

        try {
            String exchangeResp = gateway.placeOrder(intent);
            publish(intent, OrderStatus.SUBMITTED, 0, 0);
            updateStatus(intent.clientOrderId(), OrderStatus.SUBMITTED, 0);
            if (gateway.isDryRun()) {
                // dry-run 以最新市價成交（MARKET 單無 price），讓模擬盈虧/KPI 有意義
                double price = intent.price() != null && intent.price() > 0 ? intent.price() : lastPrice(intent.symbol());
                publish(intent, OrderStatus.FILLED, intent.quantity(), price);
            } else if (!udsEnabled) {
                emitFillFromResponse(intent, exchangeResp);
            }
            log.info("下單完成 {}{} {} qty={} -> {}", intent.isFutures() ? "[合約]" : "[現貨]",
                    intent.symbol(), intent.side(), intent.quantity(), exchangeResp);
            return ApiResponse.ok(exchangeResp);

        } catch (BinanceOrderGateway.TransportException e) {
            // 逾時 / 斷線 / 5xx：單子可能已在簿上。絕不標 REJECTED —— 標 UNKNOWN，立刻回查一次，查不到就交給排程持續對帳。
            log.warn("送單狀態不明 {} {}: {} → 標 UNKNOWN 並回查", intent.symbol(), intent.clientOrderId(), e.getMessage());
            updateStatus(intent.clientOrderId(), OrderStatus.UNKNOWN, 0);
            publish(intent, OrderStatus.UNKNOWN, 0, 0);
            notifyReject(intent, "送單狀態不明（" + e.getMessage() + "），已自動回查對帳，請勿重送");
            OrderStatus resolved = reconcileOne(intent.clientOrderId(), intent.symbol(), intent.isFutures(), intent.side(), intent.accountOrDefault());
            if (resolved == null) return ApiResponse.error(202, "送單狀態不明，對帳中（UNKNOWN）；勿重送 " + intent.clientOrderId());
            return resolved == OrderStatus.REJECTED
                    ? ApiResponse.error(500, "交易所未收到此單（回查確認），可重送")
                    : ApiResponse.ok("{\"reconciled\":\"" + resolved + "\"}");

        } catch (BinanceOrderGateway.ExchangeRejectedException | IllegalStateException | IllegalArgumentException e) {
            // 交易所明確拒絕 / filter 未過 / 金鑰未設：確定沒進簿
            log.error("place order rejected: {}", e.getMessage());
            updateStatus(intent.clientOrderId(), OrderStatus.REJECTED, 0);
            publish(intent, OrderStatus.REJECTED, 0, 0);
            notifyReject(intent, e.getMessage());
            return ApiResponse.error(500, e.getMessage());   // 把 filter/餘額/幣安錯誤訊息帶回前端
        } catch (RuntimeException e) {
            // 其他未知例外：保守當作狀態不明
            log.error("place order unexpected: {}", e.getMessage(), e);
            updateStatus(intent.clientOrderId(), OrderStatus.UNKNOWN, 0);
            publish(intent, OrderStatus.UNKNOWN, 0, 0);
            return ApiResponse.error(202, "送單例外，對帳中（UNKNOWN）；勿重送 " + intent.clientOrderId());
        }
    }

    // ── 對帳：UNKNOWN 訂單以 origClientOrderId 回查交易所 ───────────────────────────────────

    /** 回查一筆。回傳確定狀態；仍查不到回 null（留 UNKNOWN 給排程）。 */
    OrderStatus reconcileOne(String clientOrderId, String symbol, boolean futures, OrderSide side, String account) {
        try {
            String resp = gateway.queryOrder(symbol, clientOrderId, futures);
            JsonNode n = mapper.readTree(resp);
            String st = n.path("status").asText("");
            double executed = n.path("executedQty").asDouble(0);
            double avg = 0;
            if (executed > 0) {
                if (n.has("avgPrice")) avg = n.path("avgPrice").asDouble(0);
                else { double q = n.path("cummulativeQuoteQty").asDouble(0); avg = q > 0 ? q / executed : 0; }
            }
            OrderStatus mapped = switch (st) {
                case "NEW" -> OrderStatus.SUBMITTED;
                case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
                case "FILLED" -> OrderStatus.FILLED;
                case "CANCELED", "PENDING_CANCEL" -> OrderStatus.CANCELED;
                case "REJECTED" -> OrderStatus.REJECTED;
                case "EXPIRED", "EXPIRED_IN_MATCH" -> OrderStatus.EXPIRED;
                default -> null;
            };
            if (mapped == null) return null;
            String exchId = n.path("orderId").asText(null);
            // 只發「增量」：交易所累計成交量 − DB 已記成交量。eventId 綁累計量，重跑同一快照不會重複加。
            double known = orders.findByClientOrderId(clientOrderId)
                    .map(o -> o.getFilledQty() == null ? 0 : o.getFilledQty()).orElse(0.0);
            double delta = Math.max(0, executed - known);
            OrderStatus publishStatus = mapped;
            if (delta <= 0 && (mapped == OrderStatus.FILLED || mapped == OrderStatus.PARTIALLY_FILLED)) {
                // 成交已被 UDS 記過，只需同步狀態
                updateStatus(clientOrderId, mapped, 0);
            } else {
                updateStatus(clientOrderId, mapped == OrderStatus.SUBMITTED ? OrderStatus.SUBMITTED : mapped, 0);
                kafka.send(Topics.ORDER_EVENTS, symbol, new OrderEvent(clientOrderId, exchId, symbol, side, publishStatus,
                        delta, avg, System.currentTimeMillis(), account,
                        "reconcile|" + clientOrderId + "|" + mapped + "|" + executed));
            }
            log.info("對帳完成 {} -> {} executed={} known={} delta={} @ {}", clientOrderId, mapped, executed, known, delta, avg);
            return mapped;
        } catch (BinanceOrderGateway.ExchangeRejectedException e) {
            if (e.code == -2013) {   // Order does not exist：交易所從未收到 → 安全標 REJECTED
                updateStatus(clientOrderId, OrderStatus.REJECTED, 0);
                kafka.send(Topics.ORDER_EVENTS, symbol, new OrderEvent(clientOrderId, null, symbol, side, OrderStatus.REJECTED,
                        0, 0, System.currentTimeMillis(), account, "reconcile|" + clientOrderId + "|REJECTED|0"));
                log.info("對帳：交易所無此單 {} → REJECTED", clientOrderId);
                return OrderStatus.REJECTED;
            }
            log.warn("對帳回查被拒 {}: {}", clientOrderId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("對帳回查失敗 {}: {}", clientOrderId, e.getMessage());
            return null;
        }
    }

    /** 每 10 秒掃一次 UNKNOWN 訂單對帳；SUBMITTED 超過 N 分鐘仍無成交回報也納入（UDS 漏事件保險）。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
    public void reconcileUnknownOrders() {
        if (gateway.isDryRun()) return;
        List<OrderEntity> pending = orders.findTop50ByStatusOrderByUpdatedAtAsc(OrderStatus.UNKNOWN.name());
        for (OrderEntity o : pending) {
            OrderSide side = "SELL".equals(o.getSide()) ? OrderSide.SELL : OrderSide.BUY;
            reconcileOne(o.getClientOrderId(), o.getSymbol(), "USDT_M".equalsIgnoreCase(o.getMarket()), side,
                    o.getAccount() == null ? "main" : o.getAccount());
        }
    }

    /** 目前下單風控設定。 */
    public Map<String, Object> riskConfig() {
        return Map.of("maxNotionalUsdt", maxNotional, "balanceCheck", balanceCheck, "dryRun", gateway.isDryRun());
    }

    /** 執行期調整下單風控（風控頁推送，免重啟）。null 表示不變更。 */
    public synchronized Map<String, Object> setRiskConfig(Double maxNotionalUsdt, Boolean balanceCheck) {
        if (maxNotionalUsdt != null) {
            if (maxNotionalUsdt <= 0 && !allowUnlimitedNotional)
                throw new IllegalArgumentException("單筆名目上限不可為 0（=不限）。若確定要關閉，請設 RISK_ALLOW_UNLIMITED_NOTIONAL=true 後重啟");
            if (maxNotionalUsdt > 0) this.maxNotional = maxNotionalUsdt;
            else this.maxNotional = 0;
        }
        if (balanceCheck != null) this.balanceCheck = balanceCheck;
        log.warn("下單風控更新：maxNotional={} balanceCheck={}", maxNotional, this.balanceCheck);
        return riskConfig();
    }

    /** 事前檢查：名目金額上限 + （選配）可用餘額。通過回 null，否則回拒絕原因。 */
    String preTradeCheck(OrderIntent intent, double ref, double notional) {
        if (ref <= 0)
            return "取不到 " + intent.symbol() + " 參考價，無法檢查名目上限，拒絕下單";
        if (maxNotional <= 0 && !allowUnlimitedNotional)
            return "單筆名目上限未設定（0），且未顯式允許不限額，拒絕下單";
        if (maxNotional > 0 && notional > maxNotional)
            return String.format("名目金額 %.2f USDT 超過單筆上限 %.0f USDT", notional, maxNotional);
        if (balanceCheck && intent.side() == com.quant.common.enums.OrderSide.BUY && !intent.isFutures()) {
            try {
                Map<String, Object> bal = accountClient.balance("main").data();
                Object freeObj = bal == null ? null : bal.get("free");
                if (freeObj instanceof Number free) {
                    if (notional > free.doubleValue())
                        return String.format("餘額不足：需 %.2f USDT，可用 %.2f USDT", notional, free.doubleValue());
                }
            } catch (Exception e) {
                log.warn("餘額檢查失敗，略過（不阻擋下單）: {}", e.getMessage());
            }
        }
        return null;
    }

    private void emitFillFromResponse(OrderIntent intent, String exchangeResp) {
        try {
            JsonNode n = mapper.readTree(exchangeResp);
            double executed = n.path("executedQty").asDouble(0);
            if (executed > 0) {
                double avg;
                if (n.has("avgPrice")) avg = n.path("avgPrice").asDouble(0);           // 合約
                else { double q = n.path("cummulativeQuoteQty").asDouble(0); avg = q > 0 ? q / executed : 0; } // 現貨
                String st = n.path("status").asText("");
                OrderStatus status = "PARTIALLY_FILLED".equals(st)
                        ? OrderStatus.PARTIALLY_FILLED : OrderStatus.FILLED;
                publish(intent, status, executed, avg);
                log.info("真實成交 {} {} qty={} @ {}", intent.symbol(), status, executed, avg);
            }
        } catch (Exception e) {
            log.warn("解析成交回應失敗: {}", e.getMessage());
        }
    }

    public ApiResponse<String> query(String symbol, String clientOrderId, boolean futures) {
        try { return ApiResponse.ok(gateway.queryOrder(symbol, clientOrderId, futures)); }
        catch (Exception e) { return ApiResponse.error(500, "query failed: " + e.getMessage()); }
    }

    public ApiResponse<String> cancel(String symbol, String clientOrderId, boolean futures) {
        try {
            String resp = gateway.cancelOrder(symbol, clientOrderId, futures);
            OrderSide side = orders.findByClientOrderId(clientOrderId)
                    .map(o -> "SELL".equals(o.getSide()) ? OrderSide.SELL : OrderSide.BUY).orElse(null);
            String account = orders.findByClientOrderId(clientOrderId).map(OrderEntity::getAccount).orElse("main");
            long ts = System.currentTimeMillis();
            kafka.send(Topics.ORDER_EVENTS, symbol, new OrderEvent(clientOrderId, null, symbol, side, OrderStatus.CANCELED,
                    0, 0, ts, account, OrderEvent.eventId(clientOrderId, OrderStatus.CANCELED, 0, ts)));
            return ApiResponse.ok(resp);
        } catch (Exception e) { return ApiResponse.error(500, "cancel failed: " + e.getMessage()); }
    }


    /**
     * OCO 下單（現貨）：限價止盈 + 止損二擇一（見架構文件 §9）。
     * 走與一般單相同的閘門：冪等（listClientOrderId）→ 風控核可 / 熔斷 → 名目上限（取兩腿較大者）
     * → filter 對齊（gateway）→ 兩腿各落一列（-L / -S），成交由 UDS 以腿的 clientOrderId 回寫。
     */
    public ApiResponse<String> submitOco(String symbol, String side, double quantity,
                                         double price, double stopPrice, double stopLimitPrice, String listClientOrderId) {
        String lid = listClientOrderId == null || listClientOrderId.isBlank()
                ? "oco-" + java.util.UUID.randomUUID() : listClientOrderId;
        if (quantity <= 0 || price <= 0 || stopPrice <= 0 || stopLimitPrice <= 0)
            return ApiResponse.error(400, "OCO 參數必須皆大於 0");
        OrderSide s;
        try { s = OrderSide.valueOf(side.toUpperCase()); } catch (Exception e) { return ApiResponse.error(400, "side 必須是 BUY/SELL"); }

        Boolean first = redis.opsForValue().setIfAbsent("idem:" + lid, "1", 10, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(first)) return ApiResponse.error(409, "duplicate listClientOrderId");

        // 以限價腿建一個 intent 走風控；兩腿共用數量，名目取較大者
        OrderIntent limitLeg = new OrderIntent(lid + "-L", null, symbol, s, com.quant.common.enums.OrderType.LIMIT,
                price, quantity, false, "SPOT", "main");
        OrderIntent stopLeg = new OrderIntent(lid + "-S", null, symbol, s, com.quant.common.enums.OrderType.LIMIT,
                stopLimitPrice, quantity, false, "SPOT", "main");
        try {
            persistNew(limitLeg);
            persistNew(stopLeg);
        } catch (DataIntegrityViolationException dup) {
            return ApiResponse.error(409, "duplicate OCO leg id");
        } catch (Exception ex) {
            return ApiResponse.error(503, "訂單落地失敗，未送單: " + ex.getMessage());
        }

        RiskClient.RiskDecision decision = riskClient.check(limitLeg);
        if (!decision.approved()) {
            rejectLegs(limitLeg, stopLeg, "風控拒絕：" + decision.reason());
            return ApiResponse.error(403, "risk rejected: " + decision.reason());
        }
        double notional = quantity * Math.max(price, stopLimitPrice);
        String preReject = preTradeCheck(limitLeg, Math.max(price, stopLimitPrice), notional);
        if (preReject != null) {
            rejectLegs(limitLeg, stopLeg, preReject);
            return ApiResponse.error(403, preReject);
        }
        updateStatus(limitLeg.clientOrderId(), OrderStatus.RISK_APPROVED, 0);
        updateStatus(stopLeg.clientOrderId(), OrderStatus.RISK_APPROVED, 0);

        try {
            String resp = gateway.placeOco(symbol, s.name(), quantity, price, stopPrice, stopLimitPrice, lid);
            updateStatus(limitLeg.clientOrderId(), OrderStatus.SUBMITTED, 0);
            updateStatus(stopLeg.clientOrderId(), OrderStatus.SUBMITTED, 0);
            publish(limitLeg, OrderStatus.SUBMITTED, 0, 0);
            publish(stopLeg, OrderStatus.SUBMITTED, 0, 0);
            log.info("OCO 送出 {} {} qty={} limit={} stop={}/{} -> {}", symbol, s, quantity, price, stopPrice, stopLimitPrice, resp);
            return ApiResponse.ok(resp);
        } catch (BinanceOrderGateway.TransportException e) {
            updateStatus(limitLeg.clientOrderId(), OrderStatus.UNKNOWN, 0);
            updateStatus(stopLeg.clientOrderId(), OrderStatus.UNKNOWN, 0);
            notifyReject(limitLeg, "OCO 送單狀態不明（" + e.getMessage() + "），對帳排程將回查兩腿");
            return ApiResponse.error(202, "OCO 狀態不明，對帳中；勿重送 " + lid);
        } catch (RuntimeException e) {
            rejectLegs(limitLeg, stopLeg, e.getMessage());
            return ApiResponse.error(500, "oco failed: " + e.getMessage());
        }
    }

    private void rejectLegs(OrderIntent a, OrderIntent b, String reason) {
        updateStatus(a.clientOrderId(), OrderStatus.REJECTED, 0);
        updateStatus(b.clientOrderId(), OrderStatus.REJECTED, 0);
        publish(a, OrderStatus.REJECTED, 0, 0);
        notifyReject(a, "OCO " + reason);
    }

    public ApiResponse<String> setLeverage(String symbol, int leverage) {
        try { return ApiResponse.ok(gateway.setLeverage(symbol, leverage)); }
        catch (Exception e) { return ApiResponse.error(500, "leverage failed: " + e.getMessage()); }
    }

    /** 近期訂單（DB）。 */
    public ApiResponse<List<Map<String, Object>>> recent(int limit) {
        List<Map<String, Object>> rows = orders.findAllByOrderByIdDesc(PageRequest.of(0, Math.min(limit, 200)))
                .map(o -> Map.<String, Object>of(
                        "clientOrderId", o.getClientOrderId(),
                        "symbol", o.getSymbol(),
                        "side", o.getSide(),
                        "type", o.getType(),
                        "quantity", o.getQuantity(),
                        "filledQty", o.getFilledQty(),
                        "status", o.getStatus(),
                        "strategyId", o.getStrategyId() == null ? "" : o.getStrategyId()))
                .getContent();
        return ApiResponse.ok(rows);
    }


    /**
     * 各策略的「有號」部位與未結訂單，供 strategy-service 啟動時對帳。
     * qty = Σ(BUY filled) − Σ(SELL filled)；openOrders = 尚未終結的單數（策略應視為 pending，不可再開同向新單）。
     */
    public ApiResponse<List<Map<String, Object>>> strategyPositions() {
        Map<String, double[]> agg = new java.util.LinkedHashMap<>();   // key strategy|symbol -> [qty, open]
        for (OrderEntity o : orders.findByStrategyIdIsNotNullOrderByIdAsc()) {
            String key = o.getStrategyId() + "|" + o.getSymbol();
            double[] a = agg.computeIfAbsent(key, k -> new double[2]);
            OrderStatus st = safeStatus(o.getStatus());
            double filled = o.getFilledQty() == null ? 0 : o.getFilledQty();
            if (filled > 0) a[0] += "SELL".equals(o.getSide()) ? -filled : filled;
            boolean open = st == OrderStatus.CREATED || st == OrderStatus.RISK_APPROVED || st == OrderStatus.SUBMITTED
                    || st == OrderStatus.PARTIALLY_FILLED || st == OrderStatus.UNKNOWN;
            if (open) a[1] += 1;
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        agg.forEach((k, a) -> {
            String[] p = k.split("\\|", 2);
            rows.add(Map.of("strategyId", p[0], "symbol", p[1], "qty", a[0], "openOrders", (int) a[1]));
        });
        return ApiResponse.ok(rows);
    }

    /** 績效歸因：依 strategy_id 匯總已成交訂單（見架構文件 §11）。 */
    public ApiResponse<List<Map<String, Object>>> attribution() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Object[] r : orders.attribution()) {
            rows.add(Map.of(
                    "strategyId", r[0] == null ? "(manual)" : r[0].toString(),
                    "filledOrders", ((Number) r[1]).longValue(),
                    "filledQty", r[2] == null ? 0.0 : ((Number) r[2]).doubleValue()));
        }
        return ApiResponse.ok(rows);
    }


    /** 盈虧/勝率 KPI（見架構文件 §11）：以 FIFO 配對已成交買賣單計算已實現損益。 */
    public ApiResponse<Map<String, Object>> kpi() {
        var filled = orders.findByStatusOrderByIdAsc("FILLED");
        Map<String, java.util.Deque<double[]>> longs = new java.util.HashMap<>(); // symbol -> [qty, price]
        int wins = 0, losses = 0; double grossProfit = 0, grossLoss = 0, totalPnl = 0; int roundTrips = 0;
        for (OrderEntity o : filled) {
            double qty = o.getFilledQty() == null ? 0 : o.getFilledQty();
            double px = o.getAvgPrice() == null ? 0 : o.getAvgPrice();
            if (qty <= 0 || px <= 0) continue;
            var dq = longs.computeIfAbsent(o.getSymbol(), k -> new java.util.ArrayDeque<>());
            if ("BUY".equals(o.getSide())) {
                dq.addLast(new double[]{qty, px});
            } else { // SELL：FIFO 沖銷買方
                double remain = qty;
                while (remain > 1e-12 && !dq.isEmpty()) {
                    double[] lot = dq.peekFirst();
                    double m = Math.min(remain, lot[0]);
                    double pnl = (px - lot[1]) * m;
                    totalPnl += pnl; roundTrips++;
                    if (pnl >= 0) { wins++; grossProfit += pnl; } else { losses++; grossLoss += -pnl; }
                    lot[0] -= m; remain -= m;
                    if (lot[0] <= 1e-12) dq.removeFirst();
                }
            }
        }
        int total = wins + losses;
        double winRate = total == 0 ? 0 : (double) wins / total;
        double profitFactor = grossLoss == 0 ? (grossProfit > 0 ? 99 : 0) : grossProfit / grossLoss;
        double avgWin = wins == 0 ? 0 : grossProfit / wins, avgLoss = losses == 0 ? 0 : grossLoss / losses;
        double payoff = avgLoss == 0 ? 0 : avgWin / avgLoss;
        return ApiResponse.ok(Map.of(
                "roundTrips", roundTrips, "wins", wins, "losses", losses,
                "winRate", round4(winRate), "totalPnl", round4(totalPnl),
                "profitFactor", round4(profitFactor), "avgWin", round4(avgWin),
                "avgLoss", round4(avgLoss), "payoffRatio", round4(payoff)));
    }
    private double round4(double d){ return Math.round(d*10000.0)/10000.0; }

    /** 當日統計：今日成交筆數 + 今日已實現盈虧（FIFO 沖銷，僅計今日平倉的 round-trip）。 */
    public ApiResponse<Map<String, Object>> dailyStats() {
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        var filled = orders.findByStatusOrderByIdAsc("FILLED");
        Map<String, java.util.Deque<double[]>> longs = new java.util.HashMap<>();
        int fillsToday = 0, winsToday = 0, lossesToday = 0;
        double pnlToday = 0, buyVolToday = 0, sellVolToday = 0;
        for (OrderEntity o : filled) {
            double qty = o.getFilledQty() == null ? 0 : o.getFilledQty();
            double px = o.getAvgPrice() == null ? 0 : o.getAvgPrice();
            if (qty <= 0 || px <= 0) continue;
            boolean isToday = o.getUpdatedAt() != null
                    && o.getUpdatedAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Taipei")).toLocalDate().equals(today);
            if (isToday) {
                fillsToday++;
                if ("BUY".equals(o.getSide())) buyVolToday += qty * px; else sellVolToday += qty * px;
            }
            var dq = longs.computeIfAbsent(o.getSymbol(), k -> new java.util.ArrayDeque<>());
            if ("BUY".equals(o.getSide())) {
                dq.addLast(new double[]{qty, px});
            } else {
                double remain = qty;
                while (remain > 1e-12 && !dq.isEmpty()) {
                    double[] lot = dq.peekFirst();
                    double m = Math.min(remain, lot[0]);
                    double pnl = (px - lot[1]) * m;
                    if (isToday) {   // 只計今日平倉的損益
                        pnlToday += pnl;
                        if (pnl >= 0) winsToday++; else lossesToday++;
                    }
                    lot[0] -= m; remain -= m;
                    if (lot[0] <= 1e-12) dq.removeFirst();
                }
            }
        }
        int rt = winsToday + lossesToday;
        return ApiResponse.ok(Map.of(
                "date", today.toString(), "fills", fillsToday,
                "buyVolume", round4(buyVolToday), "sellVolume", round4(sellVolToday),
                "realizedPnl", round4(pnlToday), "roundTrips", rt,
                "wins", winsToday, "losses", lossesToday,
                "winRate", rt == 0 ? 0.0 : round4((double) winsToday / rt)));
    }

    // ---- 落地 ----
    /** 落地下單意圖。失敗要拋出（呼叫端據此拒絕送單），不可吞。 */
    private void persistNew(OrderIntent i) {
        OrderEntity e = new OrderEntity();
        e.setClientOrderId(i.clientOrderId());
        e.setSymbol(i.symbol());
        e.setSide(i.side().name());
        e.setType(i.type().name());
        e.setPrice(i.price());
        e.setQuantity(i.quantity());
        e.setStatus(OrderStatus.CREATED.name());
        e.setStrategyId(i.strategyId());
        e.setAccount(i.accountOrDefault());
        e.setMarket(i.isFutures() ? "USDT_M" : "SPOT");
        orders.saveAndFlush(e);
    }

    private void updateStatus(String clientOrderId, OrderStatus status, double filled) {
        try {
            orders.findByClientOrderId(clientOrderId).ifPresent(e -> {
                OrderStatus cur = safeStatus(e.getStatus());
                // 同步路徑只能往前推；已被 UDS/對帳寫成 FILLED/CANCELED 的單，不可被晚到的 SUBMITTED 蓋回去
                if (cur != null && cur != OrderStatus.UNKNOWN && !OrderStateMachine.canTransit(cur, status) && cur != status) {
                    log.debug("略過狀態回退 {}: {} -> {}", clientOrderId, cur, status);
                    return;
                }
                e.setStatus(status.name());
                if (filled > 0) e.setFilledQty(filled);
                e.setUpdatedAt(OffsetDateTime.now());
                orders.save(e);
            });
        } catch (Exception ex) { log.warn("訂單狀態更新失敗: {}", ex.getMessage()); }
    }

    static OrderStatus safeStatus(String s) {
        try { return s == null ? null : OrderStatus.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    private void publish(OrderIntent i, OrderStatus status, double filled, double avgPrice) {
        long ts = System.currentTimeMillis();
        kafka.send(Topics.ORDER_EVENTS, i.symbol(),
                new OrderEvent(i.clientOrderId(), null, i.symbol(), i.side(), status, filled, avgPrice, ts,
                        i.accountOrDefault(), OrderEvent.eventId(i.clientOrderId(), status, filled, ts)));
    }
}
