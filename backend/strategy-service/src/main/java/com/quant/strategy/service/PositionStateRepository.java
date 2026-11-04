package com.quant.strategy.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 重啟要保住的部位狀態（V9 strategy_position_state）：PAPER / SHADOW 部位、PEAK 最高價。 */
@Repository
public class PositionStateRepository {

    public record Row(String botId, String symbol, String kind, double qty, double entry, double peak) {}

    private final JdbcTemplate jdbc;
    public PositionStateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void upsert(String bot, String sym, String kind, double qty, double entry, double peak) {
        jdbc.update("""
            INSERT INTO strategy_position_state (bot_id, symbol, kind, qty, entry_px, peak, updated_at) VALUES (?,?,?,?,?,?, now())
            ON CONFLICT (bot_id, symbol, kind) DO UPDATE SET qty = EXCLUDED.qty, entry_px = EXCLUDED.entry_px,
              peak = EXCLUDED.peak, updated_at = now()""", bot, sym, kind, qty, entry, peak);
    }
    public void delete(String bot, String sym, String kind) {
        jdbc.update("DELETE FROM strategy_position_state WHERE bot_id = ? AND symbol = ? AND kind = ?", bot, sym, kind);
    }
    public void deleteKind(String bot, String kind) {
        jdbc.update("DELETE FROM strategy_position_state WHERE bot_id = ? AND kind = ?", bot, kind);
    }
    public void deleteBot(String bot) { jdbc.update("DELETE FROM strategy_position_state WHERE bot_id = ?", bot); }
    public List<Row> loadAll() {
        return jdbc.query("SELECT bot_id, symbol, kind, qty, entry_px, peak FROM strategy_position_state",
                (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)));
    }
}
