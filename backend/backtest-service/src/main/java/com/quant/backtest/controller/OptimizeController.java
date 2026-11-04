package com.quant.backtest.controller;

import com.quant.backtest.engine.BacktestEngine;
import com.quant.backtest.engine.Robustness;
import com.quant.backtest.engine.StrategyFactory;
import com.quant.backtest.service.BarSource;
import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import com.quant.common.util.Intervals;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 參數網格搜尋最佳化 + 多策略組合權重回測（見架構文件 §7、§8）。
 */
@RestController
@RequestMapping("/")
public class OptimizeController {

    private final BarSource source;
    public OptimizeController(BarSource source) { this.source = source; }

    /**
     * 網格搜尋：POST /api/backtest/optimize
     * body: {type, symbol, closes?, grid:{fast:[3,5,10], slow:[8,20,30], period:[...] ...}}
     * 回傳依 Sharpe 排序的所有組合與最佳參數。
     */
    @PostMapping("/optimize")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> optimize(@RequestBody OptReq req) {
        BarSource.Bars data;
        try { data = toBars(req.symbol, req.interval, req.bars, req.closes); }
        catch (BarSource.NoDataException e) { return ApiResponse.error(400, e.getMessage()); }
        List<Kline> bars = data.bars();
        List<Map<String, Object>> results = new ArrayList<>();

        List<Map<String, Object>> combos = expandGrid(req.grid);
        List<double[]> rets = new ArrayList<>();
        double barsPerYear = 1;
        for (Map<String, Object> params : combos) {
            var engine = new BacktestEngine(10000, 0.0004, 0.0002);
            var r = engine.run(bars, StrategyFactory.build(req.type, "opt", withQty(params)));
            double[] rr = Robustness.returns(r.equity());
            rets.add(rr);
            Object bpy = r.metrics().get("bars_per_year");
            if (bpy instanceof Number n) barsPerYear = n.doubleValue();
            Map<String, Object> row = new LinkedHashMap<>(params);
            row.put("sharpe", r.metrics().get("sharpe"));
            row.put("total_return", r.metrics().get("total_return"));
            row.put("max_drawdown", r.metrics().get("max_drawdown"));
            row.put("trades", r.trades());
            // 前 70% 當樣本內、後 30% 當樣本外的 Sharpe（年化），一眼看出「只在前段好看」的組合
            int cut = (int) (rr.length * 0.7);
            double ann = Math.sqrt(Math.max(1, barsPerYear));
            row.put("is_sharpe", Math.round(Robustness.sr(rr, 0, cut) * ann * 100) / 100.0);
            row.put("oos_sharpe", Math.round(Robustness.sr(rr, cut, rr.length) * ann * 100) / 100.0);
            results.add(row);
        }
        Map<String, Object> robust = Robustness.analyze(rets, barsPerYear);
        Object ri = robust.get("recommendedIndex");
        if (ri instanceof Integer i && i < results.size()) robust.put("recommended", results.get(i));
        Object wf = robust.get("walkForward");
        if (wf instanceof Map<?, ?> w && w.get("folds") instanceof List<?> fl)
            for (Object o : fl) if (o instanceof Map<?, ?> f && f.get("index") instanceof Integer i && i < combos.size())
                ((Map<String, Object>) f).put("params", combos.get(i));
        results.sort((a, b) -> Double.compare(
                ((Number) b.getOrDefault("sharpe", 0)).doubleValue(),
                ((Number) a.getOrDefault("sharpe", 0)).doubleValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", req.type); out.put("symbol", req.symbol == null ? "BTCUSDT" : req.symbol.toUpperCase());
        out.put("dataSource", data.dataSource()); out.put("bars", bars.size());
        out.put("combinations", results.size());
        out.put("best", results.isEmpty() ? Map.of() : results.get(0));
        out.put("robustness", robust);
        out.put("results", results.subList(0, Math.min(results.size(), 50)));
        return ApiResponse.ok(out);
    }

    /**
     * 多策略組合權重回測：POST /api/backtest/portfolio
     * body: {symbol, closes?, members:[{type, weight, params}]}
     * 各成員以權重加權合併權益曲線。
     */
    @PostMapping("/portfolio")
    public ApiResponse<Map<String, Object>> portfolio(@RequestBody PortReq req) {
        BarSource.Bars data;
        try { data = toBars(req.symbol, req.interval, req.bars, req.closes); }
        catch (BarSource.NoDataException e) { return ApiResponse.error(400, e.getMessage()); }
        List<Kline> bars = data.bars();
        int n = bars.size();
        double[] combined = new double[n];
        double totalW = req.members.stream().mapToDouble(m -> m.weight).sum();
        if (totalW == 0) totalW = 1;

        List<Map<String, Object>> members = new ArrayList<>();
        for (PortMember m : req.members) {
            var engine = new BacktestEngine(10000, 0.0004, 0.0002);
            var r = engine.run(bars, StrategyFactory.build(m.type, "pf", withQty(m.params)));
            double w = m.weight / totalW;
            for (int i = 0; i < n && i < r.equity().size(); i++) combined[i] += r.equity().get(i) * w;
            members.add(Map.of("type", m.type, "weight", round(w),
                    "sharpe", r.metrics().get("sharpe"), "trades", r.trades()));
        }
        // 組合績效
        double peak = combined.length > 0 ? combined[0] : 10000, mdd = 0;
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            rets.add(combined[i - 1] != 0 ? combined[i] / combined[i - 1] - 1 : 0);
            peak = Math.max(peak, combined[i]); mdd = Math.min(mdd, combined[i] / peak - 1);
        }
        double mean = rets.stream().mapToDouble(d -> d).average().orElse(0);
        double var = rets.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
        double sharpe = var > 0 ? Math.sqrt(365) * mean / Math.sqrt(var) : 0;
        List<Double> eq = new ArrayList<>();
        for (double v : combined) eq.add(round(v));
        return ApiResponse.ok(Map.of(
                "members", members,
                "metrics", Map.of("sharpe", round(sharpe), "max_drawdown", round(mdd),
                        "final_equity", n > 0 ? round(combined[n - 1]) : 10000.0),
                "equity", eq));
    }

    // ---- helpers ----
    private List<Map<String, Object>> expandGrid(Map<String, List<Object>> grid) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(new LinkedHashMap<>());
        if (grid == null) return out;
        for (var e : grid.entrySet()) {
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> base : out)
                for (Object v : e.getValue()) {
                    Map<String, Object> m = new LinkedHashMap<>(base); m.put(e.getKey(), v); next.add(m);
                }
            out = next;
        }
        return out;
    }
    private Map<String, Object> withQty(Map<String, Object> p) {
        Map<String, Object> m = p == null ? new HashMap<>() : new HashMap<>(p);
        m.putIfAbsent("qty", 0.01); return m;
    }
    private BarSource.Bars toBars(String symbol, String interval, int n, List<Double> closes) {
        String sym = (symbol == null ? "BTCUSDT" : symbol).toUpperCase();
        String itv = interval == null || interval.isBlank() ? "1m" : interval;
        if (closes != null && !closes.isEmpty()) return BarSource.fromCloses(sym, itv, closes, Intervals.millis(itv));
        return source.latest(sym, itv, n <= 0 ? 300 : Math.min(n, 1000));
    }
    private double round(double d) { return Math.round(d * 10000.0) / 10000.0; }

    public static class OptReq { public String type; public String symbol; public String interval; public int bars; public List<Double> closes; public Map<String, List<Object>> grid; }
    public static class PortReq { public String symbol; public String interval; public int bars; public List<Double> closes; public List<PortMember> members = new ArrayList<>(); }
    public static class PortMember { public String type; public double weight = 1; public Map<String, Object> params; }
}
