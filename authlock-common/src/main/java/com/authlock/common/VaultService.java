package com.authlock.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The single remote interface exposed by the AuthLock server, per
 * Architecture.md §2.3 and API-spec.md.
 *
 * <p>Methods are added incrementally as their owning Implementation.md phase
 * is completed, per Development-rules.md §1 ("implement only the requested
 * phase"): {@link #ping()} (Phase 2), {@link #login} / {@link #logout}
 * (Phase 3, this addition). {@code listFiles}, {@code uploadFile},
 * {@code downloadFile} (Phase 4) and {@code lockFile}, {@code unlockFile}
 * (Phase 5) follow.
 *
 * <p>Every method declares {@code throws RemoteException} per TRD.md §3.
 * Application-level failures (API-spec.md §3 Error Model) are surfaced as
 * {@link VaultServiceException}, not as a variety of custom exception types.
 */
public interface VaultService extends Remote {

    /**
     * Minimal connectivity/health check. Not part of the functional API
     * surface in API-spec.md — exists solely to validate the RMI
     * registry/export/lookup path during Phase 2. Requires no session.
     *
     * @return a fixed, human-readable confirmation string
     */
    String ping() throws RemoteException;

    /**
     * Authenticates a user and establishes a session (API-spec.md
     * {@code login}, FR-001/FR-003, Security.md §3).
     *
     * @param username the account's username
     * @param password the account's password, in plaintext over this call
     *                  (see Security.md §7 — transport confidentiality is
     *                  Phase 6's responsibility; not yet applied)
     * @return an opaque session token
     * @throws VaultServiceException with {@link ErrorCode#AUTHENTICATION_FAILED}
     *                                on invalid credentials — the same error
     *                                for an unknown username or a wrong
     *                                password (SEC-001 enumeration prevention)
     */
    String login(String username, String password) throws RemoteException, VaultServiceException;

    /**
     * Terminates a session (API-spec.md {@code logout}, FR-002).
     *
     * @param sessionToken the token returned by {@link #login}
     * @throws VaultServiceException with {@link ErrorCode#INVALID_SESSION}
     *                                if the token is unknown, already
     *                                invalidated, or expired
     */
    void logout(String sessionToken) throws RemoteException, VaultServiceException;
}
