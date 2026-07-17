package com.quant.order.service;

import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import com.quant.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * 訂閱 order.events 將成交/撤單等狀態寫回訂單表（見架構文件 §9）。
 * 成交量以「累加」處理，支援部分成交；為 filled_qty 的唯一寫入來源，避免與同步路徑重複計算。
 */
@Service
public class OrderDbUpdater {

    private static final Logger log = LoggerFactory.getLogger(OrderDbUpdater.class);
    private final OrderRepository orders;

    public OrderDbUpdater(OrderRepository orders) { this.orders = orders; }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "order-execution-db")
    public void onEvent(OrderEvent e) {
        OrderStatus st = e.status();
        boolean fill = st == OrderStatus.FILLED || st == OrderStatus.PARTIALLY_FILLED;
        boolean terminal = fill || st == OrderStatus.CANCELED
                || st == OrderStatus.REJECTED || st == OrderStatus.EXPIRED;
        if (!terminal) return;   // CREATED/RISK_APPROVED/SUBMITTED 由同步路徑處理

        orders.findByClientOrderId(e.clientOrderId()).ifPresent(o -> {
            o.setStatus(st.name());
            if (fill && e.filledQty() > 0) {
                double cur = o.getFilledQty() == null ? 0 : o.getFilledQty();
                o.setFilledQty(cur + e.filledQty());
            }
            if (fill && e.avgPrice() > 0) o.setAvgPrice(e.avgPrice());
            if (e.exchangeOrderId() != null) o.setExchangeOrderId(e.exchangeOrderId());
            o.setUpdatedAt(OffsetDateTime.now());
            orders.save(o);
            log.debug("訂單 DB 更新 {} -> {} filled={}", e.clientOrderId(), st, o.getFilledQty());
        });
    }
}
