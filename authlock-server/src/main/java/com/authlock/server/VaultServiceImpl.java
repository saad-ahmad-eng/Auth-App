package com.authlock.server;

import com.authlock.common.ErrorCode;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.RmiConfig;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.common.crypto.AesGcmCipher;
import com.authlock.common.crypto.TamperDetectedException;
import com.authlock.server.audit.AuditEventType;
import com.authlock.server.audit.AuditLogger;
import com.authlock.server.auth.AuthenticationService;
import com.authlock.server.auth.PasswordHasher;
import com.authlock.server.auth.User;
import com.authlock.server.auth.UserStore;
import com.authlock.server.crypto.EncryptionService;
import com.authlock.server.lock.LockManager;
import com.authlock.server.session.Session;
import com.authlock.server.session.SessionManager;
import com.authlock.server.vault.FileRecord;
import com.authlock.server.vault.VaultFileService;

import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.rmi.server.ServerNotActiveException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Server-side implementation of {@link VaultService}.
 *
 * <p><b>Phase 2:</b> {@link #ping()}.
 * <b>Phase 3:</b> {@link #login} / {@link #logout}.
 * <b>Phase 4:</b> {@link #listFiles}, {@link #uploadFile}, {@link #downloadFile}.
 * <b>Phase 5:</b> {@link #lockFile}, {@link #unlockFile}.
 * <b>Phase 6:</b> AES-256-GCM encryption + RMI-over-TLS.
 * <b>Phase 7 (this addition):</b> every method now writes a structured
 * {@link AuditLogger} record on every success/failure exit path
 * (Security.md §9, FR-011), except {@link #listFiles} — deliberately left
 * unaudited (read-only, low information value, would dominate log volume),
 * a decision already recorded in API-spec.md during the documentation
 * phase and reaffirmed here, not silently changed.
 */
public class VaultServiceImpl extends UnicastRemoteObject implements VaultService {

    private static final String DEFAULT_VAULT_DIR = "vault-storage";
    private static final String DEFAULT_KEY_FILE = "authlock-shared.key";
    private static final String DEFAULT_AUDIT_LOG = "audit.log";

    private final AuthenticationService authenticationService;
    private final UserStore userStore;
    private final SessionManager sessionManager;
    private final VaultFileService vaultFileService;
    private final LockManager lockManager;
    private final EncryptionService encryptionService;
    private final AuditLogger auditLogger;

    /** Plain RMI export (no TLS) — used by the existing test suite; see class Javadoc. */
    public VaultServiceImpl(int port) throws RemoteException, IOException {
        this(port, false);
    }

    public VaultServiceImpl() throws RemoteException, IOException {
        this(RmiConfig.SERVICE_PORT, false);
    }

    /**
     * @param tlsEnabled if true, exports over RMI-over-TLS
     *                   ({@link SslRMIClientSocketFactory}/{@link SslRMIServerSocketFactory}) —
     *                   {@link com.authlock.common.tls.DevTlsSetup#configure()} must have
     *                   already been called so the JSSE keystore properties are set.
     *                   If false, exports over plain sockets (identical to
     *                   {@link #VaultServiceImpl(int)}'s prior behavior).
     */
    public VaultServiceImpl(int port, boolean tlsEnabled) throws RemoteException, IOException {
        super(port,
                tlsEnabled ? new SslRMIClientSocketFactory() : null,
                tlsEnabled ? new SslRMIServerSocketFactory() : null);

        PasswordHasher passwordHasher = new PasswordHasher();
        this.userStore = new UserStore(passwordHasher);
        this.authenticationService = new AuthenticationService(userStore, passwordHasher);
        this.sessionManager = createSessionManager();

        Path vaultDir = Path.of(System.getProperty("authlock.vault.dir", DEFAULT_VAULT_DIR));
        this.vaultFileService = new VaultFileService(vaultDir);

        Path keyFile = Path.of(System.getProperty("authlock.crypto.keyfile", DEFAULT_KEY_FILE));
        this.encryptionService = new EncryptionService(keyFile);

        Path auditLogPath = Path.of(System.getProperty("authlock.audit.file", DEFAULT_AUDIT_LOG));
        this.auditLogger = new AuditLogger(auditLogPath);

        this.lockManager = new LockManager();
        // Architecture.md §2.7: Lock Manager's documented input includes
        // "session-expiry notifications from Session Manager" — this is that wire.
        this.sessionManager.setSessionEndedListener(lockManager::releaseAllOwnedBySession);
    }

    /**
     * Builds the session manager, honoring optional {@code authlock.session.idleTimeoutSeconds}
     * / {@code authlock.session.maxLifetimeSeconds} system properties if set. Unset (the normal
     * case) yields exactly {@link SessionManager#SessionManager()}'s OQ-08 defaults (30 min idle
     * / 8 hr absolute) — this is <b>not</b> a production configuration knob, it exists solely so
     * Phase 8's interactive session-expiry verification (TEST-UI-004, Testing.md) can run a real
     * server process with a deliberately short-lived session instead of waiting 30 real minutes.
     */
    private static SessionManager createSessionManager() {
        String idleSeconds = System.getProperty("authlock.session.idleTimeoutSeconds");
        String maxLifetimeSeconds = System.getProperty("authlock.session.maxLifetimeSeconds");
        if (idleSeconds == null && maxLifetimeSeconds == null) {
            return new SessionManager();
        }
        Duration idleTimeout = idleSeconds != null
                ? Duration.ofSeconds(Long.parseLong(idleSeconds))
                : SessionManager.IDLE_TIMEOUT;
        Duration maxLifetime = maxLifetimeSeconds != null
                ? Duration.ofSeconds(Long.parseLong(maxLifetimeSeconds))
                : SessionManager.MAX_LIFETIME;
        return new SessionManager(idleTimeout, maxLifetime);
    }

    @Override
    public String ping() throws RemoteException {
        return "AuthLock VaultService is alive at " + Instant.now();
    }

    /**
     * The caller's own account record (own {@code userId}/{@code username}
     * only — never usable to look up anyone else's). Not part of the RMI
     * {@link VaultService} surface: the Swing client has never needed this,
     * and adding it there would mean widening a {@code Remote} interface
     * for a need only the web UI's own-identity display
     * ({@code AuthLockHttpServer}'s {@code GET /api/me}) actually has. Same
     * unaudited treatment as {@link #listFiles} — a read with no
     * information value to the audit trail.
     */
    public User whoAmI(String sessionToken) throws VaultServiceException {
        String userId = requireValidSession(sessionToken);
        return userStore.findByUserId(userId)
                .orElseThrow(() -> new VaultServiceException(ErrorCode.SERVER_ERROR,
                        "No user record for an otherwise-valid session."));
    }

    /**
     * Records a rate-limited {@code POST /api/login} attempt in the audit
     * trail (Phase 13 hardening; {@code AuthLockHttpServer}'s rate
     * limiter calls this instead of silently dropping the attempt). Not
     * part of the RMI {@link VaultService} surface.
     */
    public void auditLoginThrottled(String attemptedUsername, String clientInfo) {
        auditLogger.logFailure(AuditEventType.LOGIN, attemptedUsername, "login", null, ErrorCode.RATE_LIMITED, clientInfo);
    }

    @Override
    public String login(String username, String password) throws RemoteException, VaultServiceException {
        String clientInfo = currentClientHost();
        char[] passwordChars = password == null ? new char[0] : password.toCharArray();
        Optional<User> user = authenticationService.authenticate(username, passwordChars);

        if (user.isEmpty()) {
            // Same error for unknown username or wrong password — SEC-001 enumeration prevention.
            // userId is the *attempted* username here (no authenticated identity exists yet) —
            // standard practice for spotting brute-force/enumeration attempts; not a secret.
            auditLogger.logFailure(AuditEventType.LOGIN, username, "login", null, ErrorCode.AUTHENTICATION_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.AUTHENTICATION_FAILED, "Invalid username or password.");
        }
        String token = sessionManager.create(user.get().userId());
        auditLogger.logSuccess(AuditEventType.LOGIN, user.get().userId(), "login", null, clientInfo);
        return token;
    }

    @Override
    public void logout(String sessionToken) throws RemoteException, VaultServiceException {
        String clientInfo = currentClientHost();
        // invalidate() itself fires the session-ended listener, which releases
        // any locks this session held (API-spec.md logout's documented side effect).
        Optional<Session> removed = sessionManager.invalidate(sessionToken);
        if (removed.isEmpty()) {
            auditLogger.logFailure(AuditEventType.LOGOUT, null, "logout", null, ErrorCode.INVALID_SESSION, clientInfo);
            throw new VaultServiceException(ErrorCode.INVALID_SESSION, "Session is not valid.");
        }
        auditLogger.logSuccess(AuditEventType.LOGOUT, removed.get().userId(), "logout", null, clientInfo);
    }

    @Override
    public List<FileMetadata> listFiles(String sessionToken) throws RemoteException, VaultServiceException {
        // Deliberately unaudited — see class Javadoc.
        requireValidSession(sessionToken);
        return vaultFileService.listAll().stream()
                .map(record -> toDto(record, sessionToken))
                .collect(Collectors.toList());
    }

    @Override
    public String uploadFile(String sessionToken, String filename, byte[] fileBytes, byte[] iv)
            throws RemoteException, VaultServiceException {
        String clientInfo = currentClientHost();
        String userId = requireValidSession(sessionToken, AuditEventType.UPLOAD, "uploadFile");

        byte[] plaintext;
        try {
            plaintext = encryptionService.decrypt(iv, fileBytes);
        } catch (TamperDetectedException tampered) {
            auditLogger.logFailure(AuditEventType.UPLOAD, userId, "uploadFile", null, ErrorCode.UPLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Upload payload failed integrity check.");
        } catch (RuntimeException malformed) {
            // Covers malformed (not just tampered) ciphertext/IV — e.g. wrong IV
            // length from a buggy or malicious client (SEC-010 input validation)
            // — never let a raw crypto exception escape as an opaque RMI error.
            auditLogger.logFailure(AuditEventType.UPLOAD, userId, "uploadFile", null, ErrorCode.UPLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Upload payload could not be decrypted.");
        }

        try {
            // Owner is the human-readable username, not the internal userId —
            // the userId is stable/opaque by design (User.java), but the
            // vault UI's Owner column (UIUX.md §2) needs to show something a
            // person can actually read. Audit log entries intentionally keep
            // logging the raw userId (Security.md §9) since that's a stable
            // internal identifier, not a display value.
            String ownerDisplayName = userStore.findByUserId(userId).map(User::username).orElse(userId);
            FileRecord record = vaultFileService.store(filename, plaintext, ownerDisplayName);
            auditLogger.logSuccess(AuditEventType.UPLOAD, userId, "uploadFile", record.fileId(), clientInfo);
            return record.fileId();
        } catch (IllegalArgumentException invalidFilename) {
            auditLogger.logFailure(AuditEventType.UPLOAD, userId, "uploadFile", null, ErrorCode.UPLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Invalid filename.");
        } catch (IOException storageError) {
            auditLogger.logFailure(AuditEventType.UPLOAD, userId, "uploadFile", null, ErrorCode.UPLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Could not store file.");
        }
    }

    @Override
    public FileContent downloadFile(String sessionToken, String fileId) throws RemoteException, VaultServiceException {
        String clientInfo = currentClientHost();
        String userId = requireValidSession(sessionToken, AuditEventType.DOWNLOAD, "downloadFile");
        try {
            Optional<VaultFileService.StoredFile> found = vaultFileService.retrieve(fileId);
            if (found.isEmpty()) {
                auditLogger.logFailure(AuditEventType.DOWNLOAD, userId, "downloadFile", fileId, ErrorCode.FILE_NOT_FOUND, clientInfo);
                throw new VaultServiceException(ErrorCode.FILE_NOT_FOUND, "No such file.");
            }
            // Locked-file download policy (OQ-09): no check here — a lock protects
            // writes, not reads, so download proceeds regardless of lock state.
            VaultFileService.StoredFile stored = found.get();
            AesGcmCipher.Encrypted encrypted = encryptionService.encrypt(stored.content());
            auditLogger.logSuccess(AuditEventType.DOWNLOAD, userId, "downloadFile", fileId, clientInfo);
            return new FileContent(encrypted.ciphertext(), encrypted.iv(), stored.metadata().checksum());
        } catch (IOException readError) {
            auditLogger.logFailure(AuditEventType.DOWNLOAD, userId, "downloadFile", fileId, ErrorCode.DOWNLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.DOWNLOAD_FAILED, "Could not read file.");
        }
    }

    @Override
    public void lockFile(String sessionToken, String fileId) throws RemoteException, VaultServiceException {
        String clientInfo = currentClientHost();
        String userId = requireValidSession(sessionToken, AuditEventType.LOCK, "lockFile");
        requireFileExists(fileId, AuditEventType.LOCK, "lockFile", userId);

        boolean granted = lockManager.acquire(fileId, sessionToken);
        if (!granted) {
            auditLogger.logFailure(AuditEventType.LOCK, userId, "lockFile", fileId, ErrorCode.FILE_LOCKED, clientInfo);
            throw new VaultServiceException(ErrorCode.FILE_LOCKED, "File is currently locked by another session.");
        }
        auditLogger.logSuccess(AuditEventType.LOCK, userId, "lockFile", fileId, clientInfo);
    }

    @Override
    public void unlockFile(String sessionToken, String fileId) throws RemoteException, VaultServiceException {
        String clientInfo = currentClientHost();
        String userId = requireValidSession(sessionToken, AuditEventType.UNLOCK, "unlockFile");
        requireFileExists(fileId, AuditEventType.UNLOCK, "unlockFile", userId);

        boolean released = lockManager.release(fileId, sessionToken);
        if (!released) {
            auditLogger.logFailure(AuditEventType.UNLOCK, userId, "unlockFile", fileId, ErrorCode.LOCK_NOT_OWNED, clientInfo);
            throw new VaultServiceException(ErrorCode.LOCK_NOT_OWNED, "You do not hold the lock on this file.");
        }
        auditLogger.logSuccess(AuditEventType.UNLOCK, userId, "unlockFile", fileId, clientInfo);
    }

    /**
     * Overwrites an existing file's content in place — same {@code fileId}
     * — but ONLY if the caller's session currently holds that file's lock
     * (Phase 13 follow-up: the web UI's lock-gated "Replace" action, this
     * pass's explicit request, distinct from {@link #uploadFile}'s
     * always-a-new-{@code fileId} behavior, which is unchanged). Not part
     * of the RMI {@link VaultService} surface for the same reason
     * {@link #whoAmI} isn't — nothing indicates the Swing client needs it,
     * and {@code AuthLockHttpServer} calls {@code VaultServiceImpl}
     * directly, in-process, same as everywhere else in this class.
     *
     * @throws VaultServiceException with {@link ErrorCode#LOCK_NOT_OWNED} if
     *         the caller does not currently hold this file's lock
     *         (including "not locked at all") — the same error
     *         {@link #unlockFile} uses for the equivalent situation
     */
    public FileMetadata replaceFile(String sessionToken, String fileId, byte[] fileBytes, byte[] iv)
            throws VaultServiceException {
        String clientInfo = currentClientHost();
        String userId = requireValidSession(sessionToken, AuditEventType.REPLACE, "replaceFile");
        requireFileExists(fileId, AuditEventType.REPLACE, "replaceFile", userId);

        Optional<String> lockOwner = lockManager.currentOwner(fileId);
        if (lockOwner.isEmpty() || !lockOwner.get().equals(sessionToken)) {
            auditLogger.logFailure(AuditEventType.REPLACE, userId, "replaceFile", fileId, ErrorCode.LOCK_NOT_OWNED, clientInfo);
            throw new VaultServiceException(ErrorCode.LOCK_NOT_OWNED, "You must hold this file's lock to replace its content.");
        }

        byte[] plaintext;
        try {
            plaintext = encryptionService.decrypt(iv, fileBytes);
        } catch (TamperDetectedException tampered) {
            auditLogger.logFailure(AuditEventType.REPLACE, userId, "replaceFile", fileId, ErrorCode.UPLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Upload payload failed integrity check.");
        } catch (RuntimeException malformed) {
            auditLogger.logFailure(AuditEventType.REPLACE, userId, "replaceFile", fileId, ErrorCode.UPLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Upload payload could not be decrypted.");
        }

        try {
            // Owner is the human-readable username, per uploadFile's own note — the
            // version record's "replacedBy" needs the same display value, not the
            // opaque userId.
            String replacerUsername = userStore.findByUserId(userId).map(User::username).orElse(userId);
            FileRecord updated = vaultFileService.replaceWithHistory(fileId, plaintext, replacerUsername)
                    .orElseThrow(() -> new VaultServiceException(ErrorCode.FILE_NOT_FOUND, "No such file."));
            auditLogger.logSuccess(AuditEventType.REPLACE, userId, "replaceFile", fileId, clientInfo);
            auditLogger.logSuccess(AuditEventType.VERSION_CREATED, userId, "replaceFile", fileId, clientInfo);
            return toDto(updated, sessionToken);
        } catch (IOException storageError) {
            auditLogger.logFailure(AuditEventType.REPLACE, userId, "replaceFile", fileId, ErrorCode.UPLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.UPLOAD_FAILED, "Could not store replacement content.");
        }
    }

    /**
     * A file's version timeline (Phase 13 follow-up) — read-only, no lock
     * gating (like {@link #downloadFile}, a lock protects writes, not
     * reads). Not part of the RMI surface, same reasoning as
     * {@link #replaceFile}.
     */
    public List<VaultFileService.FileVersion> getFileVersions(String sessionToken, String fileId) throws VaultServiceException {
        requireValidSession(sessionToken);
        if (!vaultFileService.exists(fileId)) {
            throw new VaultServiceException(ErrorCode.FILE_NOT_FOUND, "No such file.");
        }
        return vaultFileService.versionsOf(fileId);
    }

    /** A specific prior version's plaintext bytes — same read-only, no-lock-gating rule as {@link #getFileVersions}. */
    public byte[] downloadFileVersion(String sessionToken, String fileId, int versionNumber) throws VaultServiceException {
        String userId = requireValidSession(sessionToken);
        String clientInfo = currentClientHost();
        Optional<byte[]> content;
        try {
            content = vaultFileService.versionContent(fileId, versionNumber);
        } catch (IOException e) {
            auditLogger.logFailure(AuditEventType.DOWNLOAD, userId, "downloadFileVersion", fileId, ErrorCode.DOWNLOAD_FAILED, clientInfo);
            throw new VaultServiceException(ErrorCode.DOWNLOAD_FAILED, "Could not read version content.");
        }
        if (content.isEmpty()) {
            auditLogger.logFailure(AuditEventType.DOWNLOAD, userId, "downloadFileVersion", fileId, ErrorCode.FILE_NOT_FOUND, clientInfo);
            throw new VaultServiceException(ErrorCode.FILE_NOT_FOUND, "No such file version.");
        }
        auditLogger.logSuccess(AuditEventType.DOWNLOAD, userId, "downloadFileVersion", fileId, clientInfo);
        return content.get();
    }

    // ---- Phase 13 follow-up: admin-only user management, not part of the RMI surface (same reasoning as whoAmI/replaceFile) ----

    /** Every account (Phase 13 admin panel) — {@link User.Role#ADMIN} callers only. */
    public java.util.Collection<User> adminListUsers(String sessionToken) throws VaultServiceException {
        requireAdmin(sessionToken, "adminListUsers");
        return userStore.listAll();
    }

    /**
     * Creates a new account. {@code newPassword} is hashed immediately —
     * the same {@link PasswordHasher} every other account's password goes
     * through — and is never itself stored or returned.
     *
     * @throws VaultServiceException with {@link ErrorCode#USER_ALREADY_EXISTS} if the username is taken
     */
    public User adminCreateUser(String sessionToken, String newUsername, char[] newPassword, User.Role role) throws VaultServiceException {
        String adminUserId = requireAdmin(sessionToken, "adminCreateUser");
        String clientInfo = currentClientHost();
        try {
            User created = userStore.createUser(newUsername, newPassword, role);
            auditLogger.logSuccess(AuditEventType.ADMIN, adminUserId, "adminCreateUser:" + newUsername, null, clientInfo);
            return created;
        } catch (IllegalArgumentException duplicateUsername) {
            auditLogger.logFailure(AuditEventType.ADMIN, adminUserId, "adminCreateUser:" + newUsername, null, ErrorCode.USER_ALREADY_EXISTS, clientInfo);
            throw new VaultServiceException(ErrorCode.USER_ALREADY_EXISTS, "That username is already taken.");
        } catch (IOException storageError) {
            auditLogger.logFailure(AuditEventType.ADMIN, adminUserId, "adminCreateUser:" + newUsername, null, ErrorCode.SERVER_ERROR, clientInfo);
            throw new VaultServiceException(ErrorCode.SERVER_ERROR, "Could not persist the new account.");
        }
    }

    /**
     * Disables an account and immediately invalidates every session it
     * currently holds (Phase 13 admin panel: "disabled" must mean actually
     * locked out right now, not just blocked from a future login).
     */
    public User adminDisableUser(String sessionToken, String targetUserId) throws VaultServiceException {
        User updated = adminSetStatus(sessionToken, targetUserId, User.Status.DISABLED, "adminDisableUser");
        sessionManager.invalidateAllForUser(targetUserId);
        return updated;
    }

    /** Re-enables a previously disabled account, allowing it to log in again. */
    public User adminEnableUser(String sessionToken, String targetUserId) throws VaultServiceException {
        return adminSetStatus(sessionToken, targetUserId, User.Status.ACTIVE, "adminEnableUser");
    }

    private User adminSetStatus(String sessionToken, String targetUserId, User.Status status, String operation) throws VaultServiceException {
        String adminUserId = requireAdmin(sessionToken, operation);
        String clientInfo = currentClientHost();
        try {
            User updated = userStore.setStatus(targetUserId, status)
                    .orElseThrow(() -> new VaultServiceException(ErrorCode.USER_NOT_FOUND, "No such user."));
            auditLogger.logSuccess(AuditEventType.ADMIN, adminUserId, operation + ":" + targetUserId, null, clientInfo);
            return updated;
        } catch (VaultServiceException notFound) {
            auditLogger.logFailure(AuditEventType.ADMIN, adminUserId, operation + ":" + targetUserId, null, ErrorCode.USER_NOT_FOUND, clientInfo);
            throw notFound;
        } catch (IOException storageError) {
            auditLogger.logFailure(AuditEventType.ADMIN, adminUserId, operation + ":" + targetUserId, null, ErrorCode.SERVER_ERROR, clientInfo);
            throw new VaultServiceException(ErrorCode.SERVER_ERROR, "Could not persist the account status change.");
        }
    }

    /**
     * Validates the session AND that its owner is an {@link User.Role#ADMIN}
     * — audits every call under {@link AuditEventType#ADMIN}, success or
     * rejected (including a non-admin's attempt), per this pass's explicit
     * request, not just successful admin actions.
     */
    private String requireAdmin(String sessionToken, String operation) throws VaultServiceException {
        String clientInfo = currentClientHost();
        String userId;
        try {
            userId = requireValidSession(sessionToken);
        } catch (VaultServiceException invalidSession) {
            auditLogger.logFailure(AuditEventType.ADMIN, null, operation, null, invalidSession.getErrorCode(), clientInfo);
            throw invalidSession;
        }
        User caller = userStore.findByUserId(userId).orElse(null);
        if (caller == null || caller.role() != User.Role.ADMIN) {
            auditLogger.logFailure(AuditEventType.ADMIN, userId, operation, null, ErrorCode.UNAUTHORIZED, clientInfo);
            throw new VaultServiceException(ErrorCode.UNAUTHORIZED, "Admin role required.");
        }
        return userId;
    }

    private void requireFileExists(String fileId, AuditEventType auditEventType, String operation, String userId)
            throws VaultServiceException {
        if (!vaultFileService.exists(fileId)) {
            auditLogger.logFailure(auditEventType, userId, operation, fileId, ErrorCode.FILE_NOT_FOUND, currentClientHost());
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
     * Non-auditing session-validation guard, used only by {@link #listFiles}
     * (deliberately unaudited — see class Javadoc).
     */
    private String requireValidSession(String sessionToken) throws VaultServiceException {
        return sessionManager.validate(sessionToken)
                .map(Session::userId)
                .orElseThrow(() -> new VaultServiceException(ErrorCode.INVALID_SESSION, "Session is not valid."));
    }

    /**
     * Auditing session-validation guard for FR-004 (Security.md §5): returns
     * the owning user ID, or audits an {@code INVALID_SESSION} failure
     * against the caller's intended operation and throws.
     */
    private String requireValidSession(String sessionToken, AuditEventType auditEventType, String operation)
            throws VaultServiceException {
        Optional<Session> session = sessionManager.validate(sessionToken);
        if (session.isEmpty()) {
            auditLogger.logFailure(auditEventType, null, operation, null, ErrorCode.INVALID_SESSION, currentClientHost());
            throw new VaultServiceException(ErrorCode.INVALID_SESSION, "Session is not valid.");
        }
        return session.get().userId();
    }

    /**
     * The calling client's host/IP as seen by the RMI runtime (Security.md
     * §9 {@code clientInfo}), or {@code "unknown"} if called outside an
     * active remote invocation (defensive — should not happen in normal
     * operation, since every caller of this method runs inside one).
     */
    private static String currentClientHost() {
        try {
            return RemoteServer.getClientHost();
        } catch (ServerNotActiveException e) {
            return "unknown";
        }
    }
}
