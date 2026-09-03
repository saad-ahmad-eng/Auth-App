package com.authlock.server;

import com.authlock.common.RmiConfig;
import com.authlock.common.tls.DevTlsSetup;

import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;

/**
 * RMI server bootstrap. Starts (or attaches to) the RMI registry, exports and
 * binds the {@link VaultServiceImpl}, then returns — the RMI runtime's own
 * non-daemon threads keep the JVM alive to serve requests.
 *
 * <p>Implementation Phase 2 scope: registry + export + bind. Defaults
 * {@code java.rmi.server.hostname} to {@code localhost} unless already set
 * by the launcher — cloud deployment (Implementation.md Phase 11,
 * Architecture.md §3) overrides this with the VM's public IP via
 * {@code -Djava.rmi.server.hostname=<publicIP>}, no code change needed.
 *
 * <p><b>Phase 6 addition:</b> RMI-over-TLS is on by default
 * ({@code -Dauthlock.tls.enabled=false} to disable — e.g. for local
 * troubleshooting), protecting the whole channel including {@code login()}
 * credentials and session tokens (Security.md §7's transport-layer
 * complementary control to the AES-GCM file-payload encryption).
 * <b>The {@code java.rmi.server.hostname} default above is not just
 * cosmetic under TLS</b> — without it, RMI embeds the machine's actual
 * detected LAN IP in exported stubs, and the dev certificate's Subject
 * Alternative Names (only {@code localhost}/{@code 127.0.0.1}) then fails
 * TLS hostname verification against that IP. Phase 11 cloud deployment will
 * need to regenerate the dev certificate with the VM's public IP/hostname
 * in its SAN (or use a properly issued certificate) — tracked as a Phase 11
 * task, not solved here.
 */
public final class ServerMain {

    private ServerMain() {
    }

    public static void main(String[] args) {
        try {
            if (System.getProperty("java.rmi.server.hostname") == null) {
                System.setProperty("java.rmi.server.hostname", "localhost");
            }

            boolean tlsEnabled = Boolean.parseBoolean(System.getProperty("authlock.tls.enabled", "true"));
            if (tlsEnabled) {
                DevTlsSetup.configure();
            }

            Registry registry = getOrCreateRegistry(RmiConfig.REGISTRY_PORT, tlsEnabled);

            VaultServiceImpl service = new VaultServiceImpl(RmiConfig.SERVICE_PORT, tlsEnabled);
            registry.rebind(RmiConfig.SERVICE_NAME, service);

            System.out.println("AuthLock server ready.");
            System.out.println("  Registry port : " + RmiConfig.REGISTRY_PORT);
            System.out.println("  Service port  : " + RmiConfig.SERVICE_PORT);
            System.out.println("  Bound as      : " + RmiConfig.SERVICE_NAME);
            System.out.println("  Transport     : " + (tlsEnabled
                    ? "RMI-over-TLS (dev self-signed cert — see DevTlsSetup)"
                    : "plaintext RMI (authlock.tls.enabled=false)"));
            System.out.println("Implemented so far: ping() (Phase 2), login()/logout() (Phase 3),");
            System.out.println("  listFiles()/uploadFile()/downloadFile() (Phase 4),");
            System.out.println("  lockFile()/unlockFile() (Phase 5),");
            System.out.println("  AES-256-GCM upload/download encryption + RMI-over-TLS (Phase 6),");
            System.out.println("  audit logging to audit.log (Phase 7).");
            System.out.println("See Implementation.md Phase 8+ to continue.");
        } catch (Exception e) {
            System.err.println("Failed to start AuthLock server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Creates a registry on the given port (matching the client/server
     * socket factories to the TLS setting), or attaches to one already
     * running in this JVM/host on that port. Keeps repeated local runs
     * (and the Phase 2 integration test) from failing with
     * "port already in use" when a registry is already up.
     */
    private static Registry getOrCreateRegistry(int port, boolean tlsEnabled) throws Exception {
        try {
            if (tlsEnabled) {
                return LocateRegistry.createRegistry(port, new SslRMIClientSocketFactory(), new SslRMIServerSocketFactory());
            }
            return LocateRegistry.createRegistry(port);
        } catch (ExportException alreadyBound) {
            if (tlsEnabled) {
                return LocateRegistry.getRegistry(null, port, new SslRMIClientSocketFactory());
            }
            return LocateRegistry.getRegistry(port);
        }
    }
}
