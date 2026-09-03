package com.authlock.common.crypto;

/**
 * Thrown when AES-GCM authentication-tag verification fails during
 * decryption — evidence of tampering or corruption in transit, per
 * Security.md §7. Never carries the offending bytes in its message.
 */
public class TamperDetectedException extends RuntimeException {

    public TamperDetectedException(Throwable cause) {
        super("Ciphertext failed authentication — possible tampering or corruption in transit.", cause);
    }
}
