package com.quant.account.service;

import com.quant.common.enums.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 策略帳戶的現金帳（USDT）。權益 = 現金 + Σ 部位市值，而不是「起始資金 + 部位市值」——
 * 後者會讓每次買入都把名目額加進權益，回撤永遠觸發不了。
 * 以 Redis 持久化（compose 已開 AOF）；Redis 無值時以 BASE_CASH 初始化。
 */
@Component
public class CashLedger {

    private static final Logger log = LoggerFactory.getLogger(CashLedger.class);
    private static final String KEY = "account:cash:";

    private final StringRedisTemplate redis;
    private final double baseCash;
    private final double feeRate;

    public CashLedger(StringRedisTemplate redis,
                      @Value("${account.base-cash:10000}") double baseCash,
                      @Value("${account.fee-rate:0.001}") double feeRate) {
        this.redis = redis; this.baseCash = baseCash; this.feeRate = feeRate;
    }

    public double get(String account) {
        try {
            String v = redis.opsForValue().get(KEY + acc(account));
            return v == null ? baseCash : Double.parseDouble(v);
        } catch (Exception e) {
            log.warn("讀取現金帳失敗，暫用 baseCash: {}", e.getMessage());
            return baseCash;
        }
    }

    public synchronized void onFill(String account, OrderSide side, double qty, double price) {
        if (price <= 0 || side == null) return;
        double notional = qty * price;
        double fee = notional * feeRate;
        double delta = side == OrderSide.BUY ? -(notional + fee) : (notional - fee);
        double next = get(account) + delta;
        try { redis.opsForValue().set(KEY + acc(account), String.valueOf(next)); }
        catch (Exception e) { log.error("寫入現金帳失敗（權益將失準）: {}", e.getMessage()); }
    }

    public void reset(String account) {
        try { redis.delete(KEY + acc(account)); } catch (Exception ignore) {}
    }

    private static String acc(String a) { return a == null || a.isBlank() ? "main" : a; }
}
