package com.quant.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.common.enums.OrderSide;
import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 合約 ORDER_TRADE_UPDATE → OrderEvent：欄位在 o 底下、l/L 是本次增量、eventId 綁 tradeId。 */
class FuturesUdsParseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String PARTIAL = """
        {"e":"ORDER_TRADE_UPDATE","E":1700000000123,"T":1700000000120,
         "o":{"s":"BTCUSDT","c":"cid-f1","S":"SELL","o":"LIMIT","f":"GTC","q":"0.020","p":"60000","ap":"60010",
              "x":"TRADE","X":"PARTIALLY_FILLED","i":8886774,"l":"0.005","z":"0.005","L":"60010","t":12345,"n":"0.3","N":"USDT"}}
        """;

    @Test
    void partialFillCarriesIncrementalQtyAndTradeId() throws Exception {
        OrderEvent ev = BinanceFuturesUserDataStream.toEvent(mapper.readTree(PARTIAL), null);
        assertNotNull(ev);
        assertEquals("cid-f1", ev.clientOrderId());
        assertEquals("8886774", ev.exchangeOrderId());
        assertEquals("BTCUSDT", ev.symbol());
        assertEquals(OrderSide.SELL, ev.side());
        assertEquals(OrderStatus.PARTIALLY_FILLED, ev.status());
        assertEquals(0.005, ev.filledQty(), 1e-12, "l = 本次成交量，不是累計 z");
        assertEquals(60010, ev.avgPrice(), 1e-9);
        assertEquals("uds-fut|cid-f1|12345|PARTIALLY_FILLED|1700000000123", ev.eventId());
        assertEquals(-0.005, ev.signedQty(), 1e-12);
    }

    @Test
    void newOrderAckHasZeroFill() throws Exception {
        String json = PARTIAL.replace("\"X\":\"PARTIALLY_FILLED\"", "\"X\":\"NEW\"").replace("\"l\":\"0.005\"", "\"l\":\"0\"").replace("\"t\":12345", "\"t\":0");
        OrderEvent ev = BinanceFuturesUserDataStream.toEvent(mapper.readTree(json), null);
        assertEquals(OrderStatus.SUBMITTED, ev.status());
        assertEquals(0, ev.filledQty(), 1e-12);
    }

    @Test
    void eventWithoutOrderBlockIsIgnored() throws Exception {
        assertNull(BinanceFuturesUserDataStream.toEvent(mapper.readTree("{\"e\":\"ACCOUNT_UPDATE\",\"E\":1}"), null));
    }

    @Test
    void expiredInMatchMapsToExpired() {
        assertEquals(OrderStatus.EXPIRED, BinanceFuturesUserDataStream.mapStatus("EXPIRED_IN_MATCH"));
        assertEquals(OrderStatus.CANCELED, BinanceFuturesUserDataStream.mapStatus("CANCELED"));
    }
}
