package com.authlock.common;

import java.io.Serializable;

/**
 * Response DTO for {@link VaultService#downloadFile}, per API-spec.md §2.
 *
 * <p><b>Phase 4 scope:</b> {@code iv} is always an empty array — application-
 * layer encryption (ADR-007) is Implementation.md Phase 6's responsibility.
 * {@code fileBytes} is plaintext until then. {@code checksum} is real from
 * Phase 4 onward (SHA-256 of the plaintext content, verified server-side
 * before this DTO is returned — see Security.md §6 "File integrity").
 *
 * @param fileBytes the file content (plaintext in Phase 4; ciphertext from Phase 6 onward)
 * @param iv        AES-GCM nonce, empty until Phase 6
 * @param checksum  hex-encoded SHA-256 of the plaintext content, for client-side verification
 */
public record FileContent(byte[] fileBytes, byte[] iv, String checksum) implements Serializable {
}
