package com.authlock.server.crypto;

import com.authlock.common.crypto.AesGcmCipher;
import com.authlock.common.crypto.SharedKeyProvider;
import com.authlock.common.crypto.TamperDetectedException;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Server-side half of the AES-256-GCM transport encryption (Architecture.md
 * §2.8, Security.md §7). Decrypts incoming upload payloads on receipt and
 * encrypts outgoing download payloads before they leave the server — the
 * server is a normal participant in the per-hop encryption scheme, not a
 * blind ciphertext relay (contrast with an end-to-end design, which
 * `auth`/Security.md did not specify — see Security.md §7 "Where").
 */
public final class EncryptionService {

    private final AesGcmCipher cipher = new AesGcmCipher();
    private final SecretKey key;

    public EncryptionService(Path keyFile) throws IOException {
        this.key = SharedKeyProvider.loadOrGenerate(keyFile);
    }

    /**
     * Decrypts an incoming upload payload.
     *
     * @throws TamperDetectedException if the ciphertext fails authentication
     */
    public byte[] decrypt(byte[] iv, byte[] ciphertext) {
        return cipher.decrypt(key, iv, ciphertext);
    }

    /** Encrypts plaintext for an outgoing download response, with a fresh IV. */
    public AesGcmCipher.Encrypted encrypt(byte[] plaintext) {
        return cipher.encrypt(key, plaintext);
    }
}
