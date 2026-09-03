package com.authlock.server;

import com.authlock.common.VaultService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST-INT-001 (Testing.md §4): basic RMI round trip — a client looks up and
 * invokes {@link VaultService} over a real RMI registry/export path.
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
        }
        if (service != null) {
            UnicastRemoteObject.unexportObject(service, true);
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
}
