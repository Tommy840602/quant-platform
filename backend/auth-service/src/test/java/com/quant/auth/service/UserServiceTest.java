package com.quant.auth.service;

import com.quant.auth.entity.UserEntity;
import com.quant.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private static UserEntity user(String name, String rawPw, String roles) {
        UserEntity u = new UserEntity();
        u.setUsername(name); u.setPasswordHash(new BCryptPasswordEncoder(4).encode(rawPw)); u.setRoles(roles);
        return u;
    }

    @Test
    void bcryptLoginAcceptsRightPasswordRejectsWrong() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.findByUsername("tommy")).thenReturn(Optional.of(user("tommy", "correct-horse", "ADMIN")));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        UserService svc = new UserService(repo, mock(JdbcTemplate.class));

        assertTrue(svc.authenticate("tommy", "correct-horse").isPresent());
        assertTrue(svc.authenticate("tommy", "wrong").isEmpty());
        assertTrue(svc.authenticate("nobody", "correct-horse").isEmpty());
        assertTrue(svc.authenticate("admin", "admin").isEmpty(), "admin/admin 不再是後門");
    }

    @Test
    void createRejectsShortPasswordAndDuplicates() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.existsByUsername("dup")).thenReturn(true);
        UserService svc = new UserService(repo, mock(JdbcTemplate.class));
        assertThrows(IllegalArgumentException.class, () -> svc.create("x", "short", "TRADER"));
        assertThrows(IllegalArgumentException.class, () -> svc.create("dup", "longenough1", "TRADER"));
    }

    @Test
    void rolesAreParsed() {
        assertEquals(java.util.List.of("ADMIN", "TRADER"), UserService.roles(user("a", "pw-pw-pw-pw", "ADMIN, TRADER")));
    }
}
