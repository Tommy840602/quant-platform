package com.quant.auth.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 平台帳號。密碼只存 BCrypt 雜湊。 */
@Entity
@Table(name = "users")
public class UserEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false, length = 64)
    private String username;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    /** 逗號分隔，例如 ADMIN,TRADER */
    @Column(nullable = false)
    private String roles = "TRADER";
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "created_at")
    private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getRoles() { return roles; }
    public void setRoles(String v) { this.roles = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(OffsetDateTime v) { this.lastLoginAt = v; }
}
