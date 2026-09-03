package com.authlock.common.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link AesGcmCipher}, per Security.md §7 and ADR-007. */
class AesGcmCipherTest {

    private final AesGcmCipher cipher = new AesGcmCipher();

    private static SecretKey randomKey() throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(256);
        return gen.generateKey();
    }

    @Test
    void decryptRecoversTheOriginalPlaintext() throws Exception {
        SecretKey key = randomKey();
        byte[] plaintext = "AuthLock encrypted file content".getBytes();

        AesGcmCipher.Encrypted encrypted = cipher.encrypt(key, plaintext);
        byte[] decrypted = cipher.decrypt(key, encrypted.iv(), encrypted.ciphertext());

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    void ciphertextNeverEqualsThePlaintext() throws Exception {
        SecretKey key = randomKey();
        byte[] plaintext = "not-secret-length-but-secret-content".getBytes();

        AesGcmCipher.Encrypted encrypted = cipher.encrypt(key, plaintext);

        assertNotEquals(new String(plaintext), new String(encrypted.ciphertext()));
    }

    @Test
    void everyEncryptionUsesAFreshIv() throws Exception {
        SecretKey key = randomKey();
        byte[] plaintext = "same content, twice".getBytes();

        AesGcmCipher.Encrypted first = cipher.encrypt(key, plaintext);
        AesGcmCipher.Encrypted second = cipher.encrypt(key, plaintext);

        assertNotEquals(java.util.Arrays.toString(first.iv()), java.util.Arrays.toString(second.iv()),
                "reusing an IV with the same key breaks AES-GCM's security guarantees");
    }

    @Test
    void testSec003_tamperedCiphertextFailsAuthentication() throws Exception {
        SecretKey key = randomKey();
        AesGcmCipher.Encrypted encrypted = cipher.encrypt(key, "authentic data".getBytes());
        byte[] tampered = encrypted.ciphertext().clone();
        tampered[0] ^= 0xFF;

        assertThrows(TamperDetectedException.class, () -> cipher.decrypt(key, encrypted.iv(), tampered));
    }

    @Test
    void decryptingWithTheWrongKeyFailsAuthentication() throws Exception {
        SecretKey key = randomKey();
        SecretKey wrongKey = randomKey();
        AesGcmCipher.Encrypted encrypted = cipher.encrypt(key, "secret".getBytes());

        assertThrows(TamperDetectedException.class, () -> cipher.decrypt(wrongKey, encrypted.iv(), encrypted.ciphertext()));
    }

    @Test
    void emptyPlaintextRoundTripsCorrectly() throws Exception {
        SecretKey key = randomKey();

        AesGcmCipher.Encrypted encrypted = cipher.encrypt(key, new byte[0]);
        byte[] decrypted = cipher.decrypt(key, encrypted.iv(), encrypted.ciphertext());

        assertArrayEquals(new byte[0], decrypted);
    }
}
