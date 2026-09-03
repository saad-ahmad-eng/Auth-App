package com.authlock.server;

import com.authlock.common.RmiConfig;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;

/**
 * RMI server bootstrap. Starts (or attaches to) the RMI registry, exports and
 * binds the {@link VaultServiceImpl}, then returns — the RMI runtime's own
 * non-daemon threads keep the JVM alive to serve requests.
 *
 * <p>Implementation Phase 2 (RMI Infrastructure) scope: registry + export +
 * bind only. No {@code java.rmi.server.hostname} handling is needed for
 * {@code localhost} development; setting it for cloud deployment is
 * Implementation.md Phase 11's responsibility (Architecture.md §3) and
 * requires no code change here — it is a JVM system property set at launch.
 */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) {
        try {
            Registry registry = getOrCreateRegistry(RmiConfig.REGISTRY_PORT);

            VaultServiceImpl service = new VaultServiceImpl();
            registry.rebind(RmiConfig.SERVICE_NAME, service);

            System.out.println("AuthLock server ready.");
            System.out.println("  Registry port : " + RmiConfig.REGISTRY_PORT);
            System.out.println("  Service port  : " + RmiConfig.SERVICE_PORT);
            System.out.println("  Bound as      : " + RmiConfig.SERVICE_NAME);
            System.out.println("Phase 2 (RMI Infrastructure) only — ping() is the sole method implemented so far.");
            System.out.println("See Implementation.md Phase 3+ to continue.");
        } catch (Exception e) {
            System.err.println("Failed to start AuthLock server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Creates a registry on the given port, or attaches to one already
     * running in this JVM/host on that port. Keeps repeated local runs
     * (and the Phase 2 integration test) from failing with
     * "port already in use" when a registry is already up.
     */
    private static Registry getOrCreateRegistry(int port) throws Exception {
        try {
            return LocateRegistry.createRegistry(port);
        } catch (ExportException alreadyBound) {
            return LocateRegistry.getRegistry(port);
        }
    }
}
