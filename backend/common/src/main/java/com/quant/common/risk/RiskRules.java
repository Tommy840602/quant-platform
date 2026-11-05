package com.quant.common.risk;

import java.util.*;

/**
 * 機器人風控規則（純函式，無狀態）：出場價位、盤勢過濾、波動率目標下單量、參數預設值。
 * 實盤與回測共用 —— 改這裡兩邊同時生效，不會出現「回測跟實盤是兩套規則」。
 */
public final class RiskRules {
    private RiskRules() {}

    public static final double FALLBACK_STOP_PCT = 2.0;   // ATR 還沒算出來時的停損 %
    public static final double ADX_TREND = 25, ADX_RANGE = 20, HIGH_VOL_RATIO = 2.5;

    /** 風控參數鍵與預設值（0 = 關閉該項） */
    public static final Map<String, Double> DEFAULTS;
    static {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("stopAtr", 2.0);          // 停損 = 進場價 − k × ATR
        m.put("trailAtr", 3.0);         // 移動停利 = 進場後最高價 − k × ATR（獲利後才啟動）
        m.put("takeProfitPct", 0.0);    // 固定停利 %
        m.put("maxPositions", 3.0);     // 同時持倉上限（ALL 標的機器人才有意義）
        m.put("maxConsecLosses", 3.0);  // 連虧幾筆暫停
        m.put("maxLossUsdt", 3.0);      // 本次上線虧損上限（USDT）
        m.put("cooldownBars", 5.0);     // 出場後冷卻幾根收盤 K
        m.put("regimeFilter", 1.0);     // 1 = 依盤勢過濾進場
        m.put("blockHighVol", 1.0);     // 1 = 異常高波動時不進場
        m.put("riskPerTradePct", 0.0);  // 每筆最多虧權益 N%（以停損距離反推數量）；0 = 用每單名目
        m.put("sizeCapPct", 25.0);      // 單筆名目上限 = 權益 N%
        m.put("execMaker", 0.0);        // 1 = 限價優先：策略訊號先掛買一 / 賣一價，等 makerWaitSec 秒沒成交再撤單改市價（風控出場一律市價）
        m.put("makerWaitSec", 10.0);
        m.put("allowShort", 1.0);       // 合約機器人：1 = 也做空（反向價格分身出訊號）
        m.put("maxFundingPct", 0.05);   // 合約：資金費率對開倉方向不利超過 N%（每 8 小時）就不開；0 = 不看
        m.put("leverage", 1.0);         // 合約槓桿（第一次下單前設定；1 = 部位大小就是名目，幾乎不會被強平）
        DEFAULTS = Collections.unmodifiableMap(m);
    }
    public static boolean isRiskKey(String k) { return DEFAULTS.containsKey(k); }

    /** 從參數 map 取值（數字或字串），沒有就用預設。 */
    public static double param(Map<String, Object> p, String key) {
        Object v = p == null ? null : p.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try { return Double.parseDouble(s); } catch (NumberFormatException ignore) {}
        return DEFAULTS.getOrDefault(key, 0.0);
    }

    // ── 盤勢 ──
    public enum Regime { TREND, RANGE, MIXED, UNKNOWN }
    public static final Set<String> TREND_TYPES = Set.of("ma", "ema", "wma", "tema", "macd", "trix", "adx", "supertrend", "sar",
            "ichimoku", "donchian", "bollbreak", "keltner", "atr", "volbreak", "dualthrust", "momentum", "roc", "aroon", "heikin");
    public static final Set<String> MEANREV_TYPES = Set.of("rsi", "grid", "cci", "williams", "stochrsi", "boll", "kdj", "zscore", "vwap");
    /** 策略家族：trend / meanrev / any */
    public static String family(String type) {
        String t = type == null ? "" : type.toLowerCase();
        return TREND_TYPES.contains(t) ? "trend" : MEANREV_TYPES.contains(t) ? "meanrev" : "any";
    }
    public static Regime regime(double adx) {
        if (adx < 0) return Regime.UNKNOWN;
        return adx >= ADX_TREND ? Regime.TREND : adx <= ADX_RANGE ? Regime.RANGE : Regime.MIXED;
    }

    /** 盤勢不適合進場時回原因，否則 null。 */
    public static String regimeBlock(String strategyType, BarStats s, Map<String, Object> p) {
        double vr = s.volRatio();
        if (param(p, "blockHighVol") > 0 && vr >= HIGH_VOL_RATIO) return String.format("異常高波動（%.1f×），暫不進場", vr);
        if (param(p, "regimeFilter") <= 0) return null;
        String fam = family(strategyType);
        double adx = s.adx();
        Regime r = regime(adx);
        if ("trend".equals(fam) && r == Regime.RANGE) return "盤整盤（ADX " + Math.round(adx) + "），趨勢策略不進場";
        if ("meanrev".equals(fam) && r == Regime.TREND) return "趨勢盤（ADX " + Math.round(adx) + "），均值回歸策略不進場";
        return null;
    }

    // ── 出場價位 ──
    public static double stopPrice(double entry, double atr, Map<String, Object> p) {
        double k = param(p, "stopAtr");
        if (k <= 0 || entry <= 0) return 0;
        return atr > 0 ? entry - k * atr : entry * (1 - FALLBACK_STOP_PCT / 100);
    }
    /** 移動停利價；只在已有獲利（價位高於進場價）時才有效，否則 0。 */
    public static double trailPrice(double entry, double high, double atr, Map<String, Object> p) {
        double k = param(p, "trailAtr");
        if (k <= 0 || atr <= 0 || high <= entry) return 0;
        double t = high - k * atr;
        return t > entry ? t : 0;
    }
    public static double takeProfitPrice(double entry, Map<String, Object> p) {
        double tp = param(p, "takeProfitPct");
        return tp > 0 && entry > 0 ? entry * (1 + tp / 100) : 0;
    }
    /** 以「現價」判斷（實盤逐 tick）。回原因或 null。 */
    public static String exitReason(double px, double entry, double high, double atr, Map<String, Object> p) {
        double stop = stopPrice(entry, atr, p);
        if (stop > 0 && px <= stop) return "停損";
        double trail = trailPrice(entry, high, atr, p);
        if (trail > 0 && px <= trail) return "移動停利";
        double tp = takeProfitPrice(entry, p);
        if (tp > 0 && px >= tp) return "停利";
        return null;
    }

    // ── 空單出場（鏡像）：停損在進場價上方、移動停利以進場後最低價為基準、停利在下方 ──
    public static double shortStopPrice(double entry, double atr, Map<String, Object> p) {
        double k = param(p, "stopAtr");
        if (k <= 0 || entry <= 0) return 0;
        return atr > 0 ? entry + k * atr : entry * (1 + FALLBACK_STOP_PCT / 100);
    }
    public static double shortTrailPrice(double entry, double low, double atr, Map<String, Object> p) {
        double k = param(p, "trailAtr");
        if (k <= 0 || atr <= 0 || low <= 0 || low >= entry) return 0;
        double t = low + k * atr;
        return t < entry ? t : 0;
    }
    public static double shortTakeProfitPrice(double entry, Map<String, Object> p) {
        double tp = param(p, "takeProfitPct");
        return tp > 0 && entry > 0 ? entry * (1 - tp / 100) : 0;
    }
    public static String exitReasonShort(double px, double entry, double low, double atr, Map<String, Object> p) {
        double stop = shortStopPrice(entry, atr, p);
        if (stop > 0 && px >= stop) return "停損";
        double trail = shortTrailPrice(entry, low, atr, p);
        if (trail > 0 && px >= trail) return "移動停利";
        double tp = shortTakeProfitPrice(entry, p);
        if (tp > 0 && px <= tp) return "停利";
        return null;
    }

    /**
     * 資金費率閘門：費率 &gt; 0 是多單付錢給空單。做多遇到費率 &gt; 上限、做空遇到費率 &lt; −上限 → 不開倉。
     * @param rate 小數（0.0001 = 0.01%）
     * @return 擋下的原因；null = 放行
     */
    public static String fundingBlock(boolean isLong, double rate, Map<String, Object> p) {
        double max = param(p, "maxFundingPct");
        if (max <= 0) return null;
        double pct = rate * 100;
        if (isLong && pct > max) return String.format("資金費率 %.4f%% 對多單不利（上限 %.3f%%），不開多", pct, max);
        if (!isLong && pct < -max) return String.format("資金費率 %.4f%% 對空單不利（上限 %.3f%%），不開空", pct, max);
        return null;
    }

    /** 波動率目標下單量；回 0 = 未啟用。 */
    public static double sizeQty(double equity, double px, double atr, Map<String, Object> p) {
        double pct = param(p, "riskPerTradePct");
        if (pct <= 0 || equity <= 0 || px <= 0) return 0;
        double k = param(p, "stopAtr");
        double dist = k > 0 && atr > 0 ? k * atr : px * FALLBACK_STOP_PCT / 100;
        double qty = equity * pct / 100 / dist;
        double cap = param(p, "sizeCapPct");
        if (cap > 0) qty = Math.min(qty, equity * cap / 100 / px);
        return qty;
    }
}
