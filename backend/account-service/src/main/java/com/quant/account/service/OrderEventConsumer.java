package com.quant.account.service;

import com.quant.common.enums.OrderSide;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 訂閱訂單事件更新多帳戶部位與現金帳（見架構文件 §9.2）。
 * 以 eventId 去重（Kafka at-least-once）。對帳事件（reconcile|…）已由 OMS 換算成增量，照常累計。
 */
@Service
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private final PositionStore positions;
    private final CashLedger cash;
    private final StringRedisTemplate redis;

    public OrderEventConsumer(PositionStore positions, CashLedger cash, StringRedisTemplate redis) {
        this.positions = positions; this.cash = cash; this.redis = redis;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "account-service")
    public void onOrderEvent(OrderEvent event) {
        if (event.filledQty() <= 0) return;
        if (!claim(event)) { log.debug("重複事件略過 {}", event.eventId()); return; }

        var p = positions.onFilled(event.account(), event.symbol(), event.side(), event.filledQty(), event.avgPrice());
        if (p == null) return;
        cash.onFill(event.account(), event.side(), event.filledQty(), event.avgPrice());
        log.info("部位更新 [{}] {} {}{} @ {} → qty={} avg={}", event.account(), event.symbol(),
                event.side() == OrderSide.SELL ? "-" : "+", event.filledQty(), event.avgPrice(),
                p.getQuantity(), p.getAvgPrice());
    }

    private boolean claim(OrderEvent e) {
        String id = e.eventId() != null ? e.eventId()
                : OrderEvent.eventId(e.clientOrderId(), e.status(), e.filledQty(), e.timestamp());
        try {
            Boolean first = redis.opsForValue().setIfAbsent("evt:acct:" + id, "1", 48, TimeUnit.HOURS);
            return !Boolean.FALSE.equals(first);
        } catch (Exception ex) {
            log.warn("去重 Redis 不可用，放行事件 {}: {}", id, ex.getMessage());
            return true;
        }
    }
}
