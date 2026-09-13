package com.authlock.server.http;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Basic in-memory, per-client-IP sliding-window rate limiter for
 * {@code POST /api/login} (Phase 13 hardening — this is a login system
 * serving real credentials, and had no brute-force protection at all).
 * Keyed by IP rather than by attempted username deliberately: a per-username
 * counter would itself leak whether a username exists (an unknown username
 * would never trip its own counter) — the same enumeration concern
 * {@link com.authlock.server.auth.AuthenticationService}'s timing-equalized
 * comparison already guards against elsewhere in this codebase.
 *
 * <p>Not a distributed/production-grade limiter (single JVM, in-memory,
 * resets on restart) — a coursework-appropriate "isn't wide open to
 * brute-forcing" bar, per the request that added this.
 */
final class LoginRateLimiter {

    private final int maxAttempts;
    private final Duration window;
    private final Map<String, Deque<Instant>> attemptsByClient = new ConcurrentHashMap<>();

    LoginRateLimiter(int maxAttempts, Duration window) {
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    /** @return true if this attempt is allowed (and is now counted against the window); false if the caller is currently throttled. */
    boolean tryAcquire(String clientKey) {
        Deque<Instant> attempts = attemptsByClient.computeIfAbsent(clientKey, k -> new ArrayDeque<>());
        synchronized (attempts) {
            Instant now = Instant.now();
            while (!attempts.isEmpty() && Duration.between(attempts.peekFirst(), now).compareTo(window) > 0) {
                attempts.pollFirst();
            }
            if (attempts.size() >= maxAttempts) {
                return false;
            }
            attempts.addLast(now);
            return true;
        }
    }
}
