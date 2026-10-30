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
}
