package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import com.quant.marketdata.config.BinanceProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BinanceWebSocketClientTest {

    MarketDataService svc = mock(MarketDataService.class);
    KlineBackfillService backfill = mock(KlineBackfillService.class);
    BinanceProperties props = new BinanceProperties();

    private static String klineMsg(long openTime, boolean closed, double close) {
        return "{\"stream\":\"btcusdt@kline_1m\",\"data\":{\"e\":\"kline\",\"k\":{\"s\":\"BTCUSDT\",\"i\":\"1m\",\"t\":" + openTime
                + ",\"T\":" + (openTime + 59999) + ",\"o\":\"1\",\"h\":\"2\",\"l\":\"0.5\",\"c\":\"" + close + "\",\"v\":\"3\",\"x\":" + closed + "}}}";
    }

    @Test
    void reconnectBackfillsFromLastClosedBarAndSkipsDuplicates() throws Exception {
        props.setStreams(List.of("btcusdt@kline_1m", "btcusdt@depth10@1000ms"));
        BinanceWebSocketClient ws = new BinanceWebSocketClient(props, svc, backfill);

        ws.handleTextMessage(null, new TextMessage(klineMsg(60000, true, 100)));    // 已收盤 → 記住 60000
        when(backfill.fetchClosedSince(eq("BTCUSDT"), eq("1m"), eq(60000L), anyLong()))
                .thenReturn(List.of(new Kline("BTCUSDT", "1m", 120000, 1, 2, 0.5, 101, 3, 179999, true)));

        ws.backfillGaps();
        // 串流在補抓後又送一次同一根 120000（重複）→ 必須略過；新的 180000 要過
        ws.handleTextMessage(null, new TextMessage(klineMsg(120000, true, 101)));
        ws.handleTextMessage(null, new TextMessage(klineMsg(180000, false, 102)));

        ArgumentCaptor<Kline> cap = ArgumentCaptor.forClass(Kline.class);
        verify(svc, times(3)).onKline(cap.capture());
        List<Long> opens = cap.getAllValues().stream().map(Kline::openTime).toList();
        assertEquals(List.of(60000L, 120000L, 180000L), opens);
        verify(backfill, times(1)).fetchClosedSince(any(), any(), anyLong(), anyLong());   // depth 串流不補
    }

    @Test
    void firstConnectionDoesNotBackfill() {
        props.setStreams(List.of("btcusdt@kline_1m"));
        BinanceWebSocketClient ws = new BinanceWebSocketClient(props, svc, backfill);
        ws.backfillGaps();
        verifyNoInteractions(backfill);
    }
}
