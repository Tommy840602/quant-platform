package com.quant.risk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全域交易熔斷開關（見架構文件 §10.3），可執行期切換。
 * 狀態持久化在 Redis（compose 已開 AOF）：risk-service 重啟不會偷偷解除熔斷。
 * Redis 讀不到時 fail-closed（視為熔斷中）——寧可擋單，不可在狀態不明時放行真錢。
 */
@Component
public class KillSwitch {
    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);
    static final String K_ON = "risk:kill-switch:on";
    static final String K_REASON = "risk:kill-switch:reason";

    private final StringRedisTemplate redis;
    private final AtomicBoolean cache;      // 本地快取，Redis 失聯時最後已知值
    private volatile String reason = "";
    private volatile boolean redisHealthy = true;

    public KillSwitch(StringRedisTemplate redis, @Value("${risk.kill-switch:false}") boolean initial) {
        this.redis = redis;
        this.cache = new AtomicBoolean(initial);
        try {
            String v = redis.opsForValue().get(K_ON);
            if (v == null) {            // 第一次啟動：以設定值初始化
                redis.opsForValue().set(K_ON, initial ? "1" : "0");
            } else {
                cache.set("1".equals(v));
                String r = redis.opsForValue().get(K_REASON);
                reason = r == null ? "" : r;
            }
            log.info("Kill switch 還原: on={} reason='{}'", cache.get(), reason);
        } catch (Exception e) {
            redisHealthy = false;
            log.error("Kill switch 無法讀取 Redis，將 fail-closed: {}", e.getMessage());
        }
    }

    /** 熔斷是否啟動。Redis 失聯 → true（fail-closed）。 */
    public boolean isOn() {
        try {
            String v = redis.opsForValue().get(K_ON);
            redisHealthy = true;
            if (v != null) cache.set("1".equals(v));
            return cache.get();
        } catch (Exception e) {
            if (redisHealthy) log.error("Kill switch Redis 失聯，fail-closed 擋單: {}", e.getMessage());
            redisHealthy = false;
            return true;
        }
    }

    public String reason() {
        if (!redisHealthy) return "風控狀態儲存（Redis）失聯，保守擋單";
        return reason;
    }

    public void set(boolean v, String reason) {
        String r = reason == null ? "" : reason;
        cache.set(v); this.reason = r;
        try {
            redis.opsForValue().set(K_ON, v ? "1" : "0");
            redis.opsForValue().set(K_REASON, r);
            redisHealthy = true;
        } catch (Exception e) {
            redisHealthy = false;
            log.error("Kill switch 寫入 Redis 失敗（僅更新記憶體）: {}", e.getMessage());
            throw new IllegalStateException("熔斷狀態無法持久化: " + e.getMessage(), e);
        }
        log.warn("Kill switch → {} ({})", v ? "ON" : "OFF", r);
    }

    public boolean redisHealthy() { return redisHealthy; }
}
