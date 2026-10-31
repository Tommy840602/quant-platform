package com.quant.backtest.engine;

import com.quant.common.dto.Kline;
import com.quant.common.strategy.Strategy;
import com.quant.common.util.Intervals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 事件驅動回測引擎（見架構文件 §7.1）：與實盤共用 Strategy 介面。 */
public class BacktestEngine {

    private static final double MS_PER_YEAR = 365.0 * 86_400_000;

    private final double initialCash, fee, slippage;

    public BacktestEngine(double initialCash, double fee, double slippage) {
        this.initialCash = initialCash; this.fee = fee; this.slippage = slippage;
    }

    public Result run(List<Kline> bars, Strategy strategy) {
        BacktestContext ctx = new BacktestContext(initialCash, fee, slippage);
        List<Double> equity = new ArrayList<>();
        for (Kline bar : bars) {
            ctx.setBarTime(bar.openTime());
            ctx.updatePrice(bar.symbol(), bar.close());
            strategy.onBar(bar, ctx);
            equity.add(ctx.equity());
        }
        long intervalMs = bars.isEmpty() ? 60_000 : safeInterval(bars.get(0).interval());
        return new Result(equity, metrics(equity, intervalMs), ctx.trades(), ctx.avgFillPrice(), ctx.tradeLog(),
                bars.isEmpty() ? 0 : bars.get(0).openTime(), bars.isEmpty() ? 0 : bars.get(bars.size() - 1).openTime());
    }

    private static long safeInterval(String interval) {
        try { return Intervals.millis(interval); } catch (Exception e) { return 60_000; }
    }

    /**
     * 年化依「K 線週期」換算，不再假設一根 = 一天（1m 回測用 √365 年化 Sharpe 會低 24 倍，年化報酬完全失真）。
     */
    Map<String, Object> metrics(List<Double> equity, long intervalMs) {
        int n = equity.size();
        Map<String, Object> m = new LinkedHashMap<>();
        double barsPerYear = MS_PER_YEAR / intervalMs;
        m.put("bars", n); m.put("bars_per_year", Math.round(barsPerYear));
        if (n < 2) {
            m.put("annual_return", 0.0); m.put("sharpe", 0.0); m.put("max_drawdown", 0.0);
            m.put("total_return", 0.0); m.put("final_equity", initialCash);
            return m;
        }
        double peak = equity.get(0), mdd = 0;
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double prev = equity.get(i - 1), cur = equity.get(i);
            rets.add(prev != 0 ? cur / prev - 1 : 0);
            peak = Math.max(peak, cur); mdd = Math.min(mdd, cur / peak - 1);
        }
        double mean = rets.stream().mapToDouble(d -> d).average().orElse(0);
        double var = rets.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
        double sharpe = var > 0 ? Math.sqrt(barsPerYear) * mean / Math.sqrt(var) : 0;
        double totalRet = equity.get(n - 1) / equity.get(0) - 1;
        double years = (n - 1) / barsPerYear;
        // 樣本不足一天時年化報酬會爆炸（300 根 1m 的 1% → 年化天文數字），改回報 null 並附 total_return
        Double annual = years >= 1.0 / 365 ? Math.pow(1 + totalRet, 1 / years) - 1 : null;
        m.put("annual_return", annual == null ? null : round(annual));
        m.put("annual_return_note", annual == null ? "樣本不足 1 天，不年化（看 total_return）" : null);
        m.put("sharpe", round(sharpe)); m.put("max_drawdown", round(mdd));
        m.put("total_return", round(totalRet)); m.put("final_equity", round(equity.get(n - 1)));
        m.put("span_days", round(years * 365));
        return m;
    }
    private static double round(double d) { return Math.round(d * 10000.0) / 10000.0; }

    public record Result(List<Double> equity, Map<String, Object> metrics, int trades, double avgFillPrice,
                         List<BacktestContext.Trade> tradeLog, long fromMs, long toMs) {}
}
