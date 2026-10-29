package com.quant.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtSecretPolicyTest {
    @Test void rejectsEmpty() { assertThrows(IllegalStateException.class, () -> JwtSecretPolicy.assertStrong("")); }
    @Test void rejectsTemplatePlaceholder() {
        assertThrows(IllegalStateException.class, () -> JwtSecretPolicy.assertStrong("change-me-in-prod-please-use-a-long-random-secret-key"));
        assertThrows(IllegalStateException.class, () -> JwtSecretPolicy.assertStrong("請改成一段夠長的隨機字串至少32字元"));
    }
    @Test void rejectsShort() { assertThrows(IllegalStateException.class, () -> JwtSecretPolicy.assertStrong("only-twenty-bytes!!")); }
    @Test void acceptsRandom48() { assertDoesNotThrow(() -> JwtSecretPolicy.assertStrong("Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnN0dXY=")); }
}
