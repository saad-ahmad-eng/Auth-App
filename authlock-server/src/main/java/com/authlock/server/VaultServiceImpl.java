package com.authlock.server;

import com.authlock.common.ErrorCode;
import com.authlock.common.RmiConfig;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.server.auth.AuthenticationService;
import com.authlock.server.auth.PasswordHasher;
import com.authlock.server.auth.User;
import com.authlock.server.auth.UserStore;
import com.authlock.server.session.Session;
import com.authlock.server.session.SessionManager;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;
import java.util.Optional;

/**
 * Server-side implementation of {@link VaultService}.
 *
 * <p><b>Phase 2 scope:</b> {@link #ping()}.
 * <b>Phase 3 scope (this addition):</b> {@link #login} / {@link #logout},
 * backed by {@link AuthenticationService} (Architecture.md §2.4) and
 * {@link SessionManager} (§2.5). {@link #requireValidSession(String)} is the
 * reusable session-validation primitive that Phase 4/5 methods (vault,
 * lock) will call before doing any work, per Implementation.md Phase 3's
 * "add session-token validation to every other method stub."
 *
 * <p>Vault, lock, encryption, and audit behavior remain unimplemented —
 * added in Phases 4–7 as their internal services are built.
 */
public class VaultServiceImpl extends UnicastRemoteObject implements VaultService {

    private final AuthenticationService authenticationService;
    private final SessionManager sessionManager;

    public VaultServiceImpl(int port) throws RemoteException {
        super(port);
        PasswordHasher passwordHasher = new PasswordHasher();
        UserStore userStore = new UserStore(passwordHasher);
        this.authenticationService = new AuthenticationService(userStore, passwordHasher);
        this.sessionManager = new SessionManager();
    }

    public VaultServiceImpl() throws RemoteException {
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
        boolean invalidated = sessionManager.invalidate(sessionToken);
        if (!invalidated) {
            throw new VaultServiceException(ErrorCode.INVALID_SESSION, "Session is not valid.");
        }
        // Audit logging (LOGOUT, FR-011) is Phase 7's responsibility — not yet wired in.
    }

    /**
     * Reusable session-validation guard for future authenticated methods
     * (Phase 4+): returns the owning user ID, or throws
     * {@code INVALID_SESSION} for a missing/unknown/expired token
     * (FR-004, Security.md §5).
     */
    private String requireValidSession(String sessionToken) throws VaultServiceException {
        return sessionManager.validate(sessionToken)
                .map(Session::userId)
                .orElseThrow(() -> new VaultServiceException(ErrorCode.INVALID_SESSION, "Session is not valid."));
    }
}
