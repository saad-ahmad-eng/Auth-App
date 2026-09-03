package com.authlock.server.session;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SessionManager}, covering Testing.md §2.2
 * TEST-SESSION-001..004 plus the idle-timeout/absolute-lifetime expiry
 * rules from Security.md §5. Uses an injected, manually-advanced
 * {@link Clock} so expiry is verified deterministically without waiting
 * real minutes/hours.
 */
class SessionManagerTest {

    /** A Clock whose instant can be advanced on demand by tests. */
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
    private final SessionManager sessionManager =
            new SessionManager(Duration.ofMinutes(30), Duration.ofHours(8), clock, false);

    @AfterEach
    void tearDown() {
        sessionManager.close();
    }

    @Test
    void testSession001_validTokenValidates() {
        String token = sessionManager.create("user-1");

        Optional<Session> result = sessionManager.validate(token);

        assertTrue(result.isPresent());
        assertEquals("user-1", result.get().userId());
    }

    @Test
    void testSession002_expiredIdleTokenIsRejected() {
        String token = sessionManager.create("user-1");

        clock.advance(Duration.ofMinutes(31)); // past the 30-minute idle timeout, no activity in between

        assertTrue(sessionManager.validate(token).isEmpty());
    }

    @Test
    void absoluteMaxLifetimeExpiresEvenWithContinuousActivity() {
        String token = sessionManager.create("user-1");

        // Keep "touching" the session well inside the idle window, but let
        // total elapsed time exceed the 8-hour absolute maximum.
        for (int i = 0; i < 9; i++) {
            clock.advance(Duration.ofMinutes(50));
            sessionManager.validate(token); // refreshes idle clock, does not reset absolute lifetime
        }

        assertTrue(sessionManager.validate(token).isEmpty(),
                "session must expire at the absolute max lifetime even if continuously active");
    }

    @Test
    void testSession003_unknownTokenIsRejected() {
        assertTrue(sessionManager.validate("not-a-real-token").isEmpty());
    }

    @Test
    void testSession004_reuseAfterLogoutIsRejected() {
        String token = sessionManager.create("user-1");
        assertTrue(sessionManager.invalidate(token));

        assertTrue(sessionManager.validate(token).isEmpty());
        assertFalse(sessionManager.invalidate(token), "second logout of the same token should not re-remove anything");
    }

    @Test
    void tokensAreUniqueAndHighEntropy() {
        String tokenA = sessionManager.create("user-1");
        String tokenB = sessionManager.create("user-2");

        assertNotEquals(tokenA, tokenB);
        assertTrue(tokenA.length() >= 40, "expect a Base64-encoded 256-bit token to be well over 40 chars");
    }
}
