package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** kline 超表存取（JdbcTemplate 批次 upsert；TimescaleDB 用 JPA 不划算）。 */
@Repository
public class KlineRepository {

    private static final String UPSERT = """
        INSERT INTO kline (symbol, interval, open_time, open, high, low, close, volume)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (symbol, interval, open_time) DO UPDATE SET
          open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
          close = EXCLUDED.close, volume = EXCLUDED.volume
        """;

    private final JdbcTemplate jdbc;

    public KlineRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 批次 upsert，回寫入筆數。 */
    public int upsertAll(List<Kline> rows) {
        if (rows.isEmpty()) return 0;
        int[][] r = jdbc.batchUpdate(UPSERT, rows, 500, (PreparedStatement ps, Kline k) -> {
            ps.setString(1, k.symbol());
            ps.setString(2, k.interval());
            ps.setTimestamp(3, new Timestamp(k.openTime()));
            ps.setDouble(4, k.open()); ps.setDouble(5, k.high()); ps.setDouble(6, k.low());
            ps.setDouble(7, k.close()); ps.setDouble(8, k.volume());
        });
        int n = 0;
        for (int[] batch : r) for (int x : batch) n += (x >= 0 ? x : 1);
        return n;
    }

    /** 最新 limit 根，依時間升冪回傳。 */
    public List<Kline> latest(String symbol, String interval, int limit) {
        List<Kline> rows = jdbc.query("""
            SELECT symbol, interval, (extract(epoch FROM open_time) * 1000)::bigint AS open_ms,
                   open, high, low, close, volume
            FROM kline WHERE symbol = ? AND interval = ?
            ORDER BY open_time DESC LIMIT ?
            """, (rs, i) -> toKline(rs.getString("symbol"), rs.getString("interval"), rs.getLong("open_ms"),
                        rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"), rs.getDouble("volume")),
                symbol, interval, limit);
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** 區間查詢 [fromMs, toMs]，升冪。 */
    public List<Kline> range(String symbol, String interval, long fromMs, long toMs, int limit) {
        return jdbc.query("""
            SELECT symbol, interval, (extract(epoch FROM open_time) * 1000)::bigint AS open_ms,
                   open, high, low, close, volume
            FROM kline WHERE symbol = ? AND interval = ? AND open_time >= ? AND open_time <= ?
            ORDER BY open_time ASC LIMIT ?
            """, (rs, i) -> toKline(rs.getString("symbol"), rs.getString("interval"), rs.getLong("open_ms"),
                        rs.getDouble("open"), rs.getDouble("high"), rs.getDouble("low"), rs.getDouble("close"), rs.getDouble("volume")),
                symbol, interval, new Timestamp(fromMs), new Timestamp(toMs), limit);
    }

    /** 最後一根的 openTime（毫秒）；沒有回 null。 */
    public Long latestOpenTime(String symbol, String interval) {
        List<Long> r = jdbc.query("SELECT (extract(epoch FROM max(open_time)) * 1000)::bigint FROM kline WHERE symbol = ? AND interval = ?",
                (rs, i) -> { long v = rs.getLong(1); return rs.wasNull() ? null : v; }, symbol, interval);
        return r.isEmpty() ? null : r.get(0);
    }

    public long count(String symbol, String interval) {
        Long c = jdbc.queryForObject("SELECT count(*) FROM kline WHERE symbol = ? AND interval = ?", Long.class, symbol, interval);
        return c == null ? 0 : c;
    }

    /** 每個 symbol|interval 的筆數與最後時間（監控用）。 */
    public List<Map<String, Object>> coverage() {
        return jdbc.queryForList("""
            SELECT symbol, interval, count(*) AS bars,
                   (extract(epoch FROM min(open_time)) * 1000)::bigint AS first_ms,
                   (extract(epoch FROM max(open_time)) * 1000)::bigint AS last_ms
            FROM kline GROUP BY symbol, interval ORDER BY symbol, interval
            """);
    }

    static Kline toKline(String symbol, String interval, long openMs, double o, double h, double l, double c, double v) {
        return new Kline(symbol, interval, openMs, o, h, l, c, v, openMs + Intervals.millis(interval) - 1, true);
    }
}
