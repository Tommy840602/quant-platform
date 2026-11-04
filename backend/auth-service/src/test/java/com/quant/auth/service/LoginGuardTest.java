package com.quant.auth.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LoginGuardTest {

    @Test
    void locksUserAfterFiveFailuresForFifteenMinutes() {
        AtomicLong now = new AtomicLong(1_000_000);
        LoginGuard g = new LoginGuard(now::get);
        for (int i = 0; i < 4; i++) g.onFailure("admin", "1.1.1.1");
        assertEquals(0, g.lockedSeconds("admin", "1.1.1.1"));
        g.onFailure("Admin ", "2.2.2.2");               // 大小寫/空白視為同一帳號、換 IP 也算
        assertTrue(g.lockedSeconds("admin", "3.3.3.3") > 0);
        now.addAndGet(LoginGuard.LOCK_MS + 1);
        assertEquals(0, g.lockedSeconds("admin", "3.3.3.3"));
    }

    @Test
    void failuresOutsideWindowDoNotCount() {
        AtomicLong now = new AtomicLong(0);
        LoginGuard g = new LoginGuard(now::get);
        for (int i = 0; i < 4; i++) g.onFailure("u", "ip");
        now.addAndGet(LoginGuard.WINDOW_MS + 1);
        g.onFailure("u", "ip");
        assertEquals(0, g.lockedSeconds("u", "ip"));
    }

    @Test
    void successClearsUserFailures() {
        LoginGuard g = new LoginGuard(() -> 0);
        for (int i = 0; i < 4; i++) g.onFailure("u", "ip");
        g.onSuccess("u");
        g.onFailure("u", "ip");
        assertEquals(0, g.lockedSeconds("u", "ip"));
    }

    @Test
    void locksIpThatSpraysManyUsernames() {
        LoginGuard g = new LoginGuard(() -> 0);
        for (int i = 0; i < LoginGuard.MAX_PER_IP; i++) g.onFailure("user" + i, "6.6.6.6");
        assertTrue(g.lockedSeconds("someoneElse", "6.6.6.6") > 0);
        assertEquals(0, g.lockedSeconds("someoneElse", "7.7.7.7"));
    }

    @Test
    void clientIpPrefersCloudflareHeader() {
        assertEquals("9.9.9.9", LoginGuard.clientIp("9.9.9.9", "10.0.0.1", "8.8.8.8, 10.0.0.1", "172.18.0.5"));
        assertEquals("10.0.0.1", LoginGuard.clientIp(null, "10.0.0.1", "8.8.8.8, 10.0.0.1", "172.18.0.5"), "XFF 可偽造，不採用");
        assertEquals("10.0.0.1", LoginGuard.clientIp("", "10.0.0.1", null, "172.18.0.5"));
        assertEquals("172.18.0.5", LoginGuard.clientIp(null, null, null, "172.18.0.5"));
    }
}
