package com.quant.backtest.engine;

import com.quant.common.enums.OrderSide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回測 vs 實盤一致性（純函數，可測）。
 * 把實盤每一筆成交對到「同方向、時間差在 ±tolerance 根內」的回測成交（一對一，不重複用）。
 * score = 配對成功數 / max(實盤筆數, 回測筆數)；另回報均價偏差。
 * 以前的分數只是 |回測筆數 − 實盤筆數| 的比值，而且回測跑在隨機序列上，數字沒有意義。
 */
public final class ConsistencyScorer {
    private ConsistencyScorer() {}

    public record LiveFill(long time, OrderSide side, double qty, double price) {}

    public static Map<String, Object> score(List<LiveFill> live, List<BacktestContext.Trade> backtest, long intervalMs, int toleranceBars) {
        long tol = toleranceBars * intervalMs;
        boolean[] used = new boolean[backtest.size()];
        int matched = 0;
        double priceDiffSum = 0;
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (LiveFill lf : live) {
            int best = -1; long bestGap = Long.MAX_VALUE;
            for (int i = 0; i < backtest.size(); i++) {
                if (used[i]) continue;
                BacktestContext.Trade bt = backtest.get(i);
                if (bt.side() != lf.side()) continue;
                long gap = Math.abs(bt.time() - lf.time());
                if (gap <= tol && gap < bestGap) { best = i; bestGap = gap; }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("liveTime", lf.time()); row.put("side", lf.side().name()); row.put("livePrice", lf.price());
            if (best >= 0) {
                used[best] = true; matched++;
                BacktestContext.Trade bt = backtest.get(best);
                double diff = lf.price() > 0 ? (lf.price() - bt.price()) / bt.price() : 0;
                priceDiffSum += Math.abs(diff);
                row.put("backtestTime", bt.time()); row.put("backtestPrice", bt.price());
                row.put("barsApart", Math.round((double) (lf.time() - bt.time()) / intervalMs));
                row.put("priceDiffPct", round(diff * 100));
            } else {
                row.put("backtestTime", null); row.put("note", "回測在 ±" + toleranceBars + " 根內沒有同方向成交");
            }
            pairs.add(row);
        }
        int denom = Math.max(live.size(), backtest.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("liveFills", live.size());
        out.put("backtestTrades", backtest.size());
        out.put("matched", matched);
        out.put("unmatchedLive", live.size() - matched);
        out.put("unmatchedBacktest", backtest.size() - matched);
        out.put("consistencyScore", denom == 0 ? null : round((double) matched / denom));
        out.put("avgAbsPriceDiffPct", matched == 0 ? null : round(priceDiffSum / matched * 100));
        out.put("toleranceBars", toleranceBars);
        out.put("pairs", pairs);
        return out;
    }

    private static double round(double d) { return Math.round(d * 10000.0) / 10000.0; }
}
