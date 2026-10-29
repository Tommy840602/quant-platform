package com.quant.order.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 訂單落地實體，對應 orders 表（見 infra/init/timescale-init.sql）。 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_order_id", unique = true, nullable = false)
    private String clientOrderId;
    @Column(name = "exchange_order_id")
    private String exchangeOrderId;
    private String symbol;
    private String side;
    private String type;
    private Double price;
    private Double quantity;
    @Column(name = "filled_qty")
    private Double filledQty = 0.0;
    @Column(name = "avg_price")
    private Double avgPrice = 0.0;
    private String status;
    @Column(name = "strategy_id")
    private String strategyId;
    private String account = "main";
    /** SPOT | USDT_M — 對帳回查時決定打現貨或合約端點 */
    private String market = "SPOT";
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    // getters/setters
    public Long getId() { return id; }
    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String v) { this.clientOrderId = v; }
    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String v) { this.exchangeOrderId = v; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String v) { this.symbol = v; }
    public String getSide() { return side; }
    public void setSide(String v) { this.side = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public Double getPrice() { return price; }
    public void setPrice(Double v) { this.price = v; }
    public Double getQuantity() { return quantity; }
    public void setQuantity(Double v) { this.quantity = v; }
    public Double getFilledQty() { return filledQty; }
    public void setFilledQty(Double v) { this.filledQty = v; }
    public Double getAvgPrice() { return avgPrice; }
    public void setAvgPrice(Double v) { this.avgPrice = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getStrategyId() { return strategyId; }
    public void setStrategyId(String v) { this.strategyId = v; }
    public String getMarket() { return market; }
    public void setMarket(String v) { this.market = v; }
    public String getAccount() { return account; }
    public void setAccount(String v) { this.account = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
}
