package com.quant.strategy.service;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.strategy.core.StrategyContextImpl;
import com.quant.strategy.position.StrategyPositionBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 影子雙胞胎 + 模擬盤引擎。
 *
 * <p><b>影子（SHADOW）</b>：每個真錢機器人送單的同一刻，影子以「送單當下的參考價」理想成交（無滑價、無手續費、不會被拒）。
 * 實盤損益 − 影子損益 = 執行成本（滑價 + 手續費 + 被拒 + 延遲）。另外逐筆比對參考價與實際成交價，算出滑價 bps。
 * 差距異常擴大時告警 —— 很多「策略失效」其實是執行成本吃掉了 edge。
 *
 * <p><b>模擬盤（PAPER）</b>：晉升管線第一關。機器人照常收行情、出訊號、走風控，但單子不送 OMS，
 * 由這裡以現價成交並扣手續費估計；平倉同樣回報給風控（連虧熔斷）與自動配資，紀錄落庫給晉升關卡判斷。
 */
@Service
public class ShadowEngine implements StrategyContextImpl.Execution {

    private static final Logger log = LoggerFactory.getLogger(ShadowEngine.class);
    static final double FEE_RATE = 0.001;
    static final double SLIP_ALERT_BPS = 25;

    static final class Pos { double qty, entry, peak; }

    private final Map<String, Map<String, Pos>> paper = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Pos>> shadow = new ConcurrentHashMap<>();
    private final Map<String, Object[]> refs = new ConcurrentHashMap<>();       // cid -> {botId, symbol, side, refPx, ts}
    private final Map<String, Double> shadowRealized = new ConcurrentHashMap<>();
    private final Map<String, Integer> rejects = new ConcurrentHashMap<>();
    private final Map<String, Deque<Double>> slips = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();
    private final Map<String, Double> prices = new ConcurrentHashMap<>();

    private RoundTripRepository repo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRepo(RoundTripRepository r) { this.repo = r; }
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRedis(org.springframework.data.redis.core.StringRedisTemplate r) { this.redis = r; }
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private PositionStateRepository state;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setState(PositionStateRepository s) { this.state = s; }

    /** 重啟後還原模擬盤 / 影子部位、滑價樣本。 */
    public int restore(Collection<String> botIds) {
        int n = 0;
        if (state != null) try {
            for (PositionStateRepository.Row r : state.loadAll()) {
                Map<String, Map<String, Pos>> target = "PAPER".equals(r.kind()) ? paper : "SHADOW".equals(r.kind()) ? shadow : null;
                if (target == null || Math.abs(r.qty()) <= 1e-12) continue;
                Pos p = new Pos(); p.qty = r.qty(); p.entry = r.entry(); p.peak = r.peak();
                target.computeIfAbsent(r.botId(), k -> new ConcurrentHashMap<>()).put(r.symbol(), p); n++;
            }
        } catch (Exception e) { log.warn("還原模擬盤 / 影子部位失敗: {}", e.getMessage()); }
        if (repo != null) for (String id : botIds) try {
            List<Double> l = repo.recentSlips(id, 50);
            if (!l.isEmpty()) slips.put(id, new java.util.concurrent.ConcurrentLinkedDeque<>(l));
        } catch (Exception e) { log.debug("滑價樣本讀取失敗: {}", e.getMessage()); }
        return n;
    }
    /** 本次上線以來的影子已實現損益（重啟後由來回紀錄算回來）。 */
    public void restoreSession(String id, double shadowRealizedSinceStart) {
        if (shadowRealizedSinceStart != 0) shadowRealized.put(id, shadowRealizedSinceStart);
    }

    private void save(String id, String sym, String kind, Pos p) {
        if (state == null) return;
        try {
            if (p == null || Math.abs(p.qty) <= 1e-12) state.delete(id, sym, kind);
            else state.upsert(id, sym, kind, p.qty, p.entry, p.peak);
        } catch (Exception e) { log.warn("{} 部位寫入失敗 {} {}: {}", kind, id, sym, e.getMessage()); }
    }

    private volatile BotRiskManager risk;
    private volatile Function<String, String> stageOf = id -> "LIVE";
    private volatile Function<String, String> names = id -> id;
    private volatile NotifyClient notify;
    private volatile DecisionSink decisions;

    /** 決策日誌落點（見 DecisionLog）。 */
    public interface DecisionSink { void record(String botId, String symbol, OrderSide side, double px, String outcome); }
    public void setDecisionSink(DecisionSink d) { this.decisions = d; }

    public void bind(BotRiskManager risk, Function<String, String> stageOf, Function<String, String> names, NotifyClient notify) {
        this.risk = risk; this.stageOf = stageOf; this.names = names; this.notify = notify;
    }

    /** 書上的成交 → 滑價。 */
    public void attach(StrategyPositionBook book) { book.setFillListener(this::onFill); }

    // ── Execution ──
    @Override public boolean paper(String id) { return "PAPER".equals(stageOf.apply(id)); }

    @Override
    public void paperFill(String id, OrderIntent i, double px) {
        if (px <= 0) return;
        Map<String, Pos> m = paper.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        StrategyPositionBook.Close c = apply(id, m, i.symbol(), i.side(), i.quantity(), px, true);
        save(id, i.symbol(), "PAPER", m.get(i.symbol()));
        if (c == null) return;
        if (repo != null) try { repo.insert(c, "PAPER"); } catch (Exception e) { log.warn("模擬盤紀錄寫入失敗: {}", e.getMessage()); }
        BotRiskManager r = risk;
        if (r != null) r.onClose(c);
    }

    @Override public double paperPosition(String id, String sym) { Pos p = paper.getOrDefault(id, Map.of()).get(sym); return p == null ? 0 : p.qty; }
    @Override public double paperEntry(String id, String sym) { Pos p = paper.getOrDefault(id, Map.of()).get(sym); return p == null ? 0 : p.entry; }
    @Override public Map<String, Double> paperSnapshot(String id) { return snap(paper.get(id)); }

    @Override
    public void onLiveSend(String id, OrderIntent i, double refPx) {
        refs.put(i.clientOrderId(), new Object[]{id, i.symbol(), i.side(), refPx, System.currentTimeMillis()});
        shadowFill(id, i.symbol(), i.side(), i.quantity(), refPx);
    }

    @Override public void onLiveReject(String id, OrderIntent i, String reason) { rejects.merge(id, 1, Integer::sum); }

    @Override
    public double sizeQty(String id, String sym, double px) {
        if ("PILOT".equals(stageOf.apply(id))) return 5.5 / px;   // 試行階段：只下幣安最低名目
        BotRiskManager r = risk;
        return r == null ? 0 : r.sizeQty(id, sym, px);
    }

    @Override
    public double param(String id, String key) { BotRiskManager r = risk; return r == null ? 0 : r.p(id, key); }

    /** 買一 / 賣一：market-data 每秒寫進 Redis 的 depth10（10 秒過期，過期就不走限價優先）。 */
    @Override
    public double[] quote(String symbol) {
        if (redis == null) return null;
        try {
            String js = redis.opsForValue().get("depth:" + symbol);
            if (js == null) return null;
            var n = JSON.readTree(js);
            double bid = n.path("bids").path(0).path(0).asDouble(0), ask = n.path("asks").path(0).path(0).asDouble(0);
            return bid > 0 && ask > 0 && ask >= bid ? new double[]{bid, ask} : null;
        } catch (Exception e) { return null; }
    }

    @Override
    public void onDecision(String id, String sym, OrderSide side, double px, String outcome) {
        DecisionSink d = decisions;
        if (d != null) d.record(id, sym, side, px, (paper(id) ? "[模擬] " : "") + outcome);
    }

    // ── 影子的出場：實盤被拒時影子還拿著部位，用同一套停損 / 停利規則自己出場 ──
    public void onTick(String id, Kline k) {
        prices.put(k.symbol(), k.close());
        Pos p = shadow.getOrDefault(id, Map.of()).get(k.symbol());
        BotRiskManager r = risk;
        if (p == null || r == null) return;
        double before = p.peak;
        boolean shortPos = p.qty < 0;
        p.peak = shortPos ? Math.min(p.peak, Math.min(k.low() > 0 ? k.low() : k.close(), k.close())) : Math.max(p.peak, Math.max(k.high(), k.close()));
        if (before > 0 && Math.abs(p.peak / before - 1) >= 0.0005) save(id, k.symbol(), "SHADOW", p);
        String why = shortPos ? com.quant.common.risk.RiskRules.exitReasonShort(k.close(), p.entry, p.peak, r.stats(id, k.symbol()).atr(), riskParams(id))
                              : r.exitReason(id, k.symbol(), k.close(), p.entry, p.peak);
        if (why != null) shadowFill(id, k.symbol(), shortPos ? OrderSide.BUY : OrderSide.SELL, Math.abs(p.qty), k.close());
    }

    void onFill(String cid, String id, String sym, OrderSide side, double qty, double px) {
        Object[] ref = refs.remove(cid);
        if (ref == null) return;
        double refPx = (double) ref[3];
        if (refPx <= 0) return;
        double bps = (px - refPx) / refPx * 1e4 * (side == OrderSide.BUY ? 1 : -1);
        Deque<Double> d = slips.computeIfAbsent(id, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        d.addLast(bps); while (d.size() > 50) d.removeFirst();
        if (repo != null) try { repo.insertSlip(id, sym, side.name(), refPx, px, bps, qty); } catch (Exception e) { log.debug("slip 寫入失敗: {}", e.getMessage()); }
        log.info("[{}] {} {} 滑價 {} bps（參考 {} → 成交 {}）", id, sym, side, Math.round(bps * 10) / 10.0, refPx, px);
    }

    // ── 比較 ──
    public double shadowPnl(String id) {
        double u = 0;
        for (Map.Entry<String, Pos> e : shadow.getOrDefault(id, Map.of()).entrySet()) {
            double px = prices.getOrDefault(e.getKey(), 0.0);
            if (px > 0) u += (px - e.getValue().entry) * e.getValue().qty;
        }
        return shadowRealized.getOrDefault(id, 0.0) + u;
    }

    public double avgSlipBps(String id) {
        Deque<Double> d = slips.get(id);
        return d == null || d.isEmpty() ? 0 : d.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }
    public int slipCount(String id) { Deque<Double> d = slips.get(id); return d == null ? 0 : d.size(); }
    public int rejects(String id) { return rejects.getOrDefault(id, 0); }

    /** 實盤 vs 影子摘要（UI）。 */
    public Map<String, Object> compare(String id, double livePnl) {
        Map<String, Object> m = new LinkedHashMap<>();
        double sh = shadowPnl(id);
        m.put("shadowPnl", r4(sh)); m.put("livePnl", r4(livePnl)); m.put("gap", r4(livePnl - sh));
        m.put("slipBps", Math.round(avgSlipBps(id) * 10) / 10.0); m.put("slipN", slipCount(id));
        m.put("rejects", rejects(id));
        return m;
    }

    /** 上線：重新計算本次差距（影子部位清空，從零開始對照）。 */
    public void reset(String id) {
        shadowRealized.remove(id); rejects.remove(id); shadow.remove(id);
        if (state != null) try { state.deleteKind(id, "SHADOW"); } catch (Exception e) { log.debug("影子部位清除失敗: {}", e.getMessage()); }
    }
    /** 離開模擬盤：丟掉模擬部位。 */
    public void clearPaper(String id) {
        paper.remove(id);
        if (state != null) try { state.deleteKind(id, "PAPER"); } catch (Exception e) { log.debug("模擬部位清除失敗: {}", e.getMessage()); }
    }

    /** 每 5 分鐘：滑價或差距異常 → 告警（每機器人每小時一次）。 */
    @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
    public void watch() {
        long now = System.currentTimeMillis();
        for (String id : new ArrayList<>(slips.keySet())) {
            if (slipCount(id) < 5 || now - lastAlert.getOrDefault(id, 0L) < 3_600_000) continue;
            double s = avgSlipBps(id);
            if (s >= SLIP_ALERT_BPS) {
                lastAlert.put(id, now);
                send(String.format(":warning: [%s] 平均滑價 %.1f bps（近 %d 筆）超過 %.0f bps：執行成本正在吃掉策略獲利，考慮改限價單或避開流動性差的幣",
                        names.apply(id), s, slipCount(id), SLIP_ALERT_BPS));
            }
        }
    }

    public void alertGap(String id, double livePnl) {
        double sh = shadowPnl(id), gap = livePnl - sh;
        long now = System.currentTimeMillis();
        if (gap < -Math.max(1.0, 0.3 * Math.abs(sh)) && now - lastAlert.getOrDefault("gap:" + id, 0L) >= 3_600_000) {
            lastAlert.put("gap:" + id, now);
            send(String.format(":warning: [%s] 實盤 %.2f vs 影子 %.2f USDT，差距 %.2f（被拒 %d 筆、平均滑價 %.1f bps）",
                    names.apply(id), livePnl, sh, gap, rejects(id), avgSlipBps(id)));
        }
    }

    // ── helpers ──
    /**
     * 把一筆成交套到有號部位（多 + / 空 −）：同向開倉或加碼取加權平均；反向就是平倉，回傳來回紀錄；
     * 反向數量大於部位（翻倉）時，剩下的以本次價格開新倉。
     */
    static StrategyPositionBook.Close apply(String id, Map<String, Pos> m, String sym, OrderSide side, double q, double px, boolean fees) {
        if (q <= 1e-12 || px <= 0) return null;
        Pos p = m.get(sym);
        double cur = p == null ? 0 : p.qty, signed = side == OrderSide.BUY ? q : -q;
        if (Math.abs(cur) <= 1e-12 || Math.signum(cur) == Math.signum(signed)) {
            if (p == null) { p = new Pos(); m.put(sym, p); }
            p.entry = (p.entry * Math.abs(p.qty) + px * q) / (Math.abs(p.qty) + q);
            p.qty += signed;
            p.peak = Math.abs(cur) <= 1e-12 ? px : p.qty > 0 ? Math.max(p.peak, px) : Math.min(p.peak, px);
            return null;
        }
        double c = Math.min(Math.abs(cur), q);
        double pnl = (px - p.entry) * c * Math.signum(cur) - (fees ? (px + p.entry) * c * FEE_RATE : 0);
        StrategyPositionBook.Close close = new StrategyPositionBook.Close(id, sym, c, p.entry, px, pnl);
        double rem = q - c;
        if (rem > 1e-12) { p.qty = Math.signum(signed) * rem; p.entry = px; p.peak = px; }
        else { p.qty = cur + signed; if (Math.abs(p.qty) <= 1e-12) m.remove(sym); }
        return close;
    }

    private void shadowFill(String id, String sym, OrderSide side, double q, double px) {
        Map<String, Pos> m = shadow.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        StrategyPositionBook.Close c = apply(id, m, sym, side, q, px, false);
        save(id, sym, "SHADOW", m.get(sym));
        if (c == null) return;
        shadowRealized.merge(id, c.pnl(), Double::sum);
        if (repo != null) try { repo.insert(c, "SHADOW"); } catch (Exception e) { log.debug("影子紀錄寫入失敗: {}", e.getMessage()); }
    }

    private Map<String, Object> riskParams(String id) {
        Map<String, Object> m = new HashMap<>();
        for (String k : com.quant.common.risk.RiskRules.DEFAULTS.keySet()) m.put(k, param(id, k));
        return m;
    }

    private static Map<String, Double> snap(Map<String, Pos> m) {
        Map<String, Double> out = new HashMap<>();
        if (m != null) m.forEach((k, v) -> out.put(k, v.qty));
        return out;
    }
    private void send(String text) {
        NotifyClient n = notify;
        if (n != null) try { n.send(Map.of("text", text)); } catch (Exception e) { log.debug("通知失敗: {}", e.getMessage()); }
    }
    private static double r4(double v) { return Math.round(v * 10000) / 10000.0; }
}
