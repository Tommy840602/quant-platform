package com.quant.account.controller;

import com.quant.common.account.Accounts;
import com.quant.common.dto.ApiResponse;
import com.quant.account.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** 帳戶/部位/資金曲線/資金費率/熔斷/SSE（見架構文件 §9.2、§10.3、§11）。 */
@RestController
@RequestMapping("/")
public class AccountController {

    private final PositionStore positions;
    private final EquityCurveService equity;
    private final FundingService funding;
    private final SseHub sseHub;
    private final BinanceAccountService binanceAccount;
    private final AccountModeService mode;
    private final CashLedger cash;
    private final org.springframework.data.redis.core.StringRedisTemplate redis;

    public AccountController(PositionStore positions, EquityCurveService equity,
                            FundingService funding, SseHub sseHub, BinanceAccountService binanceAccount,
                            AccountModeService mode, CashLedger cash, org.springframework.data.redis.core.StringRedisTemplate redis) {
        this.positions = positions; this.equity = equity; this.funding = funding; this.sseHub = sseHub;
        this.binanceAccount = binanceAccount; this.mode = mode; this.cash = cash; this.redis = redis;
    }

    /** 目前帳戶模式：GET /api/account/mode → {mode: paper|main, label, paper, realConfigured, env} */
    @GetMapping("/mode")
    public ApiResponse<Map<String, Object>> getMode() { return ApiResponse.ok(mode.status()); }

    /** 切換：POST /api/account/mode {mode: "paper"|"main"} */
    @PostMapping("/mode")
    public ApiResponse<Map<String, Object>> setMode(@RequestBody Map<String, String> body) {
        try { return ApiResponse.ok(mode.switchTo(body.get("mode"))); }
        catch (IllegalStateException e) { return ApiResponse.error(409, e.getMessage()); }
    }

    /** 重置虛擬帳戶（現金回初始、部位清零）：POST /api/account/paper/reset */
    @PostMapping("/paper/reset")
    public ApiResponse<Map<String, Object>> resetPaper() { return ApiResponse.ok(mode.resetPaper()); }

    /**
     * 餘額：account 省略 = 目前模式。paper → 現金帳 + 部位市值；main → 幣安真實餘額（沒金鑰老實回 configured=false）。
     */
    @GetMapping("/balance")
    public ApiResponse<Map<String, Object>> balance(@RequestParam(required = false) String account) {
        String acct = account == null || account.isBlank() ? mode.current() : Accounts.normalize(account);
        return ApiResponse.ok(Accounts.isPaper(acct) ? paperBalance() : binanceAccount.balance());
    }

    private Map<String, Object> paperBalance() {
        double usdt = cash.get(Accounts.PAPER);
        double posValue = 0;
        List<Map<String, Object>> assets = new java.util.ArrayList<>();
        for (Map<String, Object> p : positions.snapshot(Accounts.PAPER)) {
            double qty = ((Number) p.get("quantity")).doubleValue();
            if (qty == 0) continue;
            String sym = String.valueOf(p.get("symbol"));
            double px = 0;
            try { String v = redis.opsForValue().get("price:" + sym); if (v != null) px = Double.parseDouble(v); } catch (Exception ignore) {}
            double value = qty * px;
            posValue += value;
            Map<String, Object> a = new java.util.LinkedHashMap<>();
            a.put("asset", sym.replace("USDT", "")); a.put("free", qty); a.put("locked", 0.0);
            a.put("price", px); a.put("valueUsdt", Math.round(value * 100.0) / 100.0);
            a.put("avgPrice", p.get("avgPrice"));
            assets.add(a);
        }
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("asset", "USDT"); m.put("free", Math.round(usdt * 100.0) / 100.0); m.put("locked", 0.0);
        m.put("real", false); m.put("configured", true); m.put("account", Accounts.PAPER); m.put("paper", true);
        m.put("totalUsdt", Math.round((usdt + posValue) * 100.0) / 100.0);
        m.put("positionsValueUsdt", Math.round(posValue * 100.0) / 100.0);
        m.put("assetCount", assets.size()); m.put("balances", assets);
        return m;
    }

    /** 部位：account 省略 = 目前模式。 */
    @GetMapping("/positions")
    public ApiResponse<List<Map<String, Object>>> positions(@RequestParam(required = false) String account) {
        String acct = account == null || account.isBlank() ? mode.current() : Accounts.normalize(account);
        return ApiResponse.ok(positions.snapshot(acct));
    }

    @GetMapping("/equity-curve")
    public ApiResponse<List<Map<String, Object>>> equityCurve(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(equity.curve(limit));
    }

    /** 資金費率：GET /api/account/funding?symbol=BTCUSDT */
    @GetMapping("/funding")
    public ApiResponse<Map<String, Object>> funding(@RequestParam String symbol) {
        return ApiResponse.ok(Map.of("symbol", symbol.toUpperCase(), "lastFundingRate", funding.rate(symbol)));
    }

    /** 回撤/熔斷狀態 */
    @GetMapping("/drawdown")
    public ApiResponse<Map<String, Object>> drawdown() { return ApiResponse.ok(equity.drawdownStatus()); }

    /** 手動解除回撤熔斷 */
    @PostMapping("/breaker/reset")
    public ApiResponse<String> resetBreaker() { equity.resetBreaker(); return ApiResponse.ok("reset"); }

    /** SSE 即時推播：GET /api/account/stream */
    @GetMapping("/stream")
    public SseEmitter stream() { return sseHub.register(); }

    /** 手動觸發淨值摘要並推送 Slack：POST /api/account/summary/now */
    @PostMapping("/summary/now")
    public ApiResponse<String> summaryNow() { equity.dailySummary(); return ApiResponse.ok("sent"); }

    /** 目前連線設定：GET /api/account/config */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() { return ApiResponse.ok(binanceAccount.configStatus()); }

    /** 套用連線設定：POST /api/account/config { env, apiKey, apiSecret } */
    @PostMapping("/config")
    public ApiResponse<Map<String, Object>> setConfig(@RequestBody Map<String, String> body) {
        binanceAccount.applyConfig(body.get("env"), body.get("apiKey"), body.get("apiSecret"));
        return ApiResponse.ok(binanceAccount.configStatus());
    }
}
