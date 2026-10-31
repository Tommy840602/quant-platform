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

    public AccountController(PositionStore positions, EquityCurveService equity,
                            FundingService funding, SseHub sseHub, BinanceAccountService binanceAccount) {
        this.positions = positions; this.equity = equity; this.funding = funding; this.sseHub = sseHub;
        this.binanceAccount = binanceAccount;
    }

    @GetMapping("/balance")
    public ApiResponse<Map<String, Object>> balance(@RequestParam(defaultValue = "main") String account) {
        return ApiResponse.ok(binanceAccount.balance());
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
    public ApiResponse<Map<String, Object>> getConfig() { return ApiResponse.ok(binanceAccount.configStatus()); }

    /** 套用連線設定：POST /api/account/config { env, apiKey, apiSecret } */
    @PostMapping("/config")
    public ApiResponse<Map<String, Object>> setConfig(@RequestBody Map<String, String> body) {
        binanceAccount.applyConfig(body.get("env"), body.get("apiKey"), body.get("apiSecret"));
        return ApiResponse.ok(binanceAccount.configStatus());
    }
}
