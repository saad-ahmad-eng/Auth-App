package com.authlock.server.lock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns per-file distributed lock state — the project's core feature
 * (Architecture.md §2.7, `auth` §3). Every state transition
 * ({@link #acquire}, {@link #release}) is a single atomic
 * {@link ConcurrentHashMap#compute}/{@code computeIfPresent} call, never a
 * separate check-then-set across two steps, which is exactly the race
 * window a distributed lock exists to close (Backend.md §3, TEST-CONC-001).
 *
 * <p><b>Resolution of Open Question OQ-13</b> (Context.md §7): locks have a
 * fixed 15-minute maximum hold duration, not sliding. A lock past its
 * deadline is treated as stale and silently reclaimable by the next
 * {@link #acquire} call for that file, and is also swept out periodically
 * by a background thread — see Security.md §8 "lock timeout" / "stale lock
 * recovery".
 *
 * <p>Also implements the other half of stale-lock recovery: a lock manager
 * subscribes to {@link com.authlock.server.session.SessionManager}'s
 * session-ended notifications (Architecture.md §2.7's documented input) via
 * {@link #releaseAllOwnedBySession}, so a crashed/expired/logged-out
 * session's locks are released immediately rather than waiting for the
 * lock's own timeout.
 */
public final class LockManager implements AutoCloseable {

    static final Duration LOCK_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration CLEANUP_INTERVAL = Duration.ofSeconds(60);

    private final Map<String, FileLock> locksByFileId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;
    private final Clock clock;
    private final Duration lockTimeout;

    public LockManager() {
        this(LOCK_TIMEOUT, Clock.systemUTC(), true);
    }

    /** Test-only constructor — see {@link com.authlock.server.session.SessionManager}'s equivalent for rationale. */
    LockManager(Duration lockTimeout, Clock clock, boolean startCleanupThread) {
        this.lockTimeout = lockTimeout;
        this.clock = clock;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "authlock-lock-cleanup");
            t.setDaemon(true);
            return t;
        });
        if (startCleanupThread) {
            cleanupExecutor.scheduleAtFixedRate(
                    this::removeExpiredLocks,
                    CLEANUP_INTERVAL.toSeconds(), CLEANUP_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        }
    }

    /**
     * Attempts to acquire the lock on {@code fileId} for {@code sessionToken}.
     * Atomic: exactly one caller wins a simultaneous race for the same file
     * (FR-010, TEST-CONC-001). A stale (expired) lock is transparently
     * reclaimed by whichever caller's {@code acquire} observes it first. A
     * session re-acquiring a lock it already holds succeeds idempotently
     * (Derived Decision — not specified by {@code auth}; treating this as
     * an error would be an unhelpful surprise for a client that, say, retries
     * a lock call after a slow response).
     *
     * @return true if the caller now holds the lock (freshly granted or already held by it)
     */
    public boolean acquire(String fileId, String sessionToken) {
        Instant now = clock.instant();
        AtomicBoolean granted = new AtomicBoolean(false);

        locksByFileId.compute(fileId, (id, existing) -> {
            if (existing == null || isExpired(existing, now)) {
                granted.set(true);
                return new FileLock(fileId, sessionToken, now, now.plus(lockTimeout));
            }
            if (existing.ownerSessionToken().equals(sessionToken)) {
                granted.set(true); // idempotent re-acquire by the current owner
                return existing;
            }
            granted.set(false); // held by a different, still-live session
            return existing;
        });

        return granted.get();
    }

    /**
     * Releases the lock on {@code fileId} if — and only if — it is
     * currently, validly held by {@code sessionToken} (FR-009). An unknown
     * file, an already-unlocked file, an expired lock, or a lock held by a
     * different session all result in {@code false} (mapped to
     * {@code LOCK_NOT_OWNED} by the caller — see API-spec.md {@code unlockFile}).
     */
    public boolean release(String fileId, String sessionToken) {
        Instant now = clock.instant();
        AtomicBoolean released = new AtomicBoolean(false);

        locksByFileId.computeIfPresent(fileId, (id, existing) -> {
            if (!isExpired(existing, now) && existing.ownerSessionToken().equals(sessionToken)) {
                released.set(true);
                return null; // remove the entry
            }
            return existing; // leave untouched — not this caller's to release
        });

        return released.get();
    }

    /**
     * The current, non-expired owner of {@code fileId}'s lock, if any — used
     * by {@code listFiles()} to populate {@code FileMetadata.lockState()}.
     * An expired lock reports as unlocked here even before the cleanup
     * sweep physically removes it.
     */
    public Optional<String> currentOwner(String fileId) {
        FileLock lock = locksByFileId.get(fileId);
        if (lock == null || isExpired(lock, clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(lock.ownerSessionToken());
    }

    /**
     * Releases every lock currently held by {@code sessionToken}, called
     * when that session ends for any reason (Security.md §8 stale-lock
     * recovery; wired to {@link com.authlock.server.session.SessionManager}'s
     * session-ended listener in {@code VaultServiceImpl}).
     */
    public void releaseAllOwnedBySession(String sessionToken) {
        locksByFileId.entrySet().removeIf(entry -> entry.getValue().ownerSessionToken().equals(sessionToken));
    }

    private void removeExpiredLocks() {
        Instant now = clock.instant();
        locksByFileId.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    private boolean isExpired(FileLock lock, Instant now) {
        return now.isAfter(lock.expiresAt());
    }

    @Override
    public void close() {
        cleanupExecutor.shutdownNow();
    }
}
