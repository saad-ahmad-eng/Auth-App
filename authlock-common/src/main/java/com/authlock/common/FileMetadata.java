package com.authlock.common;

import java.io.Serializable;
import java.time.Instant;

/**
 * Response DTO for {@link VaultService#listFiles}, per API-spec.md §2 and
 * Backend.md §2.3.
 *
 * <p>{@code lockState}/{@code lockOwnerHint} reflect real, live
 * {@code LockManager} state as of Phase 5 (see {@code VaultServiceImpl.toDto}).
 *
 * @param fileId        server-generated internal identifier
 * @param filename      original, client-supplied display name — untrusted
 *                      metadata only, never used as a storage path (SEC-006)
 * @param size           byte size of the stored content
 * @param owner          the uploader's <b>username</b> (human-readable display value,
 *                       resolved from the stable internal userId at upload time — see
 *                       {@code VaultServiceImpl.uploadFile}'s Owner-column fix, Phase 8/Context.md)
 * @param createdAt      upload timestamp
 * @param modifiedAt     last successful update timestamp
 * @param lockState      {@code "UNLOCKED"} or {@code "LOCKED"}
 * @param lockOwnerHint  {@code "you"}/{@code "another user"} when locked, {@code null} when
 *                       unlocked — never a raw session token or other identity (minimal disclosure)
 */
public record FileMetadata(
        String fileId,
        String filename,
        long size,
        String owner,
        Instant createdAt,
        Instant modifiedAt,
        String lockState,
        String lockOwnerHint
) implements Serializable {
}
