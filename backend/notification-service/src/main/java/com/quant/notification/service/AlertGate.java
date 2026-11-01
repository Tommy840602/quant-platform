package com.quant.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警收斂（後續 5）。所有服務的 Slack 訊息都經 notification-service 的 /test 進來，這裡是唯一出口：
 *  1. 去重壓抑：同一「指紋」（去掉數字後的前 48 字）在 window 內只發第一則，之後只計數；
 *     window 結束時若有被壓抑的，補一則「(+N 次重複)」
 *  2. 模擬成交（DRYRUN- / PAPER- 的 exchangeOrderId）不即時推，只進每日摘要
 *  3. 每日 09:00（台北）摘要：各類訊息計數 + 壓抑數 + 觸發帳戶淨值摘要
 */
@Service
public class AlertGate {

    private static final Logger log = LoggerFactory.getLogger(AlertGate.class);
    static final ZoneId TZ = ZoneId.of("Asia/Taipei");

    @Value("${notification.dedupe-window-sec:600}") private long windowSec;
    @Value("${notification.dedupe-enabled:true}") private boolean dedupeEnabled;

    private final SlackNotifier slack;
    private final java.util.function.LongSupplier clock;

    static final class Entry { long firstAt; long lastSentAt; long suppressed; String lastText; }
    private final Map<String, Entry> seen = new ConcurrentHashMap<>();
    // 每日計數（台北日）
    private volatile String day = "";
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private final AtomicLong suppressedToday = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public AlertGate(SlackNotifier slack) { this(slack, System::currentTimeMillis); }
    AlertGate(SlackNotifier slack, java.util.function.LongSupplier clock) { this.slack = slack; this.clock = clock; }

    /** 統一入口：回傳是否真的送出。 */
    public boolean publish(String text, String category) {
        rollDay();
        counts.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet();
        if (!dedupeEnabled) { slack.send(text); return true; }
        String fp = fingerprint(text);
        long now = clock.getAsLong();
        Entry e = seen.computeIfAbsent(fp, k -> new Entry());
        synchronized (e) {
            if (e.lastSentAt > 0 && now - e.lastSentAt < windowSec * 1000) {
                e.suppressed++; e.lastText = text; suppressedToday.incrementAndGet();
                return false;
            }
            String out = text;
            if (e.suppressed > 0) { out = text + "\n_（前 " + fmt(now - e.lastSentAt) + " 內同類訊息另有 " + e.suppressed + " 則已壓抑）_"; e.suppressed = 0; }
            e.lastSentAt = now; if (e.firstAt == 0) e.firstAt = now;
            slack.send(out);
            return true;
        }
    }

    /** 只計數不推（模擬成交等）。 */
    public void countOnly(String category) { rollDay(); counts.computeIfAbsent(category, k -> new AtomicLong()).incrementAndGet(); }

    /** 指紋：去掉數字 / 小數 / 時間，取前 48 字。同一種告警不同數值視為同一則。 */
    static String fingerprint(String text) {
        String t = text == null ? "" : text.replaceAll("[0-9.,:％%\\-+]+", "#").replaceAll("\\s+", " ").trim();
        return t.length() > 48 ? t.substring(0, 48) : t;
    }

    private static String fmt(long ms) { long m = ms / 60000; return m < 60 ? m + " 分鐘" : (m / 60) + " 小時"; }

    private void rollDay() {
        String d = LocalDate.now(TZ).toString();
        if (!d.equals(day)) { synchronized (this) { if (!d.equals(day)) { day = d; counts.clear(); suppressedToday.set(0); } } }
    }

    /** 清掉過期指紋（記憶體不無限長）。 */
    @Scheduled(fixedDelay = 600_000)
    public void sweep() {
        long cutoff = clock.getAsLong() - windowSec * 1000 * 6;
        seen.entrySet().removeIf(en -> en.getValue().lastSentAt < cutoff);
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", day);
        Map<String, Long> c = new LinkedHashMap<>(); counts.forEach((k, v) -> c.put(k, v.get()));
        m.put("counts", c);
        m.put("suppressedToday", suppressedToday.get());
        m.put("dedupeWindowSec", windowSec);
        m.put("trackedFingerprints", seen.size());
        return m;
    }

    /** 每日摘要文字（給排程與手動觸發）。 */
    public String digestText() {
        rollDay();
        StringBuilder sb = new StringBuilder(":newspaper: *每日告警摘要 " + day + "*\n");
        if (counts.isEmpty()) sb.append("今日無事件\n");
        counts.forEach((k, v) -> sb.append("• ").append(k).append("：").append(v.get()).append("\n"));
        if (suppressedToday.get() > 0) sb.append("• 重複訊息已壓抑：").append(suppressedToday.get()).append("\n");
        return sb.toString();
    }
}
