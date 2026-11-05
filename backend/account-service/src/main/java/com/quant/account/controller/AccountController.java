package com.quant.account.controller;

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
    private final WalletService wallet;
    private final FuturesAccountService futures;
    private final IpWatch ipWatch;
    private final PmGuard pmGuard;

    public AccountController(PositionStore positions, EquityCurveService equity,
                            FundingService funding, SseHub sseHub, BinanceAccountService binanceAccount,
                            WalletService wallet, FuturesAccountService futures, IpWatch ipWatch, PmGuard pmGuard) {
        this.positions = positions; this.equity = equity; this.funding = funding; this.sseHub = sseHub;
        this.binanceAccount = binanceAccount; this.wallet = wallet; this.futures = futures;
        this.ipWatch = ipWatch; this.pmGuard = pmGuard;
    }

    @GetMapping("/balance")
    public ApiResponse<Map<String, Object>> balance(@RequestParam(defaultValue = "main") String account) {
        Map<String, Object> b = new java.util.LinkedHashMap<>(wallet.balance());   // 現貨 + 合約
        if (b.get("futures") instanceof Map<?, ?> f && Boolean.TRUE.equals(f.get("pm"))) {
            Map<String, Object> fm = new java.util.LinkedHashMap<>();
            f.forEach((k, v) -> fm.put(String.valueOf(k), v));
            fm.put("guard", pmGuard.status());
            b.put("futures", fm);
        }
        return ApiResponse.ok(b);
    }

    @GetMapping("/positions")
    public ApiResponse<List<Map<String, Object>>> positions(@RequestParam(required = false) String account) {
        return ApiResponse.ok(positions.snapshot(account));
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
    public ApiResponse<Map<String, Object>> getConfig() { return ApiResponse.ok(configStatus()); }

    private Map<String, Object> configStatus() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(binanceAccount.configStatus());
        m.put("futuresKeySet", futures.keySet()); m.put("futuresEnabled", futures.enabled());
        m.put("futuresPm", futures.pm());
        m.put("publicIp", ipWatch.current());
        m.put("pmGuard", pmGuard.status());
        return m;
    }

    /** 套用連線設定：POST /api/account/config { env, apiKey, apiSecret } */
    @PostMapping("/config")
    public ApiResponse<Map<String, Object>> setConfig(@RequestBody Map<String, String> body) {   // futuresEnabled 由下單服務寫 Redis
        boolean envChanged = binanceAccount.applyConfig(body.get("env"), body.get("apiKey"), body.get("apiSecret"));
        futures.applyConfig(body.get("futuresApiKey"), body.get("futuresApiSecret"));   // 套到（新的）目前 env
        if (envChanged) equity.rebaseForEnvSwitch(String.valueOf(body.get("env")));
        return ApiResponse.ok(configStatus());
    }
}
