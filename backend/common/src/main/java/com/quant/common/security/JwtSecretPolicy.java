package com.quant.common.security;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** JWT 祕鑰強度檢查：拒絕範本佔位字串與太短的祕鑰，啟動即失敗，不讓可偽造的 token 上線。 */
public final class JwtSecretPolicy {
    private JwtSecretPolicy() {}

    public static final List<String> PLACEHOLDERS = List.of(
            "change-me-in-prod-please-use-a-long-random-secret-key",
            "請改成一段夠長的隨機字串至少32字元",
            "changeme", "secret", "jwt-secret");

    public static void assertStrong(String secret) {
        String s = secret == null ? "" : secret.trim();
        if (s.isEmpty())
            throw new IllegalStateException("JWT_SECRET 未設定。請執行 `openssl rand -base64 48` 產生並寫入 .env");
        if (PLACEHOLDERS.stream().anyMatch(p -> p.equalsIgnoreCase(s)))
            throw new IllegalStateException("JWT_SECRET 仍是範本佔位字串，任何人都能偽造 token。請換成隨機值");
        if (s.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET 太短（需 ≥ 32 bytes），請執行 `openssl rand -base64 48`");
    }
}
