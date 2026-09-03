package com.authlock.server.vault;

import java.time.Instant;

/**
 * Internal (server-side only, never sent over RMI) logical File Metadata
 * model, per Backend.md §2.3. {@link com.authlock.common.FileMetadata} is
 * the RMI-facing DTO derived from this record in {@code VaultServiceImpl}.
 *
 * <p>No {@code iv} field: storage is always plaintext (Phase 6 note in
 * {@link VaultFileService}) — encryption IVs are generated fresh per wire
 * transfer, never persisted.
 *
 * @param fileId           server-generated internal identifier — also the
 *                         on-disk filename stem (SEC-006: {@code filename}
 *                         below is never used to build a path)
 * @param filename         original, client-supplied display name
 * @param owner            userId of the uploader
 * @param size             byte size of the stored (plaintext) content
 * @param checksum         hex-encoded SHA-256 of the plaintext content
 * @param createdAt        upload timestamp
 * @param modifiedAt       last successful update timestamp
 */
public record FileRecord(
        String fileId,
        String filename,
        String owner,
        long size,
        String checksum,
        Instant createdAt,
        Instant modifiedAt
) {
}
