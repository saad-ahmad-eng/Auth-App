package com.authlock.common.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.Serializable;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * AES-256-GCM authenticated encryption, per ADR-007 and Security.md §7.
 * Standard JCE primitives only — no custom cryptographic protocol
 * ([p1.md](../../../../p1.md) §8). Shared between {@code authlock-client}
 * (encrypts before {@code uploadFile}, decrypts after {@code downloadFile})
 * and {@code authlock-server} (decrypts on upload receipt, encrypts before
 * returning a download) — Security.md §7's "encrypted by the sender...
 * decrypted by the receiver" applies per hop, not end-to-end.
 */
public final class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** Standard AES-GCM nonce length — 96 bits. Never reuse an IV with the same key (Security.md §7). */
    public static final int IV_LENGTH_BYTES = 12;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Ciphertext plus the fresh, random IV used to produce it. */
    public record Encrypted(byte[] iv, byte[] ciphertext) implements Serializable {
    }

    /** Encrypts {@code plaintext} under {@code key} with a freshly generated random IV. */
    public Encrypted encrypt(SecretKey key, byte[] plaintext) {
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new Encrypted(iv, ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts {@code ciphertext} under {@code key} using the given IV, and
     * verifies the GCM authentication tag.
     *
     * @throws TamperDetectedException if the ciphertext/IV/key do not match
     *                                  (tampering or corruption in transit —
     *                                  Security.md §7 "Authentication/integrity protection")
     */
    public byte[] decrypt(SecretKey key, byte[] iv, byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException tamperEvidence) {
            throw new TamperDetectedException(tamperEvidence);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }
}
