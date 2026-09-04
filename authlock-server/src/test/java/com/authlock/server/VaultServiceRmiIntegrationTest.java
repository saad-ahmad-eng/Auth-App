package com.authlock.server;

import com.authlock.common.VaultService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST-INT-001/TEST-INT-002 (Testing.md §4): basic RMI round trip, and RMI
 * communication failure handling — a client looks up and invokes
 * {@link VaultService} over a real RMI registry/export path.
 *
 * <p>Uses an ephemeral registry port and an ephemeral (port 0) object export
 * so this test never collides with a real AuthLock server instance that
 * might be running on the production ports (RmiConfig.REGISTRY_PORT /
 * RmiConfig.SERVICE_PORT) on the same machine.
 */
class VaultServiceRmiIntegrationTest {

    private static final int TEST_REGISTRY_PORT = 21099;

    private Registry registry;
    private VaultServiceImpl service;

    @AfterEach
    void tearDown() throws Exception {
        if (registry != null) {
            try {
                registry.unbind("VaultService");
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            // LocateRegistry.createRegistry(port) exports the registry itself as a
            // long-lived remote object — unbind() only clears its naming-table
            // entry, it does NOT free the port. Without this, a second @Test
            // method in this class reusing TEST_REGISTRY_PORT fails with
            // ExportException ("port already in use"). Same pitfall documented
            // in SessionManagerTest/VaultServiceAuthIntegrationTest's history.
            try {
                UnicastRemoteObject.unexportObject(registry, true);
            } catch (NoSuchObjectException alreadyUnexported) {
                // fine — already gone
            }
        }
        if (service != null) {
            try {
                UnicastRemoteObject.unexportObject(service, true);
            } catch (NoSuchObjectException alreadyUnexported) {
                // clientCallOnAnAlreadyObtainedStubFailsClearlyAfterServerStops already did this.
            }
        }
    }

    @Test
    void clientReceivesPingResponseOverRealRmiRoundTrip() throws Exception {
        // Server side: start registry, export and bind the service.
        registry = LocateRegistry.createRegistry(TEST_REGISTRY_PORT);
        service = new VaultServiceImpl(0); // ephemeral object port
        registry.rebind("VaultService", service);

        // Client side: independently look up the stub and invoke it.
        Registry clientRegistryView = LocateRegistry.getRegistry("localhost", TEST_REGISTRY_PORT);
        VaultService stub = (VaultService) clientRegistryView.lookup("VaultService");

        String response = stub.ping();

        assertNotNull(response, "ping() must return a non-null response");
        assertTrue(response.contains("AuthLock VaultService is alive"),
                "unexpected ping() response: " + response);
    }

    /**
     * TEST-INT-002 (Testing.md §4, flow.md §17 "Server Unavailable"): a
     * client that already holds a live stub (obtained while the server was
     * up) must get a clear {@link RemoteException} — not a hang, not a
     * silent failure, not a crash — when the server goes away mid-session.
     * Simulated realistically by force-unexporting the live
     * {@link VaultServiceImpl} out from under an already-obtained stub,
     * rather than merely failing an initial {@code lookup()} (that's the
     * client-side {@code RmiConnection}/{@code ServerUnavailableException}
     * path — exercised by {@code LoginFrame.connect()} /
     * {@code DashboardFrame.handleFailure()}, both code-reviewed and, for
     * the connect-time case, interactively verified in Phase 9).
     */
    @Test
    void clientCallOnAnAlreadyObtainedStubFailsClearlyAfterServerStops() throws Exception {
        registry = LocateRegistry.createRegistry(TEST_REGISTRY_PORT);
        service = new VaultServiceImpl(0);
        registry.rebind("VaultService", service);

        Registry clientRegistryView = LocateRegistry.getRegistry("localhost", TEST_REGISTRY_PORT);
        VaultService stub = (VaultService) clientRegistryView.lookup("VaultService");

        // Confirm the stub is genuinely live before pulling the rug out.
        assertNotNull(stub.ping());

        // Simulate "server stopped" — force the remote object off the wire,
        // exactly as if the server process had died mid-session.
        UnicastRemoteObject.unexportObject(service, true);
        service = null; // tearDown must not try to unexport this twice.

        assertThrows(RemoteException.class, stub::ping,
                "a call on a stub whose server has stopped must surface a clear RemoteException, "
                        + "not hang or fail silently (FR-013)");
    }
}
