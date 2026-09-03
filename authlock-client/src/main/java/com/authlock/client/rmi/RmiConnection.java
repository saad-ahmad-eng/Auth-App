package com.authlock.client.rmi;

import com.authlock.common.RmiConfig;
import com.authlock.common.VaultService;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

/**
 * Client-side RMI plumbing: looks up the {@link VaultService} stub from a
 * server's registry and translates lookup/transport failures into a single
 * {@link ServerUnavailableException} (FR-013) rather than letting a raw
 * {@link RemoteException} or {@link NotBoundException} reach UI code.
 *
 * <p>Implementation Phase 2 (RMI Infrastructure) scope: registry lookup
 * only. Session-token handling (attaching it to every call after
 * {@code login()}) is added in Phase 3.
 */
public final class RmiConnection {

    private RmiConnection() {
    }

    /** Connects to a VaultService on the default registry port on the given host. */
    public static VaultService connect(String host) {
        return connect(host, RmiConfig.REGISTRY_PORT);
    }

    public static VaultService connect(String host, int registryPort) {
        try {
            Registry registry = LocateRegistry.getRegistry(host, registryPort);
            return (VaultService) registry.lookup(RmiConfig.SERVICE_NAME);
        } catch (RemoteException | NotBoundException e) {
            throw new ServerUnavailableException(
                    "Could not reach AuthLock server at " + host + ":" + registryPort, e);
        }
    }
}
