package com.authlock.common.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link SharedKeyProvider} — the OQ-05 (application-layer half) resolution. */
class SharedKeyProviderTest {

    @Test
    void loadOrGenerateCreatesAKeyFileWhenNoneExists(@TempDir Path dir) throws IOException {
        Path keyFile = dir.resolve("shared.key");

        SecretKey key = SharedKeyProvider.loadOrGenerate(keyFile);

        assertTrue(java.nio.file.Files.exists(keyFile));
        assertTrue(key.getEncoded().length >= 32, "expect a 256-bit (32-byte) AES key");
    }

    @Test
    void loadOrGenerateReturnsTheSameKeyOnSubsequentCalls(@TempDir Path dir) throws IOException {
        Path keyFile = dir.resolve("shared.key");

        SecretKey first = SharedKeyProvider.loadOrGenerate(keyFile);
        SecretKey second = SharedKeyProvider.loadOrGenerate(keyFile);

        assertArrayEquals(first.getEncoded(), second.getEncoded(),
                "a second process/instance reading the same key file must get an identical key");
    }

    @Test
    void loadFailsClearlyWhenTheKeyFileDoesNotExist(@TempDir Path dir) {
        Path keyFile = dir.resolve("does-not-exist.key");

        assertThrows(NoSuchFileException.class, () -> SharedKeyProvider.load(keyFile));
    }

    @Test
    void loadReadsBackWhatLoadOrGenerateWrote(@TempDir Path dir) throws IOException {
        Path keyFile = dir.resolve("shared.key");
        SecretKey generated = SharedKeyProvider.loadOrGenerate(keyFile);

        SecretKey loaded = SharedKeyProvider.load(keyFile);

        assertArrayEquals(generated.getEncoded(), loaded.getEncoded());
    }
}
