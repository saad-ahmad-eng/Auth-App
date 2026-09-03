package com.authlock.client;

import com.authlock.client.rmi.RmiConnection;
import com.authlock.client.rmi.ServerUnavailableException;
import com.authlock.common.VaultService;

import java.rmi.RemoteException;

/**
 * Console entry point for the AuthLock client.
 *
 * <p>Implementation Phase 2 (RMI Infrastructure) scope only: connects to the
 * server, calls {@link VaultService#ping()}, and prints the result — proving
 * the RMI channel works end-to-end (TEST-INT-001). The Swing UI (login
 * screen, dashboard) is Phase 8's responsibility (UIUX.md); this class will
 * become that UI's bootstrap rather than a console program at that point.
 *
 * <p>Host defaults to {@code localhost}; override with
 * {@code -Dauthlock.server.host=<host>} (e.g. a cloud VM's public IP in
 * Phase 11, per Architecture.md §3) or a single command-line argument.
 */
public final class ClientMain {

    private ClientMain() {
    }

    public static void main(String[] args) {
        String host = resolveHost(args);
        System.out.println("Connecting to AuthLock server at " + host + " ...");

        try {
            VaultService service = RmiConnection.connect(host);
            String response = service.ping();
            System.out.println("Server responded: " + response);
        } catch (ServerUnavailableException e) {
            System.err.println("Server unavailable: " + e.getMessage());
            System.exit(1);
        } catch (RemoteException e) {
            System.err.println("Remote call failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String resolveHost(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        return System.getProperty("authlock.server.host", "localhost");
    }
}
