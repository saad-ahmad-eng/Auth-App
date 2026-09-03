package com.authlock.server.lock;

import java.time.Instant;

/**
 * Server-side lock record, per Backend.md §2.4.
 *
 * <p><b>Resolution of Open Question OQ-12</b> (Context.md §7): locks are
 * owned per-session ({@code ownerSessionToken}), not per-user — a user's
 * second concurrent session does not implicitly share a lock held by their
 * first session. This matches how {@link com.authlock.server.session.SessionManager}
 * already identifies callers, and means a lock is automatically eligible
 * for release the instant its owning session ends (see
 * {@link LockManager#releaseAllOwnedBySession}), without needing to track
 * "does this user have another live session" separately.
 *
 * @param fileId            the locked file
 * @param ownerSessionToken the session that acquired the lock
 * @param acquiredAt        when the lock was granted
 * @param expiresAt         fixed timeout deadline — <b>not</b> a sliding
 *                          window (unlike session idle timeout); once set at
 *                          acquisition it does not move, per Security.md §8
 *                          "maximum hold duration"
 */
public record FileLock(String fileId, String ownerSessionToken, Instant acquiredAt, Instant expiresAt) {
}
