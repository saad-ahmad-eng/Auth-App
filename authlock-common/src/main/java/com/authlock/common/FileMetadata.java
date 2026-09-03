package com.authlock.common;

import java.io.Serializable;
import java.time.Instant;

/**
 * Response DTO for {@link VaultService#listFiles}, per API-spec.md §2 and
 * Backend.md §2.3.
 *
 * <p><b>Phase 4 scope:</b> {@code lockState} is always {@code "UNLOCKED"}
 * and {@code lockOwnerHint} is always {@code null} — there is no Lock
 * Manager yet (Implementation.md Phase 5). Both become meaningful once
 * Phase 5 wires real lock state into this DTO's construction in
 * {@code VaultServiceImpl}.
 *
 * @param fileId        server-generated internal identifier
 * @param filename      original, client-supplied display name — untrusted
 *                      metadata only, never used as a storage path (SEC-006)
 * @param size           byte size of the stored content
 * @param owner          userId of the uploader
 * @param createdAt      upload timestamp
 * @param modifiedAt     last successful update timestamp
 * @param lockState      {@code "UNLOCKED"} or {@code "LOCKED"} (Phase 4: always UNLOCKED)
 * @param lockOwnerHint  generic hint when locked, never a raw session token (Phase 4: always null)
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
