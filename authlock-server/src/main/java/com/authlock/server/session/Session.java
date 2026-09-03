package com.authlock.server.session;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server-side session record, per Backend.md §2.2. {@code lastAccessedAt} is
 * mutable (updated on each successful validation) to implement the sliding
 * idle timeout in {@link SessionManager}; {@code createdAt} is fixed and
 * backs the absolute maximum-lifetime check.
 */
public final class Session {

    private final String token;
    private final String userId;
    private final Instant createdAt;
    private final AtomicReference<Instant> lastAccessedAt;

    Session(String token, String userId, Instant createdAt) {
        this.token = token;
        this.userId = userId;
        this.createdAt = createdAt;
        this.lastAccessedAt = new AtomicReference<>(createdAt);
    }

    public String token() {
        return token;
    }

    public String userId() {
        return userId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastAccessedAt() {
        return lastAccessedAt.get();
    }

    void touch(Instant now) {
        lastAccessedAt.set(now);
    }
}
