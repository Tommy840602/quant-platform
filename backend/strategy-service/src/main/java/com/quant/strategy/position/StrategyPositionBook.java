package com.quant.strategy.position;

import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略部位簿：唯一可信來源是 OMS 的成交事件，不是「送單沒丟例外」。
 * <ul>
 *   <li>送單成功（OMS code==0）→ 該 (策略, symbol) 進入 pending，策略不得再開新單，直到終結事件到達</li>
 *   <li>FILLED / PARTIALLY_FILLED → 以有號成交量更新部位</li>
 *   <li>REJECTED / CANCELED / EXPIRED → 部位不變，只解除 pending</li>
 *   <li>UNKNOWN → 維持 pending（OMS 對帳後會補發終結事件）</li>
 *   <li>啟動時從 OMS 的 /orders/strategy-positions 對帳，重啟後不會「以為空手再買」</li>
 * </ul>
 */
@Component
public class StrategyPositionBook {

    private static final Logger log = LoggerFactory.getLogger(StrategyPositionBook.class);

    /** strategyId -> symbol -> signed qty */
    private final Map<String, Map<String, Double>> positions = new ConcurrentHashMap<>();
    /** strategyId -> symbols with an in-flight order */
    private final Map<String, Set<String>> pending = new ConcurrentHashMap<>();
    /** clientOrderId -> (strategyId, symbol) for orders this instance submitted */
    private final Map<String, String[]> inflight = new ConcurrentHashMap<>();
    private volatile boolean reconciled = false;

    public record Inflight(String strategyId, String symbol) {}

    // ── queries ──
    public double position(String strategyId, String symbol) {
        return positions.getOrDefault(strategyId, Map.of()).getOrDefault(symbol, 0.0);
    }
    public double totalPosition(String strategyId) {
        return positions.getOrDefault(strategyId, Map.of()).values().stream().mapToDouble(Double::doubleValue).sum();
    }
    public boolean isPending(String strategyId, String symbol) {
        return pending.getOrDefault(strategyId, Set.of()).contains(symbol);
    }
    public boolean isReconciled() { return reconciled; }
    public Map<String, Double> snapshot(String strategyId) { return Map.copyOf(positions.getOrDefault(strategyId, Map.of())); }

    // ── mutations ──
    /** OMS 已接受（code==0）。部位先不動，只標 pending。 */
    public void onSubmitted(String clientOrderId, String strategyId, String symbol) {
        inflight.put(clientOrderId, new String[]{strategyId, symbol});
        pending.computeIfAbsent(strategyId, k -> ConcurrentHashMap.newKeySet()).add(symbol);
    }

    /** OMS 同步拒絕（code!=0）或呼叫失敗：什麼都沒發生。 */
    public void onSubmitFailed(String clientOrderId) { inflight.remove(clientOrderId); }

    /** @return true 若事件屬於本實例已知的單（否則呼叫端可考慮觸發對帳） */
    public boolean onEvent(OrderEvent e) {
        String[] ref = inflight.get(e.clientOrderId());
        if (ref == null) return false;
        String sid = ref[0], sym = ref[1];
        OrderStatus st = e.status();
        boolean fill = st == OrderStatus.FILLED || st == OrderStatus.PARTIALLY_FILLED;
        if (fill && e.filledQty() > 0 && e.side() != null) {
            double signed = e.side() == OrderSide.SELL ? -e.filledQty() : e.filledQty();
            positions.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).merge(sym, signed, Double::sum);
            log.info("[{}] 部位 {} {}{} → {}", sid, sym, signed >= 0 ? "+" : "", signed, position(sid, sym));
        }
        boolean terminal = st == OrderStatus.FILLED || st == OrderStatus.CANCELED
                || st == OrderStatus.REJECTED || st == OrderStatus.EXPIRED;
        if (terminal) {
            inflight.remove(e.clientOrderId());
            Set<String> p = pending.get(sid);
            if (p != null) p.remove(sym);
            if (st != OrderStatus.FILLED) log.info("[{}] {} 訂單 {} → {}，部位不變", sid, sym, e.clientOrderId(), st);
        }
        return true;
    }

    /** 以 OMS 快照覆寫部位與 pending（啟動或失聯後）。 */
    public void reconcile(List<Map<String, Object>> rows) {
        Map<String, Map<String, Double>> pos = new ConcurrentHashMap<>();
        Map<String, Set<String>> pend = new ConcurrentHashMap<>();
        for (Map<String, Object> r : rows) {
            String sid = String.valueOf(r.get("strategyId")), sym = String.valueOf(r.get("symbol"));
            double qty = ((Number) r.getOrDefault("qty", 0)).doubleValue();
            int open = ((Number) r.getOrDefault("openOrders", 0)).intValue();
            if (Math.abs(qty) > 1e-12) pos.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).put(sym, qty);
            if (open > 0) pend.computeIfAbsent(sid, k -> ConcurrentHashMap.newKeySet()).add(sym);
        }
        positions.clear(); positions.putAll(pos);
        pending.clear(); pending.putAll(pend);
        reconciled = true;
        log.info("策略部位已與 OMS 對帳：{} 策略有部位，{} 策略有未結單", pos.size(), pend.size());
    }
}
