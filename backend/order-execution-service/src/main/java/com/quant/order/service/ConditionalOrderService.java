package com.quant.order.service;

import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 條件單 / 智慧單引擎（見架構文件 §9）：預設觸發條件，行情達標時自動送市價單。
 * 支援：STOP_LOSS(跌破觸發賣)、TAKE_PROFIT(漲到觸發賣)、TRAILING_STOP(移動鎖利)、
 *       PRICE_ABOVE/PRICE_BELOW(突破/跌破觸發任意方向)。
 * 以 1 秒輪詢 Redis 最新價(market-data 寫入 price:SYMBOL)判斷觸發。
 */
@Service
public class ConditionalOrderService {

    private static final Logger log = LoggerFactory.getLogger(ConditionalOrderService.class);
    private final AtomicLong seq = new AtomicLong(1);
    private final Map<Long, Cond> conds = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final OrderService orderService;

    public ConditionalOrderService(StringRedisTemplate redis, OrderService orderService) {
        this.redis = redis; this.orderService = orderService;
    }

    public Map<String, Object> create(Map<String, Object> b) {
        Cond c = new Cond();
        c.id = seq.getAndIncrement();
        c.symbol = ((String) b.get("symbol")).toUpperCase();
        c.type = ((String) b.getOrDefault("type", "STOP_LOSS")).toUpperCase();
        c.side = OrderSide.valueOf(((String) b.getOrDefault("side", "SELL")).toUpperCase());
        c.quantity = ((Number) b.getOrDefault("quantity", 0.001)).doubleValue();
        c.triggerPrice = b.get("triggerPrice") == null ? 0 : ((Number) b.get("triggerPrice")).doubleValue();
        c.trailPct = b.get("trailPct") == null ? 0 : ((Number) b.get("trailPct")).doubleValue();
        c.market = (String) b.getOrDefault("market", "SPOT");
        c.account = (String) b.getOrDefault("account", "main");
        c.status = "PENDING";
        conds.put(c.id, c);
        log.info("建立條件單 #{} {} {} trigger={} trail={}", c.id, c.symbol, c.type, c.triggerPrice, c.trailPct);
        return c.toMap();
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        conds.values().forEach(c -> out.add(c.toMap()));
        out.sort(Comparator.comparingLong(m -> (Long) m.get("id")));
        return out;
    }

    public String cancel(long id) {
        Cond c = conds.get(id);
        if (c != null && "PENDING".equals(c.status)) { c.status = "CANCELED"; return "canceled: " + id; }
        return "not found or not pending: " + id;
    }

    @Scheduled(fixedRate = 1000)
    public void monitor() {
        for (Cond c : conds.values()) {
            if (!"PENDING".equals(c.status)) continue;
            String px = redis.opsForValue().get("price:" + c.symbol);
            if (px == null) continue;
            double price = Double.parseDouble(px);
            if (c.peak == 0 || price > c.peak) c.peak = price;   // 追蹤峰值供移動停利
            if (triggered(c, price)) fire(c, price);
        }
    }

    private boolean triggered(Cond c, double price) {
        return switch (c.type) {
            case "STOP_LOSS", "PRICE_BELOW" -> price <= c.triggerPrice;
            case "TAKE_PROFIT", "PRICE_ABOVE" -> price >= c.triggerPrice;
            case "TRAILING_STOP" -> c.trailPct > 0 && price <= c.peak * (1 - c.trailPct);
            default -> false;
        };
    }

    private void fire(Cond c, double price) {
        c.status = "TRIGGERED"; c.triggeredPrice = price;
        OrderIntent intent = new OrderIntent("cond-" + c.id + "-" + System.currentTimeMillis(),
                "conditional", c.symbol, c.side, OrderType.MARKET, null, c.quantity,
                c.side == OrderSide.SELL, c.market, c.account);
        try {
            orderService.submit(intent);
            log.info("條件單 #{} 觸發 @ {} → 送 {} {}", c.id, price, c.side, c.symbol);
        } catch (Exception e) { log.warn("條件單 #{} 送單失敗: {}", c.id, e.getMessage()); }
    }

    static class Cond {
        long id; String symbol, type, market, account, status;
        OrderSide side; double quantity, triggerPrice, trailPct, peak, triggeredPrice;
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("symbol", symbol); m.put("type", type); m.put("side", side.name());
            m.put("quantity", quantity); m.put("triggerPrice", triggerPrice); m.put("trailPct", trailPct);
            m.put("peak", peak); m.put("status", status); m.put("triggeredPrice", triggeredPrice);
            m.put("account", account);
            return m;
        }
    }
}
