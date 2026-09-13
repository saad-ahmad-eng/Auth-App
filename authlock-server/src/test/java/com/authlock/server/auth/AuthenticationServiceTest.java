package com.authlock.server.auth;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuthenticationService}, covering Testing.md §2.1
 * TEST-AUTH-001..004 at the service layer (the full RMI-level equivalents
 * are in {@link com.authlock.server.VaultServiceAuthIntegrationTest}).
 * Exercises the real seeded {@code seed-users.properties} (alice/bob).
 */
class AuthenticationServiceTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final UserStore userStore = newUserStore(passwordHasher);
    private final AuthenticationService authService = new AuthenticationService(userStore, passwordHasher);

    // UserStore's constructor now declares IOException (Phase 13: it may need to read a
    // runtime accounts file) — a field initializer can't declare a checked exception, so
    // this wraps it; nothing here actually touches disk (no runtime file exists under a
    // fresh checkout, so it seeds from the classpath resource same as always).
    private static UserStore newUserStore(PasswordHasher passwordHasher) {
        try {
            return new UserStore(passwordHasher);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void testAuth001_validCredentialsAuthenticate() {
        Optional<User> result = authService.authenticate("alice", "Alice2026Pass".toCharArray());
        assertTrue(result.isPresent());
        assertEquals("alice", result.get().username());
    }

    @Test
    void testAuth002_invalidUsernameFailsAuthentication() {
        Optional<User> result = authService.authenticate("no-such-user", "whatever".toCharArray());
        assertTrue(result.isEmpty());
    }

    @Test
    void testAuth003_invalidPasswordFailsAuthentication() {
        Optional<User> result = authService.authenticate("alice", "wrong-password".toCharArray());
        assertTrue(result.isEmpty());
    }

    @Test
    void testAuth004_emptyCredentialsFailAuthentication() {
        Optional<User> result = authService.authenticate("", new char[0]);
        assertTrue(result.isEmpty());
    }
}
