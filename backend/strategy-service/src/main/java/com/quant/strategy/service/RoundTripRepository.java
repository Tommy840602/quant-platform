package com.quant.strategy.service;

import com.quant.strategy.position.StrategyPositionBook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.*;

/** 來回交易 / 滑價紀錄（V7）。 */
@Repository
public class RoundTripRepository {

    private final JdbcTemplate jdbc;
    public RoundTripRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(StrategyPositionBook.Close c, String mode) {
        jdbc.update("INSERT INTO strategy_round_trips (bot_id, symbol, mode, qty, entry_px, exit_px, pnl, ret) VALUES (?,?,?,?,?,?,?,?)",
                c.strategyId(), c.symbol(), mode, c.qty(), c.entryPx(), c.exitPx(), c.pnl(), c.ret());
    }

    public void insertSlip(String bot, String sym, String side, double ref, double fill, double bps) {
        jdbc.update("INSERT INTO strategy_slippage (bot_id, symbol, side, ref_px, fill_px, bps) VALUES (?,?,?,?,?,?)",
                bot, sym, side, ref, fill, bps);
    }

    /** 統計：n、勝場、總獲利、總虧損、平均報酬、總損益。 */
    public TripStats stats(String bot, String mode, long sinceMs) {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, count(*) FILTER (WHERE pnl > 0) AS wins,
                       coalesce(sum(pnl) FILTER (WHERE pnl > 0), 0) AS gw, coalesce(-sum(pnl) FILTER (WHERE pnl < 0), 0) AS gl,
                       coalesce(avg(ret), 0) AS mean, coalesce(sum(pnl), 0) AS total
                  FROM strategy_round_trips WHERE bot_id = ? AND mode = ? AND closed_at >= ?""",
                (rs, i) -> new TripStats(rs.getInt("n"), rs.getInt("wins"), rs.getDouble("gw"), rs.getDouble("gl"),
                        rs.getDouble("mean"), rs.getDouble("total")),
                bot, mode, new Timestamp(sinceMs));
    }

    /** 最近 limit 筆的統計（降級判斷用）。 */
    public TripStats recentStats(String bot, String mode, int limit) {
        List<Double> pnl = jdbc.query("SELECT pnl FROM strategy_round_trips WHERE bot_id = ? AND mode = ? ORDER BY closed_at DESC LIMIT ?",
                (rs, i) -> rs.getDouble(1), bot, mode, limit);
        List<Double> ret = jdbc.query("SELECT ret FROM strategy_round_trips WHERE bot_id = ? AND mode = ? ORDER BY closed_at DESC LIMIT ?",
                (rs, i) -> rs.getDouble(1), bot, mode, limit);
        return TripStats.of(pnl, ret);
    }

    /** 最近的報酬率（由舊到新），重啟後讓自動配資接續學習。 */
    public List<Double> recentReturns(String bot, Collection<String> modes, int limit) {
        if (modes.isEmpty()) return List.of();
        String in = String.join(",", Collections.nCopies(modes.size(), "?"));
        List<Object> args = new ArrayList<>(); args.add(bot); args.addAll(modes); args.add(limit);
        List<Double> l = jdbc.query("SELECT ret FROM strategy_round_trips WHERE bot_id = ? AND mode IN (" + in + ") ORDER BY closed_at DESC LIMIT ?",
                (rs, i) -> rs.getDouble(1), args.toArray());
        List<Double> out = new ArrayList<>(l); Collections.reverse(out);
        return out;
    }

    /** 從最新往回數，連續虧損幾筆（遇到獲利就停）。 */
    public int trailingLosses(String bot, String mode, long sinceMs) {
        List<Double> pnl = jdbc.query("SELECT pnl FROM strategy_round_trips WHERE bot_id = ? AND mode = ? AND closed_at >= ? ORDER BY closed_at DESC LIMIT 50",
                (rs, i) -> rs.getDouble(1), bot, mode, new Timestamp(sinceMs));
        int n = 0; for (double p : pnl) { if (p < 0) n++; else break; }
        return n;
    }

    /** 最近 limit 筆滑價（由舊到新）。 */
    public List<Double> recentSlips(String bot, int limit) {
        List<Double> l = jdbc.query("SELECT bps FROM strategy_slippage WHERE bot_id = ? ORDER BY ts DESC LIMIT ?", (rs, i) -> rs.getDouble(1), bot, limit);
        List<Double> out = new ArrayList<>(l); Collections.reverse(out); return out;
    }

    /** 各標的近 days 天的平均滑價與筆數（回測成本校正用）：symbol -> [avgBps, n] */
    public Map<String, double[]> slippageBySymbol(int days) {
        Map<String, double[]> m = new LinkedHashMap<>();
        jdbc.query("SELECT symbol, avg(bps), count(*) FROM strategy_slippage WHERE ts >= now() - make_interval(days => ?) GROUP BY symbol ORDER BY symbol",
                rs -> { m.put(rs.getString(1), new double[]{rs.getDouble(2), rs.getDouble(3)}); }, days);
        return m;
    }

    /** [平均 bps, 筆數] */
    public double[] slippage(String bot, long sinceMs) {
        return jdbc.queryForObject("SELECT coalesce(avg(bps), 0), count(*) FROM strategy_slippage WHERE bot_id = ? AND ts >= ?",
                (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2)}, bot, new Timestamp(sinceMs));
    }

    public record TripStats(int n, int wins, double grossWin, double grossLoss, double meanRet, double total) {
        /** 獲利因子；沒有虧損時以 99 表示「無限大」。 */
        public double profitFactor() { return grossLoss > 0 ? grossWin / grossLoss : (grossWin > 0 ? 99 : 0); }
        public static TripStats of(List<Double> pnl, List<Double> ret) {
            int n = pnl.size(), w = 0; double gw = 0, gl = 0, tot = 0;
            for (double p : pnl) { tot += p; if (p > 0) { w++; gw += p; } else if (p < 0) gl -= p; }
            double mean = ret.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return new TripStats(n, w, gw, gl, mean, tot);
        }
    }
}
