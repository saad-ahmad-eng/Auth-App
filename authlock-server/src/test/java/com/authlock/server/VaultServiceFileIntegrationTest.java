package com.authlock.server;

import com.authlock.common.ErrorCode;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.common.crypto.AesGcmCipher;
import com.authlock.common.crypto.SharedKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4/6 RMI-level integration tests for {@code listFiles}/{@code uploadFile}/
 * {@code downloadFile}, covering Testing.md §2.3 TEST-FILE-001..006 and
 * §2.5 TEST-SEC-001/003/004 over a real RMI round trip — <b>through real
 * AES-256-GCM encryption</b> (Implementation Phase 6 made this mandatory,
 * Security.md §7): every upload here is genuinely encrypted client-side and
 * every download genuinely decrypted, exactly as a real client must now do
 * — see {@link CryptoTestSupport}.
 *
 * <p>Class-scoped setup for the same reason documented in
 * {@link VaultServiceAuthIntegrationTest}. Uses isolated {@code @TempDir}s
 * for both vault storage and the shared crypto key (via
 * {@code authlock.vault.dir} / {@code authlock.crypto.keyfile}) so these
 * tests never touch a real server's files.
 */
class VaultServiceFileIntegrationTest {

    private static final int TEST_REGISTRY_PORT = 21299;
    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";
    private static final String KEY_FILE_PROPERTY = "authlock.crypto.keyfile";

    private static Registry registry;
    private static VaultServiceImpl serviceImpl;
    private static VaultService client;
    private static String sessionToken;
    private static SecretKey sharedKey;
    private static String previousVaultDirProperty;
    private static String previousKeyFileProperty;

    @TempDir
    static Path tempVaultDir;
    @TempDir
    static Path tempKeyDir;

    @BeforeAll
    static void setUp() throws Exception {
        previousVaultDirProperty = System.getProperty(VAULT_DIR_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, tempVaultDir.toString());
        previousKeyFileProperty = System.getProperty(KEY_FILE_PROPERTY);
        Path keyFile = tempKeyDir.resolve("test-shared.key");
        System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());

        registry = LocateRegistry.createRegistry(TEST_REGISTRY_PORT);
        serviceImpl = new VaultServiceImpl(0); // generates the key file on construction
        registry.rebind("VaultService", serviceImpl);
        sharedKey = SharedKeyProvider.load(keyFile); // read back the same key the server just generated

        Registry clientRegistryView = LocateRegistry.getRegistry("localhost", TEST_REGISTRY_PORT);
        client = (VaultService) clientRegistryView.lookup("VaultService");

        sessionToken = client.login("alice", "Alice2026Pass");
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

        restoreProperty(VAULT_DIR_PROPERTY, previousVaultDirProperty);
        restoreProperty(KEY_FILE_PROPERTY, previousKeyFileProperty);
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    @Test
    void testFile001_uploadThenListThenDownloadRoundTrip() throws Exception {
        byte[] content = "AuthLock file vault round trip".getBytes();

        String fileId = CryptoTestSupport.uploadPlaintext(client, sharedKey, sessionToken, "round-trip.txt", content);
        assertNotNull(fileId);

        List<FileMetadata> files = client.listFiles(sessionToken);
        assertTrue(files.stream().anyMatch(f -> f.fileId().equals(fileId) && f.filename().equals("round-trip.txt")));

        byte[] downloaded = CryptoTestSupport.downloadPlaintext(client, sharedKey, sessionToken, fileId);
        assertArrayEquals(content, downloaded, "downloaded, decrypted bytes must be byte-identical to what was uploaded");
    }

    @Test
    void testFile002_emptyFileUploadIsAccepted() throws Exception {
        String fileId = CryptoTestSupport.uploadPlaintext(client, sharedKey, sessionToken, "empty.txt", new byte[0]);

        byte[] downloaded = CryptoTestSupport.downloadPlaintext(client, sharedKey, sessionToken, fileId);
        assertEquals(0, downloaded.length);
    }

    @Test
    void testFile003_largeFileUploadDownloadIsNotTruncated() throws Exception {
        byte[] largeContent = new byte[5 * 1024 * 1024]; // 5 MB
        new SecureRandom().nextBytes(largeContent);

        String fileId = CryptoTestSupport.uploadPlaintext(client, sharedKey, sessionToken, "large.bin", largeContent);
        byte[] downloaded = CryptoTestSupport.downloadPlaintext(client, sharedKey, sessionToken, fileId);

        assertArrayEquals(largeContent, downloaded);
    }

    @Test
    void testFile004_downloadReturnsChecksumMatchingPlaintextContent() throws Exception {
        byte[] content = "checksum me".getBytes();
        String fileId = CryptoTestSupport.uploadPlaintext(client, sharedKey, sessionToken, "checksum.txt", content);

        FileContent downloaded = client.downloadFile(sessionToken, fileId);

        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        byte[] expectedHash = sha256.digest(content); // checksum is over plaintext, not ciphertext (Security.md §6)
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
        // Session is validated before decryption is even attempted, so raw
        // (non-encrypted) bytes are fine here — this never reaches the crypto layer.
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.uploadFile("not-a-real-token", "x.txt", "x".getBytes(), new byte[0]));

        assertEquals(ErrorCode.INVALID_SESSION, ex.getErrorCode());
    }

    @Test
    void testSec001_pathTraversalFilenameIsRejectedOverRmi() {
        // Properly encrypted payload so the request reaches filename
        // validation (in VaultFileService.store()), not crypto error handling.
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> CryptoTestSupport.uploadPlaintext(client, sharedKey, sessionToken,
                        "../../etc/passwd", "malicious".getBytes()));

        assertEquals(ErrorCode.UPLOAD_FAILED, ex.getErrorCode());
    }

    @Test
    void testSec003_tamperedCiphertextIsRejected() throws Exception {
        AesGcmCipher.Encrypted encrypted = new AesGcmCipher().encrypt(sharedKey, "authentic content".getBytes());
        byte[] tamperedCiphertext = encrypted.ciphertext().clone();
        tamperedCiphertext[0] ^= 0xFF; // flip a bit — must fail GCM tag verification

        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.uploadFile(sessionToken, "tampered.txt", tamperedCiphertext, encrypted.iv()));

        assertEquals(ErrorCode.UPLOAD_FAILED, ex.getErrorCode());
    }

    @Test
    void testSec004_ciphertextOnTheWireNeverEqualsOrContainsThePlaintext() throws Exception {
        byte[] plaintext = "sensitive file content that must never appear on the wire in the clear".getBytes();
        String fileId = CryptoTestSupport.uploadPlaintext(client, sharedKey, sessionToken, "sensitive.txt", plaintext);

        // Inspect what actually crossed the RMI boundary on download — the raw DTO, not the decrypted result.
        FileContent wireContent = client.downloadFile(sessionToken, fileId);

        assertNotEquals(new String(plaintext), new String(wireContent.fileBytes()),
                "the bytes returned over RMI must not equal the plaintext");
        String wireAsLatin1 = new String(wireContent.fileBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(!wireAsLatin1.contains("sensitive file content"),
                "no substring of the plaintext should be recoverable from the wire bytes");

        // Sanity: decrypting those exact wire bytes does recover the original plaintext.
        byte[] decrypted = new AesGcmCipher().decrypt(sharedKey, wireContent.iv(), wireContent.fileBytes());
        assertArrayEquals(plaintext, decrypted);
    }
}
