package com.authlock.client;

import com.authlock.client.rmi.RmiConnection;
import com.authlock.client.rmi.ServerUnavailableException;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;

import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.List;

/**
 * Console entry point for the AuthLock client.
 *
 * <p>Implementation Phase 2 scope: connects and calls
 * {@link VaultService#ping()} (TEST-INT-001). <b>Phase 3 scope (this
 * addition):</b> also demonstrates {@code login()}/{@code logout()} against
 * one of the seeded demo accounts (Context.md OQ-01), console-only, to
 * manually verify the RMI round trip for real credentials/error handling
 * before Phase 8 replaces this whole class with the Swing login screen and
 * dashboard (UIUX.md §1–§2) — at that point {@code login}/{@code logout}
 * calls move into UI event handlers instead of running unconditionally here.
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

            String pingResponse = service.ping();
            System.out.println("Server responded: " + pingResponse);

            demoLoginLogout(service);
        } catch (ServerUnavailableException e) {
            System.err.println("Server unavailable: " + e.getMessage());
            System.exit(1);
        } catch (RemoteException e) {
            System.err.println("Remote call failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Phase 3/4 demo only: logs in as the seeded "alice" account, uploads a
     * small file, lists the vault, downloads the file back and verifies it
     * round-tripped byte-identical, then logs out. Not part of the eventual
     * Swing UI flow (Phase 8 replaces this with real event handlers).
     */
    private static void demoLoginLogout(VaultService service) throws RemoteException {
        try {
            String token = service.login("alice", "AliceP@ss1");
            System.out.println("Login succeeded. Session token: " + token);

            demoFileRoundTrip(service, token);

            service.logout(token);
            System.out.println("Logout succeeded.");
        } catch (VaultServiceException e) {
            System.out.println("Demo failed: " + e.getErrorCode() + " - " + e.getMessage());
        }
    }

    private static void demoFileRoundTrip(VaultService service, String token)
            throws RemoteException, VaultServiceException {
        byte[] content = "Hello from the AuthLock Phase 4 demo!".getBytes(StandardCharsets.UTF_8);

        String fileId = service.uploadFile(token, "demo.txt", content, new byte[0]);
        System.out.println("Uploaded demo.txt as fileId=" + fileId);

        List<FileMetadata> files = service.listFiles(token);
        System.out.println("Vault now contains " + files.size() + " file(s).");

        FileContent downloaded = service.downloadFile(token, fileId);
        boolean matches = Arrays.equals(content, downloaded.fileBytes());
        System.out.println("Downloaded content matches upload: " + matches
                + " (checksum=" + downloaded.checksum() + ")");
    }

    private static String resolveHost(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        return System.getProperty("authlock.server.host", "localhost");
    }
}
