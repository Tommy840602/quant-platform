package com.quant.account.entity;

import java.io.Serializable;
import java.util.Objects;

/** positions 複合主鍵 (account, symbol)。 */
public class PositionId implements Serializable {
    private String account;
    private String symbol;
    public PositionId() {}
    public PositionId(String account, String symbol) { this.account = account; this.symbol = symbol; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PositionId p)) return false;
        return Objects.equals(account, p.account) && Objects.equals(symbol, p.symbol);
    }
    @Override public int hashCode() { return Objects.hash(account, symbol); }
}
