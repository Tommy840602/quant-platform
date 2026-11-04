package com.quant.strategy.service;

import java.util.*;

/**
 * 模擬 / 影子 / 實盤對帳（純計算，好測）。
 *
 * <ul>
 *   <li>PAPER：模擬盤（晉升前），以現價成交、扣手續費估計。</li>
 *   <li>SHADOW：實盤送單同一刻的理想成交（無滑價、無手續費、不會被拒）。</li>
 *   <li>LIVE：真實成交（含手續費估計 0.1%/邊）。</li>
 * </ul>
 * 執行成本拆解：實盤 − 影子 = −手續費 − 滑價 − 其他（被拒、部分成交、延遲、限價沒成交改市價…）。
 * 晉升落差：模擬盤每筆平均報酬 vs 實盤每筆平均報酬，看「上實盤之後少賺多少」。
 */
public final class Reconciliation {
    private Reconciliation() {}

    public record TripRow(String mode, String symbol, int n, int wins, double pnl, double turnover, double meanRet) {}
    public record SlipRow(String symbol, int n, double avgBps, double cost, int withQty) {}

    public static Map<String, Object> build(List<TripRow> trips, List<SlipRow> slips, double feeRate, int rejectsSinceStart) {
        Map<String, Agg> byMode = new LinkedHashMap<>();
        Map<String, Map<String, Agg>> bySym = new TreeMap<>();
        for (TripRow r : trips) {
            byMode.computeIfAbsent(r.mode(), k -> new Agg()).add(r);
            bySym.computeIfAbsent(r.symbol(), k -> new HashMap<>()).computeIfAbsent(r.mode(), k -> new Agg()).add(r);
        }
        Agg live = byMode.getOrDefault("LIVE", new Agg()), shadow = byMode.getOrDefault("SHADOW", new Agg()),
            paper = byMode.getOrDefault("PAPER", new Agg());

        Map<String, SlipRow> slipBy = new HashMap<>();
        double slipCost = 0, slipBpsW = 0; int slipN = 0, slipNq = 0;
        for (SlipRow s : slips) { slipBy.put(s.symbol(), s); slipCost += s.cost(); slipBpsW += s.avgBps() * s.n(); slipN += s.n(); slipNq += s.withQty(); }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> modes = new LinkedHashMap<>();
        for (String m : List.of("PAPER", "SHADOW", "LIVE")) modes.put(m, byMode.getOrDefault(m, new Agg()).toMap());
        out.put("modes", modes);

        // 執行成本拆解（實盤 vs 影子）
        double fees = live.turnover * feeRate;
        double gap = live.pnl - shadow.pnl;
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("livePnl", r2(live.pnl)); cost.put("shadowPnl", r2(shadow.pnl)); cost.put("gap", r2(gap));
        cost.put("fees", r2(-fees));
        cost.put("slippage", r2(-slipCost));
        cost.put("other", r2(gap + fees + slipCost));
        cost.put("slipBps", slipN > 0 ? r2(slipBpsW / slipN) : null);
        cost.put("slipFills", slipN); cost.put("slipFillsWithQty", slipNq);
        cost.put("missedTrips", Math.max(0, shadow.n - live.n));   // 影子有、實盤沒成的來回（被拒 / 沒成交）
        cost.put("rejectsSinceStart", rejectsSinceStart);
        cost.put("costPerTrip", live.n > 0 ? r2((-fees - slipCost) / live.n) : null);
        out.put("execution", cost);

        // 晉升落差：同樣每筆平均報酬，模擬 → 實盤
        Map<String, Object> promo = new LinkedHashMap<>();
        promo.put("paperMeanRetPct", paper.n > 0 ? r4(paper.meanRet() * 100) : null);
        promo.put("liveMeanRetPct", live.n > 0 ? r4(live.meanRet() * 100) : null);
        promo.put("deltaPct", paper.n > 0 && live.n > 0 ? r4((live.meanRet() - paper.meanRet()) * 100) : null);
        promo.put("paperWinRate", paper.winRate()); promo.put("liveWinRate", live.winRate());
        promo.put("enough", paper.n >= 5 && live.n >= 5);
        out.put("promotion", promo);

        // 每個標的：實盤 vs 影子 + 滑價，找出哪個幣在吃成本
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> syms = new TreeSet<>(bySym.keySet()); syms.addAll(slipBy.keySet());
        for (String sym : syms) {
            Map<String, Agg> m = bySym.getOrDefault(sym, Map.of());
            Agg l = m.getOrDefault("LIVE", new Agg()), sh = m.getOrDefault("SHADOW", new Agg()), p = m.getOrDefault("PAPER", new Agg());
            SlipRow s = slipBy.get(sym);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol", sym);
            r.put("paperN", p.n); r.put("paperPnl", r2(p.pnl));
            r.put("liveN", l.n); r.put("livePnl", r2(l.pnl));
            r.put("shadowN", sh.n); r.put("shadowPnl", r2(sh.pnl));
            r.put("gap", r2(l.pnl - sh.pnl));
            r.put("fees", r2(-l.turnover * feeRate));
            r.put("slipBps", s == null ? null : r2(s.avgBps())); r.put("slipCost", s == null ? 0 : r2(-s.cost()));
            rows.add(r);
        }
        rows.sort(Comparator.comparingDouble(r -> (double) r.get("gap")));   // 差最多的在前
        out.put("symbols", rows);
        return out;
    }

    static final class Agg {
        int n, wins; double pnl, turnover, retSum;
        void add(TripRow r) { n += r.n(); wins += r.wins(); pnl += r.pnl(); turnover += r.turnover(); retSum += r.meanRet() * r.n(); }
        double meanRet() { return n > 0 ? retSum / n : 0; }
        Double winRate() { return n > 0 ? Math.round(wins * 1000.0 / n) / 10.0 : null; }
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trips", n); m.put("winRate", winRate()); m.put("pnl", r2(pnl));
            m.put("avgPnl", n > 0 ? r4(pnl / n) : null); m.put("meanRetPct", n > 0 ? r4(meanRet() * 100) : null);
            return m;
        }
    }

    static double r2(double v) { return Math.round(v * 100) / 100.0; }
    static double r4(double v) { return Math.round(v * 10000) / 10000.0; }
}
