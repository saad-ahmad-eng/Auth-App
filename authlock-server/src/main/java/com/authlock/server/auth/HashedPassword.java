package com.authlock.server.auth;

/**
 * A salted PBKDF2 password digest, per Security.md §3 (SEC-002 — never store
 * plaintext passwords). Immutable value holder; never logged or transmitted
 * (Development-rules.md §3).
 *
 * @param salt       random per-user salt
 * @param hash       PBKDF2WithHmacSHA256 derived key
 * @param iterations KDF work factor used to produce {@code hash} — stored
 *                   alongside the hash so it can be tuned upward over time
 *                   without invalidating existing digests
 */
public record HashedPassword(byte[] salt, byte[] hash, int iterations) {
}
