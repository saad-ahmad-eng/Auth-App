package com.authlock.server.auth;

/**
 * Logical User model per Backend.md §2.1. No self-service registration
 * exists (PRD.md §4.1) — instances are created only by {@link UserStore}'s
 * seed loading.
 *
 * @param userId       stable, server-generated identifier
 * @param username     unique login identifier
 * @param passwordHash salted PBKDF2 digest — never the plaintext password
 * @param status       {@code ACTIVE} or {@code DISABLED}
 */
public record User(String userId, String username, HashedPassword passwordHash, Status status) {

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
