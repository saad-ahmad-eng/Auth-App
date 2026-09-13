package com.authlock.server.auth;

/**
 * Logical User model per Backend.md §2.1. Originally created only by
 * {@link UserStore}'s seed loading (PRD.md §4.1: no self-service
 * registration) — Phase 13's admin panel adds the one exception,
 * {@link UserStore#createUser}, gated to {@link Role#ADMIN} callers only.
 *
 * @param userId       stable, server-generated identifier
 * @param username     unique login identifier
 * @param passwordHash salted PBKDF2 digest — never the plaintext password
 * @param status       {@code ACTIVE} or {@code DISABLED}
 * @param role         {@code ADMIN} or {@code STANDARD} (Phase 13 follow-up: admin panel)
 */
public record User(String userId, String username, HashedPassword passwordHash, Status status, Role role) {

    public enum Status {
        ACTIVE,
        DISABLED
    }

    public enum Role {
        ADMIN,
        STANDARD
    }
}
