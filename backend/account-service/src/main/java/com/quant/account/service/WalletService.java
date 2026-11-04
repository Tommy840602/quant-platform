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

    public Map<String, Object> balance() { return combine(spot.balance(), futures); }

    static Map<String, Object> combine(Map<String, Object> spotBal, FuturesAccountService futures) {
        Map<String, Object> out = new LinkedHashMap<>(spotBal);
        double spotTotal = spotBal.get("totalUsdt") instanceof Number n ? n.doubleValue() : 0;
        out.put("spotUsdt", spotTotal);
        if (!Boolean.TRUE.equals(spotBal.get("real")) || !futures.configured()) return out;
        var snap = futures.snapshot();
        if (snap.isPresent()) {
            var s = snap.get();
            out.put("futures", FuturesAccountService.toMap(s, futures.stale()));
            out.put("totalUsdt", Math.round((spotTotal + s.marginBalance()) * 100.0) / 100.0);
        } else {
            // 設了合約金鑰卻拿不到：總額不完整，回撤 / 日虧損不能拿它比（會誤判成大跌）
            out.put("partial", true);
            out.put("futuresError", futures.lastError() == null ? "合約帳戶查詢失敗" : futures.lastError());
        }
        return out;
    }
}
