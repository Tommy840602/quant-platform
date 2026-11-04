package com.quant.strategy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.enums.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 決策日誌（V8 strategy_decisions）：每個訊號的去向 + 當下盤勢（ADX / ATR / 波動倍數 / 排名分數）。
 * 非同步批次寫入（每 2 秒），不拖慢行情處理；同一 (機器人, 標的, 結果) 55 秒內只記一次。
 */
@Service
public class DecisionLog implements ShadowEngine.DecisionSink {

    private static final Logger log = LoggerFactory.getLogger(DecisionLog.class);
    private static final ObjectMapper M = new ObjectMapper();
    static final long DEDUPE_MS = 55_000;
    static final int MAX_QUEUE = 20_000;

    record Row(long ts, String bot, String symbol, String side, double price, boolean acted, String outcome, String detail) {}

    private final JdbcTemplate jdbc;
    private final BotRiskManager risk;
    private final Queue<Row> queue = new ConcurrentLinkedQueue<>();
    private final Map<String, Long> recent = new ConcurrentHashMap<>();

    public DecisionLog(JdbcTemplate jdbc, BotRiskManager risk) { this.jdbc = jdbc; this.risk = risk; }

    /** 這個結果是否真的動了部位（送單、模擬成交、出場）。 */
    static boolean acted(String outcome) {
        return outcome != null && (outcome.contains("已送單") || outcome.contains("模擬成交"));
    }

    @Override
    public void record(String bot, String symbol, OrderSide side, double px, String outcome) {
        if (outcome == null || outcome.isBlank() || outcome.startsWith("排名中")) return;
        long now = System.currentTimeMillis();
        String key = bot + "|" + symbol + "|" + outcome;
        Long last = recent.get(key);
        if (last != null && now - last < DEDUPE_MS) return;
        recent.put(key, now);
        if (queue.size() >= MAX_QUEUE) return;   // DB 掛了也不讓記憶體爆
        Map<String, Object> d = new LinkedHashMap<>();
        try {
            var st = risk.stats(bot, symbol);   // 該機器人自己週期的盤勢
            d.put("regime", st.regime().name());
            double a = st.adx(); if (a >= 0) d.put("adx", Math.round(a * 10) / 10.0);
            d.put("atr", st.atr());
            d.put("volRatio", Math.round(st.volRatio() * 100) / 100.0);
            d.put("score", Math.round(st.score() * 100) / 100.0);
        } catch (Exception ignore) {}
        String detail;
        try { detail = M.writeValueAsString(d); } catch (Exception e) { detail = "{}"; }
        queue.add(new Row(now, bot, symbol, side == null ? null : side.name(), px, acted(outcome), outcome, detail));
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 2_000)
    public void flush() {
        if (queue.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        Row r;
        while (batch.size() < 500 && (r = queue.poll()) != null)
            batch.add(new Object[]{new Timestamp(r.ts()), r.bot(), r.symbol(), r.side(), r.price(), r.acted(), r.outcome(), r.detail()});
        try {
            jdbc.batchUpdate("INSERT INTO strategy_decisions (ts, bot_id, symbol, side, price, acted, outcome, detail) VALUES (?,?,?,?,?,?,?,?::jsonb)", batch);
        } catch (Exception e) { log.warn("決策日誌寫入失敗（{} 筆丟棄）: {}", batch.size(), e.getMessage()); }
        long cut = System.currentTimeMillis() - DEDUPE_MS;
        recent.values().removeIf(t -> t < cut);
    }

    /** 每天清掉 30 天前的紀錄。 */
    @Scheduled(cron = "0 17 4 * * *")
    public void retention() {
        try { int n = jdbc.update("DELETE FROM strategy_decisions WHERE ts < now() - interval '30 days'"); if (n > 0) log.info("決策日誌清除 {} 筆", n); }
        catch (Exception e) { log.warn("決策日誌清除失敗: {}", e.getMessage()); }
    }

    public List<Map<String, Object>> query(String bot, String symbol, Long fromMs, Long toMs, Boolean actedOnly, int limit) {
        StringBuilder sql = new StringBuilder("SELECT (extract(epoch FROM ts) * 1000)::bigint AS ts, bot_id, symbol, side, price, acted, outcome, detail::text AS detail FROM strategy_decisions WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (bot != null && !bot.isBlank()) { sql.append(" AND bot_id = ?"); args.add(bot); }
        if (symbol != null && !symbol.isBlank()) { sql.append(" AND symbol = ?"); args.add(symbol.toUpperCase()); }
        if (fromMs != null) { sql.append(" AND ts >= ?"); args.add(new Timestamp(fromMs)); }
        if (toMs != null) { sql.append(" AND ts <= ?"); args.add(new Timestamp(toMs)); }
        if (Boolean.TRUE.equals(actedOnly)) sql.append(" AND acted");
        sql.append(" ORDER BY ts DESC LIMIT ?"); args.add(Math.max(1, Math.min(limit, 2000)));
        return jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", rs.getLong("ts")); m.put("botId", rs.getString("bot_id")); m.put("symbol", rs.getString("symbol"));
            m.put("side", rs.getString("side")); m.put("price", rs.getDouble("price")); m.put("acted", rs.getBoolean("acted"));
            m.put("outcome", rs.getString("outcome"));
            try { m.put("detail", M.readValue(rs.getString("detail"), Map.class)); } catch (Exception e) { m.put("detail", Map.of()); }
            return m;
        }, args.toArray());
    }
}
