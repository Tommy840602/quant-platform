package com.quant.gateway.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionsTest {

    @Test
    void sensitiveWritesNeedSpecificPermissions() {
        assertEquals(Permissions.CONFIG, Permissions.required("POST", "/api/order/config"));
        assertEquals(Permissions.CONFIG, Permissions.required("POST", "/api/order/config/arm"));
        assertEquals(Permissions.CONFIG, Permissions.required("POST", "/api/account/config"));
        assertEquals(Permissions.RISK, Permissions.required("POST", "/api/risk/kill-switch"));
        assertEquals(Permissions.RISK, Permissions.required("POST", "/api/order/orders/risk-config"));
        assertEquals(Permissions.TRADE, Permissions.required("POST", "/api/order/orders"));
        assertEquals(Permissions.TRADE, Permissions.required("DELETE", "/api/order/orders/abc"));
        assertEquals(Permissions.TRADE, Permissions.required("POST", "/api/strategy/strategies/b1/start"));
        assertNull(Permissions.required("GET", "/api/order/config"), "讀取不擋");
        assertNull(Permissions.required("POST", "/api/auth/register"), "auth-service 自己檢查 ADMIN");
    }

    @Test
    void roleMapping() {
        assertTrue(Permissions.has(List.of("ADMIN"), Permissions.CONFIG));
        assertTrue(Permissions.has("[TRADER]", Permissions.TRADE));
        assertFalse(Permissions.has("[TRADER]", Permissions.CONFIG), "一般交易員不能改金鑰 / 切環境 / 上膛");
        assertFalse(Permissions.has("TRADER", Permissions.RISK));
        assertFalse(Permissions.has("[VIEWER]", Permissions.TRADE));
        assertTrue(Permissions.has("trader, admin", Permissions.RISK));
        assertFalse(Permissions.has(null, Permissions.TRADE));
    }
}
