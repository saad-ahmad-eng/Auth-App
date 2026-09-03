package com.authlock.server.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

/**
 * Salted PBKDF2WithHmacSHA256 password hashing and verification, per
 * Security.md §3: "never store plaintext passwords," constant-time
 * comparison, standard JCE primitives only (no custom cryptography, per
 * p1.md §8).
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int KEY_LENGTH_BITS = 256;

    /** KDF work factor. Recommendation per Security.md §3: tuned to stay under ~250ms on the deployment VM. */
    public static final int DEFAULT_ITERATIONS = 120_000;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Hashes a password with a freshly generated random salt at {@link #DEFAULT_ITERATIONS}. */
    public HashedPassword hash(char[] password) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, DEFAULT_ITERATIONS);
        return new HashedPassword(salt, hash, DEFAULT_ITERATIONS);
    }

    /**
     * Verifies a candidate password against a stored digest using a
     * constant-time comparison (SEC-001 — no timing side-channel).
     */
    public boolean verify(char[] password, HashedPassword stored) {
        byte[] candidate = pbkdf2(password, stored.salt(), stored.iterations());
        boolean equal = MessageDigest.isEqual(candidate, stored.hash());
        Arrays.fill(candidate, (byte) 0);
        return equal;
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // ALGORITHM is a standard JDK provider name; this indicates a
            // broken JRE, not a recoverable runtime condition.
            throw new IllegalStateException("PBKDF2 algorithm unavailable: " + ALGORITHM, e);
        } finally {
            spec.clearPassword();
        }
    }
}
