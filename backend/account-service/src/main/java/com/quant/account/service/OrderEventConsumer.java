package com.quant.account.service;

import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/** 訂閱訂單事件更新多帳戶部位（見架構文件 §9.2）。 */
@Service
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private final PositionStore positions;

    public OrderEventConsumer(PositionStore positions) { this.positions = positions; }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "account-service")
    public void onOrderEvent(OrderEvent event) {
        if (event.filledQty() > 0) {
            positions.onFilled(event.account(), event.symbol(), event.filledQty(), event.avgPrice());
            log.info("部位更新 [{}] {} += {} @ {} ({})", event.account(), event.symbol(),
                    event.filledQty(), event.avgPrice(), event.status());
        }
    }
}
