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
    /**
     * Phase 13 follow-up: the web UI's lock-gated in-place "Replace" action
     * ({@code VaultServiceImpl.replaceFile}) — distinct from {@link #UPLOAD},
     * which always creates a new file rather than overwriting an existing one.
     */
    REPLACE,
    /** Phase 13 follow-up: a prior version was archived as part of a {@link #REPLACE} — its own event, distinct from REPLACE itself, per this pass's explicit request. */
    VERSION_CREATED,
    /** Phase 13 follow-up: any {@code /api/admin/*} call — list/create/disable/enable — logged for both success AND rejection (including a non-admin's attempt), per this pass's explicit request. */
    ADMIN,
    /** An unexpected, unclassified server-side error during any operation (Security.md §9 "Errors"). */
    ERROR
}
