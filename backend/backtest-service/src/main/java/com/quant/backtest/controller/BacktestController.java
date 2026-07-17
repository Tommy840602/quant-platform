package com.quant.backtest.controller;

import com.quant.backtest.engine.BacktestEngine;
import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.Kline;
import com.quant.backtest.engine.StrategyFactory;
import java.util.HashMap;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 回測 API（見架構文件 §7）：以事件驅動引擎跑雙均線，回傳績效與權益曲線。 */
@RestController
@RequestMapping("/")
public class BacktestController {

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody Req req) {
        List<Double> closes = req.closes;
        if (closes == null || closes.size() < 10) closes = synthetic(200);
        String symbol = req.symbol == null ? "BTCUSDT" : req.symbol;

        List<Kline> bars = new ArrayList<>();
        long t = System.currentTimeMillis();
        for (int i = 0; i < closes.size(); i++) {
            double c = closes.get(i);
            bars.add(new Kline(symbol, "1m", t + i * 60000L, c, c, c, c, 1, t + i * 60000L, true));
        }
        var engine = new BacktestEngine(
                req.initialCash == 0 ? 10000 : req.initialCash,
                req.fee == 0 ? 0.0004 : req.fee,
                req.slippage == 0 ? 0.0002 : req.slippage);
        var params = new HashMap<String, Object>();
        params.put("fast", req.fast == 0 ? 3 : req.fast);
        params.put("slow", req.slow == 0 ? 8 : req.slow);
        params.put("qty", 0.01);
        var strategy = StrategyFactory.build(req.type, "backtest", params);
        var result = engine.run(bars, strategy);
        return ApiResponse.ok(Map.of("metrics", result.metrics(), "equity", result.equity(), "trades", result.trades()));
    }

    private List<Double> synthetic(int n) {
        List<Double> out = new ArrayList<>();
        double p = 100;
        for (int i = 0; i < n; i++) { p += (Math.random() - 0.48) * 2; out.add(Math.round(p * 100.0) / 100.0); }
        return out;
    }

    public static class Req {
        public String symbol;
        public List<Double> closes;
        public double fee;
        public double slippage;
        public double initialCash;
        public int fast;
        public int slow;
        public String type;
    }
}
