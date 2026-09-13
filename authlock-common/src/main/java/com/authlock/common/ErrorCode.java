package com.authlock.common;

/**
 * Standardized application-level error codes, per API-spec.md §3 Error
 * Model. Distinct from transport-level {@link java.rmi.RemoteException} —
 * these represent well-formed business-logic outcomes the client is
 * expected to handle and present to the user (PRD.md FR-012).
 */
public enum ErrorCode {
    AUTHENTICATION_FAILED,
    INVALID_SESSION,
    UNAUTHORIZED,
    FILE_NOT_FOUND,
    FILE_LOCKED,
    LOCK_NOT_OWNED,
    UPLOAD_FAILED,
    DOWNLOAD_FAILED,
    SERVER_ERROR,
    /**
     * Phase 13 hardening: an audit-trail classification for a throttled
     * {@code POST /api/login} attempt (see {@code AuthLockHttpServer}'s
     * rate limiter). Never actually thrown as a {@link VaultServiceException}
     * — the RMI {@code login()} path has no rate limiter (out of scope for
     * that pass) — this exists solely so {@code AuditLogger}'s
     * {@code errorCode} field can record the fact precisely instead of
     * overloading {@link #AUTHENTICATION_FAILED} for a materially different
     * event.
     */
    RATE_LIMITED,
    /** Phase 13 admin panel: {@code POST /api/admin/users} with a username that already exists. */
    USER_ALREADY_EXISTS,
    /** Phase 13 admin panel: an admin endpoint referenced a userId that doesn't exist. */
    USER_NOT_FOUND
}
