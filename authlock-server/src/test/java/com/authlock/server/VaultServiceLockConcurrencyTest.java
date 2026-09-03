package com.authlock.server;

import com.authlock.common.ErrorCode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>TEST-CONC-001 (Testing.md §3) — the project's core distributed-systems
 * demonstration.</b> {@code auth} §3/§9 and Testing.md §3 require this to be
 * proven, not just implemented: N clients race to lock the same file at
 * (near) the same instant, and <b>exactly one must win, every single time</b>.
 *
 * <p>Real RMI, real concurrent threads (not sequential calls — that's what
 * distinguishes this from {@link VaultServiceLockIntegrationTest}'s
 * TEST-LOCK-002), a {@link CyclicBarrier} to align the start of every
 * attempt as tightly as the JVM allows, and {@value #ROUNDS} repeated
 * rounds (Testing.md §3: "at least 20–50 times... to rule out a flaky/rare
 * race window that a single run might miss").
 *
 * <p>The N "clients" are N independently logged-in sessions (per OQ-12,
 * locks are owned per-session, so this is a legitimate way to get N
 * independent race participants without needing N separate seed accounts —
 * see {@code seed-users.properties}, which only defines two).
 */
class VaultServiceLockConcurrencyTest {

    private static final int TEST_REGISTRY_PORT = 21499;
    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";
    private static final int CONCURRENT_CLIENTS = 5;
    private static final int ROUNDS = 30;

    private static Registry registry;
    private static VaultServiceImpl serviceImpl;
    private static VaultService client;
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
    void testConc001_exactlyOneOfNConcurrentLockRequestsWinsEveryRound() throws Exception {
        // Alternate between the two seeded accounts for the session pool —
        // proves the race is resolved per-session, not accidentally
        // serialized by some per-user artifact.
        List<String> sessionTokens = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
            sessionTokens.add(i % 2 == 0 ? client.login("alice", "AliceP@ss1") : client.login("bob", "BobP@ss1"));
        }
        String fileId = client.uploadFile(sessionTokens.get(0), "race-target.txt", "contested".getBytes(), new byte[0]);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CLIENTS);
        try {
            for (int round = 1; round <= ROUNDS; round++) {
                int winners = runOneRaceRound(pool, sessionTokens, fileId, round);
                assertEquals(1, winners, "round " + round + " of " + ROUNDS
                        + ": exactly one of " + CONCURRENT_CLIENTS + " concurrent lock attempts must succeed");
            }
        } finally {
            pool.shutdownNow();
            for (String token : sessionTokens) {
                try {
                    client.logout(token);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * Runs one race round: all {@code CONCURRENT_CLIENTS} sessions attempt
     * {@code lockFile} simultaneously (synchronized by a {@link CyclicBarrier}),
     * returns how many succeeded, and leaves the file unlocked again
     * (releasing via whichever session won) so the next round starts clean —
     * proving the invariant holds repeatably, not just once.
     */
    private int runOneRaceRound(ExecutorService pool, List<String> sessionTokens, String fileId, int round)
            throws Exception {
        CyclicBarrier startingLine = new CyclicBarrier(sessionTokens.size());
        List<Callable<Boolean>> attempts = new ArrayList<>();

        for (String token : sessionTokens) {
            attempts.add(() -> {
                startingLine.await(10, TimeUnit.SECONDS); // align every thread's attempt as tightly as possible
                try {
                    client.lockFile(token, fileId);
                    return true;
                } catch (VaultServiceException e) {
                    if (e.getErrorCode() == ErrorCode.FILE_LOCKED) {
                        return false;
                    }
                    throw e; // any other error is a real bug — let it fail the test loudly
                }
            });
        }

        List<Future<Boolean>> results = pool.invokeAll(attempts, 30, TimeUnit.SECONDS);

        int winnerIndex = -1;
        int winnerCount = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).get()) {
                winnerCount++;
                winnerIndex = i;
            }
        }

        if (winnerCount == 1) {
            // Release for the next round via the actual winning session.
            String winnerToken = sessionTokens.get(winnerIndex);
            assertDoesNotThrow(() -> client.unlockFile(winnerToken, fileId),
                    "round " + round + ": the round's winner must still be able to unlock its own lock");
        } else if (winnerCount > 1) {
            fail("round " + round + ": " + winnerCount + " sessions were simultaneously granted the lock — race condition!");
        }
        // winnerCount == 0 is reported by the caller's assertEquals(1, winners, ...) with full round context.

        return winnerCount;
    }

    /**
     * TEST-CONC-002 (Testing.md §3): concurrent uploads of <i>different</i>
     * files must never contend with each other — each gets its own
     * server-generated {@code fileId} (Backend.md §3), so there is no shared
     * state for N simultaneous uploads to race over.
     */
    @Test
    void testConc002_concurrentUploadsOfDifferentFilesDoNotContend() throws Exception {
        String token = client.login("alice", "AliceP@ss1");
        try {
            int uploaders = 8;
            ExecutorService pool = Executors.newFixedThreadPool(uploaders);
            try {
                CyclicBarrier startingLine = new CyclicBarrier(uploaders);
                List<Callable<String>> uploads = new ArrayList<>();
                for (int i = 0; i < uploaders; i++) {
                    int index = i;
                    uploads.add(() -> {
                        startingLine.await(10, TimeUnit.SECONDS);
                        return client.uploadFile(token, "conc2-file-" + index + ".txt",
                                ("content " + index).getBytes(), new byte[0]);
                    });
                }

                List<Future<String>> results = pool.invokeAll(uploads, 30, TimeUnit.SECONDS);
                Set<String> fileIds = new java.util.HashSet<>();
                for (Future<String> result : results) {
                    fileIds.add(result.get()); // .get() rethrows if any uploader failed
                }

                assertEquals(uploaders, fileIds.size(), "every concurrent uploader must succeed with a distinct fileId");
            } finally {
                pool.shutdownNow();
            }
        } finally {
            client.logout(token);
        }
    }

    /**
     * TEST-CONC-003 (Testing.md §3): {@code listFiles()} must remain safe
     * and never crash/return corrupt data while a lock/unlock cycle is
     * happening concurrently in the background — read access to shared
     * state must not be serialized against unrelated write activity.
     */
    @Test
    void testConc003_listFilesStaysConsistentWhileLockUnlockCycleRuns() throws Exception {
        String lockerToken = client.login("alice", "AliceP@ss1");
        String readerToken = client.login("bob", "BobP@ss1");
        try {
            String fileId = client.uploadFile(lockerToken, "conc3-target.txt", "x".getBytes(), new byte[0]);

            AtomicBoolean keepCycling = new AtomicBoolean(true);
            Set<Exception> lockCycleErrors = new CopyOnWriteArraySet<>();
            Thread lockCycleThread = new Thread(() -> {
                while (keepCycling.get()) {
                    try {
                        client.lockFile(lockerToken, fileId);
                        client.unlockFile(lockerToken, fileId);
                    } catch (Exception e) {
                        lockCycleErrors.add(e);
                    }
                }
            }, "lock-unlock-cycle");
            lockCycleThread.start();

            try {
                for (int i = 0; i < 200; i++) {
                    List<FileMetadata> files = client.listFiles(readerToken);
                    assertTrue(files.stream().anyMatch(f -> f.fileId().equals(fileId)),
                            "listFiles() must consistently include the file even while it's being locked/unlocked concurrently");
                }
            } finally {
                keepCycling.set(false);
                lockCycleThread.join(5000);
            }

            assertTrue(lockCycleErrors.isEmpty(), "background lock/unlock cycle hit unexpected errors: " + lockCycleErrors);
        } finally {
            client.logout(lockerToken);
            client.logout(readerToken);
        }
    }
}
