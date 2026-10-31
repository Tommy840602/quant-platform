package com.quant.account.service;

import com.quant.common.account.Accounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 虛擬 / 真實帳戶切換（全平台一個開關，存 Redis account:mode；OMS 與策略服務直接讀同一把鍵）。
 * 切到真實帳戶的前提：現貨金鑰已設定；Dry-Run 仍開著也能切（看餘額），但下單會被 OMS 拒。
 */
@Service
public class AccountModeService {

    private static final Logger log = LoggerFactory.getLogger(AccountModeService.class);
    private final StringRedisTemplate redis;
    private final BinanceAccountService binance;
    private final CashLedger cash;
    private final PositionStore positions;
    private final NotifyClient notify;
    private final EquityCurveService equity;

    public AccountModeService(StringRedisTemplate redis, BinanceAccountService binance, CashLedger cash,
                              PositionStore positions, NotifyClient notify, @org.springframework.context.annotation.Lazy EquityCurveService equity) {
        this.redis = redis; this.binance = binance; this.cash = cash; this.positions = positions; this.notify = notify; this.equity = equity;
    }

    public String current() {
        try { return Accounts.modeOrDefault(redis.opsForValue().get(Accounts.MODE_KEY)); }
        catch (Exception e) { return Accounts.DEFAULT_MODE; }
    }

    public Map<String, Object> status() {
        String mode = current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("label", Accounts.label(mode));
        m.put("paper", Accounts.isPaper(mode));
        m.put("realConfigured", binance.live());
        m.put("env", binance.env());
        return m;
    }

    /** 切換。切真實帳戶但金鑰沒設 → 拒絕（IllegalStateException → 409）。 */
    public Map<String, Object> switchTo(String mode) {
        String target = Accounts.modeOrDefault(mode);
        if (!Accounts.isPaper(target) && !binance.live())
            throw new IllegalStateException("尚未設定幣安 API 金鑰，無法切到真實帳戶（連線設定頁填入後再試）");
        String before = current();
        redis.opsForValue().set(Accounts.MODE_KEY, target);
        if (!before.equals(target)) {
            log.warn("帳戶模式切換：{} → {}", before, target);
            try { equity.rebasePeak(); } catch (Exception e) { log.warn("重置回撤峰值失敗: {}", e.getMessage()); }
            try { notify.send(Map.of("text", (Accounts.isPaper(target) ? ":pencil2: 切換到虛擬帳戶" : ":moneybag: 切換到真實帳戶（" + binance.env() + "）") + "，策略機器人之後的單都走此帳戶")); }
            catch (Exception ignore) {}
        }
        return status();
    }

    /** 重置虛擬帳戶：現金回 base、部位清零。 */
    public Map<String, Object> resetPaper() {
        cash.reset(Accounts.PAPER);
        int cleared = positions.clear(Accounts.PAPER);
        log.warn("虛擬帳戶重置：現金回初始值，清除 {} 筆部位", cleared);
        return Map.of("account", Accounts.PAPER, "cash", cash.get(Accounts.PAPER), "positionsCleared", cleared);
    }
}
