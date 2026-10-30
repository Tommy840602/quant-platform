package com.quant.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.*;

/** 帳號存 DB 後，啟動類不可再排除 DataSource / JPA 自動設定（否則 UserRepository 無 bean，服務起不來）。 */
class AuthApplicationWiringTest {
    @Test
    void doesNotExcludeDataSourceOrJpaAutoConfiguration() {
        SpringBootApplication ann = AuthApplication.class.getAnnotation(SpringBootApplication.class);
        assertNotNull(ann);
        assertEquals(0, ann.exclude().length, "auth-service 需要 JPA：不可 exclude DataSource/Hibernate 自動設定");
        assertEquals(0, ann.excludeName().length);
    }
}
