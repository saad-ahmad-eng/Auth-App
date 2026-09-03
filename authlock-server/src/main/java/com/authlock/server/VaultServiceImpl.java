package com.authlock.server;

import com.authlock.common.ErrorCode;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.RmiConfig;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.server.auth.AuthenticationService;
import com.authlock.server.auth.PasswordHasher;
import com.authlock.server.auth.User;
import com.authlock.server.auth.UserStore;
import com.authlock.server.lock.LockManager;
import com.authlock.server.session.Session;
import com.authlock.server.session.SessionManager;
import com.authlock.server.vault.FileRecord;
import com.authlock.server.vault.VaultFileService;

import java.io.IOException;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Server-side implementation of {@link VaultService}.
 *
 * <p><b>Phase 2:</b> {@link #ping()}.
 * <b>Phase 3:</b> {@link #login} / {@link #logout}, backed by
 * {@link AuthenticationService} and {@link SessionManager}.
 * <b>Phase 4:</b> {@link #listFiles}, {@link #uploadFile}, {@link #downloadFile},
 * backed by {@link VaultFileService}.
 * <b>Phase 5 (this addition):</b> {@link #lockFile}, {@link #unlockFile},
 * backed by {@link LockManager} (Architecture.md §2.7) — the project's core
 * distributed-locking feature. {@code SessionManager}'s session-ended
 * listener is wired to {@code LockManager.releaseAllOwnedBySession} here,
 * closing the stale-lock-recovery loop (Security.md §8).
 *
 * <p>Encryption and audit behavior remain unimplemented — added in Phases 6–7.
 */
public class VaultServiceImpl extends UnicastRemoteObject implements VaultService {

    private static final String DEFAULT_VAULT_DIR = "vault-storage";

    private final AuthenticationService authenticationService;
    private final SessionManager sessionManager;
    private final VaultFileService vaultFileService;
    private final LockManager lockManager;

    public VaultServiceImpl(int port) throws RemoteException, IOException {
        super(port);
        PasswordHasher passwordHasher = new PasswordHasher();
        UserStore userStore = new UserStore(passwordHasher);
        this.authenticationService = new AuthenticationService(userStore, passwordHasher);
        this.sessionManager = new SessionManager();

        Path vaultDir = Path.of(System.getProperty("authlock.vault.dir", DEFAULT_VAULT_DIR));
        this.vaultFileService = new VaultFileService(vaultDir);

        this.lockManager = new LockManager();
        // Architecture.md §2.7: Lock Manager's documented input includes
        // "session-expiry notifications from Session Manager" — this is that wire.
        this.sessionManager.setSessionEndedListener(lockManager::releaseAllOwnedBySession);
    }

    public VaultServiceImpl() throws RemoteException, IOException {
        this(RmiConfig.SERVICE_PORT);
    }

    @Override
    public String ping() throws RemoteException {
        return "AuthLock VaultService is alive at " + Instant.now();
    }

    @Override
    public String login(String username, String password) throws RemoteException, VaultServiceException {
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        Optional<User> user = authenticationService.authenticate(username, passwordChars);

        if (user.isEmpty()) {
            // Same error for unknown username or wrong password — SEC-001 enumeration prevention.
            throw new VaultServiceException(ErrorCode.AUTHENTICATION_FAILED, "Invalid username or password.");
        }
        // Audit logging (LOGIN SUCCESS/FAILURE, FR-011) is Phase 7's responsibility — not yet wired in.
        return sessionManager.create(user.get().userId());
    }

    @Override
    public void logout(String sessionToken) throws RemoteException, VaultServiceException {
        // invalidate() itself fires the session-ended listener, which releases
        // any locks this session held (API-spec.md logout's documented side effect).
        boolean invalidated = sessionManager.invalidate(sessionToken);
        if (!invalidated) {
            throw new VaultServiceException(ErrorCode.INVALID_SESSION, "Session is not valid.");
        }
        // Audit logging (LOGOUT, FR-011) is Phase 7's responsibility — not yet wired in.
    }

    @Override
    public List<FileMetadata> listFiles(String sessionToken) throws RemoteException, VaultServiceException {
        requireValidSession(sessionToken);
        return vaultFileService.listAll().stream()
                .map(record -> toDto(record, sessionToken))
                .collect(Collectors.toList());
    }

    @Override
    public String uploadFile(String sessionToken, String filename, byte[] fileBytes, byte[] iv)
            throws RemoteException, VaultServiceException {
        String userId = requireValidSession(sessionToken);
        try {
            FileRecord record = vaultFileService.store(filename, fileBytes, iv, userId);
            // Audit logging (UPLOAD, FR-011) is Phase 7's responsibility — not yet wired in.
            return record.fileId();
        } catch (IllegalArgumentException invalidFilename) {
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Invalid filename.");
        } catch (IOException storageError) {
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Could not store file.");
        }
    }

    @Override
    public FileContent downloadFile(String sessionToken, String fileId) throws RemoteException, VaultServiceException {
        requireValidSession(sessionToken);
        try {
            Optional<VaultFileService.StoredFile> found = vaultFileService.retrieve(fileId);
            if (found.isEmpty()) {
                throw new VaultServiceException(ErrorCode.FILE_NOT_FOUND, "No such file.");
            }
            // Audit logging (DOWNLOAD, FR-011) is Phase 7's responsibility — not yet wired in.
            // Locked-file download policy (OQ-09): no check here — a lock protects
            // writes, not reads, so download proceeds regardless of lock state.
            VaultFileService.StoredFile stored = found.get();
            return new FileContent(stored.content(), stored.metadata().iv(), stored.metadata().checksum());
        } catch (IOException readError) {
            throw new VaultServiceException(ErrorCode.DOWNLOAD_FAILED, "Could not read file.");
        }
    }

    @Override
    public void lockFile(String sessionToken, String fileId) throws RemoteException, VaultServiceException {
        requireValidSession(sessionToken);
        requireFileExists(fileId);

        boolean granted = lockManager.acquire(fileId, sessionToken);
        if (!granted) {
            throw new VaultServiceException(ErrorCode.FILE_LOCKED, "File is currently locked by another session.");
        }
        // Audit logging (LOCK, FR-011) is Phase 7's responsibility — not yet wired in.
    }

    @Override
    public void unlockFile(String sessionToken, String fileId) throws RemoteException, VaultServiceException {
        requireValidSession(sessionToken);
        requireFileExists(fileId);

        boolean released = lockManager.release(fileId, sessionToken);
        if (!released) {
            throw new VaultServiceException(ErrorCode.LOCK_NOT_OWNED, "You do not hold the lock on this file.");
        }
        // Audit logging (UNLOCK, FR-011) is Phase 7's responsibility — not yet wired in.
    }

    private void requireFileExists(String fileId) throws VaultServiceException {
        if (!vaultFileService.exists(fileId)) {
            throw new VaultServiceException(ErrorCode.FILE_NOT_FOUND, "No such file.");
        }
    }

    /**
     * Maps a stored file's metadata to its RMI-facing DTO, including real
     * lock state (Phase 5) — {@code "LOCKED"}/{@code "UNLOCKED"}, with a
     * generic {@code lockOwnerHint} ("you" if the caller holds it, otherwise
     * "another user") that never discloses another session's raw token or
     * identity (API-spec.md's minimal-disclosure note on this field).
     */
    private FileMetadata toDto(FileRecord record, String callerSessionToken) {
        Optional<String> lockOwner = lockManager.currentOwner(record.fileId());
        String lockState = lockOwner.isPresent() ? "LOCKED" : "UNLOCKED";
        String lockOwnerHint = lockOwner
                .map(owner -> owner.equals(callerSessionToken) ? "you" : "another user")
                .orElse(null);

        return new FileMetadata(
                record.fileId(), record.filename(), record.size(), record.owner(),
                record.createdAt(), record.modifiedAt(), lockState, lockOwnerHint);
    }

    /**
     * Reusable session-validation guard for authenticated methods (FR-004,
     * Security.md §5): returns the owning user ID, or throws
     * {@code INVALID_SESSION} for a missing/unknown/expired token.
     */
    private String requireValidSession(String sessionToken) throws VaultServiceException {
        return sessionManager.validate(sessionToken)
                .map(Session::userId)
                .orElseThrow(() -> new VaultServiceException(ErrorCode.INVALID_SESSION, "Session is not valid."));
    }
}
