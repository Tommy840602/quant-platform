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
    public ApiResponse<String> start(@PathVariable String id) {
        String r = runner.start(id);
        return r.startsWith("rejected") ? ApiResponse.error(409, r) : ApiResponse.ok(r);
    }

    /** 下線；flatten=true 同時對該策略持倉送市價賣單平倉 */
    /** 每個標的的盤勢：GET /api/strategy/strategies/regimes */
    @GetMapping("/regimes")
    public ApiResponse<Map<String, Map<String, Object>>> regimes() { return ApiResponse.ok(runner.regimes()); }

    /** 自動配資狀態：GET /api/strategy/strategies/bandit */
    @GetMapping("/bandit")
    public ApiResponse<Map<String, Object>> bandit() { return ApiResponse.ok(runner.banditStatus()); }

    /** 開關自動配資：POST /api/strategy/strategies/bandit?enabled=true */
    @PostMapping("/bandit")
    public ApiResponse<Map<String, Object>> setBandit(@RequestParam boolean enabled) { return ApiResponse.ok(runner.setBanditEnabled(enabled)); }

    /** 立即重新配資：POST /api/strategy/strategies/bandit/rebalance */
    @PostMapping("/bandit/rebalance")
    public ApiResponse<Map<String, Object>> rebalance() { return ApiResponse.ok(runner.applyBandit("手動")); }

    /** 手動調整晉升階段：POST /api/strategy/strategies/{id}/stage?stage=PAPER|PILOT|LIVE */
    @PostMapping("/{id}/stage")
    public ApiResponse<String> stage(@PathVariable String id, @RequestParam String stage) { return ApiResponse.ok(runner.setStage(id, stage, "手動")); }

    /** 晉升管線設定：GET /api/strategy/strategies/pipeline */
    @GetMapping("/pipeline")
    public ApiResponse<Map<String, Object>> pipeline() { return ApiResponse.ok(runner.pipelineStatus()); }

    /** 自動晉升 / 降級開關：POST /api/strategy/strategies/pipeline?auto=true */
    @PostMapping("/pipeline")
    public ApiResponse<Map<String, Object>> setPipeline(@RequestParam boolean auto) { return ApiResponse.ok(runner.setPipelineAuto(auto)); }

    /** 決策日誌：GET /api/strategy/strategies/decisions?botId=&symbol=&from=&to=&acted=&limit= */
    @GetMapping("/decisions")
    public ApiResponse<List<Map<String, Object>>> decisions(@RequestParam(required = false) String botId, @RequestParam(required = false) String symbol,
                                                            @RequestParam(required = false) Long from, @RequestParam(required = false) Long to,
                                                            @RequestParam(required = false) Boolean acted, @RequestParam(defaultValue = "300") int limit) {
        return ApiResponse.ok(runner.decisions(botId, symbol, from, to, acted, limit));
    }

    /** 各標的實測滑價（回測成本校正用）：GET /api/strategy/strategies/slippage?days=30 */
    @GetMapping("/slippage")
    public ApiResponse<Map<String, Map<String, Object>>> slippage(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(runner.slippageStats(Math.max(1, Math.min(days, 365))));
    }

    /** 熔斷後恢復進場：POST /api/strategy/strategies/{id}/resume */
    @PostMapping("/{id}/resume")
    public ApiResponse<String> resume(@PathVariable String id) { return ApiResponse.ok(runner.resume(id)); }

    @PostMapping("/{id}/stop")
    public ApiResponse<String> stop(@PathVariable String id, @RequestParam(defaultValue = "false") boolean flatten) {
        return ApiResponse.ok(flatten ? runner.stop(id, true) : runner.stop(id));
    }

    /** 任一類型改參數：POST /strategies/{id}/params-any {period: 14, mult: 2, qty: 0.002 …} */
    @PostMapping("/{id}/params-any")
    public ApiResponse<String> paramsAny(@PathVariable String id, @RequestBody Map<String, Object> b) { return ApiResponse.ok(runner.updateParamsAny(id, b)); }

    /** 刪除：DELETE /strategies/{id}?flatten=false */
    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable String id, @RequestParam(defaultValue = "false") boolean flatten) {
        String r = runner.deleteBot(id, flatten);
        return r.startsWith("deleted") ? ApiResponse.ok(r) : ApiResponse.error(400, r);
    }

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

    /** 每單名目金額：GET /api/strategy/strategies/order-notional */
    @GetMapping("/order-notional")
    public ApiResponse<Map<String, Object>> getOrderNotional() {
        return ApiResponse.ok(Map.of("notionalUsdt", runner.getOrderNotional()));
    }

    /** 設定每單名目金額：POST /api/strategy/strategies/order-notional?value=6 */
    @PostMapping("/order-notional")
    public ApiResponse<Map<String, Object>> setOrderNotional(@RequestParam double value) {
        return ApiResponse.ok(Map.of("notionalUsdt", runner.setOrderNotional(value)));
    }

    /** 建立機器人：POST /api/strategy/strategies  body: {id, type: ma|rsi|grid, symbol, params:{...}} */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ApiResponse<String> create(@RequestBody Map<String, Object> b) {
        String id = (String) b.get("id");
        String type = (String) b.get("type");
        String symbol = (String) b.get("symbol");
        if (symbol != null && (symbol.isBlank() || "ALL".equalsIgnoreCase(symbol))) symbol = null;   // 全部標的
        Map<String, Object> params = (Map<String, Object>) b.getOrDefault("params", Map.of());
        return ApiResponse.ok(runner.createBot(id, type, symbol, params));
    }
}
