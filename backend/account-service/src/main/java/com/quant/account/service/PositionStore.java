package com.quant.account.service;

import com.quant.account.entity.PositionEntity;
import com.quant.account.entity.PositionId;
import com.quant.account.repository.PositionRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 部位管理（見架構文件 §9.2）：多帳戶，以成交事件累計並落地 positions 表。 */
@Component
public class PositionStore {

    private final PositionRepository repo;
    public PositionStore(PositionRepository repo) { this.repo = repo; }

    public synchronized void onFilled(String account, String symbol, double qty, double price) {
        String acc = account == null || account.isBlank() ? "main" : account;
        PositionEntity p = repo.findById(new PositionId(acc, symbol)).orElseGet(() -> {
            PositionEntity e = new PositionEntity();
            e.setAccount(acc); e.setSymbol(symbol);
            return e;
        });
        double oldQty = p.getQuantity() == null ? 0 : p.getQuantity();
        double newQty = oldQty + qty;
        if (price > 0 && newQty != 0) {
            double oldNotional = oldQty * (p.getAvgPrice() == null ? 0 : p.getAvgPrice());
            p.setAvgPrice((oldNotional + qty * price) / newQty);
        }
        p.setQuantity(newQty);
        p.setUpdatedAt(OffsetDateTime.now());
        repo.save(p);
    }

    /** 快照；account 為 null 回全部。 */
    public List<Map<String, Object>> snapshot(String account) {
        return repo.findAll().stream()
                .filter(p -> account == null || account.equals(p.getAccount()))
                .map(p -> Map.<String, Object>of(
                        "account", p.getAccount(), "symbol", p.getSymbol(),
                        "quantity", p.getQuantity(), "avgPrice", p.getAvgPrice(),
                        "unrealizedPnl", p.getUnrealizedPnl())).collect(Collectors.toList());
    }
}
