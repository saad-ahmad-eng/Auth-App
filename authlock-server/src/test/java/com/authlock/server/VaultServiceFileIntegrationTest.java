package com.authlock.server;

import com.authlock.common.ErrorCode;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 RMI-level integration tests for {@code listFiles}/{@code uploadFile}/
 * {@code downloadFile}, covering Testing.md §2.3 TEST-FILE-001..006 over a
 * real RMI round trip. Class-scoped setup for the same reason documented in
 * {@link VaultServiceAuthIntegrationTest}. Uses an isolated {@code @TempDir}
 * as the vault storage directory (via {@code authlock.vault.dir}) so these
 * tests never touch a real server's {@code vault-storage/}.
 */
class VaultServiceFileIntegrationTest {

    private static final int TEST_REGISTRY_PORT = 21299;
    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";

    private static Registry registry;
    private static VaultServiceImpl serviceImpl;
    private static VaultService client;
    private static String sessionToken;
    private static String previousVaultDirProperty;

    @TempDir
    static Path tempVaultDir;

    @BeforeAll
    static void setUp() throws Exception {
        previousVaultDirProperty = System.getProperty(VAULT_DIR_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, tempVaultDir.toString());

        registry = LocateRegistry.createRegistry(TEST_REGISTRY_PORT);
        serviceImpl = new VaultServiceImpl(0);
        registry.rebind("VaultService", serviceImpl);

        Registry clientRegistryView = LocateRegistry.getRegistry("localhost", TEST_REGISTRY_PORT);
        client = (VaultService) clientRegistryView.lookup("VaultService");

        sessionToken = client.login("alice", "AliceP@ss1");
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            registry.unbind("VaultService");
        } catch (Exception ignored) {
            // best-effort
        }
        UnicastRemoteObject.unexportObject(serviceImpl, true);
        UnicastRemoteObject.unexportObject(registry, true);

        if (previousVaultDirProperty == null) {
            System.clearProperty(VAULT_DIR_PROPERTY);
        } else {
            System.setProperty(VAULT_DIR_PROPERTY, previousVaultDirProperty);
        }
    }

    @Test
    void testFile001_uploadThenListThenDownloadRoundTrip() throws Exception {
        byte[] content = "AuthLock file vault round trip".getBytes();

        String fileId = client.uploadFile(sessionToken, "round-trip.txt", content, new byte[0]);
        assertNotNull(fileId);

        List<FileMetadata> files = client.listFiles(sessionToken);
        assertTrue(files.stream().anyMatch(f -> f.fileId().equals(fileId) && f.filename().equals("round-trip.txt")));

        FileContent downloaded = client.downloadFile(sessionToken, fileId);
        assertArrayEquals(content, downloaded.fileBytes(), "downloaded bytes must be byte-identical to what was uploaded");
    }

    @Test
    void testFile002_emptyFileUploadIsAccepted() throws Exception {
        String fileId = client.uploadFile(sessionToken, "empty.txt", new byte[0], new byte[0]);

        FileContent downloaded = client.downloadFile(sessionToken, fileId);
        assertEquals(0, downloaded.fileBytes().length);
    }

    @Test
    void testFile003_largeFileUploadDownloadIsNotTruncated() throws Exception {
        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5 MB
        new SecureRandom().nextBytes(largeContent);

        String fileId = client.uploadFile(sessionToken, "large.bin", largeContent, new byte[0]);
        FileContent downloaded = client.downloadFile(sessionToken, fileId);

        assertArrayEquals(largeContent, downloaded.fileBytes());
    }

    @Test
    void testFile004_downloadReturnsChecksumMatchingContent() throws Exception {
        byte[] content = "checksum me".getBytes();
        String fileId = client.uploadFile(sessionToken, "checksum.txt", content, new byte[0]);

        FileContent downloaded = client.downloadFile(sessionToken, fileId);

        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        byte[] expectedHash = sha256.digest(content);
        StringBuilder expectedHex = new StringBuilder();
        for (byte b : expectedHash) {
            expectedHex.append(String.format("%02x", b));
        }
        assertEquals(expectedHex.toString(), downloaded.checksum());
    }

    @Test
    void testFile005_downloadOfMissingFileIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.downloadFile(sessionToken, "no-such-file-id"));

        assertEquals(ErrorCode.FILE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void testFile006_downloadWithInvalidSessionIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.downloadFile("not-a-real-token", "irrelevant-file-id"));

        assertEquals(ErrorCode.INVALID_SESSION, ex.getErrorCode());
    }

    @Test
    void listFilesWithInvalidSessionIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.listFiles("not-a-real-token"));

        assertEquals(ErrorCode.INVALID_SESSION, ex.getErrorCode());
    }

    @Test
    void uploadWithInvalidSessionIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.uploadFile("not-a-real-token", "x.txt", "x".getBytes(), new byte[0]));

        assertEquals(ErrorCode.INVALID_SESSION, ex.getErrorCode());
    }

    @Test
    void testSec001_pathTraversalFilenameIsRejectedOverRmi() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.uploadFile(sessionToken, "../../etc/passwd", "malicious".getBytes(), new byte[0]));

        assertEquals(ErrorCode.UPLOAD_FAILED, ex.getErrorCode());
    }
}
