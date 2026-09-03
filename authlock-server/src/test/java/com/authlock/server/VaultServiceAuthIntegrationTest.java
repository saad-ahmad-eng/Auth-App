package com.authlock.server;

import com.authlock.common.ErrorCode;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 RMI-level integration tests for {@code login}/{@code logout},
 * covering Testing.md §2.1 TEST-AUTH-001..004 and §2.2 TEST-SESSION-003/004
 * over a real RMI round trip (also exercising {@link VaultServiceException}
 * serialization across the wire). Uses a dedicated test registry port and
 * an ephemeral object port, distinct from both the production ports
 * (RmiConfig) and the Phase 2 test's port, so test runs never collide with
 * a real running server.
 *
 * <p>Server/registry setup is {@code @BeforeAll} (class-scoped, not
 * per-method): {@code LocateRegistry.createRegistry(port)} exports the
 * registry itself as a long-lived remote object on that port —
 * {@code unbind()} only clears a naming-table entry, it does not release
 * the port — so repeating {@code createRegistry} on the same port in a
 * per-method {@code @BeforeEach} throws {@link java.rmi.server.ExportException}
 * from the second test method onward. None of these tests depend on a
 * fresh server per method (each login call produces its own unique token),
 * so one shared instance for the whole class is both correct and simpler.
 */
class VaultServiceAuthIntegrationTest {

    private static final int TEST_REGISTRY_PORT = 21199;

    private static Registry registry;
    private static VaultServiceImpl serviceImpl;
    private static VaultService client;

    @BeforeAll
    static void setUp() throws Exception {
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
    }

    @Test
    void testAuth001_validLoginReturnsSessionToken() throws Exception {
        String token = client.login("alice", "AliceP@ss1");

        assertNotNull(token);
        assertTrue(token.length() > 0);
    }

    @Test
    void testAuth002_invalidUsernameIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.login("no-such-user", "whatever"));

        assertEquals(ErrorCode.AUTHENTICATION_FAILED, ex.getErrorCode());
    }

    @Test
    void testAuth003_invalidPasswordIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.login("alice", "wrong-password"));

        assertEquals(ErrorCode.AUTHENTICATION_FAILED, ex.getErrorCode());
    }

    @Test
    void testAuth002and003_sameErrorForBothCases_preventsUsernameEnumeration() {
        VaultServiceException unknownUser = assertThrows(VaultServiceException.class,
                () -> client.login("no-such-user", "whatever"));
        VaultServiceException wrongPassword = assertThrows(VaultServiceException.class,
                () -> client.login("alice", "wrong-password"));

        assertEquals(unknownUser.getErrorCode(), wrongPassword.getErrorCode(),
                "SEC-001: unknown username and wrong password must be indistinguishable to the client");
    }

    @Test
    void testAuth004_emptyCredentialsAreRejected() {
        assertThrows(VaultServiceException.class, () -> client.login("", ""));
    }

    @Test
    void testAuth005_repeatedFailuresDoNotCrashOrLeak() {
        // auth does not require throttling/lockout (Testing.md §2.1 TEST-AUTH-005
        // note) — this only asserts the server keeps rejecting cleanly, with no
        // crash or resource exhaustion, across repeated failed attempts.
        for (int i = 0; i < 5; i++) {
            VaultServiceException ex = assertThrows(VaultServiceException.class,
                    () -> client.login("alice", "wrong-password-" + Math.random()));
            assertEquals(ErrorCode.AUTHENTICATION_FAILED, ex.getErrorCode());
        }

        // Server is still healthy and correct credentials still work afterward.
        assertDoesNotThrow(() -> client.login("alice", "AliceP@ss1"));
    }

    @Test
    void logoutOfValidSessionSucceeds() throws Exception {
        String token = client.login("bob", "BobP@ss1");

        assertDoesNotThrow(() -> client.logout(token));
    }

    @Test
    void testSession003_logoutOfUnknownTokenIsRejected() {
        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.logout("not-a-real-token"));

        assertEquals(ErrorCode.INVALID_SESSION, ex.getErrorCode());
    }

    @Test
    void testSession004_reusingTokenAfterLogoutIsRejected() throws Exception {
        String token = client.login("alice", "AliceP@ss1");
        client.logout(token);

        VaultServiceException ex = assertThrows(VaultServiceException.class,
                () -> client.logout(token));

        assertEquals(ErrorCode.INVALID_SESSION, ex.getErrorCode());
    }
}
