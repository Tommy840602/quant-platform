package com.quant.risk.controller;

import com.quant.risk.service.KillSwitch;
import com.quant.risk.service.RiskEngine;
import com.quant.risk.service.RiskLimits;
import org.springframework.web.bind.annotation.*;

import com.quant.common.dto.ApiResponse;
import java.util.Map;

/** 風控 API：事中核可 + 熔斷開關（見架構文件 §10）。 */
@RestController
@RequestMapping("/")
public class RiskController {

    private final RiskEngine engine;
    private final KillSwitch killSwitch;
    private final RiskLimits limits;

    public RiskController(RiskEngine engine, KillSwitch killSwitch, RiskLimits limits) {
        this.engine = engine; this.killSwitch = killSwitch; this.limits = limits;
    }

    /** 風控門檻：GET /api/risk/limits */
    @GetMapping("/limits")
    public ApiResponse<Map<String, Object>> limits() { return ApiResponse.ok(limits.snapshot()); }

    /** 更新門檻（即時生效、存 Redis）：POST /api/risk/limits {maxPositionPct, maxDailyLossPct, maxDailyOrders, …} */
    @PostMapping("/limits")
    public ApiResponse<Map<String, Object>> setLimits(@RequestBody Map<String, Object> body) { return ApiResponse.ok(limits.update(body)); }

    /** 今日狀態：GET /api/risk/status?account=main */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(@RequestParam(defaultValue = "main") String account) { return ApiResponse.ok(engine.status(account)); }

    /** 重置今日計數 / 日初權益：POST /api/risk/status/reset?account=main */
    @PostMapping("/status/reset")
    public ApiResponse<Map<String, Object>> resetDay(@RequestParam(defaultValue = "main") String account) {
        engine.resetDay(account); return ApiResponse.ok(engine.status(account));
    }

    @PostMapping("/check")
    public RiskEngine.RiskDecision check(@RequestBody Map<String, Object> intent) {
        return engine.evaluate(intent);
    }

    /** 觸發/解除熔斷：POST /api/risk/kill-switch?on=true&reason=drawdown */
    @PostMapping("/kill-switch")
    public ApiResponse<Map<String, Object>> setKill(@RequestParam boolean on,
                                                    @RequestParam(required = false) String reason) {
        killSwitch.set(on, reason);
        return ApiResponse.ok(killState());
    }

    /** 以前回裸 Map，前端讀 k.data?.on 永遠 undefined → 熔斷頁永遠顯示「正常」。統一包 ApiResponse。 */
    @GetMapping("/kill-switch")
    public ApiResponse<Map<String, Object>> getKill() { return ApiResponse.ok(killState()); }

    private Map<String, Object> killState() {
        String reason = killSwitch.reason();
        return Map.of("on", killSwitch.isOn(), "reason", reason == null ? "" : reason, "persisted", killSwitch.redisHealthy());
    }
}
