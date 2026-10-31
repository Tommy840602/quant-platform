package com.quant.backtest.service;

import com.quant.common.dto.Kline;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 回測資料來源。只有兩種：真實 K 線（market-data）或呼叫端明確提供的 closes（O=H=L=C，標記在 dataSource）。
 * 不再有「拿不到就隨機生一段」—— 隨機序列跑出來的績效與一致性分數沒有任何意義。
 */
@Service
public class BarSource {

    public static final int MIN_BARS = 10;

    public record Bars(List<Kline> bars, String dataSource) {}

    public static class NoDataException extends RuntimeException {
        public NoDataException(String m) { super(m); }
    }

    private final MarketClient market;

    public BarSource(MarketClient market) { this.market = market; }

    /** 最新 n 根。 */
    public Bars latest(String symbol, String interval, int n) {
        List<Kline> bars;
        try { bars = market.klines(symbol, interval, n).data(); }
        catch (Exception e) { throw new NoDataException("取不到 " + symbol + " " + interval + " K 線（market-data-service 未啟動？）: " + e.getMessage()); }
        return check(bars, symbol, interval, "market-data:latest");
    }

    /** 區間。 */
    public Bars range(String symbol, String interval, long from, long to) {
        List<Kline> bars;
        try { bars = market.range(symbol, interval, from, to, 50_000).data(); }
        catch (Exception e) { throw new NoDataException("取不到 " + symbol + " " + interval + " 區間 K 線: " + e.getMessage()); }
        return check(bars, symbol, interval, "market-data:range");
    }

    /** 呼叫端自帶收盤價：O=H=L=C，只有 close 可信；依賴 high/low 的策略（ATR/Donchian/Keltner…）結果不準，dataSource 會標明。 */
    public static Bars fromCloses(String symbol, String interval, List<Double> closes, long intervalMs) {
        if (closes == null || closes.size() < MIN_BARS)
            throw new NoDataException("closes 至少 " + MIN_BARS + " 筆");
        List<Kline> bars = new ArrayList<>(closes.size());
        long t0 = System.currentTimeMillis() - (long) closes.size() * intervalMs;
        for (int i = 0; i < closes.size(); i++) {
            double c = closes.get(i);
            long t = t0 + i * intervalMs;
            bars.add(new Kline(symbol, interval, t, c, c, c, c, 0, t + intervalMs - 1, true));
        }
        return new Bars(bars, "closes(O=H=L=C)");
    }

    private static Bars check(List<Kline> bars, String symbol, String interval, String source) {
        if (bars == null || bars.size() < MIN_BARS)
            throw new NoDataException(symbol + " " + interval + " 只有 " + (bars == null ? 0 : bars.size())
                    + " 根（至少 " + MIN_BARS + "）—— market-data 暖機中或此幣未訂閱；可 POST /api/market/klines/warmup");
        // 只用已收盤的；最後一根若未收盤會讓最後一筆權益跳動
        List<Kline> closed = bars.stream().filter(Kline::closed).toList();
        return new Bars(closed.size() >= MIN_BARS ? closed : bars, source);
    }
}
