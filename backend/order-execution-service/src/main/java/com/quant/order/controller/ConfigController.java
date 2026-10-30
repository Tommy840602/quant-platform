package com.quant.order.controller;

import com.quant.common.dto.ApiResponse;
import com.quant.order.service.BinanceOrderGateway;
import com.quant.order.service.UdsRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 下單連線設定：env(testnet/mainnet)、dry-run、現貨金鑰（登入頁推送）。 */
@RestController
@RequestMapping("/config")
public class ConfigController {

    private final BinanceOrderGateway gateway;
    private final UdsRegistry uds;
    public ConfigController(BinanceOrderGateway gateway, UdsRegistry uds) { this.gateway = gateway; this.uds = uds; }

    /** User Data Stream 連線狀態：GET /api/order/config/uds（expected=應連、connected=真的連著）。 */
    @GetMapping("/uds")
    public ApiResponse<Map<String, Object>> uds() { return ApiResponse.ok(uds.status()); }

    /** 目前設定：GET /api/order/config */
    @GetMapping
    public ApiResponse<Map<String, Object>> get() { return ApiResponse.ok(gateway.configStatus()); }

    /** 套用設定：POST /api/order/config { env, dryRun, apiKey, apiSecret } */
    @PostMapping
    public ApiResponse<Map<String, Object>> set(@RequestBody Map<String, Object> body) {
        String env = body.get("env") == null ? null : String.valueOf(body.get("env"));
        Boolean dry = body.get("dryRun") == null ? null : Boolean.valueOf(String.valueOf(body.get("dryRun")));
        String key = body.get("apiKey") == null ? null : String.valueOf(body.get("apiKey"));
        String sec = body.get("apiSecret") == null ? null : String.valueOf(body.get("apiSecret"));
        return ApiResponse.ok(gateway.applyConfig(env, dry, key, sec));
    }
}
