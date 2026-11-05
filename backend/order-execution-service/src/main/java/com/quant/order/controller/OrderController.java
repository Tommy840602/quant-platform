package com.quant.order.controller;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import com.quant.order.service.OrderService;
import com.quant.order.service.ConditionalOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 訂單 API：下單 / 查單 / 撤單 / 查詢歷史 / 設定槓桿（見架構文件 §9）。 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final ConditionalOrderService conditional;
    public OrderController(OrderService orderService, ConditionalOrderService conditional) {
        this.orderService = orderService; this.conditional = conditional;
    }

    @PostMapping
    public ApiResponse<String> submit(@Valid @RequestBody OrderIntent intent) {
        return orderService.submit(intent);
    }

    /** 歷史訂單（DB）：GET /api/order/orders?limit=50 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(defaultValue = "50") int limit) {
        return orderService.recent(limit);
    }


    /** 盈虧/勝率 KPI：GET /api/order/orders/kpi */
    @GetMapping("/kpi")
    public ApiResponse<Map<String, Object>> kpi() { return orderService.kpi(); }

    /** 每月損益：GET /api/order/orders/monthly?months=12（明細 + 每月合計） */
    @GetMapping("/monthly")
    public ApiResponse<Map<String, Object>> monthly(@RequestParam(defaultValue = "12") int months) {
        var rows = orderService.monthly(Math.min(months, 60));
        return ApiResponse.ok(Map.of("rows", rows.stream().map(com.quant.order.service.MonthlyPnl.Row::toMap).toList(),
                "totals", com.quant.order.service.MonthlyPnl.totals(rows),
                "feeNote", "手續費為估計：現貨 0.1%、合約 0.05% × 成交額"));
    }

    /** 每月損益 CSV：GET /api/order/orders/monthly.csv?months=12 */
    @GetMapping(value = "/monthly.csv", produces = "text/csv;charset=UTF-8")
    public org.springframework.http.ResponseEntity<String> monthlyCsv(@RequestParam(defaultValue = "12") int months) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"pnl-monthly.csv\"")
                .body(com.quant.order.service.MonthlyPnl.csv(orderService.monthly(Math.min(months, 60))));
    }

    /** 當日成交筆數 + 已實現盈虧：GET /api/order/orders/daily-stats */
    @GetMapping("/daily-stats")
    public ApiResponse<Map<String, Object>> dailyStats() { return orderService.dailyStats(); }

    /** 下單風控設定：GET /api/order/orders/risk-config */
    @GetMapping("/risk-config")
    public ApiResponse<Map<String, Object>> riskConfig() { return ApiResponse.ok(orderService.riskConfig()); }

    /** 調整下單風控：POST /api/order/orders/risk-config { maxNotionalUsdt, balanceCheck } */
    @PostMapping("/risk-config")
    public ApiResponse<Map<String, Object>> setRiskConfig(@RequestBody Map<String, Object> b) {
        Double max = b.get("maxNotionalUsdt") == null ? null : Double.valueOf(b.get("maxNotionalUsdt").toString());
        Boolean bc = b.get("balanceCheck") == null ? null : Boolean.valueOf(b.get("balanceCheck").toString());
        try { return ApiResponse.ok(orderService.setRiskConfig(max, bc)); }
        catch (IllegalArgumentException e) { return ApiResponse.error(400, e.getMessage()); }
    }

    /** 建立條件單/智慧單：POST /api/order/orders/conditional */
    @PostMapping("/conditional")
    public ApiResponse<Map<String, Object>> createCond(@RequestBody Map<String, Object> b) {
        return ApiResponse.ok(conditional.create(b));
    }
    @GetMapping("/conditional")
    public ApiResponse<List<Map<String, Object>>> listCond() { return ApiResponse.ok(conditional.list()); }
    @DeleteMapping("/conditional/{id}")
    public ApiResponse<String> cancelCond(@PathVariable long id) { return ApiResponse.ok(conditional.cancel(id)); }

    /** 績效歸因：GET /api/order/orders/attribution */
    /** 各策略有號部位 + 未結單數（strategy-service 啟動對帳用）。 */
    @GetMapping("/strategy-positions")
    public ApiResponse<List<Map<String, Object>>> strategyPositions(@RequestParam(defaultValue = "main") String account) { return orderService.strategyPositions(account); }

    /** 某策略的實盤成交明細：GET /api/order/orders/fills?strategyId=ma-fast */
    @GetMapping("/fills")
    public ApiResponse<List<Map<String, Object>>> fills(@RequestParam String strategyId) {
        return orderService.fillsByStrategy(strategyId);
    }

    /** 單一策略本次上線以來損益：GET /api/order/orders/strategy-pnl?strategyId=&since=ms */
    @GetMapping("/strategy-pnl")
    public ApiResponse<Map<String, Object>> strategyPnl(@RequestParam String strategyId, @RequestParam(defaultValue = "0") long since) {
        return orderService.strategyPnl(strategyId, since);
    }

    @GetMapping("/attribution")
    public ApiResponse<List<Map<String, Object>>> attribution() {
        return orderService.attribution();
    }

    /** OMS 本地紀錄：GET /api/order/orders/local/{clientOrderId} → {exists, status, filledQty}（策略確認「OMS 到底有沒有收到」用） */
    @GetMapping("/local/{clientOrderId}")
    public ApiResponse<Map<String, Object>> local(@PathVariable String clientOrderId) {
        return ApiResponse.ok(orderService.local(clientOrderId));
    }

    /** 查單：GET /api/order/orders/{clientOrderId}?symbol=BTCUSDT&market=SPOT */
    @GetMapping("/{clientOrderId}")
    public ApiResponse<String> query(@PathVariable String clientOrderId,
                                     @RequestParam String symbol,
                                     @RequestParam(defaultValue = "SPOT") String market) {
        return orderService.query(symbol, clientOrderId, "USDT_M".equalsIgnoreCase(market));
    }

    /** 撤單：DELETE /api/order/orders/{clientOrderId}?symbol=BTCUSDT&market=SPOT */
    @DeleteMapping("/{clientOrderId}")
    public ApiResponse<String> cancel(@PathVariable String clientOrderId,
                                      @RequestParam String symbol,
                                      @RequestParam(defaultValue = "SPOT") String market) {
        return orderService.cancel(symbol, clientOrderId, "USDT_M".equalsIgnoreCase(market));
    }


    /** OCO 下單（現貨）：POST /api/order/orders/oco */
    @PostMapping("/oco")
    public ApiResponse<String> oco(@RequestBody Map<String, Object> b) {
        try {
            return orderService.submitOco(
                    (String) b.get("symbol"), (String) b.get("side"),
                    ((Number) b.get("quantity")).doubleValue(),
                    ((Number) b.get("price")).doubleValue(),
                    ((Number) b.get("stopPrice")).doubleValue(),
                    ((Number) b.get("stopLimitPrice")).doubleValue(),
                    b.get("listClientOrderId") == null ? null : String.valueOf(b.get("listClientOrderId")));
        } catch (NullPointerException | ClassCastException e) {
            return ApiResponse.error(400, "OCO 需要 symbol/side/quantity/price/stopPrice/stopLimitPrice");
        }
    }

    /** 設定合約槓桿：POST /api/order/orders/leverage?symbol=BTCUSDT&leverage=5 */
    @PostMapping("/leverage")
    public ApiResponse<String> leverage(@RequestParam String symbol, @RequestParam int leverage) {
        return orderService.setLeverage(symbol, leverage);
    }
}
