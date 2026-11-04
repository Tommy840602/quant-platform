package com.quant.order.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

/**
 * 每月損益（已實現）：依 (機器人, 市場, 標的) 以 FIFO 沖銷成交，平倉那一刻的月份記帳。
 * 合約有號：空手時 SELL = 開空、BUY = 回補；現貨沒部位的 SELL（帳外持幣）不計損益。
 * 手續費為估計：現貨 0.1%、合約 0.05%（taker）× 成交額；實際以幣安對帳單為準。
 */
public final class MonthlyPnl {
    private MonthlyPnl() {}

    public static final double SPOT_FEE = 0.001, FUTURES_FEE = 0.0005;

    public record Fill(long ts, String strategy, String market, String symbol, boolean buy, double qty, double px) {}

    public static final class Row {
        public final String month, strategy, market;
        public int trips, wins, fills; public double realized, fees, turnover;
        Row(String month, String strategy, String market) { this.month = month; this.strategy = strategy; this.market = market; }
        public double net() { return realized - fees; }
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", month); m.put("strategy", strategy); m.put("market", market);
            m.put("fills", fills); m.put("roundTrips", trips); m.put("wins", wins);
            m.put("winRate", trips > 0 ? Math.round(wins * 1000.0 / trips) / 10.0 : null);
            m.put("turnover", r2(turnover)); m.put("realizedPnl", r4(realized)); m.put("fees", r4(fees)); m.put("netPnl", r4(net()));
            return m;
        }
    }

    /** 依月份新到舊、再依淨損益排序。 */
    public static List<Row> compute(List<Fill> fills, ZoneId zone) {
        Map<String, Row> rows = new LinkedHashMap<>();
        Map<String, Deque<double[]>> lots = new HashMap<>();   // key -> lots {signedQty, px}
        List<Fill> sorted = new ArrayList<>(fills);
        sorted.sort(Comparator.comparingLong(Fill::ts));
        for (Fill f : sorted) {
            if (f.qty() <= 0 || f.px() <= 0) continue;
            boolean fut = "USDT_M".equalsIgnoreCase(f.market());
            String strat = f.strategy() == null || f.strategy().isBlank() ? "(手動)" : f.strategy();
            String market = fut ? "USDT_M" : "SPOT";
            String month = YearMonth.from(Instant.ofEpochMilli(f.ts()).atZone(zone)).toString();
            Row row = rows.computeIfAbsent(month + "|" + strat + "|" + market, k -> new Row(month, strat, market));
            double notional = f.qty() * f.px();
            row.fills++; row.turnover += notional; row.fees += notional * (fut ? FUTURES_FEE : SPOT_FEE);

            Deque<double[]> dq = lots.computeIfAbsent(strat + "|" + market + "|" + f.symbol(), k -> new ArrayDeque<>());
            double signed = f.buy() ? f.qty() : -f.qty();
            double remain = Math.abs(signed);
            // 反向於現有部位 → 沖銷
            while (remain > 1e-12 && !dq.isEmpty() && Math.signum(dq.peekFirst()[0]) != Math.signum(signed)) {
                double[] lot = dq.peekFirst();
                double m = Math.min(remain, Math.abs(lot[0]));
                double pnl = (f.px() - lot[1]) * m * Math.signum(lot[0]);   // 多：賣價−買價；空：買價−賣價（取負號）
                row.realized += pnl; row.trips++; if (pnl > 0) row.wins++;
                lot[0] -= Math.signum(lot[0]) * m; remain -= m;
                if (Math.abs(lot[0]) <= 1e-12) dq.removeFirst();
            }
            if (remain > 1e-12) {
                if (f.buy() || fut) dq.addLast(new double[]{Math.signum(signed) * remain, f.px()});   // 現貨不會因 SELL 變空單
            }
        }
        List<Row> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparing((Row r) -> r.month).reversed().thenComparing(r -> -r.net()));
        return out;
    }

    /** 每月合計。 */
    public static List<Map<String, Object>> totals(List<Row> rows) {
        Map<String, Row> t = new TreeMap<>(Comparator.reverseOrder());
        for (Row r : rows) {
            Row a = t.computeIfAbsent(r.month, m -> new Row(m, "ALL", "ALL"));
            a.trips += r.trips; a.wins += r.wins; a.fills += r.fills; a.realized += r.realized; a.fees += r.fees; a.turnover += r.turnover;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        t.values().forEach(r -> out.add(r.toMap()));
        return out;
    }

    /** CSV（UTF-8 BOM，Excel 直接開不亂碼）。 */
    public static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("month,strategy,market,fills,round_trips,wins,win_rate_pct,turnover_usdt,realized_pnl_usdt,fees_est_usdt,net_pnl_usdt\n");
        for (Row r : rows) {
            sb.append(r.month).append(',').append(esc(r.strategy)).append(',').append(r.market).append(',')
              .append(r.fills).append(',').append(r.trips).append(',').append(r.wins).append(',')
              .append(r.trips > 0 ? String.format(Locale.ROOT, "%.1f", r.wins * 100.0 / r.trips) : "").append(',')
              .append(String.format(Locale.ROOT, "%.2f,%.4f,%.4f,%.4f", r.turnover, r.realized, r.fees, r.net())).append('\n');
        }
        return sb.toString();
    }

    private static String esc(String s) { return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s; }
    static double r2(double v) { return Math.round(v * 100) / 100.0; }
    static double r4(double v) { return Math.round(v * 10000) / 10000.0; }
}
