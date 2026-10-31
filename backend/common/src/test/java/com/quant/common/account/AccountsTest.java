package com.quant.common.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AccountsTest {
    @Test
    void onlyPaperIsPaper() {
        assertTrue(Accounts.isPaper("paper")); assertTrue(Accounts.isPaper("PAPER"));
        assertFalse(Accounts.isPaper("main")); assertFalse(Accounts.isPaper(null)); assertFalse(Accounts.isPaper("bot-1"));
    }
    @Test
    void unknownModeFallsBackToPaper() {
        assertEquals("paper", Accounts.modeOrDefault(null));
        assertEquals("paper", Accounts.modeOrDefault(""));
        assertEquals("paper", Accounts.modeOrDefault("garbage"));
        assertEquals("main", Accounts.modeOrDefault("main"));
        assertEquals("main", Accounts.normalize("anything-else"));
        assertEquals("paper", Accounts.normalize("paper"));
    }
}
