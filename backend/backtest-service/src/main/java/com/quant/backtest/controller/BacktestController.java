package com.quant.backtest.controller;

import com.quant.backtest.engine.BacktestEngine;
import com.quant.backtest.engine.StrategyFactory;
import com.quant.backtest.service.BarSource;
import com.quant.common.dto.ApiResponse;
import com.quant.common.util.Intervals;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回測 API（見架構文件 §7）：以事件驅動引擎在「真實 K 線」上跑策略，回傳績效與權益曲線。
 * 資料來源優先序：closes（呼叫端明確給） > market-data 區間（from/to） > market-data 最新 bars 根。
 * 拿不到資料回 400，不再偷偷用隨機序列。
 */
@RestController
@RequestMapping("/")
public class BacktestController {

    private final BarSource bars;
    private final com.quant.backtest.service.CostModel costs;
    public BacktestController(BarSource bars, com.quant.backtest.service.CostModel costs) { this.bars = bars; this.costs = costs; }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody Req req) {
        String symbol = (req.symbol == null ? "BTCUSDT" : req.symbol).toUpperCase();
        String interval = req.interval == null || req.interval.isBlank() ? "1m" : req.interval;
        BarSource.Bars data;
        try {
            data = load(req, symbol, interval);
        } catch (BarSource.NoDataException e) {
            return ApiResponse.error(400, e.getMessage());
        }
        boolean futures = "USDT_M".equalsIgnoreCase(req.market);
        // 合約 taker 0.05%（現貨 0.1%）；有指定 fee 就照用
        var cost = costs.resolve(symbol, req.fee > 0 ? req.fee : futures ? FUTURES_TAKER_FEE : 0, req.slippage, req.costModel);
        var engine = new BacktestEngine(req.initialCash == 0 ? 10000 : req.initialCash, cost.fee(), cost.slippage());
        var params = new HashMap<String, Object>(req.params == null ? Map.of() : req.params);
        params.put("fast", req.fast == 0 ? params.getOrDefault("fast", 3) : req.fast);
        params.put("slow", req.slow == 0 ? params.getOrDefault("slow", 8) : req.slow);
        params.putIfAbsent("qty", 0.01);
        var strategy = StrategyFactory.build(req.type, "backtest", params);
        String type = req.type == null ? "ma" : req.type;
        var spec = Boolean.FALSE.equals(req.applyRisk) ? null
                : new BacktestEngine.RiskSpec(type, req.risk, req.notionalPct, req.liveEquity);
        boolean allowShort = futures && com.quant.common.risk.RiskRules.param(req.risk == null ? Map.of() : req.risk, "allowShort") > 0;
        var twin = allowShort ? StrategyFactory.build(req.type, "backtest-short", params) : null;   // 空單分身：同參數、吃倒數價格
        var result = engine.run(data.bars(), strategy, twin, spec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol); out.put("interval", interval);
        out.put("market", futures ? "USDT_M" : "SPOT"); out.put("shorts", allowShort);
        out.put("dataSource", data.dataSource());
        out.put("bars", data.bars().size());
        out.put("from", result.fromMs()); out.put("to", result.toMs());
        out.put("metrics", result.metrics());
        out.put("equity", result.equity());
        out.put("trades", result.trades());
        out.put("tradeLog", result.tradeLog());
        out.put("risk", result.risk());
        out.put("costs", cost.toMap());
        if (data.dataSource().startsWith("closes"))
            out.put("warning", "closes 只有收盤價（O=H=L=C），依賴 high/low 的策略結果不準；改傳 symbol+interval+bars 用真實 K 線");
        return ApiResponse.ok(out);
    }

    private BarSource.Bars load(Req req, String symbol, String interval) {
        if (req.closes != null && !req.closes.isEmpty())
            return BarSource.fromCloses(symbol, interval, req.closes, Intervals.millis(interval));
        if (req.from > 0 && req.to > req.from) return bars.range(symbol, interval, req.from, req.to);
        return bars.latest(symbol, interval, req.bars <= 0 ? 300 : Math.min(req.bars, 1000));
    }

    static final double FUTURES_TAKER_FEE = 0.0005;

    public static class Req {
        /** SPOT（預設，只做多）| USDT_M（U 本位合約 1 倍，risk.allowShort=1 時也做空） */
        public String market;
        public String symbol;
        public String interval;
        public int bars;
        public long from, to;
        public List<Double> closes;          // 選填：自帶收盤價（O=H=L=C）
        public double fee;
        public double slippage;
        public double initialCash;
        public int fast;
        public int slow;
        public String type;
        /** 套用與實盤相同的機器人風控（出場 / 盤勢 / 冷卻 / 熔斷 / 下單量）；預設 true */
        public Boolean applyRisk;
        public Map<String, Object> risk;
        /** 每單名目佔權益 %（實盤每單名目 ÷ 實盤權益）；0 = 95% 現金 all-in（舊行為） */
        public double notionalPct;
        /** 實盤權益，把 maxLossUsdt 換算到回測本金 */
        public double liveEquity;
        /** measured（預設：用實盤實測滑價）| fixed */
        public String costModel;
        public Map<String, Object> params;   // 其他策略參數（period / mult / k …）
    }
}
