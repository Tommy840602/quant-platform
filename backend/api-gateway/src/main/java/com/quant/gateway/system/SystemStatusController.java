package com.quant.gateway.system;

import com.quant.gateway.filter.JwtAuthFilter;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系統狀態總覽：GET /api/system/status
 * 逐一打每個服務的 actuator/health（經 Nacos 負載平衡），回每個服務 UP/DOWN、延遲、各元件（db / redis / kafka / discovery）狀態。
 * 這是 gateway 自己的端點，不走路由，所以 JWT 在這裡自己驗。
 */
@RestController
public class SystemStatusController {

    @Configuration
    static class LbWebClient {
        @Bean @LoadBalanced WebClient.Builder lbWebClientBuilder() { return WebClient.builder(); }
    }

    /** 服務名 → 其 actuator 路徑（各服務 context-path 不同） */
    static final Map<String, String> SERVICES = new LinkedHashMap<>();
    static {
        SERVICES.put("auth-service", "/api/auth/actuator/health");
        SERVICES.put("market-data-service", "/api/market/actuator/health");
        SERVICES.put("strategy-service", "/api/strategy/actuator/health");
        SERVICES.put("order-execution-service", "/api/order/actuator/health");
        SERVICES.put("risk-service", "/api/risk/actuator/health");
        SERVICES.put("account-service", "/api/account/actuator/health");
        SERVICES.put("backtest-service", "/api/backtest/actuator/health");
        SERVICES.put("notification-service", "/api/notify/actuator/health");
    }

    private final WebClient web;
    private final JwtAuthFilter jwt;

    public SystemStatusController(WebClient.Builder lbWebClientBuilder, JwtAuthFilter jwt) {
        this.web = lbWebClientBuilder.build(); this.jwt = jwt;
    }

    @GetMapping("/api/system/status")
    public Mono<Map<String, Object>> status(ServerHttpRequest req) {
        if (!jwt.isAuthorized(req)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        long t0 = System.currentTimeMillis();
        return Flux.fromIterable(SERVICES.entrySet())
                .flatMap(e -> probe(e.getKey(), e.getValue()))
                .collectList()
                .map(list -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("code", 0); out.put("message", "ok");
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("gateway", Map.of("status", "UP"));
                    List<Map<String, Object>> sorted = list.stream()
                            .sorted((a, b) -> Integer.compare(order(a), order(b))).toList();
                    data.put("services", sorted);
                    long up = list.stream().filter(m -> "UP".equals(m.get("status"))).count();
                    data.put("summary", Map.of("up", up, "total", list.size(), "elapsedMs", System.currentTimeMillis() - t0));
                    out.put("data", data);
                    return out;
                });
    }

    private static int order(Map<String, Object> m) {
        int i = 0; for (String k : SERVICES.keySet()) { if (k.equals(m.get("name"))) return i; i++; } return i;
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> probe(String name, String path) {
        long t0 = System.currentTimeMillis();
        return web.get().uri("http://" + name + path).retrieve()
                .onStatus(s -> s.value() == 503, r -> Mono.empty())   // DOWN 也回 body，不當成錯誤
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(4))
                .map(body -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name);
                    m.put("status", String.valueOf(body.getOrDefault("status", "UNKNOWN")));
                    m.put("latencyMs", System.currentTimeMillis() - t0);
                    Map<String, Object> comps = new LinkedHashMap<>();
                    Object c = body.get("components");
                    if (c instanceof Map<?, ?> cm) {
                        for (Map.Entry<?, ?> ce : cm.entrySet()) {
                            Object v = ce.getValue();
                            if (v instanceof Map<?, ?> vm && vm.get("status") != null) comps.put(String.valueOf(ce.getKey()), String.valueOf(vm.get("status")));
                        }
                    }
                    m.put("components", comps);
                    return m;
                })
                .onErrorResume(ex -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", name); m.put("status", "DOWN");
                    m.put("latencyMs", System.currentTimeMillis() - t0);
                    m.put("error", ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage().split("\\n")[0]));
                    m.put("components", Map.of());
                    return Mono.just(m);
                });
    }
}
