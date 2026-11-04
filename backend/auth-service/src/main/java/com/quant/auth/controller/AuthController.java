package com.quant.auth.controller;

import com.quant.auth.entity.UserEntity;
import com.quant.auth.service.JwtService;
import com.quant.auth.service.UserService;
import com.quant.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 認證 API（見架構文件 §12.1）。
 * login 匿名；register 需 ADMIN（gateway 驗過 JWT 後注入 X-User-Roles）；password 改自己的密碼。
 */
@RestController
@RequestMapping("/")
public class AuthController {

    private final JwtService jwt;
    private final UserService users;
    private final com.quant.auth.service.LoginGuard guard;

    public AuthController(JwtService jwt, UserService users, com.quant.auth.service.LoginGuard guard) {
        this.jwt = jwt; this.users = users; this.guard = guard;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                  jakarta.servlet.http.HttpServletRequest req) {
        String username = body.get("username");
        String ip = com.quant.auth.service.LoginGuard.clientIp(req.getHeader("CF-Connecting-IP"), req.getHeader("X-Real-IP"),
                req.getHeader("X-Forwarded-For"), req.getRemoteAddr());
        long locked = guard.lockedSeconds(username, ip);
        if (locked > 0) return ApiResponse.error(429, "登入失敗次數過多，請 " + ((locked + 59) / 60) + " 分鐘後再試");
        return users.authenticate(username, body.get("password"))
                .map(u -> {
                    guard.onSuccess(username);
                    var roles = UserService.roles(u);
                    String token = jwt.generate(u.getUsername(), roles);
                    return ApiResponse.ok(Map.<String, Object>of(
                            "accessToken", token, "tokenType", "Bearer",
                            "username", u.getUsername(), "roles", roles,
                            "expiresIn", jwt.ttlSeconds()));
                })
                .orElseGet(() -> { guard.onFailure(username, ip); return ApiResponse.error(401, "帳號或密碼錯誤"); });
    }

    /** 建立帳號：只有 ADMIN 能呼叫。 */
    @PostMapping("/register")
    public ApiResponse<String> register(@RequestHeader(value = "X-User-Roles", required = false) String callerRoles,
                                        @RequestBody Map<String, String> body) {
        if (callerRoles == null || !callerRoles.contains("ADMIN")) return ApiResponse.error(403, "需要 ADMIN 權限");
        try {
            UserEntity u = users.create(body.get("username"), body.get("password"), body.getOrDefault("roles", "TRADER"));
            return ApiResponse.ok("created: " + u.getUsername());
        } catch (IllegalArgumentException e) { return ApiResponse.error(400, e.getMessage()); }
    }

    /** 改自己的密碼。 */
    @PostMapping("/password")
    public ApiResponse<String> changePassword(@RequestHeader(value = "X-User-Id", required = false) String caller,
                                              @RequestBody Map<String, String> body) {
        if (caller == null || caller.isBlank()) return ApiResponse.error(401, "未登入");
        try {
            users.changePassword(caller, body.get("oldPassword"), body.get("newPassword"));
            return ApiResponse.ok("password changed");
        } catch (IllegalArgumentException e) { return ApiResponse.error(400, e.getMessage()); }
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(@RequestHeader(value = "X-User-Id", required = false) String caller,
                                               @RequestHeader(value = "X-User-Roles", required = false) String roles) {
        if (caller == null || caller.isBlank()) return ApiResponse.error(401, "未登入");
        return ApiResponse.ok(Map.of("username", caller, "roles", roles == null ? "" : roles));
    }
}
