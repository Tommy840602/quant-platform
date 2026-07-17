package com.quant.account.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 部位落地實體，對應 positions 表（見 infra/init/timescale-init.sql）。 */
@Entity
@Table(name = "positions")
@IdClass(PositionId.class)
public class PositionEntity {
    @Id private String account;
    @Id private String symbol;
    private Double quantity = 0.0;
    @Column(name = "avg_price") private Double avgPrice = 0.0;
    @Column(name = "unrealized_pnl") private Double unrealizedPnl = 0.0;
    @Column(name = "updated_at") private OffsetDateTime updatedAt = OffsetDateTime.now();

    public String getAccount() { return account; }
    public void setAccount(String v) { this.account = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public Double getQuantity() { return quantity; }
    public void setQuantity(Double v) { this.quantity = v; }
    public Double getAvgPrice() { return avgPrice; }
    public void setAvgPrice(Double v) { this.avgPrice = v; }
    public Double getUnrealizedPnl() { return unrealizedPnl; }
    public void setUnrealizedPnl(Double v) { this.unrealizedPnl = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
