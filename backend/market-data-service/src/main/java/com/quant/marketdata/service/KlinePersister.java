package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 已收盤 K 線落 TimescaleDB：先進佇列，每秒或滿 200 根批次 upsert（20 條 1m 串流每分鐘才 20 根，批次綽綽有餘）。
 * DB 掛了不丟：留在佇列重試，上限 50k 根（約 1m×20 幣 × 40 小時），超過丟最舊並告警。
 */
@Service
public class KlinePersister {

    private static final Logger log = LoggerFactory.getLogger(KlinePersister.class);
    static final int FLUSH_AT = 200, MAX_QUEUE = 50_000;

    private final KlineRepository repo;
    private final ConcurrentLinkedQueue<Kline> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicLong persisted = new AtomicLong(), dropped = new AtomicLong();
    private volatile long lastError = 0;
    private volatile String lastErrorMsg = "";

    public KlinePersister(KlineRepository repo) { this.repo = repo; }

    public void enqueue(Kline k) {
        if (!k.closed()) return;
        if (size.get() >= MAX_QUEUE) {
            Kline old = queue.poll();
            if (old != null) { size.decrementAndGet(); dropped.incrementAndGet(); }
            if (dropped.get() % 1000 == 1) log.error("K 線落庫佇列滿（{}），已丟 {} 根 — DB 是否掛了？{}", MAX_QUEUE, dropped.get(), lastErrorMsg);
        }
        queue.add(k);
        if (size.incrementAndGet() >= FLUSH_AT) flush();
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void flush() {
        if (queue.isEmpty()) return;
        List<Kline> batch = new ArrayList<>(Math.min(size.get(), 1000));
        Kline k;
        while (batch.size() < 1000 && (k = queue.poll()) != null) { batch.add(k); size.decrementAndGet(); }
        try {
            repo.upsertAll(batch);
            persisted.addAndGet(batch.size());
        } catch (Exception e) {
            // 放回佇列頭：ConcurrentLinkedQueue 沒有 addFirst，直接加回尾端（順序無所謂，upsert 冪等）
            queue.addAll(batch); size.addAndGet(batch.size());
            lastError = System.currentTimeMillis(); lastErrorMsg = e.getMessage();
            log.warn("K 線落庫失敗（{} 根留在佇列重試）: {}", batch.size(), e.getMessage());
        }
    }

    @PreDestroy
    public void drain() { flush(); }

    public java.util.Map<String, Object> stats() {
        return java.util.Map.of("queued", size.get(), "persisted", persisted.get(), "dropped", dropped.get(),
                "lastErrorAt", lastError, "lastError", lastErrorMsg);
    }
}
