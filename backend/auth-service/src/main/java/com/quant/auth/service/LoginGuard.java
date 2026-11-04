package com.quant.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.LongSupplier;

/**
 * 登入暴力破解防護（開到外網前必備）：
 * 同一「帳號」15 分鐘內失敗 5 次、或同一「來源 IP」15 分鐘內失敗 20 次 → 鎖 15 分鐘。
 * 鎖定期間連正確密碼也拒絕，回剩餘秒數；成功登入清掉該帳號的失敗紀錄。
 */
@Service
public class LoginGuard {
    private static final Logger log = LoggerFactory.getLogger(LoginGuard.class);
    static final long WINDOW_MS = 15 * 60_000, LOCK_MS = 15 * 60_000;
    static final int MAX_PER_USER = 5, MAX_PER_IP = 20;

    private final Map<String, Deque<Long>> fails = new ConcurrentHashMap<>();
    private final Map<String, Long> lockedUntil = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public LoginGuard() { this(System::currentTimeMillis); }
    LoginGuard(LongSupplier clock) { this.clock = clock; }

    static String userKey(String u) { return "u:" + (u == null ? "" : u.trim().toLowerCase()); }
    static String ipKey(String ip) { return "ip:" + (ip == null ? "" : ip); }

    /** 被鎖住回剩餘秒數，否則 0。 */
    public long lockedSeconds(String username, String ip) {
        long now = clock.getAsLong();
        long until = Math.max(lockedUntil.getOrDefault(userKey(username), 0L), lockedUntil.getOrDefault(ipKey(ip), 0L));
        return until > now ? (until - now + 999) / 1000 : 0;
    }

    public void onFailure(String username, String ip) {
        long now = clock.getAsLong();
        if (count(userKey(username), now) >= MAX_PER_USER) lock(userKey(username), now, ip);
        if (ip != null && !ip.isBlank() && count(ipKey(ip), now) >= MAX_PER_IP) lock(ipKey(ip), now, ip);
    }

    public void onSuccess(String username) { fails.remove(userKey(username)); lockedUntil.remove(userKey(username)); }

    private int count(String key, long now) {
        Deque<Long> d = fails.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        d.addLast(now);
        while (!d.isEmpty() && now - d.peekFirst() > WINDOW_MS) d.pollFirst();
        return d.size();
    }
    private void lock(String key, long now, String ip) {
        lockedUntil.put(key, now + LOCK_MS);
        fails.remove(key);
        log.warn("登入失敗次數過多，鎖定 {} 15 分鐘（來源 IP {}）", key, ip);
    }

    /**
     * 真實來源 IP：Cloudflare 會覆寫 CF-Connecting-IP（外面偽造不了）→ nginx 設的 X-Real-IP → 連線位址。
     * X-Forwarded-For 第一段是客戶端自己能塞的，不採用（否則攻擊者每次換一個就繞過 IP 限制）。
     */
    public static String clientIp(String cfConnectingIp, String xRealIp, String xForwardedFor, String remoteAddr) {
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) return cfConnectingIp.trim();
        if (xRealIp != null && !xRealIp.isBlank()) return xRealIp.trim();
        return remoteAddr;
    }
}
