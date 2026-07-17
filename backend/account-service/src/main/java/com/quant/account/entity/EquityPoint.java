package com.quant.account.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 權益曲線點，對應 equity_curve 表。 */
@Entity
@Table(name = "equity_curve")
public class EquityPoint {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String account = "main";
    private OffsetDateTime ts = OffsetDateTime.now();
    private Double equity;

    public Long getId() { return id; }
    public String getAccount() { return account; }
    public void setAccount(String v) { this.account = v; }
    public OffsetDateTime getTs() { return ts; }
    public void setTs(OffsetDateTime v) { this.ts = v; }
    public Double getEquity() { return equity; }
    public void setEquity(Double v) { this.equity = v; }
}
