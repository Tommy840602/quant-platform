package com.quant.order.service;

import com.quant.common.dto.ApiResponse;
import com.quant.common.dto.OrderIntent;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.enums.OrderType;
import com.quant.common.event.OrderEvent;
import com.quant.order.entity.OrderEntity;
import com.quant.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 送單路徑的三條關鍵規則：落地失敗不送、逾時標 UNKNOWN 並回查、交易所明拒才標 REJECTED。 */
class OrderServiceSubmitTest {

    RiskClient risk = mock(RiskClient.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> ops = mock(ValueOperations.class);
    @SuppressWarnings("unchecked") KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    BinanceOrderGateway gateway = mock(BinanceOrderGateway.class);
    OrderRepository orders = mock(OrderRepository.class);
    AccountClient account = mock(AccountClient.class);
    NotifyClient notify = mock(NotifyClient.class);

    OrderService svc;
    OrderIntent intent = new OrderIntent("cid-1", "s1", "BTCUSDT", OrderSide.BUY, OrderType.MARKET, null, 0.01, false, "SPOT", "main");

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(ops.get("price:BTCUSDT")).thenReturn("60000");
        when(gateway.referencePrice(eq("BTCUSDT"), anyBoolean())).thenReturn(60000.0);
        when(risk.check(any())).thenReturn(new RiskClient.RiskDecision(true, "ok", null));
        when(gateway.isDryRun()).thenReturn(false);
        OrderEntity row = new OrderEntity(); row.setClientOrderId("cid-1"); row.setStatus("CREATED"); row.setSide("BUY");
        when(orders.findByClientOrderId("cid-1")).thenReturn(Optional.of(row));
        when(orders.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(orders.save(any())).thenAnswer(i -> i.getArgument(0));
        svc = new OrderService(risk, redis, kafka, gateway, orders, account, notify);
    }

    @Test
    void persistFailureMeansOrderIsNeverSent() {
        when(orders.saveAndFlush(any())).thenThrow(new RuntimeException("db down"));
        ApiResponse<String> r = svc.submit(intent);
        assertEquals(503, r.code());
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void duplicateClientOrderIdInDbIs409() {
        when(orders.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertEquals(409, svc.submit(intent).code());
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void transportTimeoutBecomesUnknownAndIsReconciled_filled() {
        when(gateway.placeOrder(any())).thenThrow(new BinanceOrderGateway.TransportException("timeout", null));
        when(gateway.queryOrder(eq("BTCUSDT"), eq("cid-1"), eq(false)))
                .thenReturn("{\"status\":\"FILLED\",\"executedQty\":\"0.01\",\"cummulativeQuoteQty\":\"600\",\"orderId\":42}");

        ApiResponse<String> r = svc.submit(intent);

        assertEquals(0, r.code(), "回查確認成交 → 視為成功");
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(kafka, atLeastOnce()).send(anyString(), anyString(), cap.capture());
        List<OrderEvent> events = cap.getAllValues().stream().map(o -> (OrderEvent) o).toList();
        assertTrue(events.stream().anyMatch(e -> e.status() == OrderStatus.UNKNOWN), "先標 UNKNOWN");
        OrderEvent fill = events.stream().filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(0.01, fill.filledQty(), 1e-12);
        assertEquals(60000, fill.avgPrice(), 1e-6);
        assertEquals(OrderSide.BUY, fill.side());
        assertTrue(fill.eventId().startsWith("reconcile|"));
        assertFalse(events.stream().anyMatch(e -> e.status() == OrderStatus.REJECTED), "絕不可標 REJECTED");
    }

    @Test
    void transportTimeoutWithOrderDoesNotExistIsSafeReject() {
        when(gateway.placeOrder(any())).thenThrow(new BinanceOrderGateway.TransportException("timeout", null));
        when(gateway.queryOrder(any(), any(), anyBoolean()))
                .thenThrow(new BinanceOrderGateway.ExchangeRejectedException(400, -2013, "Order does not exist"));
        ApiResponse<String> r = svc.submit(intent);
        assertEquals(500, r.code());
        assertTrue(r.message().contains("可重送"));
    }

    @Test
    void transportTimeoutStillUnknownWhenQueryFails() {
        when(gateway.placeOrder(any())).thenThrow(new BinanceOrderGateway.TransportException("timeout", null));
        when(gateway.queryOrder(any(), any(), anyBoolean())).thenThrow(new RuntimeException("still down"));
        ApiResponse<String> r = svc.submit(intent);
        assertEquals(202, r.code());
        assertTrue(r.message().contains("UNKNOWN"));
    }

    @Test
    void exchangeRejectionIsRejected() {
        when(gateway.placeOrder(any())).thenThrow(new BinanceOrderGateway.ExchangeRejectedException(400, -2010, "insufficient balance"));
        ApiResponse<String> r = svc.submit(intent);
        assertEquals(500, r.code());
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(kafka, atLeastOnce()).send(anyString(), anyString(), cap.capture());
        assertTrue(cap.getAllValues().stream().map(o -> (OrderEvent) o).anyMatch(e -> e.status() == OrderStatus.REJECTED));
        verify(gateway, never()).queryOrder(any(), any(), anyBoolean());
    }

    @Test
    void dryRunReadsGatewayFlagNotItsOwnCopy() {
        when(gateway.isDryRun()).thenReturn(true);
        when(gateway.placeOrder(any())).thenReturn("DRYRUN-cid-1");
        svc.submit(intent);
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(kafka, atLeastOnce()).send(anyString(), anyString(), cap.capture());
        OrderEvent fill = cap.getAllValues().stream().map(o -> (OrderEvent) o)
                .filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(60000, fill.avgPrice(), 1e-6);
    }

    // ── PR4: 名目上限 fail-closed ──
    @Test
    void missingReferencePriceRejectsInsteadOfPassing() {
        when(gateway.referencePrice(any(), anyBoolean())).thenReturn(0.0);
        ApiResponse<String> r = svc.submit(intent);
        assertEquals(403, r.code());
        assertTrue(r.message().contains("參考價"));
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void notionalAboveCapIsRejected() {
        OrderIntent big = new OrderIntent("cid-1", "s1", "BTCUSDT", OrderSide.BUY, OrderType.MARKET, null, 1.0, false, "SPOT", "main"); // 60k > 1000
        ApiResponse<String> r = svc.submit(big);
        assertEquals(403, r.code());
        verify(gateway, never()).placeOrder(any());
    }

    @Test
    void zeroCapIsNotUnlimitedByDefault() {
        assertThrows(IllegalArgumentException.class, () -> svc.setRiskConfig(0.0, null));
    }

    // ── PR4: OCO 走完整閘門 ──
    @Test
    void ocoGoesThroughRiskAndNotionalChecks() {
        when(risk.check(any())).thenReturn(new RiskClient.RiskDecision(false, "熔斷已啟動", null));
        ApiResponse<String> r = svc.submitOco("BTCUSDT", "SELL", 0.01, 61000, 59000, 58900, "oco-1");
        assertEquals(403, r.code());
        verify(gateway, never()).placeOco(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        verify(orders, times(2)).saveAndFlush(any());   // 兩腿都先落地
    }

    @Test
    void ocoPersistsBothLegsAndSubmits() {
        when(gateway.placeOco(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn("{\"listOrderStatus\":\"EXECUTING\"}");
        OrderEntity l = new OrderEntity(); l.setClientOrderId("oco-1-L"); l.setStatus("CREATED"); l.setSide("SELL");
        OrderEntity st = new OrderEntity(); st.setClientOrderId("oco-1-S"); st.setStatus("CREATED"); st.setSide("SELL");
        when(orders.findByClientOrderId("oco-1-L")).thenReturn(Optional.of(l));
        when(orders.findByClientOrderId("oco-1-S")).thenReturn(Optional.of(st));
        ApiResponse<String> r = svc.submitOco("BTCUSDT", "SELL", 0.01, 61000, 59000, 58900, "oco-1");
        assertEquals(0, r.code());
        assertEquals("SUBMITTED", l.getStatus());
        assertEquals("SUBMITTED", st.getStatus());
        verify(gateway).placeOco(eq("BTCUSDT"), eq("SELL"), eq(0.01), eq(61000.0), eq(59000.0), eq(58900.0), eq("oco-1"));
    }

    // ── PR5: 合約成交回報 + UDS 未連線時的退路 ──
    private List<OrderEvent> events() {
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(kafka, atLeastOnce()).send(anyString(), anyString(), cap.capture());
        return cap.getAllValues().stream().map(o -> (OrderEvent) o).toList();
    }

    @Test
    void futuresResultResponseFillIsRecordedWithAvgPrice() {
        OrderIntent fut = new OrderIntent("cid-1", "s1", "BTCUSDT", OrderSide.BUY, OrderType.MARKET, null, 0.01, false, "USDT_M", "main");
        when(gateway.placeOrder(any())).thenReturn(
                "{\"orderId\":77,\"status\":\"FILLED\",\"executedQty\":\"0.010\",\"avgPrice\":\"60100.5\",\"cumQuote\":\"601.005\"}");
        assertEquals(0, svc.submit(fut).code());
        OrderEvent fill = events().stream().filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(0.01, fill.filledQty(), 1e-12);
        assertEquals(60100.5, fill.avgPrice(), 1e-6);
        assertEquals("77", fill.exchangeOrderId());
        assertTrue(fill.eventId().startsWith("resp|cid-1|"));
    }

    @Test
    void ackStyleResponseWithoutExecutedQtyRecordsNoFill() {
        // 合約預設 ACK：沒有 executedQty 欄位 → 不可假造成交，等對帳補記
        when(gateway.placeOrder(any())).thenReturn("{\"orderId\":77,\"clientOrderId\":\"cid-1\",\"status\":\"NEW\"}");
        assertEquals(0, svc.submit(intent).code());
        assertFalse(events().stream().anyMatch(e -> e.status() == OrderStatus.FILLED || e.status() == OrderStatus.PARTIALLY_FILLED));
    }

    @Test
    void streamConnectedMeansResponseFillIsNotDoubleCounted() {
        UdsRegistry uds = new UdsRegistry(notify);
        uds.expect(UdsRegistry.SPOT); uds.markConnected(UdsRegistry.SPOT);
        svc.setUdsRegistry(uds);
        when(gateway.placeOrder(any())).thenReturn(
                "{\"orderId\":1,\"status\":\"FILLED\",\"executedQty\":\"0.01\",\"cummulativeQuoteQty\":\"600\"}");
        assertEquals(0, svc.submit(intent).code());
        assertFalse(events().stream().anyMatch(e -> e.status() == OrderStatus.FILLED), "串流活著 → 成交由 executionReport 記，不從回應重複記");
    }

    @Test
    void streamExpectedButDownFallsBackToResponseFill() {
        UdsRegistry uds = new UdsRegistry(notify);
        uds.expect(UdsRegistry.SPOT);   // 設定開了，但此刻沒連上（以前：永遠不記成交）
        svc.setUdsRegistry(uds);
        when(gateway.placeOrder(any())).thenReturn(
                "{\"orderId\":1,\"status\":\"FILLED\",\"executedQty\":\"0.01\",\"cummulativeQuoteQty\":\"600\"}");
        assertEquals(0, svc.submit(intent).code());
        OrderEvent fill = events().stream().filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(60000, fill.avgPrice(), 1e-6);
    }

    @Test
    void futuresStreamStateDoesNotAffectSpotDecision() {
        UdsRegistry uds = new UdsRegistry(notify);
        uds.expect(UdsRegistry.FUTURES); uds.markConnected(UdsRegistry.FUTURES);
        svc.setUdsRegistry(uds);
        when(gateway.placeOrder(any())).thenReturn(
                "{\"orderId\":1,\"status\":\"FILLED\",\"executedQty\":\"0.01\",\"cummulativeQuoteQty\":\"600\"}");
        svc.submit(intent);   // 現貨單
        assertTrue(events().stream().anyMatch(e -> e.status() == OrderStatus.FILLED), "合約串流連著不代表現貨有回報");
    }

    @Test
    void reconcileOpenOrdersPublishesOnlyIncrementalFill() {
        OrderEntity open = new OrderEntity(); open.setClientOrderId("cid-9"); open.setSymbol("BTCUSDT");
        open.setSide("BUY"); open.setStatus("PARTIALLY_FILLED"); open.setFilledQty(0.004); open.setMarket("USDT_M");
        when(orders.findTop200ByStatusInAndMarketOrderByUpdatedAtAsc(anyList(), eq("USDT_M"))).thenReturn(List.of(open));
        when(orders.findByClientOrderId("cid-9")).thenReturn(Optional.of(open));
        when(gateway.queryOrder("BTCUSDT", "cid-9", true))
                .thenReturn("{\"orderId\":9,\"status\":\"FILLED\",\"executedQty\":\"0.010\",\"avgPrice\":\"60000\"}");
        assertEquals(1, svc.reconcileOpenOrders(true));
        OrderEvent fill = events().stream().filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(0.006, fill.filledQty(), 1e-9, "只補 0.010 − 0.004 的增量");
    }

    // ── 限價單 ──
    @Test
    void dryRunLimitNotTouchedRestsThenFillsWhenPriceReaches() {
        when(gateway.isDryRun()).thenReturn(true);
        when(gateway.placeOrder(any())).thenReturn("DRYRUN-cid-1");
        OrderIntent lim = new OrderIntent("cid-1", "s1", "BTCUSDT", OrderSide.BUY, OrderType.LIMIT, 59000.0, 0.01, false, "SPOT", "main");
        OrderEntity row = new OrderEntity(); row.setClientOrderId("cid-1"); row.setStatus("CREATED"); row.setSide("BUY");
        row.setSymbol("BTCUSDT"); row.setPrice(59000.0); row.setQuantity(0.01);
        when(orders.findByClientOrderId("cid-1")).thenReturn(Optional.of(row));

        assertEquals(0, svc.submit(lim).code());
        assertFalse(events().stream().anyMatch(e -> e.status() == OrderStatus.FILLED), "現價 60000 > 買限價 59000，不該成交");
        assertEquals("DRYRUN", row.getExchangeOrderId());
        assertEquals("SUBMITTED", row.getStatus());

        // 價格跌到 58900 → 排程撮合
        when(ops.get("price:BTCUSDT")).thenReturn("58900");
        when(orders.findTop200ByStatusAndExchangeOrderIdOrderByIdAsc("SUBMITTED", "DRYRUN")).thenReturn(List.of(row));
        svc.matchDryRunRestingOrders();
        OrderEvent fill = events().stream().filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(59000, fill.avgPrice(), 1e-6, "以限價成交");
        assertTrue(fill.eventId().startsWith("dryfill|"));
    }

    @Test
    void dryRunMarketableLimitFillsImmediatelyAtLimit() {
        when(gateway.isDryRun()).thenReturn(true);
        when(gateway.placeOrder(any())).thenReturn("DRYRUN-cid-1");
        OrderIntent lim = new OrderIntent("cid-1", "s1", "BTCUSDT", OrderSide.BUY, OrderType.LIMIT, 61000.0, 0.01, false, "SPOT", "main");
        svc.submit(lim);
        OrderEvent fill = events().stream().filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(61000, fill.avgPrice(), 1e-6);
    }

    @Test
    void limitTouchedRules() {
        assertTrue(OrderService.limitTouched(OrderSide.BUY, 100, 99));
        assertFalse(OrderService.limitTouched(OrderSide.BUY, 100, 101));
        assertTrue(OrderService.limitTouched(OrderSide.SELL, 100, 101));
        assertFalse(OrderService.limitTouched(OrderSide.SELL, 100, 99));
        assertFalse(OrderService.limitTouched(OrderSide.BUY, 100, 0));
    }

    @Test
    void restingRealOrdersAreReconciledWhenStreamIsDown() {
        when(gateway.isDryRun()).thenReturn(false);
        UdsRegistry uds = new UdsRegistry(notify); svc.setUdsRegistry(uds);   // 沒連線
        OrderEntity open = new OrderEntity(); open.setClientOrderId("cid-9"); open.setSymbol("BTCUSDT");
        open.setSide("BUY"); open.setStatus("SUBMITTED"); open.setFilledQty(0.0); open.setMarket("SPOT");
        when(orders.findTop200ByStatusInAndMarketOrderByUpdatedAtAsc(anyList(), eq("SPOT"))).thenReturn(List.of(open));
        when(orders.findTop200ByStatusInAndMarketOrderByUpdatedAtAsc(anyList(), eq("USDT_M"))).thenReturn(List.of());
        when(orders.findByClientOrderId("cid-9")).thenReturn(Optional.of(open));
        when(gateway.queryOrder("BTCUSDT", "cid-9", false))
                .thenReturn("{\"orderId\":9,\"status\":\"FILLED\",\"executedQty\":\"0.010\",\"cummulativeQuoteQty\":\"590\"}");
        svc.reconcileRestingRealOrders();
        OrderEvent fill = events().stream().filter(e -> e.status() == OrderStatus.FILLED).findFirst().orElseThrow();
        assertEquals(0.01, fill.filledQty(), 1e-12);
        assertEquals(59000, fill.avgPrice(), 1e-6);
    }

    @Test
    void restingRealOrdersSkippedWhenStreamConnected() {
        when(gateway.isDryRun()).thenReturn(false);
        UdsRegistry uds = new UdsRegistry(notify); uds.markConnected(UdsRegistry.SPOT); uds.markConnected(UdsRegistry.FUTURES);
        svc.setUdsRegistry(uds);
        svc.reconcileRestingRealOrders();
        verify(orders, never()).findTop200ByStatusInAndMarketOrderByUpdatedAtAsc(anyList(), anyString());
    }

    @Test
    void unchangedRestingOrderDoesNotSpamSubmittedEvents() {
        when(gateway.isDryRun()).thenReturn(false);
        OrderEntity open = new OrderEntity(); open.setClientOrderId("cid-9"); open.setSymbol("BTCUSDT");
        open.setSide("BUY"); open.setStatus("SUBMITTED"); open.setFilledQty(0.0); open.setMarket("SPOT");
        when(orders.findByClientOrderId("cid-9")).thenReturn(Optional.of(open));
        when(gateway.queryOrder("BTCUSDT", "cid-9", false)).thenReturn("{\"orderId\":9,\"status\":\"NEW\",\"executedQty\":\"0\"}");
        svc.reconcileOne("cid-9", "BTCUSDT", false, OrderSide.BUY, "main");
        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    // ── Outbox 回收 ──
    private OrderEntity stuck(String cid, String status, long ageMs) {
        OrderEntity o = new OrderEntity(); o.setClientOrderId(cid); o.setSymbol("BTCUSDT"); o.setSide("BUY");
        o.setStatus(status); o.setMarket("SPOT"); o.setQuantity(0.01);
        o.setUpdatedAt(java.time.OffsetDateTime.now().minusNanos(ageMs * 1_000_000));
        when(orders.findByClientOrderId(cid)).thenReturn(Optional.of(o));
        return o;
    }

    @Test
    void stuckRiskApprovedOrderIsReconciledAfterRestart() {
        when(gateway.isDryRun()).thenReturn(false);
        OrderEntity o = stuck("cid-s1", "RISK_APPROVED", 60_000);
        when(orders.findTop200ByStatusInOrderByIdAsc(anyList())).thenReturn(List.of(o));
        when(gateway.queryOrder("BTCUSDT", "cid-s1", false))
                .thenReturn("{\"orderId\":5,\"status\":\"FILLED\",\"executedQty\":\"0.01\",\"cummulativeQuoteQty\":\"600\"}");
        assertEquals(1, svc.recoverStuckOrders());
        assertEquals("FILLED", o.getStatus(), "交易所其實成交了 → 採用");
        assertTrue(events().stream().anyMatch(e -> e.status() == OrderStatus.FILLED && e.filledQty() == 0.01));
    }

    @Test
    void stuckRiskApprovedUnknownToExchangeBecomesRejected() {
        when(gateway.isDryRun()).thenReturn(false);
        OrderEntity o = stuck("cid-s2", "RISK_APPROVED", 60_000);
        when(orders.findTop200ByStatusInOrderByIdAsc(anyList())).thenReturn(List.of(o));
        when(gateway.queryOrder(any(), eq("cid-s2"), anyBoolean()))
                .thenThrow(new BinanceOrderGateway.ExchangeRejectedException(400, -2013, "Order does not exist"));
        svc.recoverStuckOrders();
        assertEquals("REJECTED", o.getStatus(), "交易所沒收到 → 安全作廢");
    }

    @Test
    void stuckCreatedOrderIsRejectedWithoutQuerying() {
        when(gateway.isDryRun()).thenReturn(false);
        OrderEntity o = stuck("cid-s3", "CREATED", 60_000);
        when(orders.findTop200ByStatusInOrderByIdAsc(anyList())).thenReturn(List.of(o));
        svc.recoverStuckOrders();
        assertEquals("REJECTED", o.getStatus());
        verify(gateway, never()).queryOrder(any(), any(), anyBoolean());
    }

    @Test
    void freshInFlightOrderIsLeftAlone() {
        when(gateway.isDryRun()).thenReturn(false);
        OrderEntity o = stuck("cid-s4", "RISK_APPROVED", 2_000);   // 2 秒前，可能正在送
        when(orders.findTop200ByStatusInOrderByIdAsc(anyList())).thenReturn(List.of(o));
        assertEquals(0, svc.recoverStuckOrders());
        assertEquals("RISK_APPROVED", o.getStatus());
    }
}
