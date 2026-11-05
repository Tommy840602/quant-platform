package com.quant.account.entity;

import java.io.Serializable;
import java.util.Objects;

/** positions 複合主鍵 (account, env, executionMode, market, symbol)：現貨 / 合約、模擬 / 真單、各環境分開。 */
public class PositionId implements Serializable {
    private String account;
    private String env;
    private String executionMode;
    private String market;
    private String symbol;
    public PositionId() {}
    public PositionId(String account, String env, String executionMode, String market, String symbol) {
        this.account = account; this.env = env; this.executionMode = executionMode; this.market = market; this.symbol = symbol;
    }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PositionId p)) return false;
        return Objects.equals(account, p.account) && Objects.equals(env, p.env) && Objects.equals(executionMode, p.executionMode)
                && Objects.equals(market, p.market) && Objects.equals(symbol, p.symbol);
    }
    @Override public int hashCode() { return Objects.hash(account, env, executionMode, market, symbol); }
}
