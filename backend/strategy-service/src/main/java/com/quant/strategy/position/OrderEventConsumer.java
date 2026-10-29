package com.quant.strategy.position;

import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import com.quant.strategy.service.StrategyRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/** 把 OMS 的成交/終結事件餵進策略部位簿；遇到不認識的單（重啟前送的）就觸發一次對帳。 */
@Service
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    private final StrategyPositionBook book;
    private final StrategyRunner runner;

    public OrderEventConsumer(StrategyPositionBook book, StrategyRunner runner) { this.book = book; this.runner = runner; }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "strategy-service-orders",
                   properties = {"spring.json.value.default.type=com.quant.common.event.OrderEvent"})
    public void onOrderEvent(OrderEvent e) {
        if (e == null || e.clientOrderId() == null) return;
        boolean known = book.onEvent(e);
        if (!known && e.filledQty() > 0) {
            log.info("收到未知訂單的成交 {}（可能為重啟前送出），觸發對帳", e.clientOrderId());
            runner.reconcileFromOms();
        }
    }
}
