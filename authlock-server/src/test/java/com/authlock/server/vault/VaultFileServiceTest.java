package com.authlock.server.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VaultFileService}, covering Testing.md §2.3
 * TEST-FILE-001/004/005 and §2.5 TEST-SEC-001 at the storage layer (RMI-level
 * equivalents are in {@code VaultServiceFileIntegrationTest}), plus the
 * restart-survival behavior promised by ADR-010/OQ-07's resolution.
 */
class VaultFileServiceTest {

    @Test
    void testFile001_storedFileIsRetrievableWithMatchingContentAndChecksum(@TempDir Path tempDir) throws IOException {
        VaultFileService service = new VaultFileService(tempDir);
        byte[] content = "hello, vault".getBytes();

        FileRecord stored = service.store("notes.txt", content, "user-1");
        Optional<VaultFileService.StoredFile> retrieved = service.retrieve(stored.fileId());

        assertTrue(retrieved.isPresent());
        assertArrayEquals(content, retrieved.get().content());
        assertEquals(stored.checksum(), retrieved.get().metadata().checksum());
    }

    @Test
    void testFile005_unknownFileIdReturnsEmpty(@TempDir Path tempDir) throws IOException {
        VaultFileService service = new VaultFileService(tempDir);

        assertTrue(service.retrieve("no-such-file-id").isEmpty());
    }

    @Test
    void testSec001_pathTraversalFilenameIsRejected(@TempDir Path tempDir) throws IOException {
        VaultFileService service = new VaultFileService(tempDir);

        assertThrows(IllegalArgumentException.class,
                () -> service.store("../../etc/passwd", "malicious".getBytes(), "user-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.store("nested/dir/file.txt", "x".getBytes(), "user-1"));
    }

    @Test
    void blankFilenameIsRejected(@TempDir Path tempDir) throws IOException {
        VaultFileService service = new VaultFileService(tempDir);

        assertThrows(IllegalArgumentException.class,
                () -> service.store("   ", "x".getBytes(), "user-1"));
    }

    @Test
    void metadataAndContentSurviveServiceRestart_resolvesOQ07(@TempDir Path tempDir) throws IOException {
        VaultFileService first = new VaultFileService(tempDir);
        byte[] content = "persisted content".getBytes();
        FileRecord stored = first.store("persist-me.txt", content, "user-1");

        // Simulate a server restart: a fresh VaultFileService over the same directory.
        VaultFileService afterRestart = new VaultFileService(tempDir);
        Optional<VaultFileService.StoredFile> retrieved = afterRestart.retrieve(stored.fileId());

        assertTrue(retrieved.isPresent(), "file metadata/content must survive a service restart (ADR-010)");
        assertArrayEquals(content, retrieved.get().content());
        assertEquals("persist-me.txt", retrieved.get().metadata().filename());
        assertEquals(1, afterRestart.listAll().size());
    }

    @Test
    void listAllReflectsAllStoredFiles(@TempDir Path tempDir) throws IOException {
        VaultFileService service = new VaultFileService(tempDir);
        service.store("a.txt", "a".getBytes(), "user-1");
        service.store("b.txt", "b".getBytes(), "user-2");

        assertEquals(2, service.listAll().size());
    }
}
