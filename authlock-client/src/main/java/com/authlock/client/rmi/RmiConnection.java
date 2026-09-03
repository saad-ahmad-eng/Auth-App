package com.authlock.client.rmi;

import com.authlock.common.RmiConfig;
import com.authlock.common.VaultService;

import javax.rmi.ssl.SslRMIClientSocketFactory;
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
 * <p>Implementation Phase 2 scope: registry lookup. Session-token handling
 * is Phase 3's concern. <b>Phase 6 addition:</b> the lookup can use
 * {@link SslRMIClientSocketFactory} to match a TLS-enabled server —
 * {@link com.authlock.common.tls.DevTlsSetup#configure()} must have already
 * been called by the caller so the JSSE truststore property is set.
 */
public final class RmiConnection {

    private RmiConnection() {
    }

    /** Connects (TLS-enabled by default — matches {@code ServerMain}'s default) to the default registry port. */
    public static VaultService connect(String host) {
        return connect(host, RmiConfig.REGISTRY_PORT,
                Boolean.parseBoolean(System.getProperty("authlock.tls.enabled", "true")));
    }

    public static VaultService connect(String host, int registryPort, boolean tlsEnabled) {
        try {
            Registry registry = tlsEnabled
                    ? LocateRegistry.getRegistry(host, registryPort, new SslRMIClientSocketFactory())
                    : LocateRegistry.getRegistry(host, registryPort);
            return (VaultService) registry.lookup(RmiConfig.SERVICE_NAME);
        } catch (RemoteException | NotBoundException e) {
            throw new ServerUnavailableException(
                    "Could not reach AuthLock server at " + host + ":" + registryPort, e);
        }
    }
}
