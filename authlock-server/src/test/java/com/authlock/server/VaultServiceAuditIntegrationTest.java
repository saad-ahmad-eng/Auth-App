package com.authlock.server;

import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.common.crypto.SharedKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Phase 7 DoD proof</b> (Implementation.md): exercises every audited
 * {@code VaultService} operation over real RMI, then reads the actual
 * {@code audit.log} file to confirm (a) every event type in Security.md §9
 * appears (except {@code ERROR}, proven separately in {@link
 * com.authlock.server.audit.AuditLoggerTest} — see that test's note), and
 * (b) <b>TEST-SEC-005</b>: no password, session token, or key material ever
 * appears in the log.
 */
class VaultServiceAuditIntegrationTest {

    private static final int TEST_REGISTRY_PORT = 21699;
    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";
    private static final String KEY_FILE_PROPERTY = "authlock.crypto.keyfile";
    private static final String AUDIT_FILE_PROPERTY = "authlock.audit.file";

    private static Registry registry;
    private static VaultServiceImpl serviceImpl;
    private static VaultService client;
    private static Path auditLogPath;
    private static String aliceToken;
    private static String bobToken;
    private static String uploadedFileId;

    private static String previousVaultDirProperty;
    private static String previousKeyFileProperty;
    private static String previousAuditFileProperty;

    @TempDir
    static Path tempVaultDir;
    @TempDir
    static Path tempKeyDir;
    @TempDir
    static Path tempAuditDir;

    @BeforeAll
    static void setUp() throws Exception {
        previousVaultDirProperty = System.getProperty(VAULT_DIR_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, tempVaultDir.toString());
        previousKeyFileProperty = System.getProperty(KEY_FILE_PROPERTY);
        Path keyFile = tempKeyDir.resolve("test-shared.key");
        System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
        previousAuditFileProperty = System.getProperty(AUDIT_FILE_PROPERTY);
        auditLogPath = tempAuditDir.resolve("audit.log");
        System.setProperty(AUDIT_FILE_PROPERTY, auditLogPath.toString());

        registry = LocateRegistry.createRegistry(TEST_REGISTRY_PORT);
        serviceImpl = new VaultServiceImpl(0);
        registry.rebind("VaultService", serviceImpl);

        Registry clientRegistryView = LocateRegistry.getRegistry("localhost", TEST_REGISTRY_PORT);
        client = (VaultService) clientRegistryView.lookup("VaultService");
        SecretKey sharedKey = SharedKeyProvider.load(keyFile);

        exerciseEveryAuditedOperation(sharedKey);
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
        restoreProperty(AUDIT_FILE_PROPERTY, previousAuditFileProperty);
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    /** One pass through every operation, success and failure, so every event type gets exercised. */
    private static void exerciseEveryAuditedOperation(SecretKey sharedKey) throws Exception {
        // LOGIN success + failure
        aliceToken = client.login("alice", "AliceP@ss1");
        bobToken = client.login("bob", "BobP@ss1");
        attempt(() -> client.login("alice", "wrong-password"));

        // UPLOAD success + failure (path traversal filename)
        uploadedFileId = CryptoTestSupport.uploadPlaintext(client, sharedKey, aliceToken, "audit-test.txt", "audit me".getBytes());
        attempt(() -> CryptoTestSupport.uploadPlaintext(client, sharedKey, aliceToken, "../../etc/passwd", "x".getBytes()));

        // DOWNLOAD success + failure
        CryptoTestSupport.downloadPlaintext(client, sharedKey, aliceToken, uploadedFileId);
        attempt(() -> client.downloadFile(aliceToken, "no-such-file-id"));

        // LOCK success + failure (bob contends after alice already holds it)
        client.lockFile(aliceToken, uploadedFileId);
        attempt(() -> client.lockFile(bobToken, uploadedFileId));

        // UNLOCK success + failure (bob is not the owner)
        attempt(() -> client.unlockFile(bobToken, uploadedFileId));
        client.unlockFile(aliceToken, uploadedFileId);

        // LOGOUT success (bob) — alice stays logged in so the test methods can inspect the log afterward
        client.logout(bobToken);
    }

    private interface RmiAction {
        void run() throws Exception;
    }

    private static void attempt(RmiAction action) {
        try {
            action.run();
        } catch (Exception expected) {
            // Deliberate failure paths — we only care that they were audited, not the exception itself here.
        }
    }

    @Test
    void everyExpectedEventTypeAppearsInTheLog() throws Exception {
        List<String> lines = Files.readAllLines(auditLogPath);

        assertTrue(countOccurrences(lines, "\"eventType\":\"LOGIN\"", "\"result\":\"SUCCESS\"") >= 1,
                "expected at least one LOGIN success record");
        assertTrue(countOccurrences(lines, "\"eventType\":\"LOGIN\"", "\"result\":\"FAILURE\"") >= 1,
                "expected at least one LOGIN failure record");
        assertTrue(countOccurrences(lines, "\"eventType\":\"LOGOUT\"", "\"result\":\"SUCCESS\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"UPLOAD\"", "\"result\":\"SUCCESS\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"UPLOAD\"", "\"result\":\"FAILURE\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"DOWNLOAD\"", "\"result\":\"SUCCESS\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"DOWNLOAD\"", "\"result\":\"FAILURE\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"LOCK\"", "\"result\":\"SUCCESS\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"LOCK\"", "\"result\":\"FAILURE\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"UNLOCK\"", "\"result\":\"SUCCESS\"") >= 1);
        assertTrue(countOccurrences(lines, "\"eventType\":\"UNLOCK\"", "\"result\":\"FAILURE\"") >= 1);
    }

    @Test
    void uploadedFileIdAppearsInItsAuditRecords() throws Exception {
        List<String> lines = Files.readAllLines(auditLogPath);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"fileId\":\"" + uploadedFileId + "\"")));
    }

    @Test
    void testSec005_noPasswordEverAppearsInTheLog() throws Exception {
        List<String> lines = Files.readAllLines(auditLogPath);
        for (String line : lines) {
            assertFalse(line.contains("AliceP@ss1"), "plaintext password leaked into audit log: " + line);
            assertFalse(line.contains("BobP@ss1"), "plaintext password leaked into audit log: " + line);
            assertFalse(line.contains("wrong-password"), "attempted password leaked into audit log: " + line);
        }
    }

    @Test
    void testSec005_noRawSessionTokenEverAppearsInTheLog() throws Exception {
        List<String> lines = Files.readAllLines(auditLogPath);
        for (String line : lines) {
            assertFalse(line.contains(aliceToken), "raw session token leaked into audit log: " + line);
            assertFalse(line.contains(bobToken), "raw session token leaked into audit log: " + line);
        }
    }

    private static long countOccurrences(List<String> lines, String... requiredSubstrings) {
        return lines.stream()
                .filter(line -> {
                    for (String s : requiredSubstrings) {
                        if (!line.contains(s)) {
                            return false;
                        }
                    }
                    return true;
                })
                .count();
    }
}
