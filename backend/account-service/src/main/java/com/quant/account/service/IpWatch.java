package com.quant.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Function;

/**
 * 對外 IP 監看：家用網路的 IP 會變，一變幣安 API 白名單就失效（所有下單被拒 -2015）。
 * 每 10 分鐘查一次 IPv4 / IPv6，跟上次不同就發 Slack，附上新 IP 讓你直接去幣安改白名單。
 */
@Service
public class IpWatch {
    private static final Logger log = LoggerFactory.getLogger(IpWatch.class);
    static final String KEY = "net:public-ip";
    private final StringRedisTemplate redis;
    private final NotifyClient notify;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    Function<String, String> fetcher = this::get;   // 測試替換
    private volatile String current;

    public IpWatch(StringRedisTemplate redis, NotifyClient notify) { this.redis = redis; this.notify = notify; }

    /** 目前對外 IP（"1.2.3.4" 或 "1.2.3.4 / 2001:db8::1"）；還沒查到回 null。 */
    public String current() { return current; }

    @Scheduled(initialDelay = 30_000, fixedDelay = 600_000)
    public void check() {
        String v4 = clean(fetcher.apply("https://api.ipify.org"));
        String v6 = clean(fetcher.apply("https://api64.ipify.org"));
        if (v6 != null && (v6.equals(v4) || !v6.contains(":"))) v6 = null;   // 沒有 IPv6 時 api64 會回 IPv4
        if (v4 == null && v6 == null) return;                                // 查不到（斷網）：不動
        String now = v6 == null ? v4 : (v4 == null ? v6 : v4 + " / " + v6);
        current = now;
        String before = null;
        try { before = redis.opsForValue().get(KEY); redis.opsForValue().set(KEY, now); } catch (Exception e) { log.debug("Redis: {}", e.getMessage()); }
        if (before != null && !before.equals(now)) {
            log.warn("對外 IP 變更：{} → {}", before, now);
            try {
                notify.send(java.util.Map.of("text", ":globe_with_meridians: *對外 IP 變了*：" + before + " → *" + now + "*\n"
                        + "幣安 API 有設 IP 白名單的話，現在下單會被拒（-2015）。請到幣安「API 管理」→ 編輯 → IP 存取限制，把新 IP 加進去"));
            } catch (Exception e) { log.warn("通知失敗: {}", e.getMessage()); }
        } else if (before == null) log.info("對外 IP：{}", now);
    }

    static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.matches("[0-9a-fA-F:.]{3,45}") ? s : null;
    }

    private String get(String url) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) { return null; }
    }
}
