package com.authlock.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The single remote interface exposed by the AuthLock server, per
 * Architecture.md §2.3 and API-spec.md.
 *
 * <p><b>Implementation Phase 2 (RMI Infrastructure) scope only:</b> this
 * interface currently declares nothing but a connectivity health check
 * ({@link #ping()}), used to prove the RMI channel works end-to-end
 * (TEST-INT-001) before any real functionality is added. The full contract
 * — {@code login}, {@code logout}, {@code listFiles}, {@code uploadFile},
 * {@code downloadFile}, {@code lockFile}, {@code unlockFile} — is added
 * incrementally in later phases (see Implementation.md Phases 3–5), each
 * method appearing here only once its owning phase is actually implemented,
 * per Development-rules.md §1 ("implement only the requested phase").
 *
 * <p>Every method declares {@code throws RemoteException} per TRD.md §3.
 */
public interface VaultService extends Remote {

    /**
     * Minimal connectivity/health check. Not part of the functional API
     * surface in API-spec.md — exists solely to validate the RMI
     * registry/export/lookup path during Phase 2.
     *
     * @return a fixed, human-readable confirmation string
     */
    String ping() throws RemoteException;
}
