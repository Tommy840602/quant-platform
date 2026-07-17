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

    /** 當日成交筆數 + 已實現盈虧：GET /api/order/orders/daily-stats */
    @GetMapping("/daily-stats")
    public ApiResponse<Map<String, Object>> dailyStats() { return orderService.dailyStats(); }

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
    @GetMapping("/attribution")
    public ApiResponse<List<Map<String, Object>>> attribution() {
        return orderService.attribution();
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
        return orderService.submitOco(
                (String) b.get("symbol"), (String) b.get("side"),
                ((Number) b.get("quantity")).doubleValue(),
                ((Number) b.get("price")).doubleValue(),
                ((Number) b.get("stopPrice")).doubleValue(),
                ((Number) b.get("stopLimitPrice")).doubleValue());
    }

    /** 設定合約槓桿：POST /api/order/orders/leverage?symbol=BTCUSDT&leverage=5 */
    @PostMapping("/leverage")
    public ApiResponse<String> leverage(@RequestParam String symbol, @RequestParam int leverage) {
        return orderService.setLeverage(symbol, leverage);
    }
}
