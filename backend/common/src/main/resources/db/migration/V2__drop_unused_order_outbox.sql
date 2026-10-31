-- order_outbox 從未被寫入：orders 表本身就是 outbox（送單前落地、client_order_id 冪等），
-- 崩潰回收由 OrderService.recoverStuckOrders 掃 CREATED / RISK_APPROVED 卡住的單回查交易所。
DROP TABLE IF EXISTS order_outbox;
