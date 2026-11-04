package com.quant.common.risk;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 單一標的的已收盤 K 歷史（最近 {@value #HIST} 根）與衍生指標：ATR(14)、Wilder ADX(14)、波動倍數、排名分數。
 * 實盤（strategy-service 的 BotRiskManager）與回測（backtest-service）共用同一份計算，確保回測結果反映機器人真實行為。
 */
public final class BarStats {
    public static final int ATR_N = 14, MOM_N = 10, HIST = 60;
    private final Deque<double[]> d = new ConcurrentLinkedDeque<>();   // [high, low, close]
    private volatile long closed = 0;

    public void add(double high, double low, double close) {
        d.addLast(new double[]{high, low, close});
        while (d.size() > HIST) d.removeFirst();
        closed++;
    }
    public long closedCount() { return closed; }
    public int size() { return d.size(); }

    private static double tr(double[] c, double[] p) {
        return Math.max(c[0] - c[1], Math.max(Math.abs(c[0] - p[2]), Math.abs(c[1] - p[2])));
    }

    public double atr() {
        if (d.size() < ATR_N + 1) return 0;
        List<double[]> l = new ArrayList<>(d);
        double sum = 0;
        for (int i = l.size() - ATR_N; i < l.size(); i++) sum += tr(l.get(i), l.get(i - 1));
        return sum / ATR_N;
    }

    /** 近 MOM_N 根漲幅 ÷ ATR（跨幣可比）。資料不足 = 0。 */
    public double score() {
        double a = atr();
        if (d.size() <= MOM_N || a <= 0) return 0;
        List<double[]> l = new ArrayList<>(d);
        return (l.get(l.size() - 1)[2] - l.get(l.size() - 1 - MOM_N)[2]) / a;
    }

    /** Wilder ADX(14)。資料不足回 -1。 */
    public double adx() {
        int n = ATR_N;
        if (d.size() < 2 * n + 1) return -1;
        List<double[]> l = new ArrayList<>(d);
        double tr = 0, pdm = 0, mdm = 0, adx = 0; int dxCount = 0;
        for (int i = 1; i < l.size(); i++) {
            double[] c = l.get(i), p = l.get(i - 1);
            double t = tr(c, p);
            double up = c[0] - p[0], dn = p[1] - c[1];
            double pd = up > dn && up > 0 ? up : 0, md = dn > up && dn > 0 ? dn : 0;
            if (i <= n) { tr += t; pdm += pd; mdm += md; if (i < n) continue; }
            else { tr = tr - tr / n + t; pdm = pdm - pdm / n + pd; mdm = mdm - mdm / n + md; }
            double pdi = tr > 0 ? 100 * pdm / tr : 0, mdi = tr > 0 ? 100 * mdm / tr : 0;
            double dx = pdi + mdi > 0 ? 100 * Math.abs(pdi - mdi) / (pdi + mdi) : 0;
            if (dxCount < n) { adx += dx; dxCount++; if (dxCount == n) adx /= n; }
            else adx = (adx * (n - 1) + dx) / n;
        }
        return dxCount < n ? -1 : adx;
    }

    /** 近 14 根平均 TR% ÷ 整段 TR% 中位數。資料不足回 0。 */
    public double volRatio() {
        if (d.size() < 30) return 0;
        List<double[]> l = new ArrayList<>(d);
        List<Double> trp = new ArrayList<>();
        for (int i = 1; i < l.size(); i++) { double[] p = l.get(i - 1); trp.add(p[2] > 0 ? tr(l.get(i), p) / p[2] : 0); }
        List<Double> sorted = new ArrayList<>(trp); Collections.sort(sorted);
        double med = sorted.get(sorted.size() / 2);
        double recent = trp.subList(trp.size() - ATR_N, trp.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return med > 0 ? recent / med : 0;
    }

    public RiskRules.Regime regime() { return RiskRules.regime(adx()); }
}
