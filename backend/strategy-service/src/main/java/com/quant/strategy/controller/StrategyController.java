package com.quant.strategy.controller;

import com.quant.common.dto.ApiResponse;
import com.quant.strategy.service.StrategyRunner;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 策略/機器人 API：清單、上下線、參數熱更新、動態建立機器人（見架構文件 §3.2.2）。 */
@RestController
@RequestMapping("/strategies")
public class StrategyController {

    private final StrategyRunner runner;
    public StrategyController(StrategyRunner runner) { this.runner = runner; }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() { return ApiResponse.ok(runner.list()); }

    @PostMapping("/{id}/start")
    public ApiResponse<String> start(@PathVariable String id) { return ApiResponse.ok(runner.start(id)); }

    @PostMapping("/{id}/stop")
    public ApiResponse<String> stop(@PathVariable String id) { return ApiResponse.ok(runner.stop(id)); }

    @PostMapping("/{id}/params")
    public ApiResponse<String> params(@PathVariable String id, @RequestBody Map<String, Object> b) {
        int fast = ((Number) b.getOrDefault("fast", 3)).intValue();
        int slow = ((Number) b.getOrDefault("slow", 8)).intValue();
        double qty = ((Number) b.getOrDefault("qty", 0.001)).doubleValue();
        return ApiResponse.ok(runner.updateParams(id, fast, slow, qty));
    }

    /** 資金配置：POST /api/strategy/strategies/{id}/allocation?weight=1.5 */
    @PostMapping("/{id}/allocation")
    public ApiResponse<String> allocation(@PathVariable String id, @RequestParam double weight) {
        return ApiResponse.ok(runner.setAllocation(id, weight));
    }

    /** 建立機器人：POST /api/strategy/strategies  body: {id, type: ma|rsi|grid, symbol, params:{...}} */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ApiResponse<String> create(@RequestBody Map<String, Object> b) {
        String id = (String) b.get("id");
        String type = (String) b.get("type");
        String symbol = (String) b.get("symbol");
        Map<String, Object> params = (Map<String, Object>) b.getOrDefault("params", Map.of());
        return ApiResponse.ok(runner.createBot(id, type, symbol, params));
    }
}
