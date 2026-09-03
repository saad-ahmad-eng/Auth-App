package com.authlock.server;

import com.authlock.common.ErrorCode;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 RMI-level integration tests for {@code lockFile}/{@code unlockFile},
 * covering Testing.md §2.4 TEST-LOCK-001..004 (and the {@code listFiles}
 * lock-state reflection) over a real RMI round trip, plus the
 * {@code logout} side effect of releasing held locks. TEST-CONC-001 (the
 * mandatory N-client race) is in {@link VaultServiceLockConcurrencyTest} —
 * it needs real concurrent threads, not sequential calls.
 *
 * <p>Class-scoped setup for the same reason documented in
 * {@link VaultServiceAuthIntegrationTest}. Uses two independently logged-in
 * sessions (alice, bob) to exercise real cross-session lock contention.
 */
class VaultServiceLockIntegrationTest {

    private static final int TEST_REGISTRY_PORT = 21399;
    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";

    private static Registry registry;
    private static VaultServiceImpl serviceImpl;
    private static VaultService client;
    private static String previousVaultDirProperty;

    @TempDir
    static Path tempVaultDir;

    private String fileId;
    private String aliceToken;
    private String bobToken;

    @BeforeAll
    static void setUpServer() throws Exception {
        previousVaultDirProperty = System.getProperty(VAULT_DIR_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, tempVaultDir.toString());

        registry = LocateRegistry.createRegistry(TEST_REGISTRY_PORT);
        serviceImpl = new VaultServiceImpl(0);
        registry.rebind("VaultService", serviceImpl);

        Registry clientRegistryView = LocateRegistry.getRegistry("localhost", TEST_REGISTRY_PORT);
        client = (VaultService) clientRegistryView.lookup("VaultService");
    }

    @AfterAll
    static void tearDownServer() throws Exception {
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

    /**
     * Fresh sessions and a fresh file per test method (unlike the read-only
     * file tests, lock state is mutated by every test here, so sharing
     * would make tests order-dependent).
     */
    @org.junit.jupiter.api.BeforeEach
    void freshFixture() throws Exception {
        aliceToken = client.login("alice", "AliceP@ss1");
        bobToken = client.login("bob", "BobP@ss1");
        fileId = client.uploadFile(aliceToken, "shared.txt", "shared content".getBytes(), new byte[0]);
    }

    @org.junit.jupiter.api.AfterEach
    void logoutFixture() throws Exception {
        // Also exercises "logout releases held locks" for whichever test left one held.
        try {
            client.logout(aliceToken);
        } catch (Exception ignored) {
        }
        try {
            client.logout(bobToken);
        } catch (Exception ignored) {
        }
    }

    @Test
    void testLock001_acquiringAnUnlockedFileSucceeds() {
        assertDoesNotThrow(() -> client.lockFile(aliceToken, fileId));
    }

    @Test
    void testLock002_secondSessionCannotLockAnAlreadyLockedFile() throws Exception {
        client.lockFile(aliceToken, fileId);

        VaultServiceException ex = assertThrows(VaultServiceException.class, () -> client.lockFile(bobToken, fileId));
        assertEquals(ErrorCode.FILE_LOCKED, ex.getErrorCode());
    }

    @Test
    void testLock003_ownerCanUnlockItsOwnLock() throws Exception {
        client.lockFile(aliceToken, fileId);

        assertDoesNotThrow(() -> client.unlockFile(aliceToken, fileId));
        assertDoesNotThrow(() -> client.lockFile(bobToken, fileId), "file must be lockable again after the owner releases it");
    }

    @Test
    void testLock004_nonOwnerCannotUnlockSomeoneElsesLock() throws Exception {
        client.lockFile(aliceToken, fileId);

        VaultServiceException ex = assertThrows(VaultServiceException.class, () -> client.unlockFile(bobToken, fileId));
        assertEquals(ErrorCode.LOCK_NOT_OWNED, ex.getErrorCode());
    }

    @Test
    void unlockingAnUnlockedFileIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class, () -> client.unlockFile(aliceToken, fileId));
        assertEquals(ErrorCode.LOCK_NOT_OWNED, ex.getErrorCode());
    }

    @Test
    void lockingAnUnknownFileIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.lockFile(aliceToken, "no-such-file-id"));
        assertEquals(ErrorCode.FILE_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void lockingWithInvalidSessionIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.lockFile("not-a-real-token", fileId));
        assertEquals(ErrorCode.INVALID_SESSION, ex.getErrorCode());
    }

    @Test
    void listFilesReflectsRealLockStateAndOwnerHint() throws Exception {
        client.lockFile(aliceToken, fileId);

        var asSeenByAlice = client.listFiles(aliceToken).stream()
                .filter(f -> f.fileId().equals(fileId)).findFirst().orElseThrow();
        var asSeenByBob = client.listFiles(bobToken).stream()
                .filter(f -> f.fileId().equals(fileId)).findFirst().orElseThrow();

        assertEquals("LOCKED", asSeenByAlice.lockState());
        assertEquals("you", asSeenByAlice.lockOwnerHint(), "the lock owner should see 'you', not another session's identity");
        assertEquals("LOCKED", asSeenByBob.lockState());
        assertEquals("another user", asSeenByBob.lockOwnerHint(), "a non-owner must never see who actually holds the lock");
    }

    @Test
    void oq009_downloadByANonOwnerStillSucceedsWhileFileIsLocked() throws Exception {
        // Resolution of Open Question OQ-09 (Context.md §7, Security.md §4):
        // a lock protects writes, not reads — locking does not block download.
        client.lockFile(aliceToken, fileId);

        assertDoesNotThrow(() -> client.downloadFile(bobToken, fileId),
                "a lock must not block a non-owner's read-only download");
    }

    @Test
    void logoutReleasesLocksHeldByThatSession() throws Exception {
        client.lockFile(aliceToken, fileId);

        client.logout(aliceToken);
        aliceToken = null; // already logged out — afterEach must not try again

        assertDoesNotThrow(() -> client.lockFile(bobToken, fileId),
                "logout must release locks held by that session (API-spec.md logout side effect)");
    }
}
