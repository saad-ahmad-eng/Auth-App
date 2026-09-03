package com.authlock.server.lock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LockManager}, covering Testing.md §2.4
 * TEST-LOCK-001..007 with a manually-advanced {@link Clock} for
 * deterministic timeout/stale-lock testing (same pattern as
 * {@code SessionManagerTest}; the N-client concurrent race itself —
 * TEST-CONC-001 — lives at the RMI level in
 * {@code VaultServiceLockConcurrencyTest} since it needs to exercise real
 * concurrent threads, not just sequential calls).
 */
class LockManagerTest {

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(i -> i.plus(by));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final LockManager lockManager = new LockManager(Duration.ofMinutes(15), clock, false);

    @AfterEach
    void tearDown() {
        lockManager.close();
    }

    @Test
    void testLock001_acquiringAnUnlockedFileSucceeds() {
        assertTrue(lockManager.acquire("file-1", "session-A"));
        assertEquals(Optional.of("session-A"), lockManager.currentOwner("file-1"));
    }

    @Test
    void testLock002_secondSessionCannotAcquireAHeldLock() {
        assertTrue(lockManager.acquire("file-1", "session-A"));
        assertFalse(lockManager.acquire("file-1", "session-B"));
        assertEquals(Optional.of("session-A"), lockManager.currentOwner("file-1"), "lock must remain with the original owner");
    }

    @Test
    void testLock003_ownerCanReleaseItsOwnLock() {
        lockManager.acquire("file-1", "session-A");

        assertTrue(lockManager.release("file-1", "session-A"));
        assertEquals(Optional.empty(), lockManager.currentOwner("file-1"));
    }

    @Test
    void testLock004_nonOwnerCannotReleaseSomeoneElsesLock() {
        lockManager.acquire("file-1", "session-A");

        assertFalse(lockManager.release("file-1", "session-B"));
        assertEquals(Optional.of("session-A"), lockManager.currentOwner("file-1"), "lock must remain untouched");
    }

    @Test
    void releasingAnUnlockedFileFails() {
        assertFalse(lockManager.release("never-locked", "session-A"));
    }

    @Test
    void testLock005_concurrentRacePairwise_exactlyOneWinner() {
        // Full N-client race is TEST-CONC-001 (RMI level); this is the
        // simplest sequential sanity check that a second caller for the
        // same file, once the first has won, is always denied.
        boolean firstGranted = lockManager.acquire("file-1", "session-A");
        boolean secondGranted = lockManager.acquire("file-1", "session-B");

        assertTrue(firstGranted);
        assertFalse(secondGranted);
    }

    @Test
    void testLock006_staleLockIsReclaimedAfterTimeout() {
        lockManager.acquire("file-1", "session-A");

        clock.advance(Duration.ofMinutes(16)); // past the 15-minute lock timeout

        assertTrue(lockManager.acquire("file-1", "session-B"), "an expired lock must be reclaimable by a new caller");
        assertEquals(Optional.of("session-B"), lockManager.currentOwner("file-1"));
    }

    @Test
    void testLock007_lockNotYetTimedOutCannotBeStolen() {
        lockManager.acquire("file-1", "session-A");

        clock.advance(Duration.ofMinutes(14)); // still within the 15-minute timeout

        assertFalse(lockManager.acquire("file-1", "session-B"));
    }

    @Test
    void reacquiringByTheCurrentOwnerIsIdempotent() {
        lockManager.acquire("file-1", "session-A");

        assertTrue(lockManager.acquire("file-1", "session-A"), "the current owner re-locking its own file should succeed, not error");
    }

    @Test
    void releaseAllOwnedBySessionReleasesOnlyThatSessionsLocks() {
        lockManager.acquire("file-1", "session-A");
        lockManager.acquire("file-2", "session-A");
        lockManager.acquire("file-3", "session-B");

        lockManager.releaseAllOwnedBySession("session-A");

        assertEquals(Optional.empty(), lockManager.currentOwner("file-1"));
        assertEquals(Optional.empty(), lockManager.currentOwner("file-2"));
        assertEquals(Optional.of("session-B"), lockManager.currentOwner("file-3"), "another session's lock must be untouched");
    }
}
