package com.quant.risk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 風控規則引擎（見架構文件 §10.2）。事前/事中基本規則 + 全域熔斷。 */
@Service
public class RiskEngine {

    @Value("${risk.max-order-qty:1.0}")
    private double maxOrderQty;

    private final KillSwitch killSwitch;
    public RiskEngine(KillSwitch killSwitch) { this.killSwitch = killSwitch; }

    public RiskDecision evaluate(java.util.Map<String, Object> intent) {
        if (killSwitch.isOn())
            return new RiskDecision(false, "熔斷已啟動: " + killSwitch.reason(), null);
        double qty = intent.get("quantity") == null ? 0 :
                Double.parseDouble(intent.get("quantity").toString());
        if (qty <= 0) return new RiskDecision(false, "數量不合法", null);
        if (qty > maxOrderQty) return new RiskDecision(false, "超過單筆數量上限", maxOrderQty);
        // TODO: 部位限制、集中度、保證金率、下單頻率、價格偏離保護
        return new RiskDecision(true, "ok", null);
    }

    public record RiskDecision(boolean approved, String reason, Double adjustedQty) {}
}
