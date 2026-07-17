package com.quant.backtest.engine;

import com.quant.common.dto.Kline;
import com.quant.common.strategy.Strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 事件驅動回測引擎（見架構文件 §7.1）：與實盤共用 Strategy 介面。 */
public class BacktestEngine {

    private final double initialCash, fee, slippage;

    public BacktestEngine(double initialCash, double fee, double slippage) {
        this.initialCash = initialCash; this.fee = fee; this.slippage = slippage;
    }

    public Result run(List<Kline> bars, Strategy strategy) {
        BacktestContext ctx = new BacktestContext(initialCash, fee, slippage);
        List<Double> equity = new ArrayList<>();
        for (Kline bar : bars) {
            ctx.updatePrice(bar.symbol(), bar.close());
            strategy.onBar(bar, ctx);
            equity.add(ctx.equity());
        }
        return new Result(equity, metrics(equity), ctx.trades(), ctx.avgFillPrice());
    }

    private Map<String, Object> metrics(List<Double> equity) {
        int n = equity.size();
        if (n < 2) return Map.of("annual_return", 0.0, "sharpe", 0.0, "max_drawdown", 0.0, "final_equity", initialCash);
        double peak = equity.get(0), mdd = 0;
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double prev = equity.get(i - 1), cur = equity.get(i);
            rets.add(prev != 0 ? cur / prev - 1 : 0);
            peak = Math.max(peak, cur); mdd = Math.min(mdd, cur / peak - 1);
        }
        double mean = rets.stream().mapToDouble(d -> d).average().orElse(0);
        double var = rets.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
        double sharpe = var > 0 ? Math.sqrt(365) * mean / Math.sqrt(var) : 0;
        double totalRet = equity.get(n - 1) / equity.get(0) - 1;
        return Map.of("annual_return", round(Math.pow(1 + totalRet, 365.0 / n) - 1),
                "sharpe", round(sharpe), "max_drawdown", round(mdd),
                "total_return", round(totalRet), "final_equity", round(equity.get(n - 1)));
    }
    private double round(double d) { return Math.round(d * 10000.0) / 10000.0; }

    public record Result(List<Double> equity, Map<String, Object> metrics, int trades, double avgFillPrice) {}
}
