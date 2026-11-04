package com.quant.strategy.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thompson Sampling 自動配資：把每個機器人當成一支拉桿，獎勵 = 每筆來回交易的報酬率（損益 ÷ 進場名目）。
 *
 * <p>每個機器人的「真實平均報酬」用常態後驗估計：先驗 = 均值 0、{@value #PRIOR_N} 筆虛擬交易（新機器人不會因為
 * 運氣好一筆就被灌滿資金）；標準差取樣本標準差，下限 1.5%。每次重新配資抽 {@value #DRAWS} 次，
 * 算出「每個機器人是最好的那個」的機率 P(best)，配資倍率 = 機器人數 × P(best)（平均 = 1，總曝險不變），
 * 再夾在 [下限, 上限]。下限確保「表現差的還留一點錢繼續探索」，也確保單筆名目不會低於幣安最低 5 USDT。
 */
@Service
public class BanditAllocator {

    static final int PRIOR_N = 5, DRAWS = 4000, WINDOW = 50;
    static final double MIN_STD = 0.015;  // 1.5%：幣圈每筆來回報酬的合理波動下限，避免少數幾筆就過度自信

    private final Map<String, Deque<Double>> returns = new ConcurrentHashMap<>();
    private final Random rnd;

    @org.springframework.beans.factory.annotation.Autowired
    public BanditAllocator() { this(new Random()); }
    BanditAllocator(Random r) { this.rnd = r; }

    /** 記一筆來回交易報酬率（例：+0.012 = +1.2%）。只保留最近 {@value #WINDOW} 筆，讓配資跟得上盤勢變化。 */
    public void record(String id, double ret) {
        if (Double.isNaN(ret) || Double.isInfinite(ret)) return;
        Deque<Double> d = returns.computeIfAbsent(id, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        d.addLast(ret);
        while (d.size() > WINDOW) d.removeFirst();
    }

    public int n(String id) { Deque<Double> d = returns.get(id); return d == null ? 0 : d.size(); }

    /** [後驗均值, 後驗均值的標準差] */
    double[] posterior(String id) {
        List<Double> l = new ArrayList<>(returns.getOrDefault(id, new ArrayDeque<>()));
        int n = l.size();
        double sum = 0; for (double v : l) sum += v;
        double mean = sum / (n + PRIOR_N);                       // 先驗均值 0、權重 PRIOR_N
        double var = 0; double m = n == 0 ? 0 : sum / n;
        for (double v : l) var += (v - m) * (v - m);
        double sd = n > 1 ? Math.sqrt(var / (n - 1)) : 0;
        sd = Math.max(sd, MIN_STD);
        return new double[]{mean, sd / Math.sqrt(n + PRIOR_N)};
    }

    /** 每個機器人是「最好」的機率。 */
    public Map<String, Double> pBest(Collection<String> ids) {
        List<String> l = new ArrayList<>(ids);
        Map<String, Double> out = new LinkedHashMap<>();
        if (l.isEmpty()) return out;
        double[][] post = new double[l.size()][];
        for (int i = 0; i < l.size(); i++) post[i] = posterior(l.get(i));
        int[] wins = new int[l.size()];
        for (int k = 0; k < DRAWS; k++) {
            int best = 0; double bv = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < l.size(); i++) {
                double v = post[i][0] + post[i][1] * rnd.nextGaussian();
                if (v > bv) { bv = v; best = i; }
            }
            wins[best]++;
        }
        for (int i = 0; i < l.size(); i++) out.put(l.get(i), wins[i] / (double) DRAWS);
        return out;
    }

    /** 配資倍率：機器人數 × P(best)，夾在 [floor, cap]。 */
    public Map<String, Double> allocations(Collection<String> ids, double floor, double cap) {
        Map<String, Double> p = pBest(ids);
        Map<String, Double> out = new LinkedHashMap<>();
        int n = p.size();
        p.forEach((id, pb) -> out.put(id, Math.round(Math.max(floor, Math.min(cap, n * pb)) * 100) / 100.0));
        return out;
    }

    public Map<String, Object> stats(String id) {
        double[] po = posterior(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trades", n(id));
        m.put("meanPct", Math.round(po[0] * 1e6) / 1e4);   // %
        m.put("sdPct", Math.round(po[1] * 1e6) / 1e4);
        return m;
    }
}
