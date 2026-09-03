package com.authlock.client;

import com.authlock.client.rmi.RmiConnection;
import com.authlock.client.rmi.ServerUnavailableException;
import com.authlock.common.ErrorCode;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.common.crypto.AesGcmCipher;
import com.authlock.common.crypto.SharedKeyProvider;
import com.authlock.common.tls.DevTlsSetup;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.List;

/**
 * Console entry point for the AuthLock client.
 *
 * <p>Implementation Phase 2 scope: connects and calls
 * {@link VaultService#ping()} (TEST-INT-001). Phase 3: {@code login()}/
 * {@code logout()}. Phase 4: {@code uploadFile}/{@code listFiles}/
 * {@code downloadFile}. Phase 5: {@code lockFile}/{@code unlockFile}
 * contention between two seeded accounts. <b>Phase 6 (this addition):</b>
 * every upload is now genuinely AES-256-GCM encrypted before it leaves this
 * process, and every download genuinely decrypted after it arrives — see
 * {@link AesGcmCipher} and Security.md §7. The RMI connection itself is
 * TLS-protected by default too (matching {@code ServerMain}, disable with
 * {@code -Dauthlock.tls.enabled=false}), covering {@code login()}
 * credentials and session tokens. Console-only, to manually verify each RMI
 * round trip before Phase 8 replaces this whole class with the Swing login
 * screen and dashboard (UIUX.md §1–§2).
 *
 * <p>Host defaults to {@code localhost}; override with
 * {@code -Dauthlock.server.host=<host>} (e.g. a cloud VM's public IP in
 * Phase 11, per Architecture.md §3) or a single command-line argument.
 * The shared encryption key defaults to {@code authlock-shared.key} in the
 * working directory, overridable via {@code -Dauthlock.crypto.keyfile=<path>}
 * — it must be the same file the server generated (see
 * {@code SharedKeyProvider}'s Javadoc for why this is a coursework-scope
 * simplification, not a production key-distribution scheme).
 */
public final class ClientMain {

    private static final String DEFAULT_KEY_FILE = "authlock-shared.key";
    private static final AesGcmCipher CIPHER = new AesGcmCipher();

    private ClientMain() {
    }

    public static void main(String[] args) {
        String host = resolveHost(args);
        boolean tlsEnabled = Boolean.parseBoolean(System.getProperty("authlock.tls.enabled", "true"));

        if (tlsEnabled) {
            try {
                DevTlsSetup.configure();
            } catch (Exception e) {
                System.err.println("Failed to configure TLS: " + e.getMessage());
                System.exit(1);
                return;
            }
        }

        System.out.println("Connecting to AuthLock server at " + host
                + " (" + (tlsEnabled ? "TLS" : "plaintext") + ") ...");

        SecretKey sharedKey;
        try {
            Path keyFile = Path.of(System.getProperty("authlock.crypto.keyfile", DEFAULT_KEY_FILE));
            sharedKey = SharedKeyProvider.load(keyFile);
        } catch (NoSuchFileException e) {
            System.err.println("Shared encryption key not found: " + e.getMessage());
            System.err.println("Start the server first (it generates the key), or copy its key file here.");
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("Failed to load shared encryption key: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            VaultService service = RmiConnection.connect(host, com.authlock.common.RmiConfig.REGISTRY_PORT, tlsEnabled);

            String pingResponse = service.ping();
            System.out.println("Server responded: " + pingResponse);

            runDemo(service, sharedKey);
        } catch (ServerUnavailableException e) {
            System.err.println("Server unavailable: " + e.getMessage());
            System.exit(1);
        } catch (RemoteException e) {
            System.err.println("Remote call failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Phase 3–6 demo only: logs in as both seeded accounts, uploads and
     * round-trips an encrypted file as alice, then demonstrates lock
     * contention between alice and bob. Not part of the eventual Swing UI flow.
     */
    private static void runDemo(VaultService service, SecretKey sharedKey) throws RemoteException {
        String aliceToken = null;
        String bobToken = null;
        try {
            aliceToken = service.login("alice", "AliceP@ss1");
            System.out.println("alice logged in. Session token: " + aliceToken);
            bobToken = service.login("bob", "BobP@ss1");
            System.out.println("bob logged in. Session token: " + bobToken);

            String fileId = demoFileRoundTrip(service, sharedKey, aliceToken);
            demoLocking(service, aliceToken, bobToken, fileId);
        } catch (VaultServiceException e) {
            System.out.println("Demo failed: " + e.getErrorCode() + " - " + e.getMessage());
        } finally {
            logoutQuietly(service, "alice", aliceToken);
            logoutQuietly(service, "bob", bobToken);
        }
    }

    private static String demoFileRoundTrip(VaultService service, SecretKey sharedKey, String token)
            throws RemoteException, VaultServiceException {
        byte[] plaintext = "Hello from the AuthLock Phase 6 demo!".getBytes(StandardCharsets.UTF_8);

        AesGcmCipher.Encrypted encrypted = CIPHER.encrypt(sharedKey, plaintext);
        String fileId = service.uploadFile(token, "demo.txt", encrypted.ciphertext(), encrypted.iv());
        System.out.println("Uploaded demo.txt as fileId=" + fileId
                + " (encrypted: " + encrypted.ciphertext().length + " ciphertext bytes on the wire)");

        List<FileMetadata> files = service.listFiles(token);
        System.out.println("Vault now contains " + files.size() + " file(s).");

        FileContent downloaded = service.downloadFile(token, fileId);
        byte[] decrypted = CIPHER.decrypt(sharedKey, downloaded.iv(), downloaded.fileBytes());
        boolean matches = Arrays.equals(plaintext, decrypted);
        System.out.println("Downloaded, decrypted content matches upload: " + matches
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
