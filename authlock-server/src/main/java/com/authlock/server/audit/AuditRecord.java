package com.authlock.server.audit;

import com.authlock.common.ErrorCode;

import java.time.Instant;

/**
 * One audit log entry, per Security.md §9's metadata schema.
 *
 * <p><b>Never carries:</b> passwords, password hashes, raw session tokens,
 * or encryption keys (Security.md §9's explicit prohibition). {@code userId}
 * identifies the acting user, never the raw session token that authorized
 * the call — see {@link com.authlock.server.VaultServiceImpl}'s call sites,
 * which always pass a resolved user ID (or, for a failed login where no
 * identity has been established yet, the attempted username — clearly not
 * a secret, and standard practice for detecting brute-force/enumeration
 * attempts).
 *
 * @param timestamp  event time (UTC)
 * @param eventType  what kind of operation this was
 * @param userId     the acting user's ID, the attempted username on a failed
 *                    login, or {@code null} if no identity is resolvable
 *                    (e.g. an unknown/expired session token)
 * @param operation  the {@code VaultService} method name, e.g. {@code "uploadFile"}
 * @param fileId     the target file's ID, where applicable, else {@code null}
 * @param result     {@link AuditResult#SUCCESS} or {@link AuditResult#FAILURE}
 * @param errorCode  the failure's {@link ErrorCode}, or {@code null} on success
 * @param clientInfo the calling client's host/IP as seen by the RMI server,
 *                    where available, else {@code null}
 */
public record AuditRecord(
        Instant timestamp,
        AuditEventType eventType,
        String userId,
        String operation,
        String fileId,
        AuditResult result,
        ErrorCode errorCode,
        String clientInfo
) {
}
