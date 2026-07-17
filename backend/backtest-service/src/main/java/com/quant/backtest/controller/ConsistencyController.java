package com.quant.backtest.controller;

import com.quant.backtest.engine.BacktestEngine;
import com.quant.backtest.service.LiveOrderClient;
import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import com.quant.backtest.engine.StrategyFactory;
import java.util.HashMap;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 回測 vs 實盤一致性報告（見架構文件 §7.4、§15）。
 * 以同一策略介面在歷史序列上跑回測，與實盤已成交訂單比對交易次數與均價，估算落差。
 * 註：此處回測資料為合成序列示意；正式應回放實盤策略當時所見的同一段歷史 K 線。
 */
@RestController
@RequestMapping("/consistency")
public class ConsistencyController {

    private final LiveOrderClient liveOrders;
    public ConsistencyController(LiveOrderClient liveOrders) { this.liveOrders = liveOrders; }

    @GetMapping
    public ApiResponse<Map<String, Object>> report(
            @RequestParam(defaultValue = "ma-fast") String strategyId,
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "3") int fast,
            @RequestParam(defaultValue = "8") int slow,
            @RequestParam(defaultValue = "ma") String type,
            @RequestParam(defaultValue = "200") int bars) {

        // 1) 回測（合成序列）
        List<Kline> series = synthetic(symbol, bars);
        var engine = new BacktestEngine(10000, 0.0004, 0.0002);
        var params = new HashMap<String, Object>();
        params.put("fast", fast); params.put("slow", slow); params.put("qty", 0.001);
        var result = engine.run(series, StrategyFactory.build(type, strategyId, params));
        int backtestTrades = result.trades();

        // 2) 實盤成交（由 order-execution 歸因）
        long liveFilled = 0;
        try {
            var attribution = liveOrders.attribution().data();
            for (Map<String, Object> row : attribution) {
                if (strategyId.equals(row.get("strategyId")))
                    liveFilled = ((Number) row.get("filledOrders")).longValue();
            }
        } catch (Exception ignore) {}

        // 3) 一致性指標
        long diff = Math.abs(backtestTrades - liveFilled);
        double consistency = backtestTrades == 0 ? (liveFilled == 0 ? 1 : 0)
                : 1 - Math.min(1.0, (double) diff / Math.max(backtestTrades, 1));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyId", strategyId);
        out.put("backtestTrades", backtestTrades);
        out.put("backtestAvgFillPrice", Math.round(result.avgFillPrice() * 100.0) / 100.0);
        out.put("backtestMetrics", result.metrics());
        out.put("liveFilledOrders", liveFilled);
        out.put("tradeCountDiff", diff);
        out.put("consistencyScore", Math.round(consistency * 100.0) / 100.0);
        out.put("note", "回測為合成序列示意；正式一致性需回放實盤同段歷史行情並比對逐筆成交與滑點。");
        return ApiResponse.ok(out);
    }

    private List<Kline> synthetic(String symbol, int n) {
        List<Kline> bars = new ArrayList<>();
        double p = 100; long t = System.currentTimeMillis();
        for (int i = 0; i < n; i++) {
            p += (Math.random() - 0.48) * 2;
            double c = Math.round(p * 100.0) / 100.0;
            bars.add(new Kline(symbol, "1m", t + i * 60000L, c, c, c, c, 1, t + i * 60000L, true));
        }
        return bars;
    }
}
