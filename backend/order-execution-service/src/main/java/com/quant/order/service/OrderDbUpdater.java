package com.quant.order.service;

import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import com.quant.order.entity.OrderEntity;
import com.quant.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 訂閱 order.events 將成交/撤單等狀態寫回訂單表（見架構文件 §9）。
 * <ul>
 *   <li>以 eventId 去重（Kafka at-least-once；rebalance / 重啟會重送）</li>
 *   <li>狀態只能依 {@link OrderStateMachine} 前進，FILLED 不會被晚到的 PARTIALLY_FILLED 蓋回</li>
 *   <li>avg_price 以成交量加權，而不是用最後一筆部分成交覆蓋</li>
 *   <li>對帳事件（reconcile|…）已由 OMS 換算成增量，與 UDS 事件一視同仁累加</li>
 * </ul>
 */
@Service
public class OrderDbUpdater {

    private static final Logger log = LoggerFactory.getLogger(OrderDbUpdater.class);

    private final OrderRepository orders;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    static final String CONSUMER = "order-execution-db";

    public OrderDbUpdater(OrderRepository orders, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.orders = orders; this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "order-execution-db")
    @Transactional
    public void onEvent(OrderEvent e) {
        OrderStatus st = e.status();
        boolean fill = st == OrderStatus.FILLED || st == OrderStatus.PARTIALLY_FILLED;
        boolean terminal = fill || st == OrderStatus.CANCELED
                || st == OrderStatus.REJECTED || st == OrderStatus.EXPIRED;
        if (!terminal) return;   // CREATED/RISK_APPROVED/SUBMITTED/UNKNOWN 由同步路徑處理

        if (!claim(e)) { log.debug("重複事件略過 {}", e.eventId()); return; }

        orders.findByClientOrderId(e.clientOrderId()).ifPresent(o -> {
            OrderStatus cur = OrderService.safeStatus(o.getStatus());

            if (fill && e.filledQty() > 0) {
                double prevQty = o.getFilledQty() == null ? 0 : o.getFilledQty();
                double prevAvg = o.getAvgPrice() == null ? 0 : o.getAvgPrice();
                double newQty = prevQty + e.filledQty();
                if (e.avgPrice() > 0) {
                    o.setAvgPrice(newQty > 0 ? (prevQty * prevAvg + e.filledQty() * e.avgPrice()) / newQty : e.avgPrice());
                }
                o.setFilledQty(newQty);
            }

            // 狀態只前進
            if (cur == null || cur == st || OrderStateMachine.canTransit(cur, st)) {
                o.setStatus(st.name());
            } else {
                log.debug("狀態不回退 {}: {} -x-> {}", e.clientOrderId(), cur, st);
            }
            if (e.exchangeOrderId() != null) o.setExchangeOrderId(e.exchangeOrderId());
            o.setUpdatedAt(OffsetDateTime.now());
            orders.save(o);
            log.debug("訂單 DB 更新 {} -> {} filled={} avg={}", e.clientOrderId(), o.getStatus(), o.getFilledQty(), o.getAvgPrice());
        });
    }

    /**
     * 去重寫在 processed_events，與訂單更新同一筆 DB transaction：更新失敗 → 去重紀錄一起回滾 → Kafka 重送時會再處理。
     * （以前 Redis SETNX 先宣告、DB 後寫，中間掛掉就永遠漏記；Redis 不可用時放行又可能重複加總。）
     */
    private boolean claim(OrderEvent e) {
        String id = e.eventId() != null ? e.eventId()
                : OrderEvent.eventId(e.clientOrderId(), e.status(), e.filledQty(), e.timestamp());
        return jdbc.update("INSERT INTO processed_events (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING", CONSUMER, id) == 1;
    }

    @org.springframework.scheduling.annotation.Scheduled(cron = "0 23 4 * * *", zone = "Asia/Taipei")
    public void purge() {
        try { jdbc.update("DELETE FROM processed_events WHERE consumer = ? AND processed_at < now() - interval '14 days'", CONSUMER); }
        catch (Exception ex) { log.warn("清理 processed_events 失敗: {}", ex.getMessage()); }
    }
}
