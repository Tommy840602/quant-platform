package com.quant.marketdata.service;

import com.quant.common.dto.Kline;
import com.quant.common.util.Intervals;
import com.quant.marketdata.config.BinanceProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 啟動暖機：每條 kline 串流從「DB 最後一根」或「now − historyBars×interval」（取較晚者）用 REST 分頁補到現在，
 * 回測 / 圖表一開機就有歷史；服務重啟期間漏掉的 K 線也在這裡補回（WS 層的補抓只認記憶體內的最後一根）。
 * 背景執行緒跑，不擋啟動；20 幣 × 2000 根 1m ≈ 40 次請求（weight 2/次），遠低於 1200/分鐘上限。
 */
@Service
public class KlineHistoryLoader {

    private static final Logger log = LoggerFactory.getLogger(KlineHistoryLoader.class);

    @Value("${binance.history-bars:2000}") private int historyBars;
    @Value("${binance.history-enabled:true}") private boolean enabled;

    private final BinanceProperties props;
    private final KlineBackfillService backfill;
    private final KlineRepository repo;
    private final LongSupplier clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile long lastRunAt = 0, lastLoaded = 0;

    @org.springframework.beans.factory.annotation.Autowired
    public KlineHistoryLoader(BinanceProperties props, KlineBackfillService backfill, KlineRepository repo) {
        this(props, backfill, repo, System::currentTimeMillis);
    }

    KlineHistoryLoader(BinanceProperties props, KlineBackfillService backfill, KlineRepository repo, LongSupplier clock) {
        this.props = props; this.backfill = backfill; this.repo = repo; this.clock = clock;
    }

    @PostConstruct
    public void start() {
        if (!enabled) return;
        Thread t = new Thread(this::loadAll, "kline-history-loader");
        t.setDaemon(true); t.start();
    }

    /** 全部串流暖機；回補的總根數。可重入保護：同時只跑一次。 */
    public long loadAll() {
        if (!running.compareAndSet(false, true)) return 0;
        long total = 0;
        try {
            for (String stream : props.getStreams()) {
                int at = stream.indexOf("@kline_");
                if (at < 0) continue;
                String symbol = stream.substring(0, at).toUpperCase();
                String interval = stream.substring(at + "@kline_".length());
                try { total += load(symbol, interval); }
                catch (Exception e) { log.warn("暖機失敗 {} {}: {}", symbol, interval, e.getMessage()); }
            }
            log.info("K 線歷史暖機完成：補 {} 根", total);
        } finally {
            lastRunAt = clock.getAsLong(); lastLoaded = total; running.set(false);
        }
        return total;
    }

    /** 單一 symbol/interval：分頁抓到追上現在為止。 */
    long load(String symbol, String interval) {
        long now = clock.getAsLong();
        long step = Intervals.millis(interval);
        long floor = now - (long) historyBars * step;
        Long last = repo.latestOpenTime(symbol, interval);
        long since = last == null ? floor : Math.max(last, floor);
        long loaded = 0;
        for (int page = 0; page < 50; page++) {              // 50 頁 × 1000 根保險上限
            List<Kline> rows = backfill.fetchClosedSince(symbol, interval, since, now);
            if (rows.isEmpty()) break;
            repo.upsertAll(rows);
            loaded += rows.size();
            since = rows.get(rows.size() - 1).openTime();
            if (rows.size() < 1000 || since + step >= now) break;   // 最後一頁 / 已追上
        }
        if (loaded > 0) log.info("暖機 {} {}: +{} 根（至 {}）", symbol, interval, loaded, since);
        return loaded;
    }

    public java.util.Map<String, Object> stats() {
        return java.util.Map.of("running", running.get(), "lastRunAt", lastRunAt, "lastLoaded", lastLoaded, "historyBars", historyBars);
    }
}
