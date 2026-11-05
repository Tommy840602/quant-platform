package com.quant.account.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 總資產 = 現貨錢包 + USDT-M 合約保證金餘額（錢包 + 未實現盈虧）。
 * 風控（日虧損、集中度、合約曝險）與回撤熔斷都讀這裡的 totalUsdt；
 * 只有現貨是真實帳戶時才合併（示意餘額不跟真合約混算）。
 */
@Service
public class WalletService {
    private final BinanceAccountService spot;
    private final FuturesAccountService futures;

    public WalletService(BinanceAccountService spot, FuturesAccountService futures) { this.spot = spot; this.futures = futures; }

    public Map<String, Object> balance() { return combine(spot.balance(), futures, spot::prices); }

    static Map<String, Object> combine(Map<String, Object> spotBal, FuturesAccountService futures) { return combine(spotBal, futures, Map::of); }

    static Map<String, Object> combine(Map<String, Object> spotBal, FuturesAccountService futures,
                                       java.util.function.Supplier<Map<String, Double>> prices) {
        Map<String, Object> out = new LinkedHashMap<>(spotBal);
        double spotTotal = spotBal.get("totalUsdt") instanceof Number n ? n.doubleValue() : 0;
        out.put("spotUsdt", spotTotal);
        if (!Boolean.TRUE.equals(spotBal.get("real")) || !futures.configured()) return out;
        var snap = futures.snapshot();
        if (snap.isPresent()) {
            var s = snap.get();
            out.put("futures", FuturesAccountService.toMap(s, futures.stale()));
            double total = Math.round((spotTotal + s.marginBalance()) * 100.0) / 100.0;
            out.put("totalUsdt", total);
            if (s.pm() && s.pmAssets() != null && !s.pmAssets().isEmpty()) out.put("balances", withPmAssets(spotBal.get("balances"), s.pmAssets(), prices.get(), total));
        } else {
            // 設了合約金鑰卻拿不到：總額不完整，回撤 / 日虧損不能拿它比（會誤判成大跌）
            out.put("partial", true);
            out.put("futuresError", futures.lastError() == null ? "合約帳戶查詢失敗" : futures.lastError());
        }
        return out;
    }

    /** 把統一帳戶裡的持幣併進資產明細（標 wallet=PM），重算佔比。 */
    @SuppressWarnings("unchecked")
    static java.util.List<Map<String, Object>> withPmAssets(Object spotRows, java.util.List<Map<String, Object>> pm, Map<String, Double> px, double total) {
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        if (spotRows instanceof java.util.List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) { Map<String, Object> r = new LinkedHashMap<>((Map<String, Object>) m); r.putIfAbsent("wallet", "SPOT"); rows.add(r); }
        for (Map<String, Object> a : pm) {
            String asset = String.valueOf(a.get("asset"));
            double qty = ((Number) a.get("free")).doubleValue() + ((Number) a.get("locked")).doubleValue();
            double price = BinanceAccountService.STABLE_SET.contains(asset) ? 1.0 : px.getOrDefault(asset + "USDT", 0.0);
            double value = qty * price;
            if (value < 1.0) continue;
            Map<String, Object> r = new LinkedHashMap<>(a);
            r.put("price", Math.round(price * 100.0) / 100.0); r.put("valueUsdt", Math.round(value * 100.0) / 100.0); r.put("wallet", "PM");
            rows.add(r);
        }
        for (Map<String, Object> r : rows)
            r.put("weight", total > 0 ? Math.round(((Number) r.get("valueUsdt")).doubleValue() / total * 10000.0) / 100.0 : 0.0);
        rows.sort((x, y) -> Double.compare(((Number) y.get("valueUsdt")).doubleValue(), ((Number) x.get("valueUsdt")).doubleValue()));
        return rows;
    }
}
