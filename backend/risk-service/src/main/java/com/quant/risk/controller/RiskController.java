package com.quant.risk.controller;

import com.quant.risk.service.KillSwitch;
import com.quant.risk.service.RiskEngine;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 風控 API：事中核可 + 熔斷開關（見架構文件 §10）。 */
@RestController
@RequestMapping("/")
public class RiskController {

    private final RiskEngine engine;
    private final KillSwitch killSwitch;

    public RiskController(RiskEngine engine, KillSwitch killSwitch) {
        this.engine = engine; this.killSwitch = killSwitch;
    }

    @PostMapping("/check")
    public RiskEngine.RiskDecision check(@RequestBody Map<String, Object> intent) {
        return engine.evaluate(intent);
    }

    /** 觸發/解除熔斷：POST /api/risk/kill-switch?on=true&reason=drawdown */
    @PostMapping("/kill-switch")
    public Map<String, Object> setKill(@RequestParam boolean on,
                                       @RequestParam(required = false) String reason) {
        killSwitch.set(on, reason);
        return Map.of("on", killSwitch.isOn(), "reason", killSwitch.reason(), "persisted", killSwitch.redisHealthy());
    }

    @GetMapping("/kill-switch")
    public Map<String, Object> getKill() {
        return Map.of("on", killSwitch.isOn(), "reason", killSwitch.reason(), "persisted", killSwitch.redisHealthy());
    }
}
