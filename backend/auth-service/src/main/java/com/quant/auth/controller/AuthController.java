package com.quant.auth.controller;

import com.quant.auth.service.JwtService;
import com.quant.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 認證 API（見架構文件 §12.1）。骨架以記憶體帳號示範；正式應查 DB 並用 BCrypt 驗證密碼。
 * 預設帳號：admin / admin。
 */
@RestController
@RequestMapping("/")
public class AuthController {

    private final JwtService jwt;
    public AuthController(JwtService jwt) { this.jwt = jwt; }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        // TODO: 改為查 DB + BCrypt.matches
        if ("admin".equals(username) && "admin".equals(password)) {
            String token = jwt.generate(username, List.of("ADMIN"));
            return ApiResponse.ok(Map.of("accessToken", token, "tokenType", "Bearer", "username", username));
        }
        return ApiResponse.error(401, "帳號或密碼錯誤");
    }

    @PostMapping("/register")
    public ApiResponse<String> register(@RequestBody Map<String, String> body) {
        // TODO: 建立帳號（BCrypt 雜湊密碼）
        return ApiResponse.ok("registered");
    }
}
