package com.quant.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 全域 JWT 鑑權過濾器（見架構文件 §12.1）。
 * security.jwt.enabled 預設 true：所有 /api/** 都要 Bearer token；只有 login 與 actuator 匿名。
 * 顯式設 JWT_ENABLED=false 才放行（只適合本機開發），啟動時會大聲警告。
 * SSE（EventSource 不能帶 header）：僅對 */stream 路徑接受 ?access_token=。
 * jwt.secret 必須與 auth-service 相同，且不得為範本佔位字串。
 */
@Order(-100)
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final List<String> WHITELIST = List.of("/api/auth/login", "/actuator");

    private final boolean jwtEnabled;
    private final SecretKey key;

    public JwtAuthFilter(@Value("${jwt.secret:}") String secret,
                         @Value("${security.jwt.enabled:true}") boolean jwtEnabled) {
        this.jwtEnabled = jwtEnabled;
        if (jwtEnabled) {
            assertStrongSecret(secret);
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        } else {
            this.key = null;
            log.warn("""

                ⚠⚠⚠  JWT_ENABLED=false：API Gateway 不驗證身份，任何能連到 :8080 的人都能下單、換金鑰、關熔斷。
                     只允許在本機開發使用。正式環境請移除此設定（預設即為啟用）。
                """);
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!jwtEnabled) return chain.filter(exchange);

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        if (WHITELIST.stream().anyMatch(path::startsWith)) return chain.filter(exchange);

        String token = bearer(request);
        if (token == null && path.endsWith("/stream")) {
            token = request.getQueryParams().getFirst("access_token");   // SSE only
        }
        if (token == null) return unauthorized(exchange);

        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            ServerHttpRequest mutated = request.mutate()
                    .headers(h -> { h.remove("X-User-Id"); h.remove("X-User-Roles"); })   // 不信任外部帶入的身分標頭
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Roles", String.valueOf(claims.get("roles")))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return unauthorized(exchange);
        }
    }

    /** 與 common 的 JwtSecretPolicy 同規則（gateway 是 WebFlux，不引 common 以免帶進 servlet 依賴）。 */
    static void assertStrongSecret(String secret) {
        String s = secret == null ? "" : secret.trim();
        List<String> placeholders = List.of("change-me-in-prod-please-use-a-long-random-secret-key",
                "請改成一段夠長的隨機字串至少32字元", "changeme", "secret", "jwt-secret");
        if (s.isEmpty())
            throw new IllegalStateException("JWT_SECRET 未設定。請執行 `openssl rand -base64 48` 產生並寫入 .env");
        if (placeholders.stream().anyMatch(p -> p.equalsIgnoreCase(s)))
            throw new IllegalStateException("JWT_SECRET 仍是範本佔位字串，任何人都能偽造 token。請換成隨機值");
        if (s.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET 太短（需 ≥ 32 bytes），請執行 `openssl rand -base64 48`");
    }

    private static String bearer(ServerHttpRequest req) {
        String auth = req.getHeaders().getFirst("Authorization");
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Override public int getOrder() { return -100; }
}
