package com.quant.account.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 資金費率（見架構文件 §5、§7.2）：定期抓 USDT-M 永續 premiumIndex 的資金費率，存 Redis。
 * 合約持倉的資金費結算 = 部位名目 × 資金費率（多頭付/收，視費率正負），此處記錄示意。
 */
@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final StringRedisTemplate redis;

    @Value("${binance.futures-rest-url:https://testnet.binancefuture.com}")
    private String futUrl;
    @Value("#{'${funding.symbols:BTCUSDT,ETHUSDT}'.split(',')}")
    private List<String> symbols;

    public FundingService(StringRedisTemplate redis) { this.redis = redis; }

    @Scheduled(fixedRate = 60000)
    public void poll() {
        for (String sym : symbols) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(futUrl + "/fapi/v1/premiumIndex?symbol=" + sym))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode n = mapper.readTree(resp.body());
                    String rate = n.path("lastFundingRate").asText("0");
                    redis.opsForValue().set("funding:" + sym, rate);
                }
            } catch (Exception e) { log.debug("抓資金費率 {} 失敗: {}", sym, e.getMessage()); }
        }
    }

    public String rate(String symbol) {
        String r = redis.opsForValue().get("funding:" + symbol.toUpperCase());
        return r == null ? "0" : r;
    }
}
