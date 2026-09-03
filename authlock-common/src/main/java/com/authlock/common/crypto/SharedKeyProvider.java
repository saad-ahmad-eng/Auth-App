package com.authlock.common.crypto;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Loads (or, server-side, generates) the AES-256 key used by
 * {@link AesGcmCipher}, per the resolution of Open Question OQ-05
 * (Context.md §7, Security.md §7).
 *
 * <p><b>Resolution of OQ-05 (application-layer half):</b> a pre-shared key,
 * stored Base64-encoded in a local file (default {@code authlock-shared.key},
 * path overridable via {@code -Dauthlock.crypto.keyfile=<path>}) that both
 * client and server read. This is an explicit <b>coursework-scope
 * simplification</b> — not a production key-management scheme (no rotation,
 * no per-user keys, no KMS) — chosen because: (a) it needs no in-band key
 * exchange or additional server API surface; (b) it is honest and inspectable
 * (the key file's very existence documents the limitation rather than hiding
 * it behind an opaque protocol); (c) the credential/session-token
 * confidentiality gap this leaves is separately closed by RMI-over-TLS
 * (Security.md §7's transport-layer complementary control), so this key's
 * blast radius if the file is copied is "file contents," not "everything."
 *
 * <p>The server generates the key on first startup if the file does not yet
 * exist ({@link #loadOrGenerate}); the client only ever reads
 * ({@link #load}) and fails clearly if the file is missing — it should be
 * copied from the server (or, for local development, the two processes
 * simply share a working directory).
 */
public final class SharedKeyProvider {

    private static final int KEY_LENGTH_BITS = 256;

    private SharedKeyProvider() {
    }

    /** Server-side: load the key if the file exists, or generate and persist a new one. */
    public static SecretKey loadOrGenerate(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return load(keyFile);
        }
        SecretKey key = generate();
        if (keyFile.toAbsolutePath().getParent() != null) {
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
        }
        String encoded = Base64.getEncoder().encodeToString(key.getEncoded());
        Files.writeString(keyFile, encoded, StandardCharsets.US_ASCII);
        return key;
    }

    /** Client-side (and server-side re-reads): load an existing key file. */
    public static SecretKey load(Path keyFile) throws IOException {
        if (!Files.exists(keyFile)) {
            throw new NoSuchFileException(keyFile.toString(),
                    null, "AuthLock shared key file not found — start the server first, or copy its key file here.");
        }
        String encoded = Files.readString(keyFile, StandardCharsets.US_ASCII).strip();
        byte[] raw = Base64.getDecoder().decode(encoded);
        return new SecretKeySpec(raw, "AES");
    }

    private static SecretKey generate() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(KEY_LENGTH_BITS, new SecureRandom());
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES key generation unavailable", e);
        }
    }
}
