package com.authlock.common;

import java.io.Serializable;

/**
 * Response DTO for {@link VaultService#downloadFile}, per API-spec.md §2.
 *
 * <p>Since Phase 6 (ADR-007), {@code fileBytes} is AES-256-GCM ciphertext,
 * freshly encrypted server-side (a new random {@code iv} every download —
 * never reused, per {@link com.authlock.common.crypto.AesGcmCipher}'s "never
 * reuse an IV with the same key" rule) from the plaintext read off disk.
 * {@code checksum} is computed server-side over that plaintext, before
 * encryption, so the client verifies it after decrypting (Security.md §6
 * "File integrity").
 *
 * @param fileBytes AES-256-GCM ciphertext of the file content
 * @param iv        the AES-GCM nonce {@code fileBytes} was encrypted with, freshly generated for this download
 * @param checksum  hex-encoded SHA-256 of the plaintext content, for client-side verification after decryption
 */
public record FileContent(byte[] fileBytes, byte[] iv, String checksum) implements Serializable {
}
