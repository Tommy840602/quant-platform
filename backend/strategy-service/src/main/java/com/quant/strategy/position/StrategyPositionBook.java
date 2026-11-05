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
    /** clientOrderId -> 已成交量（限價優先：撤單後補市價只補剩下的） */
    private final Map<String, Double> filledByCid = new ConcurrentHashMap<>();
    public boolean isInflight(String clientOrderId) { return inflight.containsKey(clientOrderId); }
    public double filled(String clientOrderId) { return filledByCid.getOrDefault(clientOrderId, 0.0); }
    public void forgetFilled(String clientOrderId) { filledByCid.remove(clientOrderId); }
    private volatile boolean reconciled = false;
    /** strategyId -> symbol -> 平均成本（出場風控算停損/停利、來回損益用） */
    private final Map<String, Map<String, Double>> avgCost = new ConcurrentHashMap<>();
    /** 平倉（SELL 成交）時回報一筆來回損益：(strategyId, symbol, pnlUsdt) */
    /** 一筆平倉（來回）：qty 為平掉的數量，pnl 已扣來回手續費估計。 */
    public record Close(String strategyId, String symbol, double qty, double entryPx, double exitPx, double pnl) {
        public double entryNotional() { return entryPx * qty; }
        public double ret() { return entryNotional() > 0 ? pnl / entryNotional() : 0; }
    }
    public interface CloseListener { void onClose(Close c); }
    private volatile CloseListener closeListener;
    public void setCloseListener(CloseListener l) { this.closeListener = l; }
    /** 每筆成交（滑價計算用）：(clientOrderId, strategyId, symbol, side, qty, price) */
    public interface FillListener { void onFill(String clientOrderId, String strategyId, String symbol, OrderSide side, double qty, double px); }
    private volatile FillListener fillListener;
    public void setFillListener(FillListener l) { this.fillListener = l; }
    private static final double FEE_RATE = 0.001;   // 單邊手續費估計，來回 ×2

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
    /** 這個策略還有任何在途單（任一標的）。 */
    public boolean hasPending(String strategyId) { return !pending.getOrDefault(strategyId, Set.of()).isEmpty(); }
    public Map<String, Double> snapshot(String strategyId) { return Map.copyOf(positions.getOrDefault(strategyId, Map.of())); }
    public double avgCost(String strategyId, String symbol) { return avgCost.getOrDefault(strategyId, Map.of()).getOrDefault(symbol, 0.0); }
    /** 有部位或有未結單的標的數（同時持倉上限用） */
    public int openSlots(String strategyId) {
        Set<String> s = new java.util.HashSet<>(pending.getOrDefault(strategyId, Set.of()));
        positions.getOrDefault(strategyId, Map.of()).forEach((k, v) -> { if (Math.abs(v) > 1e-12) s.add(k); });
        return s.size();
    }
    /** 補登入場成本（例如舊版 OMS 對帳沒帶成本時，以現價代替） */
    public void seedCost(String strategyId, String symbol, double px) {
        if (px > 0) avgCost.computeIfAbsent(strategyId, k -> new ConcurrentHashMap<>()).putIfAbsent(symbol, px);
    }

    // ── mutations ──
    /** OMS 已接受（code==0）。部位先不動，只標 pending。 */
    public void onSubmitted(String clientOrderId, String strategyId, String symbol) {
        inflight.put(clientOrderId, new String[]{strategyId, symbol});
        pending.computeIfAbsent(strategyId, k -> ConcurrentHashMap.newKeySet()).add(symbol);
    }

    /** OMS 同步拒絕（code!=0）或確認 OMS 沒收到：什麼都沒發生 → 解除在途（同標的沒有其他在途單才清 pending）。 */
    public void onSubmitFailed(String clientOrderId) {
        String[] k = inflight.remove(clientOrderId);
        if (k == null) return;
        boolean other = inflight.values().stream().anyMatch(v -> v[0].equals(k[0]) && v[1].equals(k[1]));
        if (!other) { Set<String> p = pending.get(k[0]); if (p != null) p.remove(k[1]); }
    }

    /** @return true 若事件屬於本實例已知的單（否則呼叫端可考慮觸發對帳） */
    public boolean onEvent(OrderEvent e) {
        String[] ref = inflight.get(e.clientOrderId());
        if (ref == null) return false;
        String sid = ref[0], sym = ref[1];
        OrderStatus st = e.status();
        boolean fill = st == OrderStatus.FILLED || st == OrderStatus.PARTIALLY_FILLED;
        if (fill && e.filledQty() > 0 && e.side() != null) {
            filledByCid.merge(e.clientOrderId(), e.filledQty(), Double::sum);
            double signed = e.side() == OrderSide.SELL ? -e.filledQty() : e.filledQty();
            double before = position(sid, sym), px = e.avgPrice();
            Map<String, Double> costs = avgCost.computeIfAbsent(sid, k -> new ConcurrentHashMap<>());
            double avg = costs.getOrDefault(sym, 0.0);
            if (px > 0) {
                if (Math.abs(before) <= 1e-12 || Math.signum(before) == Math.signum(signed)) {
                    // 開倉 / 同向加碼：加權平均成本（多單、空單皆同）
                    costs.put(sym, (avg * Math.abs(before) + px * Math.abs(signed)) / (Math.abs(before) + Math.abs(signed)));
                } else {
                    // 反向成交 = 平倉（多單賣出 / 空單回補）：回報來回損益；翻倉的剩餘以本次價格為新成本
                    double q = Math.min(Math.abs(before), Math.abs(signed));
                    if (avg > 0) {
                        double pnl = (px - avg) * q * Math.signum(before) - (px + avg) * q * FEE_RATE;
                        CloseListener l = closeListener;
                        if (l != null) try { l.onClose(new Close(sid, sym, q, avg, px, pnl)); } catch (Exception ex) { log.warn("平倉回報處理失敗: {}", ex.getMessage()); }
                    }
                    if (Math.abs(signed) > Math.abs(before) + 1e-12) costs.put(sym, px);
                }
            }
            positions.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).merge(sym, signed, Double::sum);
            if (Math.abs(position(sid, sym)) <= 1e-12) costs.remove(sym);
            FillListener fl = fillListener;
            if (fl != null && px > 0) try { fl.onFill(e.clientOrderId(), sid, sym, e.side(), e.filledQty(), px); } catch (Exception ex) { log.debug("fill hook: {}", ex.getMessage()); }
            log.info("[{}] 部位 {} {}{} → {}", sid, sym, signed >= 0 ? "+" : "", signed, position(sid, sym));
        }
        boolean terminal = st == OrderStatus.FILLED || st == OrderStatus.CANCELED
                || st == OrderStatus.REJECTED || st == OrderStatus.EXPIRED;
        if (terminal) {
            inflight.remove(e.clientOrderId());
            // 同一 (策略, 標的) 還有其他在途單（例如限價撤單後補的市價單）就維持 pending
            boolean other = inflight.values().stream().anyMatch(v -> v[0].equals(sid) && v[1].equals(sym));
            Set<String> p = pending.get(sid);
            if (p != null && !other) p.remove(sym);
            if (st != OrderStatus.FILLED) log.info("[{}] {} 訂單 {} → {}，部位不變", sid, sym, e.clientOrderId(), st);
        }
        return true;
    }

    /** 以 OMS 快照覆寫部位與 pending（啟動或失聯後）。 */
    public void reconcile(List<Map<String, Object>> rows) {
        Map<String, Map<String, Double>> pos = new ConcurrentHashMap<>();
        Map<String, Set<String>> pend = new ConcurrentHashMap<>();
        Map<String, Map<String, Double>> cost = new ConcurrentHashMap<>();
        for (Map<String, Object> r : rows) {
            String sid = String.valueOf(r.get("strategyId")), sym = String.valueOf(r.get("symbol"));
            double qty = ((Number) r.getOrDefault("qty", 0)).doubleValue();
            int open = ((Number) r.getOrDefault("openOrders", 0)).intValue();
            if (Math.abs(qty) > 1e-12) pos.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).put(sym, qty);
            Object ac = r.get("avgCost");
            if (Math.abs(qty) > 1e-12 && ac instanceof Number n && n.doubleValue() > 0)
                cost.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).put(sym, n.doubleValue());
            else if (Math.abs(qty) > 1e-12) { double old = avgCost(sid, sym); if (old > 0) cost.computeIfAbsent(sid, k -> new ConcurrentHashMap<>()).put(sym, old); }
            if (open > 0) pend.computeIfAbsent(sid, k -> ConcurrentHashMap.newKeySet()).add(sym);
        }
        positions.clear(); positions.putAll(pos);
        pending.clear(); pending.putAll(pend);
        avgCost.clear(); avgCost.putAll(cost);
        reconciled = true;
        log.info("策略部位已與 OMS 對帳：{} 策略有部位，{} 策略有未結單", pos.size(), pend.size());
    }
}
