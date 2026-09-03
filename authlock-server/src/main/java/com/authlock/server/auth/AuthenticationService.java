package com.authlock.server.auth;

import java.util.Arrays;
import java.util.Optional;

/**
 * Verifies credentials against the {@link UserStore}, per Architecture.md
 * §2.4 and Security.md §3.
 *
 * <p>Deliberately performs the full password-hashing/comparison step even
 * when the username is unknown, comparing against a fixed dummy digest, so
 * an unknown-username request takes the same time as a known-username/
 * wrong-password request — preventing username enumeration via timing
 * (SEC-001), in addition to {@link com.authlock.common.VaultService#login}
 * already returning the same {@code AUTHENTICATION_FAILED} error for both
 * cases.
 */
public final class AuthenticationService {

    private final UserStore userStore;
    private final PasswordHasher passwordHasher;
    private final HashedPassword dummyHash;

    public AuthenticationService(UserStore userStore, PasswordHasher passwordHasher) {
        this.userStore = userStore;
        this.passwordHasher = passwordHasher;
        // Fixed per-process dummy digest — never matches a real password;
        // exists only to equalize timing for unknown usernames.
        this.dummyHash = passwordHasher.hash("authlock-dummy-timing-equalizer".toCharArray());
    }

    /**
     * @param username candidate username
     * @param password candidate password — cleared from memory before returning
     * @return the matching, active {@link User} if credentials are valid; empty otherwise
     */
    public Optional<User> authenticate(String username, char[] password) {
        try {
            Optional<User> found = userStore.findByUsername(username);
            HashedPassword target = found.map(User::passwordHash).orElse(dummyHash);

            boolean passwordMatches = passwordHasher.verify(password, target);

            if (found.isPresent() && passwordMatches && found.get().status() == User.Status.ACTIVE) {
                return found;
            }
            return Optional.empty();
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
