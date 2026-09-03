package com.authlock.server.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for salted PBKDF2 hashing (Security.md §3, SEC-002). */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void correctPasswordVerifiesSuccessfully() {
        HashedPassword stored = hasher.hash("correct horse battery staple".toCharArray());
        assertTrue(hasher.verify("correct horse battery staple".toCharArray(), stored));
    }

    @Test
    void wrongPasswordFailsVerification() {
        HashedPassword stored = hasher.hash("correct horse battery staple".toCharArray());
        assertFalse(hasher.verify("wrong password".toCharArray(), stored));
    }

    @Test
    void hashingTheSamePasswordTwiceProducesDifferentSaltsAndDigests() {
        HashedPassword first = hasher.hash("same-password".toCharArray());
        HashedPassword second = hasher.hash("same-password".toCharArray());

        assertNotEquals(java.util.Arrays.toString(first.salt()), java.util.Arrays.toString(second.salt()),
                "salts must be freshly randomized per hash — never store plaintext-equivalent digests");
        assertNotEquals(java.util.Arrays.toString(first.hash()), java.util.Arrays.toString(second.hash()));
    }
}
