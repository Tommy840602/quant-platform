package com.quant.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 推播中心（見架構文件 §11.3）：定期把部位/回撤狀態推給所有前端連線，取代輪詢。
 */
@Service
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final PositionStore positions;
    private final EquityCurveService equity;

    public SseHub(PositionStore positions, EquityCurveService equity) {
        this.positions = positions; this.equity = equity;
    }

    public SseEmitter register() {
        SseEmitter e = new SseEmitter(0L); // 不逾時
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        return e;
    }

    @Scheduled(fixedRate = 3000)
    public void broadcast() {
        if (emitters.isEmpty()) return;
        Map<String, Object> payload = Map.of(
                "positions", positions.snapshot(null),
                "drawdown", equity.drawdownStatus(),
                "ts", System.currentTimeMillis());
        for (SseEmitter e : emitters) {
            try { e.send(SseEmitter.event().name("snapshot").data(payload)); }
            catch (IOException ex) { emitters.remove(e); }
        }
    }
}
