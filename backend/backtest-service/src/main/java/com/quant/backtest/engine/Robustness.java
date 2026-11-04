package com.quant.backtest.engine;

import java.util.*;

/**
 * 參數最佳化的「過擬合體檢」。網格搜尋挑出「歷史上最好」的那組，通常只是最會擬合雜訊的那組；這裡用三個方法量化：
 *
 * <ol>
 *   <li><b>PBO（Probability of Backtest Overfitting，CSCV）</b>— Bailey, Borwein, López de Prado, Zhu (2015)。
 *       把樣本切成 S 段，所有「一半當樣本內、一半當樣本外」的組合（S=10 → 252 種）都試一次：
 *       樣本內最好的那組，到樣本外排名掉到中位數以下的比例 = PBO。&gt; 50% 代表「挑最好的」比亂挑還差。</li>
 *   <li><b>DSR（Deflated Sharpe Ratio）</b>— Bailey &amp; López de Prado (2014)。試了 N 組，最好那組的 Sharpe 本來就會偏高；
 *       依試驗次數、Sharpe 在各組間的離散度、報酬的偏態與峰態，算出「真實 Sharpe &gt; 0」的機率。</li>
 *   <li><b>Walk-forward</b>：滾動「用前一段挑參數 → 下一段驗證」，看選出來的參數在沒看過的資料上還剩多少 Sharpe（效率 = OOS / IS）。</li>
 * </ol>
 * 另外給出 <b>推薦參數</b>：在 252 種切法的樣本外平均 Sharpe 最高的那組（穩健，而不是單一樣本最亮眼）。
 */
public final class Robustness {

    private Robustness() {}
    static final double EULER = 0.5772156649;

    /** 每根 K 的報酬（由權益曲線） */
    public static double[] returns(List<Double> equity) {
        int n = equity.size();
        double[] r = new double[Math.max(0, n - 1)];
        for (int i = 1; i < n; i++) { double p = equity.get(i - 1); r[i - 1] = p != 0 ? equity.get(i) / p - 1 : 0; }
        return r;
    }

    /** 每根的 Sharpe（未年化）。 */
    static double sr(double sum, double sumsq, int cnt) {
        if (cnt < 2) return 0;
        double m = sum / cnt, v = sumsq / cnt - m * m;
        return v > 1e-18 ? m / Math.sqrt(v) : 0;
    }
    public static double sr(double[] r, int from, int to) {
        double s = 0, q = 0; for (int i = from; i < to; i++) { s += r[i]; q += r[i] * r[i]; }
        return sr(s, q, to - from);
    }

    public static Map<String, Object> analyze(List<double[]> rets, double barsPerYear) {
        int N = rets.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trials", N);
        int T = rets.stream().mapToInt(a -> a.length).min().orElse(0);
        double ann = Math.sqrt(Math.max(1, barsPerYear));
        if (N == 0 || T < 40) { out.put("verdict", "INSUFFICIENT"); out.put("reasons", List.of("樣本太少（< 40 根）")); return out; }

        // ── 全樣本 Sharpe、最好那組 ──
        double[] full = new double[N];
        int best = 0;
        for (int n = 0; n < N; n++) { full[n] = sr(rets.get(n), 0, T); if (full[n] > full[best]) best = n; }

        // ── CSCV / PBO ──
        int S = T >= 400 ? 10 : T >= 120 ? 8 : 6;
        int blk = T / S;
        double[][] bs = new double[N][S], bq = new double[N][S];
        for (int n = 0; n < N; n++) {
            double[] r = rets.get(n);
            for (int b = 0; b < S; b++) for (int i = b * blk; i < (b + 1) * blk; i++) { bs[n][b] += r[i]; bq[n][b] += r[i] * r[i]; }
        }
        int splits = 0, below = 0;
        double[] oosSum = new double[N];
        double degIs = 0, degOos = 0;
        for (int mask = 0; mask < (1 << S); mask++) {
            if (Integer.bitCount(mask) != S / 2) continue;
            splits++;
            double[] is = new double[N], oos = new double[N];
            for (int n = 0; n < N; n++) {
                double s1 = 0, q1 = 0, s2 = 0, q2 = 0;
                for (int b = 0; b < S; b++) {
                    if ((mask >> b & 1) == 1) { s1 += bs[n][b]; q1 += bq[n][b]; } else { s2 += bs[n][b]; q2 += bq[n][b]; }
                }
                is[n] = sr(s1, q1, blk * S / 2); oos[n] = sr(s2, q2, blk * S / 2);
                oosSum[n] += oos[n];
            }
            int star = 0; for (int n = 1; n < N; n++) if (is[n] > is[star]) star = n;
            int rank = 1; for (int n = 0; n < N; n++) if (oos[n] < oos[star]) rank++;   // 1 = 最差
            double w = rank / (double) (N + 1);
            if (Math.log(w / (1 - w)) <= 0) below++;
            degIs += is[star]; degOos += oos[star];
        }
        double pbo = N > 1 ? below / (double) splits : Double.NaN;
        int rec = 0; for (int n = 1; n < N; n++) if (oosSum[n] > oosSum[rec]) rec = n;

        // ── DSR ──
        double[] rb = Arrays.copyOf(rets.get(best), T);
        double m = Arrays.stream(rb).average().orElse(0);
        double m2 = 0, m3 = 0, m4 = 0;
        for (double x : rb) { double d = x - m; m2 += d * d; m3 += d * d * d; m4 += d * d * d * d; }
        m2 /= T; m3 /= T; m4 /= T;
        double skew = m2 > 0 ? m3 / Math.pow(m2, 1.5) : 0, kurt = m2 > 0 ? m4 / (m2 * m2) : 3;
        double srBest = full[best];
        double meanSr = Arrays.stream(full).average().orElse(0);
        double varSr = 0; for (double x : full) varSr += (x - meanSr) * (x - meanSr);
        varSr = N > 1 ? varSr / (N - 1) : 0;
        double sr0 = N > 1 ? Math.sqrt(varSr) * ((1 - EULER) * invPhi(1 - 1.0 / N) + EULER * invPhi(1 - 1.0 / (N * Math.E))) : 0;
        double denom = 1 - skew * srBest + (kurt - 1) / 4 * srBest * srBest;
        double dsr = denom > 0 ? phi((srBest - sr0) * Math.sqrt(T - 1) / Math.sqrt(denom)) : 0;

        // ── Walk-forward（滾動：第 k 段挑、第 k+1 段驗）──
        int K = 4, seg = T / (K + 1);
        List<Map<String, Object>> folds = new ArrayList<>();
        double sumIs = 0, sumOos = 0; Set<Integer> chosen = new LinkedHashSet<>();
        for (int k = 0; k < K; k++) {
            int a = k * seg, b = (k + 1) * seg, c = (k + 2) * seg;
            int pick = 0; double pickIs = Double.NEGATIVE_INFINITY;
            for (int n = 0; n < N; n++) { double s = sr(rets.get(n), a, b); if (s > pickIs) { pickIs = s; pick = n; } }
            double o = sr(rets.get(pick), b, c);
            chosen.add(pick); sumIs += pickIs; sumOos += o;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("fold", k + 1); f.put("trainBars", new int[]{a, b}); f.put("testBars", new int[]{b, c});
            f.put("index", pick); f.put("isSharpe", r2(pickIs * ann)); f.put("oosSharpe", r2(o * ann));
            folds.add(f);
        }
        double eff = sumIs > 0 ? sumOos / sumIs : 0;

        // ── 判定 ──
        List<String> reasons = new ArrayList<>();
        String verdict = "ROBUST";
        if (N > 1 && pbo > 0.5) { verdict = "OVERFIT"; reasons.add(String.format("PBO %.0f%%：樣本內最好的組合，在樣本外多半掉到中位數以下", pbo * 100)); }
        if (dsr < 0.5) { verdict = "OVERFIT"; reasons.add(String.format("DSR %.0f%%：扣掉試了 %d 組的運氣後，真實 Sharpe > 0 的機率不到一半", dsr * 100, N)); }
        if (!"OVERFIT".equals(verdict)) {
            if (N > 1 && pbo > 0.3) { verdict = "SUSPECT"; reasons.add(String.format("PBO %.0f%% 偏高", pbo * 100)); }
            if (dsr < 0.9) { verdict = "SUSPECT"; reasons.add(String.format("DSR %.0f%% < 90%%", dsr * 100)); }
            if (eff < 0.5) { verdict = "SUSPECT"; reasons.add(String.format("Walk-forward 效率 %.0f%%：換到沒看過的資料只剩不到一半 Sharpe", eff * 100)); }
        }
        if (sumOos <= 0) { if ("ROBUST".equals(verdict)) verdict = "SUSPECT"; reasons.add("Walk-forward 樣本外 Sharpe 合計 ≤ 0"); }
        if (reasons.isEmpty()) reasons.add("三項檢查都通過");

        out.put("verdict", verdict); out.put("reasons", reasons);
        out.put("pbo", N > 1 ? r4(pbo) : null); out.put("splits", splits); out.put("blocks", S);
        out.put("dsr", r4(dsr)); out.put("bestSharpe", r2(srBest * ann)); out.put("sharpeHurdle", r2(sr0 * ann));
        out.put("skew", r2(skew)); out.put("kurtosis", r2(kurt));
        out.put("degradation", Map.of("isSharpe", r2(degIs / Math.max(1, splits) * ann), "oosSharpe", r2(degOos / Math.max(1, splits) * ann)));
        out.put("walkForward", Map.of("folds", folds, "efficiency", r2(eff), "oosSharpe", r2(sumOos / K * ann), "distinctPicks", chosen.size()));
        out.put("bestIndex", best); out.put("recommendedIndex", rec);
        out.put("recommendedOosSharpe", r2(oosSum[rec] / Math.max(1, splits) * ann));
        return out;
    }

    // ── 常態分配 ──
    static double phi(double x) { return 0.5 * erfc(-x / Math.sqrt(2)); }
    static double erfc(double x) {   // Numerical Recipes erfcc，誤差 < 1.2e-7
        double z = Math.abs(x), t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }
    /** Φ⁻¹（Acklam），相對誤差 < 1.2e-9 */
    static double invPhi(double p) {
        if (p <= 0) return Double.NEGATIVE_INFINITY; if (p >= 1) return Double.POSITIVE_INFINITY;
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double q, r;
        if (p < 0.02425) { q = Math.sqrt(-2 * Math.log(p)); return (((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1); }
        if (p > 1 - 0.02425) { q = Math.sqrt(-2 * Math.log(1 - p)); return -(((((c[0]*q+c[1])*q+c[2])*q+c[3])*q+c[4])*q+c[5]) / ((((d[0]*q+d[1])*q+d[2])*q+d[3])*q+1); }
        q = p - 0.5; r = q * q;
        return (((((a[0]*r+a[1])*r+a[2])*r+a[3])*r+a[4])*r+a[5])*q / (((((b[0]*r+b[1])*r+b[2])*r+b[3])*r+b[4])*r+1);
    }
    private static double r2(double v) { return Math.round(v * 100) / 100.0; }
    private static double r4(double v) { return Math.round(v * 10000) / 10000.0; }
}
