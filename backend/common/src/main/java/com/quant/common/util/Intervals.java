package com.quant.common.util;

/** 幣安 interval 字串 ↔ 毫秒。 */
public final class Intervals {
    private Intervals() {}

    public static long millis(String interval) {
        if (interval == null || interval.length() < 2) throw new IllegalArgumentException("interval: " + interval);
        long n = Long.parseLong(interval.substring(0, interval.length() - 1));
        char u = interval.charAt(interval.length() - 1);
        long unit = switch (u) {
            case 's' -> 1_000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 7 * 86_400_000L;
            case 'M' -> 30L * 86_400_000L;
            default -> throw new IllegalArgumentException("interval: " + interval);
        };
        return n * unit;
    }
}
