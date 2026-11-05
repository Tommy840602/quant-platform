package com.quant.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 統一帳戶風險保護：每 30 秒看 uniMMR（維持保證金率）與帳戶狀態。
 * <ul>
 *   <li>uniMMR &lt; 警示線（預設 1.5）→ Slack 預警</li>
 *   <li>uniMMR &lt; 停止線（預設 1.2）或狀態不是 NORMAL → 寫 Redis risk:pm-guard，風控擋所有「合約開倉」，平倉 / 減倉照常</li>
 *   <li>回到警示線以上且狀態 NORMAL → 解除並通知</li>
 * </ul>
 * 統一帳戶是全帳戶共用保證金：一個倉位虧太多會拖到強平整個帳戶，所以寧可提早停止加倉。
 * Redis 鍵有 15 分鐘 TTL、每次檢查續期：account-service 掛掉不會永遠鎖住。
 */
@Service
public class PmGuard {
    private static final Logger log = LoggerFactory.getLogger(PmGuard.class);
    public static final String KEY = "risk:pm-guard";
    public enum Level { OK, WARN, STOP }

    private final FuturesAccountService futures;
    private final StringRedisTemplate redis;
    private final NotifyClient notify;
    @Value("${pm.mmr-warn:1.5}") double warnMmr;
    @Value("${pm.mmr-stop:1.2}") double stopMmr;

    private volatile Level level = Level.OK;
    private volatile String reason;
    private volatile long lastStopNotice;

    public PmGuard(FuturesAccountService futures, StringRedisTemplate redis, NotifyClient notify) {
        this.futures = futures; this.redis = redis; this.notify = notify;
    }

    /** 純判斷：回 [等級, 原因]。 */
    static Object[] judge(String status, Double mmr, double warn, double stop) {
        if (status != null && !"NORMAL".equalsIgnoreCase(status))
            return new Object[]{Level.STOP, "帳戶狀態 " + status};
        if (mmr == null) return new Object[]{Level.OK, null};
        if (mmr < stop) return new Object[]{Level.STOP, String.format("uniMMR %.2f 低於停止線 %.2f", mmr, stop)};
        if (mmr < warn) return new Object[]{Level.WARN, String.format("uniMMR %.2f 低於警示線 %.2f", mmr, warn)};
        return new Object[]{Level.OK, null};
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 30_000)
    public void check() {
        if (!futures.configured() || !futures.pm()) { if (level != Level.OK) clear("不再是統一帳戶模式"); return; }
        var snap = futures.snapshot();
        if (snap.isEmpty() || !snap.get().pm()) return;   // 查不到：維持現狀（不亂解鎖、也不亂鎖）
        var s = snap.get();
        Double mmr = null;
        try { if (s.uniMMR() != null && !s.uniMMR().isBlank()) mmr = Double.parseDouble(s.uniMMR()); } catch (NumberFormatException ignore) {}
        Object[] j = judge(s.pmStatus(), mmr, warnMmr, stopMmr);
        apply((Level) j[0], (String) j[1], s.marginBalance());
    }

    synchronized void apply(Level next, String why, double equity) {
        Level prev = level;
        level = next; reason = why;
        long now = System.currentTimeMillis();
        if (next == Level.STOP) {
            try { redis.opsForValue().set(KEY, why, 15, TimeUnit.MINUTES); } catch (Exception e) { log.error("寫入 {} 失敗: {}", KEY, e.getMessage()); }
            if (prev != Level.STOP || now - lastStopNotice > 30 * 60_000) {
                lastStopNotice = now;
                send(String.format(":rotating_light: *統一帳戶風險保護啟動*：%s ｜ 權益 %.2f USD ｜ 已停止所有合約開倉，平倉 / 減倉照常。請降低倉位或補保證金", why, equity));
            }
        } else {
            if (prev == Level.STOP) clear(next == Level.WARN ? why + "（仍在警示區）" : "uniMMR 回到安全範圍");
            if (next == Level.WARN && prev == Level.OK)
                send(String.format(":warning: 統一帳戶預警：%s ｜ 權益 %.2f USD ｜ 低於 %.2f 會自動停止合約開倉", why, equity, stopMmr));
            if (next == Level.OK && prev == Level.WARN) send(":white_check_mark: 統一帳戶 uniMMR 已回到安全範圍");
        }
    }

    private void clear(String why) {
        try { redis.delete(KEY); } catch (Exception ignore) {}
        if (level == Level.STOP) level = Level.OK;
        send(":white_check_mark: 統一帳戶風險保護解除（" + why + "），合約可以再開倉");
        log.warn("統一帳戶風險保護解除：{}", why);
    }

    private void send(String text) {
        log.warn("[統一帳戶] {}", text);
        try { notify.send(Map.of("text", text)); } catch (Exception e) { log.warn("通知失敗: {}", e.getMessage()); }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level.name()); m.put("reason", reason); m.put("warnMmr", warnMmr); m.put("stopMmr", stopMmr);
        return m;
    }
}
