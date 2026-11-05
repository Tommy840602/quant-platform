package com.quant.gateway.filter;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交易管理操作的權限（review #9）：JWT 驗身分之外，敏感操作還要有對應權限。
 * <ul>
 *   <li>TRADING_CONFIG_WRITE：切環境、改金鑰、上膛 / Dry-Run、每單名目、晉升管線、自動配資</li>
 *   <li>RISK_OVERRIDE：風控門檻、熔斷開關、日計數重置、回撤熔斷重置、下單風控</li>
 *   <li>TRADE_EXECUTE：下單 / 撤單 / 槓桿 / 條件單、機器人上下線 / 參數 / 建立 / 刪除</li>
 * </ul>
 * 角色：ADMIN 全部；TRADER 只有 TRADE_EXECUTE；其他角色（例如 VIEWER）只能讀。
 */
public final class Permissions {
    private Permissions() {}

    public static final String CONFIG = "TRADING_CONFIG_WRITE", RISK = "RISK_OVERRIDE", TRADE = "TRADE_EXECUTE";

    static final Map<String, Set<String>> ROLE_PERMS = Map.of(
            "ADMIN", Set.of(CONFIG, RISK, TRADE),
            "TRADER", Set.of(TRADE));

    /** 依順序比對（越具體越前面）：[method 或 *, 路徑前綴, 權限]。 */
    static final List<String[]> RULES = List.of(
            new String[]{"*", "/api/order/config", CONFIG},
            new String[]{"*", "/api/account/config", CONFIG},
            new String[]{"*", "/api/strategy/strategies/order-notional", CONFIG},
            new String[]{"*", "/api/strategy/strategies/pipeline", CONFIG},
            new String[]{"*", "/api/strategy/strategies/bandit", CONFIG},
            new String[]{"*", "/api/risk/", RISK},
            new String[]{"*", "/api/order/orders/risk-config", RISK},
            new String[]{"*", "/api/account/drawdown", RISK},
            new String[]{"*", "/api/account/breaker", RISK},
            new String[]{"*", "/api/order/", TRADE},
            new String[]{"*", "/api/strategy/", TRADE},
            new String[]{"*", "/api/backtest/", TRADE},
            new String[]{"*", "/api/account/", TRADE},
            new String[]{"*", "/api/notify/", TRADE});

    /** 需要的權限；null = 不需要（GET 等讀取、或沒列管的路徑）。只管會改東西的方法。 */
    public static String required(String method, String path) {
        if (method == null || "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) return null;
        for (String[] r : RULES) if (("*".equals(r[0]) || r[0].equalsIgnoreCase(method)) && path.startsWith(r[1])) return r[2];
        return null;
    }

    /** roles 來自 JWT claim（List 或 "[ADMIN, TRADER]" / "ADMIN,TRADER" 字串）。 */
    public static boolean has(Object roles, String perm) {
        for (String role : parse(roles)) if (ROLE_PERMS.getOrDefault(role, Set.of()).contains(perm)) return true;
        return false;
    }

    static List<String> parse(Object roles) {
        if (roles instanceof Collection<?> c) return c.stream().map(o -> String.valueOf(o).trim().toUpperCase()).toList();
        if (roles == null) return List.of();
        String s = String.valueOf(roles).replace("[", "").replace("]", "");
        return java.util.Arrays.stream(s.split(",")).map(x -> x.trim().toUpperCase()).filter(x -> !x.isEmpty()).toList();
    }
}
