package com.authlock.server.session;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns session lifecycle: creation, validation, invalidation, and expiry
 * cleanup, per Architecture.md §2.5 and Security.md §5.
 *
 * <p><b>Resolution of Open Question OQ-08</b> (Context.md §7): idle timeout
 * is set to 30 minutes, sliding (renewed on every successful
 * {@link #validate(String)} call), plus a fixed 8-hour absolute maximum
 * lifetime regardless of activity, so a continuously-used session cannot
 * live forever. Both are coursework-reasonable defaults, not values
 * mandated by {@code auth}.
 *
 * <p>Tokens are generated via {@link SecureRandom} (SEC-003 — ≥128 bits of
 * entropy: 256 bits here) and never derived from predictable data. The
 * session table is a {@link ConcurrentHashMap}, safe under the concurrent
 * RMI call threads described in Backend.md §3.
 *
 * <p><b>Phase 5 addition:</b> an optional session-ended listener
 * (Architecture.md §2.7 — the Lock Manager's documented input is "session-
 * expiry notifications from Session Manager") is invoked whenever a session
 * ends, for any reason: idle/absolute expiry (lazily in {@link #validate},
 * or via the periodic cleanup sweep) or explicit {@link #invalidate}
 * (logout). This is how {@code LockManager} releases locks left behind by
 * a session that ended — see Security.md §8 "stale lock recovery" and
 * API-spec.md {@code logout}'s documented side effect.
 */
public final class SessionManager implements AutoCloseable {

    public static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    public static final Duration MAX_LIFETIME = Duration.ofHours(8);
    private static final Duration CLEANUP_INTERVAL = Duration.ofSeconds(60);
    private static final int TOKEN_BYTES = 32; // 256 bits

    private final Map<String, Session> sessionsByToken = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final ScheduledExecutorService cleanupExecutor;
    private final Clock clock;
    private final Duration idleTimeout;
    private final Duration maxLifetime;
    private volatile Consumer<String> sessionEndedListener = token -> { };

    public SessionManager() {
        this(IDLE_TIMEOUT, MAX_LIFETIME, Clock.systemUTC(), true);
    }

    /**
     * Same as {@link #SessionManager()} but with explicit timeouts instead
     * of the {@link #IDLE_TIMEOUT}/{@link #MAX_LIFETIME} defaults. Intended
     * for a deliberately short-lived <b>server process</b> configured for
     * interactive session-expiry verification (see
     * {@code VaultServiceImpl}'s {@code authlock.session.*} system
     * properties) — not for changing the shipped defaults, which remain
     * OQ-08's 30-minute idle / 8-hour absolute values unless this
     * constructor is used explicitly.
     */
    public SessionManager(Duration idleTimeout, Duration maxLifetime) {
        this(idleTimeout, maxLifetime, Clock.systemUTC(), true);
    }

    /**
     * Test-only constructor allowing an injected {@link Clock} and shorter
     * timeouts, so expiry/cleanup behavior can be verified deterministically
     * without waiting real minutes/hours. {@code startCleanupThread=false}
     * lets a test drive expiry purely via {@link #validate(String)} without
     * a background thread racing the assertions.
     */
    SessionManager(Duration idleTimeout, Duration maxLifetime, Clock clock, boolean startCleanupThread) {
        this.idleTimeout = idleTimeout;
        this.maxLifetime = maxLifetime;
        this.clock = clock;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "authlock-session-cleanup");
            t.setDaemon(true); // never blocks JVM shutdown
            return t;
        });
        if (startCleanupThread) {
            cleanupExecutor.scheduleAtFixedRate(
                    this::removeExpiredSessions,
                    CLEANUP_INTERVAL.toSeconds(), CLEANUP_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * Registers the callback invoked (with the session token) whenever a
     * session ends — idle/absolute expiry or explicit logout. {@code null}
     * clears any previously registered listener.
     */
    public void setSessionEndedListener(Consumer<String> listener) {
        this.sessionEndedListener = listener == null ? (token -> { }) : listener;
    }

    /** Creates a new session for the given user and returns its opaque token. */
    public String create(String userId) {
        String token = generateToken();
        Session session = new Session(token, userId, clock.instant());
        sessionsByToken.put(token, session);
        return token;
    }

    /**
     * Validates a token: returns the session if it exists and has not
     * expired (refreshing its idle-timeout clock), or empty otherwise —
     * an unknown or expired token is treated identically (FR-004,
     * Security.md §5 "invalid token handling").
     */
    public Optional<Session> validate(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Session session = sessionsByToken.get(token);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (isExpired(session, now)) {
            sessionsByToken.remove(token);
            sessionEndedListener.accept(token);
            return Optional.empty();
        }
        session.touch(now);
        return Optional.of(session);
    }

    /**
     * Invalidates a session immediately (logout). Returns the removed
     * session (so the caller can attribute an audit record to its owning
     * user — Phase 7, Security.md §9) if one was actually removed, or
     * empty for an unknown/already-invalidated token.
     */
    public Optional<Session> invalidate(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Session removed = sessionsByToken.remove(token);
        if (removed != null) {
            sessionEndedListener.accept(token);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Immediately invalidates every session belonging to {@code userId}
     * (Phase 13 admin panel: disabling an account must kill its existing
     * sessions, not just block future logins) — fires the session-ended
     * listener for each one removed, same as {@link #invalidate}, so any
     * locks held by those sessions are released too.
     */
    public void invalidateAllForUser(String userId) {
        sessionsByToken.entrySet().removeIf(entry -> {
            boolean matches = entry.getValue().userId().equals(userId);
            if (matches) {
                sessionEndedListener.accept(entry.getKey());
            }
            return matches;
        });
    }

    /** Number of currently tracked (not necessarily still valid) sessions — for tests/diagnostics. */
    public int sessionCount() {
        return sessionsByToken.size();
    }

    private void removeExpiredSessions() {
        Instant now = clock.instant();
        sessionsByToken.entrySet().removeIf(entry -> {
            boolean expired = isExpired(entry.getValue(), now);
            if (expired) {
                sessionEndedListener.accept(entry.getKey());
            }
            return expired;
        });
    }

    private boolean isExpired(Session session, Instant now) {
        boolean idleExpired = Duration.between(session.lastAccessedAt(), now).compareTo(idleTimeout) > 0;
        boolean lifetimeExpired = Duration.between(session.createdAt(), now).compareTo(maxLifetime) > 0;
        return idleExpired || lifetimeExpired;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public void close() {
        cleanupExecutor.shutdownNow();
    }
}
