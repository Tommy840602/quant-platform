package com.quant.account.service;

import com.quant.account.entity.PositionEntity;
import com.quant.account.entity.PositionId;
import com.quant.account.repository.PositionRepository;
import com.quant.common.enums.OrderSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 部位管理（見架構文件 §9.2）：多帳戶，以「有號」成交量累計並落地 positions 表。
 * BUY 增加、SELL 減少；均價只在同方向加倉時重算，減倉時保留（已實現損益由 OMS 的 FIFO KPI 計算）。
 * 穿越零（由多翻空或由空翻多）時，新方向的均價就是這筆成交價。
 */
@Component
public class PositionStore {

    private static final Logger log = LoggerFactory.getLogger(PositionStore.class);
    private static final double EPS = 1e-12;

    private final PositionRepository repo;
    public PositionStore(PositionRepository repo) { this.repo = repo; }

    /** @return 更新後的部位；side 為 null 代表舊版事件 → 拒絕更新（寧可漏算也不可把 SELL 當 BUY）。 */
    public synchronized PositionEntity onFilled(String account, String symbol, OrderSide side, double qty, double price) {
        if (side == null) {
            log.warn("成交事件缺少 side，略過部位更新 [{}] {} qty={}", account, symbol, qty);
            return null;
        }
        String acc = account == null || account.isBlank() ? "main" : account;
        PositionEntity p = repo.findById(new PositionId(acc, symbol)).orElseGet(() -> {
            PositionEntity e = new PositionEntity();
            e.setAccount(acc); e.setSymbol(symbol);
            return e;
        });
        double oldQty = p.getQuantity() == null ? 0 : p.getQuantity();
        double oldAvg = p.getAvgPrice() == null ? 0 : p.getAvgPrice();
        double delta = side == OrderSide.SELL ? -qty : qty;
        double newQty = oldQty + delta;

        double newAvg = computeAvg(oldQty, oldAvg, delta, price);
        if (Math.abs(newQty) < EPS) newQty = 0;

        p.setQuantity(newQty);
        p.setAvgPrice(newAvg);
        p.setUpdatedAt(OffsetDateTime.now());
        return repo.save(p);
    }

    /**
     * 均價規則（純函式，方便測試）：
     *  - 同方向加倉：加權平均
     *  - 減倉但未穿零：均價不變
     *  - 穿零或從零開倉：均價 = 本筆成交價
     *  - 歸零：均價歸零
     */
    static double computeAvg(double oldQty, double oldAvg, double delta, double price) {
        double newQty = oldQty + delta;
        if (price <= 0) return oldAvg;
        if (Math.abs(newQty) < EPS) return 0;
        boolean sameDir = oldQty == 0 || Math.signum(oldQty) == Math.signum(delta);
        boolean crossed = oldQty != 0 && Math.signum(oldQty) != Math.signum(newQty);
        if (crossed || oldQty == 0) return price;
        if (sameDir) return (Math.abs(oldQty) * oldAvg + Math.abs(delta) * price) / Math.abs(newQty);
        return oldAvg;   // 減倉未穿零
    }

    /** 快照；account 為 null 回全部。 */
    /** 清除某帳戶全部部位（虛擬帳戶重置用）。回清除筆數。 */
    public synchronized int clear(String account) {
        List<PositionEntity> rows = repo.findAll().stream().filter(p -> account.equals(p.getAccount())).toList();
        repo.deleteAll(rows);
        return rows.size();
    }

    public List<Map<String, Object>> snapshot(String account) {
        return repo.findAll().stream()
                .filter(p -> account == null || account.equals(p.getAccount()))
                .map(p -> Map.<String, Object>of(
                        "account", p.getAccount(), "symbol", p.getSymbol(),
                        "quantity", p.getQuantity(), "avgPrice", p.getAvgPrice(),
                        "unrealizedPnl", p.getUnrealizedPnl())).collect(Collectors.toList());
    }
}
