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
    public List<String> getStreams() { return streams; }
    public void setStreams(List<String> v) { this.streams = v; }
}
