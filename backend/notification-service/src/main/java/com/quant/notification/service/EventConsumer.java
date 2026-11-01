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
    private final AlertGate gate;
    public EventConsumer(SlackNotifier slack, AlertGate gate) { this.slack = slack; this.gate = gate; }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "notification-service")
    public void onOrder(OrderEvent e) {
        // 只通知關鍵狀態，避免洗版
        String ex = e.exchangeOrderId() == null ? "" : e.exchangeOrderId();
        boolean simulated = ex.startsWith("DRYRUN") || ex.startsWith("PAPER");
        if (e.status() == OrderStatus.FILLED) {
            if (simulated) { gate.countOnly("模擬成交"); return; }   // 模擬成交只進每日摘要，不洗版
            // 真成交每筆都要看到：指紋含 symbol+方向但去掉數量價格，同幣 10 分鐘內連續小成交會被壓抑成一則 +N
            gate.publish(String.format(":white_check_mark: 成交 [%s] %s %s 數量 %.4f @ %.2f",
                    e.account(), e.symbol(), e.side(), e.filledQty(), e.avgPrice()), "真實成交");
        } else if (e.status() == OrderStatus.CANCELED) {
            if (simulated) { gate.countOnly("模擬撤單"); return; }
            gate.publish(String.format(":no_entry: 撤單 [%s] %s (%s)", e.account(), e.symbol(), e.clientOrderId()), "撤單");
        } else if (e.status() == OrderStatus.REJECTED) {
            gate.countOnly("拒單");   // 拒單原因由 OMS 自己推（含理由），這裡只計數
        }
    }

    @KafkaListener(topics = Topics.RISK_ALERTS, groupId = "notification-service")
    public void onRisk(String message) {
        gate.publish(":rotating_light: 風控告警: " + message, "風控告警");
    }
}
