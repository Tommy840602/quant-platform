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

    @Value("${trading.dry-run:true}") private boolean dryRun;
    @Value("${binance.user-data-stream-enabled:false}") private boolean udsEnabled;
    @Value("${risk.max-notional-usdt:1000}") private double maxNotional;   // 單筆名目金額上限(USDT)
    @Value("${risk.balance-check:false}") private boolean balanceCheck;    // BUY 前檢查可用餘額(會多打一次 account)

    public OrderService(RiskClient riskClient, StringRedisTemplate redis,
                        KafkaTemplate<String, Object> kafka, BinanceOrderGateway gateway,
                        OrderRepository orders, AccountClient accountClient) {
        this.riskClient = riskClient;
        this.redis = redis;
        this.kafka = kafka;
        this.gateway = gateway;
        this.orders = orders;
        this.accountClient = accountClient;
    }

    private double lastPrice(String symbol) {
        try { String v = redis.opsForValue().get("price:" + symbol); return v == null ? 0 : Double.parseDouble(v); }
        catch (Exception e) { return 0; }
    }

    public ApiResponse<String> submit(OrderIntent intent) {
        Boolean first = redis.opsForValue()
                .setIfAbsent("idem:" + intent.clientOrderId(), "1", 10, TimeUnit.MINUTES);
        if (Boolean.FALSE.equals(first)) return ApiResponse.error(409, "duplicate clientOrderId");

        persistNew(intent);

        RiskClient.RiskDecision decision = riskClient.check(intent);
        if (!decision.approved()) {
            updateStatus(intent.clientOrderId(), OrderStatus.REJECTED, 0);
            publish(intent.clientOrderId(), intent.symbol(), OrderStatus.REJECTED, 0, 0, intent.accountOrDefault());
            return ApiResponse.error(403, "risk rejected: " + decision.reason());
        }
        // ── 名目金額上限 + 餘額檢查（事中風控補強）──
        double ref = intent.price() != null && intent.price() > 0 ? intent.price() : lastPrice(intent.symbol());
        double notional = intent.quantity() * ref;
        String preReject = preTradeCheck(intent, ref, notional);
        if (preReject != null) {
            updateStatus(intent.clientOrderId(), OrderStatus.REJECTED, 0);
            publish(intent.clientOrderId(), intent.symbol(), OrderStatus.REJECTED, 0, 0, intent.accountOrDefault());
            return ApiResponse.error(403, preReject);
        }

        publish(intent.clientOrderId(), intent.symbol(), OrderStatus.RISK_APPROVED, 0, 0, intent.accountOrDefault());
        updateStatus(intent.clientOrderId(), OrderStatus.RISK_APPROVED, 0);

        try {
            String exchangeResp = gateway.placeOrder(intent);
            publish(intent.clientOrderId(), intent.symbol(), OrderStatus.SUBMITTED, 0, 0, intent.accountOrDefault());
            updateStatus(intent.clientOrderId(), OrderStatus.SUBMITTED, 0);
            if (dryRun) {
                // dry-run 以最新市價成交（MARKET 單無 price），讓模擬盈虧/KPI 有意義
                double price = intent.price() != null && intent.price() > 0 ? intent.price() : lastPrice(intent.symbol());
                publish(intent.clientOrderId(), intent.symbol(), OrderStatus.FILLED, intent.quantity(), price, intent.accountOrDefault());
            } else if (!udsEnabled) {
                emitFillFromResponse(intent, exchangeResp);
            }
            log.info("下單完成 {}{} {} qty={} -> {}", intent.isFutures() ? "[合約]" : "[現貨]",
                    intent.symbol(), intent.side(), intent.quantity(), exchangeResp);
            return ApiResponse.ok(exchangeResp);
        } catch (Exception e) {
            log.error("place order failed: {}", e.getMessage());
            updateStatus(intent.clientOrderId(), OrderStatus.REJECTED, 0);
            publish(intent.clientOrderId(), intent.symbol(), OrderStatus.REJECTED, 0, 0, intent.accountOrDefault());
            return ApiResponse.error(500, e.getMessage());   // 把 filter/餘額/幣安錯誤訊息帶回前端
        }
    }

    /** 目前下單風控設定。 */
    public Map<String, Object> riskConfig() {
        return Map.of("maxNotionalUsdt", maxNotional, "balanceCheck", balanceCheck, "dryRun", dryRun);
    }

    /** 執行期調整下單風控（風控頁推送，免重啟）。null 表示不變更。 */
    public synchronized Map<String, Object> setRiskConfig(Double maxNotionalUsdt, Boolean balanceCheck) {
        if (maxNotionalUsdt != null && maxNotionalUsdt >= 0) this.maxNotional = maxNotionalUsdt;
        if (balanceCheck != null) this.balanceCheck = balanceCheck;
        log.warn("下單風控更新：maxNotional={} balanceCheck={}", maxNotional, this.balanceCheck);
        return riskConfig();
    }

    /** 事前檢查：名目金額上限 + （選配）可用餘額。通過回 null，否則回拒絕原因。 */
    private String preTradeCheck(OrderIntent intent, double ref, double notional) {
        if (maxNotional > 0 && ref > 0 && notional > maxNotional)
            return String.format("名目金額 %.2f USDT 超過單筆上限 %.0f USDT", notional, maxNotional);
        if (balanceCheck && intent.side() == com.quant.common.enums.OrderSide.BUY && !intent.isFutures() && ref > 0) {
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
                publish(intent.clientOrderId(), intent.symbol(), status, executed, avg, intent.accountOrDefault());
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
            publish(clientOrderId, symbol, OrderStatus.CANCELED, 0, 0, "main");
            return ApiResponse.ok(resp);
        } catch (Exception e) { return ApiResponse.error(500, "cancel failed: " + e.getMessage()); }
    }


    /** OCO 下單（現貨）：限價止盈 + 止損二擇一（見架構文件 §9）。 */
    public ApiResponse<String> submitOco(String symbol, String side, double quantity,
                                         double price, double stopPrice, double stopLimitPrice) {
        String lid = "oco-" + System.currentTimeMillis();
        try {
            return ApiResponse.ok(gateway.placeOco(symbol, side, quantity, price, stopPrice, stopLimitPrice, lid));
        } catch (Exception e) {
            return ApiResponse.error(500, "oco failed: " + e.getMessage());
        }
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
    private void persistNew(OrderIntent i) {
        try {
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
            orders.save(e);
        } catch (Exception ex) { log.warn("訂單落地失敗: {}", ex.getMessage()); }
    }

    private void updateStatus(String clientOrderId, OrderStatus status, double filled) {
        try {
            orders.findByClientOrderId(clientOrderId).ifPresent(e -> {
                e.setStatus(status.name());
                if (filled > 0) e.setFilledQty(filled);
                e.setUpdatedAt(OffsetDateTime.now());
                orders.save(e);
            });
        } catch (Exception ex) { log.warn("訂單狀態更新失敗: {}", ex.getMessage()); }
    }

    private void publish(String clientOrderId, String symbol, OrderStatus status, double filled, double avgPrice, String account) {
        kafka.send(Topics.ORDER_EVENTS, symbol,
                new OrderEvent(clientOrderId, null, symbol, status, filled, avgPrice, System.currentTimeMillis(), account));
    }
}
