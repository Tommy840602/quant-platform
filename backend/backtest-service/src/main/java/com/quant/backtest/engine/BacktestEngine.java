package com.quant.backtest.engine;

import com.quant.common.dto.Kline;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderType;
import com.quant.common.risk.BarStats;
import com.quant.common.risk.RiskRules;
import com.quant.common.strategy.Strategy;
import com.quant.common.strategy.StrategyContext;
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

    public Result run(List<Kline> bars, Strategy strategy) { return run(bars, strategy, null); }

    /**
     * 機器人風控設定（與實盤 strategy-service 共用 {@link RiskRules} / {@link BarStats}）。
     * @param type         策略類型（決定趨勢型 / 均值回歸型，盤勢過濾用）
     * @param params       風控參數（stopAtr、trailAtr、takeProfitPct、cooldownBars、regimeFilter、blockHighVol、
     *                     riskPerTradePct、sizeCapPct、maxConsecLosses、maxLossUsdt），沒給的用預設值
     * @param notionalPct  每單名目佔權益 %（實盤每單名目 ÷ 實盤權益）；0 = 舊行為（95% 現金 all-in）
     * @param liveEquity   實盤權益（USDT），用來把 maxLossUsdt 換算到回測本金；0 = 不換算
     */
    public record RiskSpec(String type, Map<String, Object> params, double notionalPct, double liveEquity) {}

    /**
     * 回測與實盤一致：出場（ATR 停損 / 移動停利 / 停利，K 線內觸價以觸發價成交、跳空以開盤價）、
     * 盤勢過濾、出場後冷卻、機器人熔斷（連虧 / 虧損上限後停止進場）、每單名目比例或波動率目標下單量。
     * spec = null → 只跑策略訊號（舊行為）。
     */
    public Result run(List<Kline> bars, Strategy strategy, RiskSpec spec) {
        BacktestContext ctx = new BacktestContext(initialCash, fee, slippage);
        List<Double> equity = new ArrayList<>();
        Guard g = spec == null ? null : new Guard(ctx, spec, initialCash);
        for (Kline bar : bars) {
            ctx.setBarTime(bar.openTime());
            if (g != null) g.beforeBar(bar);          // 用「這根之前」的 ATR 檢查 K 線內是否觸價出場
            ctx.updatePrice(bar.symbol(), bar.close());
            if (g != null) { g.stats.add(bar.high(), bar.low(), bar.close()); g.breaker(bar); }
            strategy.onBar(bar, g != null ? g : ctx);
            equity.add(ctx.equity());
        }
        long intervalMs = bars.isEmpty() ? 60_000 : safeInterval(bars.get(0).interval());
        return new Result(equity, metrics(equity, intervalMs), ctx.trades(), ctx.avgFillPrice(), ctx.tradeLog(),
                bars.isEmpty() ? 0 : bars.get(0).openTime(), bars.isEmpty() ? 0 : bars.get(bars.size() - 1).openTime(),
                g == null ? Map.of("applied", false) : g.report());
    }

    /** 包住回測撮合的風控層：策略看到的 context 是它，跟實盤 BotRiskManager + StrategyContextImpl 走同一套規則。 */
    static final class Guard implements StrategyContext {
        final BacktestContext ctx; final RiskSpec spec; final Map<String, Object> p;
        final BarStats stats = new BarStats();
        final double lossScale;
        double peak = 0; long cooldownUntil = -1;
        boolean paused = false; String pausedReason = null; long pausedAt = 0;
        int consec = 0, trips = 0, wins = 0; double realized = 0;
        final Map<String, Integer> exits = new LinkedHashMap<>(), blocked = new LinkedHashMap<>();

        Guard(BacktestContext ctx, RiskSpec spec, double initialCash) {
            this.ctx = ctx; this.spec = spec; this.p = spec.params() == null ? Map.of() : spec.params();
            this.lossScale = spec.liveEquity() > 0 ? initialCash / spec.liveEquity() : 1;
        }

        void beforeBar(Kline bar) {
            String sym = bar.symbol();
            double q = ctx.position(sym);
            if (q <= 1e-12) return;
            double entry = ctx.entry(sym), atr = stats.atr();
            String why = null; double px = 0;
            double stop = RiskRules.stopPrice(entry, atr, p);
            double trail = RiskRules.trailPrice(entry, peak, atr, p);
            double tp = RiskRules.takeProfitPrice(entry, p);
            if (stop > 0 && bar.low() <= stop) { why = "停損"; px = Math.min(bar.open(), stop); }
            else if (trail > 0 && bar.low() <= trail) { why = "移動停利"; px = Math.min(bar.open(), trail); }
            else if (tp > 0 && bar.high() >= tp) { why = "停利"; px = Math.max(bar.open(), tp); }
            if (why != null) {
                BacktestContext.Trade t = ctx.submitAt(sell(sym), px, 0);
                if (t != null) { trip(entry, t); exits.merge(why, 1, Integer::sum); cooldownUntil = stats.closedCount() + (long) RiskRules.param(p, "cooldownBars"); }
                peak = 0;
            } else peak = Math.max(peak, bar.high());
        }

        void breaker(Kline bar) {
            if (paused) return;
            int maxN = (int) RiskRules.param(p, "maxConsecLosses");
            double maxLoss = RiskRules.param(p, "maxLossUsdt") * lossScale;
            double q = ctx.position(bar.symbol());
            double unreal = q > 0 ? (bar.close() - ctx.entry(bar.symbol())) * q : 0;
            if (maxN > 0 && consec >= maxN) pause("連虧 " + consec + " 筆", bar);
            else if (maxLoss > 0 && realized + unreal <= -maxLoss) pause(String.format("虧損 %.2f ≥ 上限 %.2f", -(realized + unreal), maxLoss), bar);
        }
        private void pause(String why, Kline bar) { paused = true; pausedReason = why; pausedAt = bar.openTime(); }

        private void trip(double entry, BacktestContext.Trade exit) {
            double f = ctx.fee();
            double pnl = (exit.price() - entry) * exit.qty() - (exit.price() + entry) * exit.qty() * f;
            realized += pnl; trips++;
            if (pnl < 0) consec++; else { consec = 0; wins++; }
        }

        @Override
        public void submit(OrderIntent intent) {
            String sym = intent.symbol();
            double px = ctx.price(sym);
            if (intent.side() == OrderSide.SELL) {
                double entry = ctx.entry(sym);
                BacktestContext.Trade t = ctx.submitAt(intent, px, 0);
                if (t != null) { trip(entry, t); exits.merge("策略訊號", 1, Integer::sum); peak = 0; }
                return;
            }
            if (ctx.position(sym) > 0) return;
            if (paused) { blocked.merge("熔斷暫停", 1, Integer::sum); return; }
            if (stats.closedCount() < cooldownUntil) { blocked.merge("出場冷卻", 1, Integer::sum); return; }
            String rb = RiskRules.regimeBlock(spec.type(), stats, p);
            if (rb != null) { blocked.merge(rb.startsWith("異常") ? "異常高波動" : "盤勢不符", 1, Integer::sum); return; }
            double eq = ctx.equity();
            double qty = RiskRules.sizeQty(eq, px, stats.atr(), p);
            if (qty <= 0 && spec.notionalPct() > 0 && px > 0) qty = eq * spec.notionalPct() / 100 / px;
            if (ctx.submitAt(intent, px, qty) != null) peak = px;
        }

        @Override public double position(String symbol) { return ctx.position(symbol); }
        @Override public double price(String symbol) { return ctx.price(symbol); }

        private static OrderIntent sell(String sym) {
            return new OrderIntent("bt-exit", "bt", sym, OrderSide.SELL, OrderType.MARKET, null, 0, false, "SPOT", "main");
        }

        Map<String, Object> report() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applied", true);
            m.put("exits", exits); m.put("blocked", blocked);
            m.put("roundTrips", trips); m.put("winRate", trips > 0 ? Math.round(wins * 1000.0 / trips) / 10.0 : null);
            m.put("pausedReason", pausedReason); m.put("pausedAt", paused ? pausedAt : null);
            Map<String, Object> eff = new LinkedHashMap<>();
            for (String k : RiskRules.DEFAULTS.keySet()) eff.put(k, RiskRules.param(p, k));
            m.put("params", eff);
            m.put("notionalPct", spec.notionalPct());
            return m;
        }
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
                         List<BacktestContext.Trade> tradeLog, long fromMs, long toMs, Map<String, Object> risk) {}
}
