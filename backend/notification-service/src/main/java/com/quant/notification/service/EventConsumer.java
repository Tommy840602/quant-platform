package com.quant.notification.service;

import com.quant.common.enums.OrderStatus;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/** 消費訂單/風控事件並轉 Slack 通知（見架構文件 §3.2）。 */
@Service
public class EventConsumer {

    private final SlackNotifier slack;
    public EventConsumer(SlackNotifier slack) { this.slack = slack; }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "notification-service")
    public void onOrder(OrderEvent e) {
        // 只通知關鍵狀態，避免洗版
        if (e.status() == OrderStatus.FILLED) {
            slack.send(String.format(":white_check_mark: 成交 [%s] %s 數量 %.4f @ %.2f",
                    e.account(), e.symbol(), e.filledQty(), e.avgPrice()));
        } else if (e.status() == OrderStatus.CANCELED) {
            slack.send(String.format(":no_entry: 撤單 [%s] %s (%s)", e.account(), e.symbol(), e.clientOrderId()));
        }
    }

    @KafkaListener(topics = Topics.RISK_ALERTS, groupId = "notification-service")
    public void onRisk(String message) {
        slack.send(":rotating_light: 風控告警: " + message);
    }
}
