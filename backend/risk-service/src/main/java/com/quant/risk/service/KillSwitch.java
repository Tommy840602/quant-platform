package com.quant.risk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** 全域交易熔斷開關（見架構文件 §10.3），可執行期切換。 */
@Component
public class KillSwitch {
    private final AtomicBoolean on;
    private volatile String reason = "";

    public KillSwitch(@Value("${risk.kill-switch:false}") boolean initial) {
        this.on = new AtomicBoolean(initial);
    }
    public boolean isOn() { return on.get(); }
    public String reason() { return reason; }
    public void set(boolean v, String reason) { on.set(v); this.reason = reason == null ? "" : reason; }
}
