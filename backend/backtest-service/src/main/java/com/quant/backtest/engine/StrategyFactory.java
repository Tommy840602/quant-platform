package com.quant.backtest.engine;

import com.quant.common.strategy.*;

import java.util.Map;

/** 依類型建立策略供回測（與實盤共用 common.strategy）。type: ma|ema|macd|boll|kdj|rsi|grid。 */
public final class StrategyFactory {
    private StrategyFactory() {}

    public static Strategy build(String type, String id, Map<String, Object> p) {
        double qty = num(p, "qty", 0.001);
        return switch (type == null ? "ma" : type.toLowerCase()) {
            case "cci"      -> new CciStrategy(id, (int) num(p, "period", 20), qty);
            case "williams" -> new WilliamsRStrategy(id, (int) num(p, "period", 14), qty);
            case "donchian" -> new DonchianStrategy(id, (int) num(p, "period", 20), qty);
            case "momentum" -> new MomentumStrategy(id, (int) num(p, "period", 10), num(p, "threshold", 0.002), qty);
            case "atr"      -> new AtrChannelStrategy(id, (int) num(p, "period", 14), num(p, "mult", 2), qty);
            case "supertrend" -> new SuperTrendStrategy(id, (int) num(p, "period", 10), num(p, "mult", 3), qty);
            case "ichimoku" -> new IchimokuStrategy(id, qty);
            case "sar" -> new SarStrategy(id, num(p, "step", 0.02), num(p, "max", 0.2), qty);
            case "vwap" -> new VwapStrategy(id, (int) num(p, "period", 20), num(p, "dev", 0.003), qty);
            case "stochrsi" -> new StochRsiStrategy(id, (int) num(p, "period", 14), qty);
            case "trix" -> new TrixStrategy(id, (int) num(p, "period", 15), qty);
            case "adx" -> new AdxStrategy(id, (int) num(p, "period", 14), qty);
            case "keltner" -> new KeltnerStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty);
            case "bollbreak" -> new BollBreakStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty);
            case "dualthrust" -> new DualThrustStrategy(id, (int) num(p, "period", 5), num(p, "k", 0.5), qty);
            case "roc" -> new RocStrategy(id, (int) num(p, "period", 12), num(p, "threshold", 0.003), qty);
            case "aroon" -> new AroonStrategy(id, (int) num(p, "period", 25), qty);
            case "cmf" -> new CmfStrategy(id, (int) num(p, "period", 20), qty);
            case "zscore" -> new ZScoreStrategy(id, (int) num(p, "period", 20), num(p, "threshold", 2), qty);
            case "volbreak" -> new VolBreakStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty);
            case "tema" -> new TemaStrategy(id, (int) num(p, "fast", 10), (int) num(p, "slow", 30), qty);
            case "heikin" -> new HeikinAshiStrategy(id, qty);
            case "wma" -> new WmaStrategy(id, (int) num(p, "fast", 10), (int) num(p, "slow", 30), qty);
            case "ema"  -> new EmaStrategy(id, (int) num(p, "fast", 12), (int) num(p, "slow", 26), qty);
            case "macd" -> new MacdStrategy(id, (int) num(p, "fast", 12), (int) num(p, "slow", 26), (int) num(p, "signal", 9), qty);
            case "boll" -> new BollStrategy(id, (int) num(p, "period", 20), num(p, "mult", 2), qty);
            case "kdj"  -> new KdjStrategy(id, (int) num(p, "period", 9), qty);
            case "rsi"  -> new RsiStrategy(id, (int) num(p, "window", 14), num(p, "oversold", 30), num(p, "overbought", 70), qty);
            case "grid" -> new GridStrategy(id, num(p, "gridPct", 0.005), qty);
            default     -> new MovingAverageStrategy(id, (int) num(p, "fast", 3), (int) num(p, "slow", 8), qty);
        };
    }
    private static double num(Map<String, Object> p, String k, double def) {
        Object v = p == null ? null : p.get(k);
        return v == null ? def : ((Number) v).doubleValue();
    }
}
