package com.authlock.client;

import com.authlock.client.rmi.RmiConnection;
import com.authlock.client.rmi.ServerUnavailableException;
import com.authlock.common.ErrorCode;
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
 * {@link VaultService#ping()} (TEST-INT-001). Phase 3: {@code login()}/
 * {@code logout()}. Phase 4: {@code uploadFile}/{@code listFiles}/
 * {@code downloadFile}. <b>Phase 5 (this addition):</b> also demonstrates
 * {@code lockFile}/{@code unlockFile} contention between two seeded
 * accounts. Console-only, to manually verify each RMI round trip before
 * Phase 8 replaces this whole class with the Swing login screen and
 * dashboard (UIUX.md §1–§2) — at that point these calls move into UI event
 * handlers instead of running unconditionally here.
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

            runDemo(service);
        } catch (ServerUnavailableException e) {
            System.err.println("Server unavailable: " + e.getMessage());
            System.exit(1);
        } catch (RemoteException e) {
            System.err.println("Remote call failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Phase 3–5 demo only: logs in as both seeded accounts, uploads and
     * round-trips a file as alice, then demonstrates lock contention between
     * alice and bob. Not part of the eventual Swing UI flow.
     */
    private static void runDemo(VaultService service) throws RemoteException {
        String aliceToken = null;
        String bobToken = null;
        try {
            aliceToken = service.login("alice", "AliceP@ss1");
            System.out.println("alice logged in. Session token: " + aliceToken);
            bobToken = service.login("bob", "BobP@ss1");
            System.out.println("bob logged in. Session token: " + bobToken);

            String fileId = demoFileRoundTrip(service, aliceToken);
            demoLocking(service, aliceToken, bobToken, fileId);
        } catch (VaultServiceException e) {
            System.out.println("Demo failed: " + e.getErrorCode() + " - " + e.getMessage());
        } finally {
            logoutQuietly(service, "alice", aliceToken);
            logoutQuietly(service, "bob", bobToken);
        }
    }

    private static String demoFileRoundTrip(VaultService service, String token)
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
        return fileId;
    }

    private static void demoLocking(VaultService service, String aliceToken, String bobToken, String fileId)
            throws RemoteException, VaultServiceException {
        service.lockFile(aliceToken, fileId);
        System.out.println("alice locked " + fileId + ".");

        try {
            service.lockFile(bobToken, fileId);
            System.out.println("UNEXPECTED: bob was also able to lock the file!");
        } catch (VaultServiceException e) {
            System.out.println("bob's lock attempt correctly rejected: " + e.getErrorCode());
        }

        try {
            service.unlockFile(bobToken, fileId);
            System.out.println("UNEXPECTED: bob was able to unlock alice's lock!");
        } catch (VaultServiceException e) {
            if (e.getErrorCode() == ErrorCode.LOCK_NOT_OWNED) {
                System.out.println("bob's unlock attempt correctly rejected: " + e.getErrorCode());
            } else {
                throw e;
            }
        }

        service.unlockFile(aliceToken, fileId);
        System.out.println("alice unlocked " + fileId + ". File is available again.");
    }

    private static void logoutQuietly(VaultService service, String label, String token) {
        if (token == null) {
            return;
        }
        try {
            service.logout(token);
            System.out.println(label + " logged out.");
        } catch (Exception e) {
            System.out.println(label + " logout failed: " + e.getMessage());
        }
    }

    private static String resolveHost(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        return System.getProperty("authlock.server.host", "localhost");
    }
}
