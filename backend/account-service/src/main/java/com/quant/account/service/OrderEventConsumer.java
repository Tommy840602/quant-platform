package com.quant.account.service;

import com.quant.common.enums.OrderSide;
import com.quant.common.event.OrderEvent;
import com.quant.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 訂閱訂單事件更新多帳戶部位與現金帳（見架構文件 §9.2）。
 * 去重（processed_events）、部位、現金分錄在「同一筆 DB transaction」：要嘛全部成功，要嘛全部回滾讓 Kafka 重送。
 * 以前先在 Redis 宣告已處理、再寫 DB —— 中間失敗就永遠漏帳；Redis 掛掉又放行 → 可能重複入帳。
 * 部位依 (帳戶, 環境, 執行模式, 市場, 標的) 分開；合約成交只動合約部位，不套現貨的全額買賣現金規則。
 */
@Service
public class OrderEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);
    static final String CONSUMER = "account-service";
    private final PositionStore positions;
    private final CashLedger cash;
    private final JdbcTemplate jdbc;

    public OrderEventConsumer(PositionStore positions, CashLedger cash, JdbcTemplate jdbc) {
        this.positions = positions; this.cash = cash; this.jdbc = jdbc;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "account-service")
    @Transactional
    public void onOrderEvent(OrderEvent event) {
        if (event.filledQty() <= 0) return;
        OrderEvent e = withDims(event);
        String id = e.eventId() != null ? e.eventId() : OrderEvent.eventId(e.clientOrderId(), e.status(), e.filledQty(), e.timestamp());
        if (!claim(id)) { log.debug("重複事件略過 {}", id); return; }

        String env = e.env() == null ? "legacy" : e.env();
        String mode = e.executionMode() == null ? "LIVE" : e.executionMode();
        String market = e.futures() ? "USDT_M" : "SPOT";
        var p = positions.onFilled(e.account(), env, mode, market, e.symbol(), e.side(), e.filledQty(), e.avgPrice());
        if (p == null) return;
        if (!e.futures()) cash.onFill(e.account(), env, mode, e.side(), e.filledQty(), e.avgPrice(), id);
        log.info("部位更新 [{}|{}|{}|{}] {} {}{} @ {} → qty={} avg={}", e.account(), env, mode, market, e.symbol(),
                e.side() == OrderSide.SELL ? "-" : "+", e.filledQty(), e.avgPrice(), p.getQuantity(), p.getAvgPrice());
    }

    /** 去重：插入成功（新事件）回 true；已存在回 false。DB 失敗直接丟例外 → transaction 回滾、Kafka 重送。 */
    boolean claim(String eventId) {
        return jdbc.update("INSERT INTO processed_events (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING", CONSUMER, eventId) == 1;
    }

    /** 舊事件沒帶 env / market / mode：回查訂單（同一個 DB）。 */
    OrderEvent withDims(OrderEvent e) {
        if (e.env() != null && e.market() != null && e.executionMode() != null) return e;
        try {
            List<String[]> r = jdbc.query("SELECT env, market, execution_mode, exchange_order_id FROM orders WHERE client_order_id = ?",
                    (rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, e.clientOrderId());
            if (r.isEmpty()) return e;
            String[] o = r.get(0);
            String mode = o[2] != null ? o[2] : (o[3] != null && o[3].startsWith("DRYRUN") ? "DRY" : "LIVE");
            return e.withDims(e.env() != null ? e.env() : o[0], e.market() != null ? e.market() : (o[1] == null ? "SPOT" : o[1]),
                    e.executionMode() != null ? e.executionMode() : mode);
        } catch (Exception ex) { return e; }
    }

    /** 去重紀錄保留 14 天（Kafka 重送窗口遠小於此）。 */
    @Scheduled(cron = "0 17 4 * * *", zone = "Asia/Taipei")
    public void purge() {
        try { jdbc.update("DELETE FROM processed_events WHERE consumer = ? AND processed_at < now() - interval '14 days'", CONSUMER); }
        catch (Exception e) { log.warn("清理 processed_events 失敗: {}", e.getMessage()); }
    }
}
