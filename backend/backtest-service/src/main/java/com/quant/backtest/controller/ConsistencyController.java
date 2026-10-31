package com.quant.backtest.controller;

import com.quant.backtest.engine.BacktestEngine;
import com.quant.backtest.engine.ConsistencyScorer;
import com.quant.backtest.engine.StrategyFactory;
import com.quant.backtest.service.BarSource;
import com.quant.backtest.service.LiveOrderClient;
import com.quant.common.dto.ApiResponse;
import com.quant.common.enums.OrderSide;
import com.quant.common.util.Intervals;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 回測 vs 實盤一致性報告（見架構文件 §7.4、§15）。
 * 取該策略實盤成交的時間窗（前面多留 warmup 根讓指標暖機），抓同一段真實 K 線重跑策略，
 * 逐筆配對（同方向、±tolerance 根）算一致性；沒有實盤成交就老實回 null，不再回 1.0。
 */
@RestController
@RequestMapping("/consistency")
public class ConsistencyController {

    private final LiveOrderClient liveOrders;
    private final BarSource bars;
    public ConsistencyController(LiveOrderClient liveOrders, BarSource bars) { this.liveOrders = liveOrders; this.bars = bars; }

    @GetMapping
    public ApiResponse<Map<String, Object>> report(
            @RequestParam(defaultValue = "ma-fast") String strategyId,
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(defaultValue = "3") int fast,
            @RequestParam(defaultValue = "8") int slow,
            @RequestParam(defaultValue = "ma") String type,
            @RequestParam(defaultValue = "200") int warmupBars,
            @RequestParam(defaultValue = "2") int toleranceBars,
            @RequestParam(required = false) Map<String, String> query) {

        // 1) 實盤成交
        List<ConsistencyScorer.LiveFill> live = new ArrayList<>();
        try {
            for (Map<String, Object> r : liveOrders.fills(strategyId).data()) {
                if (!symbol.equalsIgnoreCase(String.valueOf(r.get("symbol")))) continue;
                live.add(new ConsistencyScorer.LiveFill(((Number) r.get("time")).longValue(),
                        "SELL".equals(r.get("side")) ? OrderSide.SELL : OrderSide.BUY,
                        ((Number) r.get("filledQty")).doubleValue(), ((Number) r.get("avgPrice")).doubleValue()));
            }
        } catch (Exception e) {
            return ApiResponse.error(502, "取不到實盤成交（order-execution-service）: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyId", strategyId); out.put("symbol", symbol.toUpperCase()); out.put("interval", interval);
        if (live.isEmpty()) {
            out.put("consistencyScore", null);
            out.put("liveFills", 0);
            out.put("note", "此策略在 " + symbol + " 尚無實盤成交，無從比對（以前這裡會回 1.0，是假的）");
            return ApiResponse.ok(out);
        }

        // 2) 同一段真實 K 線重跑
        long step = Intervals.millis(interval);
        long from = live.get(0).time() - (long) warmupBars * step;
        long to = live.get(live.size() - 1).time() + step;
        BarSource.Bars data;
        try { data = bars.range(symbol.toUpperCase(), interval, from, to); }
        catch (BarSource.NoDataException e) { return ApiResponse.error(400, e.getMessage()); }

        var params = new HashMap<String, Object>();
        params.put("fast", fast); params.put("slow", slow); params.put("qty", 0.001);
        if (query != null) query.forEach((k, v) -> { if (!Set.of("strategyId","symbol","interval","fast","slow","type","warmupBars","toleranceBars").contains(k)) params.put(k, parse(v)); });
        var result = new BacktestEngine(10000, 0.0004, 0.0002).run(data.bars(), StrategyFactory.build(type, strategyId, params));

        // 3) 逐筆配對
        out.putAll(ConsistencyScorer.score(live, result.tradeLog(), step, toleranceBars));
        out.put("window", Map.of("from", from, "to", to, "bars", data.bars().size(), "dataSource", data.dataSource()));
        out.put("backtestMetrics", result.metrics());
        return ApiResponse.ok(out);
    }

    private static Object parse(String v) {
        try { return v.contains(".") ? Double.parseDouble(v) : Integer.parseInt(v); } catch (Exception e) { return v; }
    }
}
