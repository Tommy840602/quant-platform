package com.quant.account.service;

import com.quant.common.enums.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 策略帳戶的「現貨」現金帳（USDT），依 (帳戶, 環境, 執行模式) 分開。權益 = 現金 + Σ 現貨部位市值。
 * 以前存在 Redis：去重鍵先寫、入帳後寫，中間掛掉就漏帳或重複。現在餘額與分錄（cash_entries，可重播）都在 DB，
 * 由呼叫端的 transaction（與去重、部位更新同一筆）一起提交。合約不走這裡（保證金與損益由交易所算）。
 */
@Component
public class CashLedger {

    private static final Logger log = LoggerFactory.getLogger(CashLedger.class);
    private final JdbcTemplate jdbc;
    private final double baseCash;
    private final double feeRate;

    public CashLedger(JdbcTemplate jdbc,
                      @Value("${account.base-cash:10000}") double baseCash,
                      @Value("${account.fee-rate:0.001}") double feeRate) {
        this.jdbc = jdbc; this.baseCash = baseCash; this.feeRate = feeRate;
    }

    public double get(String account, String env, String mode) {
        try {
            List<Double> v = jdbc.query("SELECT cash FROM cash_ledger WHERE account = ? AND env = ? AND execution_mode = ?",
                    (rs, i) -> rs.getDouble(1), acc(account), env, mode);
            return v.isEmpty() ? baseCash : v.get(0);
        } catch (Exception e) {
            log.warn("讀取現金帳失敗，暫用 baseCash: {}", e.getMessage());
            return baseCash;
        }
    }

    /** 現貨成交入帳。必須在呼叫端的 transaction 裡（與 processed_events、positions 同一筆提交）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public double onFill(String account, String env, String mode, OrderSide side, double qty, double price, String eventId) {
        if (price <= 0 || side == null) return get(account, env, mode);
        double notional = qty * price, fee = notional * feeRate;
        double delta = side == OrderSide.BUY ? -(notional + fee) : (notional - fee);
        String a = acc(account);
        // 沒有列就以 baseCash 開帳；有列就原子累加（同列並發會被 row lock 串行化）
        Double after = jdbc.queryForObject("""
                INSERT INTO cash_ledger (account, env, execution_mode, cash, updated_at) VALUES (?, ?, ?, ?, now())
                ON CONFLICT (account, env, execution_mode) DO UPDATE SET cash = cash_ledger.cash + ?, updated_at = now()
                RETURNING cash""", Double.class, a, env, mode, baseCash + delta, delta);
        jdbc.update("INSERT INTO cash_entries (account, env, execution_mode, event_id, delta, balance_after, note) VALUES (?,?,?,?,?,?,?)",
                a, env, mode, eventId, delta, after, side + " " + qty + " @ " + price);
        return after == null ? 0 : after;
    }

    public void reset(String account, String env, String mode) {
        try { jdbc.update("DELETE FROM cash_ledger WHERE account = ? AND env = ? AND execution_mode = ?", acc(account), env, mode); }
        catch (Exception ignore) {}
    }

    private static String acc(String a) { return a == null || a.isBlank() ? "main" : a; }
}
