package com.quant.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 每日 09:00（台北）摘要（後續 7 升級）：一則看完——
 * 環境 / 送單模式、各機器人執行時長與收益、風控今日計數 / 損益 / 持倉占比、UDS 狀態、告警計數；再請 account-service 推淨值摘要。
 * 任一服務不在就該段寫「取不到」，不讓整則摘要失敗。
 */
@Service
public class DailyDigest {

    private static final Logger log = LoggerFactory.getLogger(DailyDigest.class);
    private final AlertGate gate;
    private final SlackNotifier slack;
    private final AccountClient account;
    private final PlatformClients.StrategyClient strategy;
    private final PlatformClients.RiskClient risk;
    private final PlatformClients.OmsClient oms;
    private final KafkaLagMonitor lag;

    public DailyDigest(AlertGate gate, SlackNotifier slack, AccountClient account, PlatformClients.StrategyClient strategy,
                       PlatformClients.RiskClient risk, PlatformClients.OmsClient oms, KafkaLagMonitor lag) {
        this.gate = gate; this.slack = slack; this.account = account; this.strategy = strategy; this.risk = risk; this.oms = oms; this.lag = lag;
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Taipei")
    public void send() { sendNow(); }

    public String sendNow() {
        StringBuilder sb = new StringBuilder(gate.digestText());
        sb.append(section("下單", this::tradingLine));
        sb.append(section("機器人", this::botsLines));
        sb.append(section("風控", this::riskLines));
        sb.append(section("成交回報 / Kafka", this::infraLines));
        String text = sb.toString();
        slack.send(text);                       // 摘要不走去重（一天一次）
        try { account.summaryNow(); } catch (Exception e) { log.warn("淨值摘要觸發失敗: {}", e.getMessage()); }
        return text;
    }

    private static String section(String title, java.util.function.Supplier<String> body) {
        String b;
        try { b = body.get(); } catch (Exception e) { b = "取不到（" + e.getClass().getSimpleName() + "）"; }
        return "\n*" + title + "*\n" + b + "\n";
    }

    private String tradingLine() {
        Map<String, Object> c = oms.config().data();
        boolean mainnet = Boolean.TRUE.equals(c.get("mainnet"));
        boolean dry = !Boolean.FALSE.equals(c.get("effectiveDryRun"));
        String s = (mainnet ? "正式網" : "測試網") + " · " + (dry ? "Dry-Run 不送單" : "真實送單中");
        if (mainnet && !dry) {
            Object rem = c.get("armRemainingSec");
            s += Boolean.TRUE.equals(c.get("armUnlimited")) ? "（不限時）" : rem instanceof Number n ? String.format("（剩 %.1f 小時）", n.doubleValue() / 3600) : "";
        }
        Object off = c.get("clockOffsetMs");
        if (off instanceof Number n && Math.abs(n.doubleValue()) > 1000) s += " · ⚠️ 對時偏移 " + n + " ms";
        return s;
    }

    private String botsLines() {
        List<Map<String, Object>> bots = strategy.list().data();
        StringBuilder sb = new StringBuilder();
        int running = 0;
        for (Map<String, Object> b : bots) {
            if (!"RUNNING".equals(b.get("status"))) continue;
            running++;
            long sec = ((Number) b.getOrDefault("runningSec", 0)).longValue();
            String dur = sec >= 86400 ? (sec / 86400) + "天" + (sec % 86400 / 3600) + "時" : (sec / 3600) + "時" + (sec % 3600 / 60) + "分";
            String pnl = "—";
            if (b.get("pnl") instanceof Map<?, ?> p && p.get("pnl") instanceof Number n) {
                double pct = p.get("pnlPct") instanceof Number x ? x.doubleValue() : 0;
                Object trades = p.get("trades") == null ? 0 : p.get("trades");
                pnl = String.format("%+.2f USDT（%+.2f%%，%s 筆）", n.doubleValue(), pct * 100, trades);
            }
            sb.append("• ").append(b.get("name")).append("（").append(b.get("id")).append("）").append(b.get("symbol")).append(" · 運行 ").append(dur).append(" · 收益 ").append(pnl).append("\n");
        }
        if (running == 0) sb.append("沒有機器人在運行\n");
        return sb.toString().stripTrailing();
    }

    private String riskLines() {
        Map<String, Object> r = risk.status("main").data();
        @SuppressWarnings("unchecked") Map<String, Object> lim = (Map<String, Object>) r.getOrDefault("limits", Map.of());
        String pnl = r.get("dayPnlPct") instanceof Number n ? String.format("%+.2f%%", n.doubleValue() * 100) : "—";
        String pos = r.get("positionPct") instanceof Number n ? String.format("%.1f%%", n.doubleValue() * 100) : "—";
        return String.format("今日單數 %s / %s · 名目 %s / %s · 權益 %s · 今日損益 %s · 持倉占比 %s（上限 %.0f%%） · 熔斷 %s",
                r.get("ordersToday"), lim.getOrDefault("maxDailyOrders", "∞"), r.get("notionalToday"),
                num(lim.get("maxDailyNotional")) > 0 ? lim.get("maxDailyNotional") : "∞",
                r.get("equity"), pnl, pos, num(lim.get("maxPositionPct")) * 100, Boolean.TRUE.equals(r.get("killSwitch")) ? "已觸發 ⚠️" : "正常");
    }

    private String infraLines() {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> u = oms.uds().data();
        for (Map.Entry<String, Object> e : u.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("expected"))) continue;
            sb.append(e.getKey()).append(" UDS ").append(Boolean.TRUE.equals(m.get("connected")) ? "即時" : "未連線 ⚠️").append("（").append(m.get("events")).append(" 筆事件）· ");
        }
        if (sb.length() == 0) sb.append("UDS 未啟用（30 秒輪詢）· ");
        Object groups = lag.status().get("groups");
        long worst = 0; String worstName = "";
        if (groups instanceof List<?> gs) for (Object g : gs) if (g instanceof Map<?, ?> m && m.get("lag") instanceof Number n && n.longValue() > worst) { worst = n.longValue(); worstName = String.valueOf(m.get("group")); }
        sb.append("Kafka 最大 lag ").append(worst).append(worst > 0 ? "（" + worstName + "）" : "");
        return sb.toString();
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
}
