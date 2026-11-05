package com.quant.risk.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * 風控計數與「在途額度預留」的原子操作（review #5）：
 * 以前「讀計數 → 判斷 → 加計數」分三步，兩張單同時進來會一起通過上限；
 * 集中度 / 合約曝險也只看已成交持倉，看不到剛核可、還沒成交的單。
 */
public interface RiskStore {
    /** 檢查並預留（原子）。headroom &lt; 0 = 不檢查曝險；kind = "spot" | "fut" | ""（不預留曝險）。 */
    Result reserve(String ordersKey, String notionalKey, String pendingKey, int maxOrders, double maxNotional,
                   double notional, String cid, long nowMs, long windowMs, String kind, double headroom);

    /** 只累計（減倉 / 平倉：不擋，但要算進今日統計）。 */
    void count(String ordersKey, String notionalKey, double notional);

    /** 讀計數（狀態頁用）。 */
    double get(String key);

    /** code：0 核可、1 次數滿、2 名目滿、3 曝險（含在途）超過；value = 觸發時的現值。 */
    record Result(int code, double value) {}

    /** Redis Lua：整段在 Redis 單執行緒內完成，不會被並發穿插。 */
    final class Redis implements RiskStore {
        private static final DefaultRedisScript<List> RESERVE = new DefaultRedisScript<>("""
                local orders = tonumber(redis.call('GET', KEYS[1]) or '0')
                local maxOrders = tonumber(ARGV[1])
                if maxOrders > 0 and orders >= maxOrders then return {1, tostring(orders)} end
                local nt = tonumber(redis.call('GET', KEYS[2]) or '0')
                local maxN = tonumber(ARGV[2]); local notional = tonumber(ARGV[3])
                if maxN > 0 and notional > 0 and nt + notional > maxN then return {2, tostring(nt)} end
                local kind = ARGV[8]
                if kind ~= '' then
                  local now = tonumber(ARGV[6]); local win = tonumber(ARGV[7])
                  redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - win)
                  local pending = 0
                  for _, m in ipairs(redis.call('ZRANGE', KEYS[3], 0, -1)) do
                    local k, v = string.match(m, '^(%a+)|([%d%.eE%-]+)|')
                    if k == kind then pending = pending + tonumber(v) end
                  end
                  local headroom = tonumber(ARGV[9])
                  if headroom >= 0 and pending + notional > headroom then return {3, tostring(pending)} end
                  redis.call('ZADD', KEYS[3], now, kind .. '|' .. ARGV[3] .. '|' .. ARGV[5])
                  redis.call('PEXPIRE', KEYS[3], win * 2)
                end
                redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], 172800)
                if notional > 0 then redis.call('INCRBYFLOAT', KEYS[2], ARGV[3]); redis.call('EXPIRE', KEYS[2], 172800) end
                return {0, '0'}
                """, List.class);
        private final StringRedisTemplate redis;
        public Redis(StringRedisTemplate redis) { this.redis = redis; }

        @Override
        public Result reserve(String ordersKey, String notionalKey, String pendingKey, int maxOrders, double maxNotional,
                              double notional, String cid, long nowMs, long windowMs, String kind, double headroom) {
            List<?> r = redis.execute(RESERVE, List.of(ordersKey, notionalKey, pendingKey),
                    String.valueOf(maxOrders), String.valueOf(maxNotional), String.valueOf(notional), "172800",
                    cid == null ? "" : cid, String.valueOf(nowMs), String.valueOf(windowMs), kind, String.valueOf(headroom));
            if (r == null || r.size() < 2) throw new IllegalStateException("風控預留無回應");
            return new Result(((Number) r.get(0)).intValue(), Double.parseDouble(String.valueOf(r.get(1))));
        }

        @Override
        public double get(String key) {
            try { String v = redis.opsForValue().get(key); return v == null ? 0 : Double.parseDouble(v); } catch (Exception e) { return 0; }
        }

        @Override
        public void count(String ordersKey, String notionalKey, double notional) {
            try {
                redis.opsForValue().increment(ordersKey, 1); redis.expire(ordersKey, java.time.Duration.ofHours(48));
                if (notional > 0) { redis.opsForValue().increment(notionalKey, notional); redis.expire(notionalKey, java.time.Duration.ofHours(48)); }
            } catch (Exception ignore) {}
        }
    }

    /** 單機記憶體版（測試用；語意與 Lua 版相同，以 synchronized 保證原子）。 */
    class Memory implements RiskStore {
        final java.util.Map<String, Double> kv = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String, java.util.TreeMap<Long, java.util.List<String>>> z = new java.util.concurrent.ConcurrentHashMap<>();
        @Override
        public synchronized Result reserve(String ok, String nk, String pk, int maxOrders, double maxN, double notional, String cid,
                                           long now, long win, String kind, double headroom) {
            double orders = kv.getOrDefault(ok, 0.0), nt = kv.getOrDefault(nk, 0.0);
            if (maxOrders > 0 && orders >= maxOrders) return new Result(1, orders);
            if (maxN > 0 && notional > 0 && nt + notional > maxN) return new Result(2, nt);
            if (!kind.isEmpty()) {
                var m = z.computeIfAbsent(pk, k -> new java.util.TreeMap<>());
                m.headMap(now - win, true).clear();
                double pending = m.values().stream().flatMap(java.util.List::stream)
                        .filter(s -> s.startsWith(kind + "|")).mapToDouble(s -> Double.parseDouble(s.split("\\|")[1])).sum();
                if (headroom >= 0 && pending + notional > headroom) return new Result(3, pending);
                m.computeIfAbsent(now, k -> new java.util.ArrayList<>()).add(kind + "|" + notional + "|" + cid);
            }
            kv.merge(ok, 1.0, Double::sum);
            if (notional > 0) kv.merge(nk, notional, Double::sum);
            return new Result(0, 0);
        }
        @Override public double get(String key) { return kv.getOrDefault(key, 0.0); }
        @Override public synchronized void count(String ok, String nk, double notional) {
            kv.merge(ok, 1.0, Double::sum); if (notional > 0) kv.merge(nk, notional, Double::sum);
        }
    }
}
