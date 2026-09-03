package com.authlock.server.auth;

import org.junit.jupiter.api.Test;

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
    private final UserStore userStore = new UserStore(passwordHasher);
    private final AuthenticationService authService = new AuthenticationService(userStore, passwordHasher);

    @Test
    void testAuth001_validCredentialsAuthenticate() {
        Optional<User> result = authService.authenticate("alice", "AliceP@ss1".toCharArray());
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
