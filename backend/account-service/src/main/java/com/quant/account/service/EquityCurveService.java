package com.quant.account.service;

import com.quant.account.entity.EquityPoint;
import com.quant.account.entity.PositionEntity;
import com.quant.account.repository.EquityRepository;
import com.quant.account.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 資金曲線 + 回撤熔斷（見架構文件 §10.3、§11）。
 * 定期計算權益、追蹤峰值與回撤；回撤超過閾值即呼叫風控熔斷並告警。
 */
@Service
public class EquityCurveService {

    private static final Logger log = LoggerFactory.getLogger(EquityCurveService.class);
    private static final double BASE_CASH = 10000.0;

    private final PositionRepository positions;
    private final EquityRepository equity;
    private final StringRedisTemplate redis;
    private final RiskClient riskClient;
    private final NotifyClient notifyClient;
    private final BinanceAccountService binanceAccount;
    private final OrderStatsClient orderStats;

    @Value("${risk.max-drawdown:0.1}")   // 10% 回撤熔斷
    private double maxDrawdown;
    @Value("${risk.drawdown-warn:0.05}")  // 5% 回撤預警（未熔斷先提醒）
    private double warnDrawdown;

    private double peak = BASE_CASH;
    private volatile double lastEquity = BASE_CASH;
    private volatile double lastDrawdown = 0;
    private volatile boolean tripped = false;
    private volatile boolean warned = false;

    public EquityCurveService(PositionRepository positions, EquityRepository equity,
                              StringRedisTemplate redis, RiskClient riskClient,
                              NotifyClient notifyClient, BinanceAccountService binanceAccount,
                              OrderStatsClient orderStats) {
        this.positions = positions; this.equity = equity; this.redis = redis; this.riskClient = riskClient;
        this.notifyClient = notifyClient; this.binanceAccount = binanceAccount; this.orderStats = orderStats;
    }

    private void notify(String text) {
        try { notifyClient.send(Map.of("text", text)); }
        catch (Exception e) { log.debug("通知發送失敗（忽略）: {}", e.getMessage()); }
    }

    @Scheduled(fixedRate = 30000)
    public void snapshot() {
        try {
            double eq = BASE_CASH;
            for (PositionEntity p : positions.findAll()) {
                String px = redis.opsForValue().get("price:" + p.getSymbol());
                if (px != null && p.getQuantity() != null) eq += p.getQuantity() * Double.parseDouble(px);
            }
            EquityPoint pt = new EquityPoint();
            pt.setEquity(Math.round(eq * 100.0) / 100.0);
            equity.save(pt);

            // 回撤監控
            lastEquity = pt.getEquity();
            peak = Math.max(peak, eq);
            lastDrawdown = peak > 0 ? (eq / peak - 1) : 0;
            // 預警：先於熔斷提醒（每次跌破只提醒一次，回到門檻內重置）
            if (!warned && !tripped && lastDrawdown <= -warnDrawdown && lastDrawdown > -maxDrawdown) {
                warned = true;
                notify(String.format(":warning: 回撤預警：目前回撤 %.2f%%（預警線 %.0f%%，熔斷線 %.0f%%）｜權益 %.2f",
                        lastDrawdown * 100, warnDrawdown * 100, maxDrawdown * 100, lastEquity));
            } else if (lastDrawdown > -warnDrawdown) {
                warned = false;
            }
            if (!tripped && lastDrawdown <= -maxDrawdown) {
                tripped = true;
                String reason = String.format("回撤 %.2f%% 超過閾值 %.0f%%", lastDrawdown * 100, maxDrawdown * 100);
                try { riskClient.killSwitch(true, reason); } catch (Exception ignore) {}
                log.warn("⚠️ 觸發回撤熔斷: {}", reason);
                notify(String.format(":rotating_light: 觸發回撤熔斷！%s ｜ 權益 %.2f ｜ 已停止下單，請至平台手動解除",
                        reason, lastEquity));
            }
        } catch (Exception e) { log.warn("權益快照/回撤監控失敗: {}", e.getMessage()); }
    }

    public List<Map<String, Object>> curve(int limit) {
        List<Map<String, Object>> rows = new ArrayList<>(
                equity.findAllByOrderByIdDesc(PageRequest.of(0, Math.min(limit, 500)))
                        .map(p -> Map.<String, Object>of("ts", p.getTs().toString(), "equity", p.getEquity()))
                        .getContent());
        java.util.Collections.reverse(rows);
        return rows;
    }

    public Map<String, Object> drawdownStatus() {
        return Map.of("peak", Math.round(peak * 100.0) / 100.0,
                "drawdown", Math.round(lastDrawdown * 10000.0) / 10000.0,
                "threshold", -maxDrawdown, "tripped", tripped);
    }

    /** 手動解除熔斷並重置峰值（見架構文件 §10.3）。 */
    public void resetBreaker() {
        tripped = false; warned = false; peak = BASE_CASH;
        try { riskClient.killSwitch(false, "manual reset"); } catch (Exception ignore) {}
        notify(":arrows_counterclockwise: 已手動解除回撤熔斷，恢復下單");
    }

    /** 淨值摘要（預設每天 00/06/12/18 時台北時間；可用 nav.summary-cron 覆蓋）。 */
    @Scheduled(cron = "${nav.summary-cron:0 0 0,6,12,18 * * *}", zone = "Asia/Taipei")
    public void dailySummary() {
        try {
            double total = lastEquity;
            String source = "策略權益";
            try {   // 若已接真實帳戶，優先用真實總資產
                Map<String, Object> bal = binanceAccount.balance();
                if (Boolean.TRUE.equals(bal.get("real")) && bal.get("totalUsdt") != null) {
                    total = ((Number) bal.get("totalUsdt")).doubleValue();
                    source = "真實帳戶";
                }
            } catch (Exception ignore) {}
            String tradeLine = "";
            try {
                Map<String, Object> ds = orderStats.dailyStats().data();
                if (ds != null) {
                    long fills = ((Number) ds.getOrDefault("fills", 0)).longValue();
                    double pnl = ((Number) ds.getOrDefault("realizedPnl", 0)).doubleValue();
                    long rt = ((Number) ds.getOrDefault("roundTrips", 0)).longValue();
                    double wr = ((Number) ds.getOrDefault("winRate", 0)).doubleValue();
                    String pnlEmoji = pnl > 0 ? ":chart_with_upwards_trend:" : pnl < 0 ? ":chart_with_downwards_trend:" : ":heavy_minus_sign:";
                    tradeLine = String.format("\n%s 今日成交 %d 筆 ｜ 平倉 %d 筆(勝率 %.0f%%) ｜ 已實現盈虧 %+.2f USDT",
                            pnlEmoji, fills, rt, wr * 100, pnl);
                }
            } catch (Exception e) { log.debug("取當日成交統計失敗（忽略）: {}", e.getMessage()); }
            notify(String.format(":bar_chart: 每日淨值摘要 ｜ %s 總值 %.2f USDT ｜ 峰值 %.2f ｜ 目前回撤 %.2f%% ｜ 熔斷 %s%s",
                    source, total, peak, lastDrawdown * 100, tripped ? "是" : "否", tradeLine));
        } catch (Exception e) { log.warn("每日摘要失敗: {}", e.getMessage()); }
    }
}
