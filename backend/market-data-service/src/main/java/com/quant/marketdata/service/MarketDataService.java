package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import com.quant.common.event.MarketDataEvent;
import com.quant.common.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/** 行情處理：K 線快照/分發/落地；訂單簿深度快照存 Redis（見架構文件 §5.2.1）。 */
@Service
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);
    private final KafkaTemplate<String, Object> kafka;
    private final StringRedisTemplate redis;

    public MarketDataService(KafkaTemplate<String, Object> kafka, StringRedisTemplate redis) {
        this.kafka = kafka; this.redis = redis;
    }

    public void onKline(Kline k) {
        // 10 分鐘 TTL：行情串流掛掉時不讓舊價一直留著當參考價（下單閘道拿不到會改打 REST ticker）
        redis.opsForValue().set("price:" + k.symbol(), String.valueOf(k.close()), 10, TimeUnit.MINUTES);
        kafka.send(Topics.MARKET_KLINE, k.symbol(), new MarketDataEvent("kline", k, System.currentTimeMillis()));
        if (k.closed()) log.debug("persist closed kline {} {} close={}", k.symbol(), k.interval(), k.close());
    }

    /** 訂單簿深度快照（partial book depth）存 Redis，10 秒過期。 */
    public void onDepth(String symbol, String json) {
        redis.opsForValue().set("depth:" + symbol, json, 10, TimeUnit.SECONDS);
    }
}
