package com.quant.common.binance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 幣安伺服器時間偏移校正。簽名請求的 timestamp 必須落在伺服器時間 ±recvWindow（預設 5s）內，
 * 本機時鐘漂移超過就回 -1021「Timestamp for this request is outside of the recvWindow」，所有真單被拒。
 * 每個 base URL 各自維護 offset = serverTime − 本機中點時間（扣掉往返延遲一半）。
 * 無 Spring 依賴：由各服務排程呼叫 {@link #sync}，簽名時用 {@link #now} 取時間；遇 -1021 用 {@link #isTimestampError} 判斷後重同步再送一次。
 */
public class BinanceClock {

    /** 偏移超過此值記 warn：代表本機 NTP 沒在跑，校正能撐住但該修機器。 */
    public static final long WARN_SKEW_MS = 1000;
    public static final int TIMESTAMP_ERROR_CODE = -1021;

    private static final Pattern SERVER_TIME = Pattern.compile("\"serverTime\"\\s*:\\s*(\\d+)");

    /** 回傳 base 的伺服器毫秒時間；失敗丟例外。 */
    @FunctionalInterface
    public interface ServerTimeFetcher { long fetch(String baseUrl) throws Exception; }

    private final ServerTimeFetcher fetcher;
    private final LongSupplier localClock;
    private final Map<String, Long> offsets = new ConcurrentHashMap<>();
    private final Map<String, Long> syncedAt = new ConcurrentHashMap<>();

    public BinanceClock() { this(BinanceClock::httpFetch, System::currentTimeMillis); }

    public BinanceClock(ServerTimeFetcher fetcher, LongSupplier localClock) {
        this.fetcher = fetcher; this.localClock = localClock;
    }

    /** 校正後的「現在」：本機時間 + 該 base 的偏移。沒同步過就是本機時間。 */
    public long now(String baseUrl) { return localClock.getAsLong() + offsets.getOrDefault(baseUrl, 0L); }

    public long offset(String baseUrl) { return offsets.getOrDefault(baseUrl, 0L); }
    public boolean synced(String baseUrl) { return syncedAt.containsKey(baseUrl); }
    public long syncedAt(String baseUrl) { return syncedAt.getOrDefault(baseUrl, 0L); }

    /**
     * 向交易所對時。回傳新的偏移（毫秒）；失敗丟例外、舊偏移保留。
     * 用請求前後本機時間的中點當本機參考，抵消往返延遲。
     */
    public long sync(String baseUrl) throws Exception {
        long t0 = localClock.getAsLong();
        long server = fetcher.fetch(baseUrl);
        long t1 = localClock.getAsLong();
        long off = server - (t0 + t1) / 2;
        offsets.put(baseUrl, off);
        syncedAt.put(baseUrl, t1);
        return off;
    }

    /** 同步但不丟例外：回 null 代表失敗（呼叫端只記 log，簽名退回本機時間）。 */
    public Long trySync(String baseUrl) {
        try { return sync(baseUrl); } catch (Exception e) { return null; }
    }

    public static boolean skewTooLarge(long offsetMs) { return Math.abs(offsetMs) > WARN_SKEW_MS; }

    /** 交易所回應是否為時間偏移錯誤（-1021）。 */
    public static boolean isTimestampError(int code, String body) {
        if (code == TIMESTAMP_ERROR_CODE) return true;
        return body != null && (body.contains("\"code\":-1021") || body.contains("outside of the recvWindow"));
    }

    /** 現貨 /api/v3/time、合約 /fapi/v1/time；依 base 判斷。 */
    public static String timePath(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.toLowerCase();
        return (b.contains("fapi") || b.contains("binancefuture")) ? "/fapi/v1/time" : "/api/v3/time";
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static long httpFetch(String baseUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + timePath(baseUrl)))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        Matcher m = SERVER_TIME.matcher(resp.body());
        if (!m.find()) throw new IllegalStateException("回應無 serverTime: " + resp.body());
        return Long.parseLong(m.group(1));
    }
}
