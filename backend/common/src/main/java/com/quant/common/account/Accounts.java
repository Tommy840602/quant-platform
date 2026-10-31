package com.quant.common.account;

/**
 * 帳戶模式：平台固定兩個帳戶。
 * - {@link #PAPER} 虛擬帳戶：訂單在 OMS 本機撮合（市價 = 參考價、限價 = 限價），不碰交易所；現金帳 / 部位走 Redis + DB
 * - {@link #REAL}  真實帳戶：訂單送幣安（受 TRADING_DRY_RUN 安全鎖與金鑰設定約束）
 * 目前啟用的模式存 Redis {@link #MODE_KEY}，手動下單預設、策略機器人、餘額、權益曲線都跟著切。
 */
public final class Accounts {
    private Accounts() {}

    public static final String PAPER = "paper";
    public static final String REAL = "main";
    public static final String MODE_KEY = "account:mode";
    public static final String DEFAULT_MODE = PAPER;

    public static boolean isPaper(String account) { return PAPER.equalsIgnoreCase(account); }

    /** 任何不是 paper 的值（含 null / 舊的 main）都視為真實帳戶 main。 */
    public static String normalize(String account) { return isPaper(account) ? PAPER : REAL; }

    /** Redis 讀到的模式字串 → paper | main；空值或壞值回預設（虛擬，安全）。 */
    public static String modeOrDefault(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_MODE;
        return REAL.equalsIgnoreCase(raw) ? REAL : PAPER;
    }

    public static String label(String account) { return isPaper(account) ? "虛擬帳戶" : "真實帳戶"; }
}
