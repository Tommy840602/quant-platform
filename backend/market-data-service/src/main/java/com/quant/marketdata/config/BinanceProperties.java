package com.quant.marketdata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** 幣安連線設定，支援 testnet / mainnet 切換（見架構文件 §6.4）。 */
@Component
@ConfigurationProperties(prefix = "binance")
public class BinanceProperties {
    /** 行情 WS base，如 wss://stream.binance.com:9443 或 testnet。 */
    private String wsBaseUrl = "wss://stream.testnet.binance.vision";
    /** REST base，如 https://api.binance.com 或 testnet。 */
    private String restBaseUrl = "https://testnet.binance.vision";
    /** 訂閱的行情，如 btcusdt@kline_1m。 */
    private List<String> streams = List.of("btcusdt@kline_1m", "ethusdt@kline_1m");

    public String getWsBaseUrl() { return wsBaseUrl; }
    public void setWsBaseUrl(String v) { this.wsBaseUrl = v; }
    public String getRestBaseUrl() { return restBaseUrl; }
    public void setRestBaseUrl(String v) { this.restBaseUrl = v; }
    /** 機器人可選的額外 K 線週期：每條 @kline_1m 會自動多訂這些週期（機器人可用 5m / 15m / 1h 跑，不必全吃 1 分 K）。 */
    private List<String> extraIntervals = List.of("5m", "15m", "1h");
    public List<String> getExtraIntervals() { return extraIntervals; }
    public void setExtraIntervals(List<String> v) { this.extraIntervals = v == null ? List.of() : v; }

    /** 設定的串流 + 每條 1m K 線衍生的額外週期（去重、保持順序）。 */
    public List<String> getStreams() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(streams);
        for (String st : streams) {
            if (!st.endsWith("@kline_1m")) continue;
            String base = st.substring(0, st.length() - "1m".length());
            for (String iv : extraIntervals) if (iv != null && !iv.isBlank()) out.add(base + iv.trim());
        }
        return new java.util.ArrayList<>(out);
    }
    public void setStreams(List<String> v) { this.streams = v; }
}
