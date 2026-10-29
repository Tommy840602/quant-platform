package com.quant.auth.service;

import com.quant.auth.entity.UserEntity;
import com.quant.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 帳號服務：BCrypt 驗證、啟動時自動建立 users 表與第一個管理員。
 * 管理員密碼來自 ADMIN_PASSWORD；未設定則產生隨機密碼並只在啟動 log 印一次（之後請立即改密碼）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    private static final List<String> WEAK = List.of("admin", "password", "123456", "12345678", "quant", "changeme");

    private final UserRepository users;
    private final JdbcTemplate jdbc;
    @Value("${auth.admin.username:admin}") private String adminUser;
    @Value("${auth.admin.password:}") private String adminPassword;

    public UserService(UserRepository users, JdbcTemplate jdbc) { this.users = users; this.jdbc = jdbc; }

    /** users 表若不存在就建（init SQL 只在空 volume 跑一次，既有部署升級也要能用）。 */
    @PostConstruct
    void bootstrap() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id            BIGSERIAL PRIMARY KEY,
                username      VARCHAR(64) UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                roles         TEXT NOT NULL DEFAULT 'TRADER',
                enabled       BOOLEAN NOT NULL DEFAULT TRUE,
                created_at    TIMESTAMPTZ DEFAULT now(),
                last_login_at TIMESTAMPTZ
            )""");
        if (users.count() > 0) return;

        String pw = adminPassword == null ? "" : adminPassword.trim();
        boolean generated = pw.isEmpty();
        if (generated) pw = randomPassword();
        create(adminUser, pw, "ADMIN");
        if (generated) {
            log.warn("""

                ╔══════════════════════════════════════════════════════════════╗
                ║  已建立初始管理員（ADMIN_PASSWORD 未設定，使用隨機密碼）       ║
                ║  帳號: {}                                                  
                ║  密碼: {}                                 
                ║  此密碼只顯示這一次，請登入後立即到帳戶頁修改。               ║
                ╚══════════════════════════════════════════════════════════════╝
                """, adminUser, pw);
        } else {
            if (WEAK.contains(pw.toLowerCase()) || pw.length() < 8)
                log.warn("ADMIN_PASSWORD 太弱（{} 字元），正式環境請更換", pw.length());
            log.info("已建立初始管理員 {}", adminUser);
        }
    }

    public Optional<UserEntity> authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) return Optional.empty();
        Optional<UserEntity> u = users.findByUsername(username.trim());
        // 帳號不存在時也做一次 BCrypt，避免以回應時間探測帳號
        String hash = u.map(UserEntity::getPasswordHash).orElse("$2a$12$C6UzMDM.H6dfI/f/IKcEeO4F3UhjdDj1gQKZxw6M9e7gmMhvXYtKe");
        boolean ok = ENCODER.matches(rawPassword, hash);
        if (!ok || u.isEmpty() || !u.get().isEnabled()) return Optional.empty();
        u.get().setLastLoginAt(OffsetDateTime.now());
        users.save(u.get());
        return u;
    }

    @Transactional
    public UserEntity create(String username, String rawPassword, String roles) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("帳號不可空白");
        if (rawPassword == null || rawPassword.length() < 8) throw new IllegalArgumentException("密碼至少 8 字元");
        if (users.existsByUsername(username)) throw new IllegalArgumentException("帳號已存在");
        UserEntity u = new UserEntity();
        u.setUsername(username.trim());
        u.setPasswordHash(ENCODER.encode(rawPassword));
        u.setRoles(roles == null || roles.isBlank() ? "TRADER" : roles);
        return users.save(u);
    }

    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        UserEntity u = users.findByUsername(username).orElseThrow(() -> new IllegalArgumentException("帳號不存在"));
        if (!ENCODER.matches(oldPassword, u.getPasswordHash())) throw new IllegalArgumentException("舊密碼錯誤");
        if (newPassword == null || newPassword.length() < 8) throw new IllegalArgumentException("新密碼至少 8 字元");
        if (WEAK.contains(newPassword.toLowerCase())) throw new IllegalArgumentException("新密碼太弱");
        u.setPasswordHash(ENCODER.encode(newPassword));
        users.save(u);
    }

    public static List<String> roles(UserEntity u) {
        return Arrays.stream(u.getRoles().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }
}
