package com.authlock.server.audit;

/**
 * Audit event categories, per Security.md §9.
 *
 * <p><b>Derived Decision (Implementation Phase 7):</b> Security.md §9's
 * original table listed "Authentication failure" and "Authorization
 * failure" as separate event rows. In practice every failure already has a
 * concrete triggering operation — a failed {@code login()} attempt IS the
 * authentication-failure event, and an {@code INVALID_SESSION}/
 * {@code LOCK_NOT_OWNED} rejection on any method IS that method's
 * authorization-failure event. Modeling them as separate generic event
 * types would just duplicate the same fact two ways. Instead, every event
 * here maps 1:1 to a {@code VaultService} method, and {@link AuditRecord#result()}
 * plus {@link AuditRecord#errorCode()} distinguish success from the
 * specific failure reason — a strict simplification of the original
 * schema, not a reduction in what's recorded.
 */
public enum AuditEventType {
    LOGIN,
    LOGOUT,
    UPLOAD,
    DOWNLOAD,
    LOCK,
    UNLOCK,
    /** An unexpected, unclassified server-side error during any operation (Security.md §9 "Errors"). */
    ERROR
}
