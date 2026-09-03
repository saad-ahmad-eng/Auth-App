package com.authlock.server;

import com.authlock.common.RmiConfig;
import com.authlock.common.VaultService;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Instant;

/**
 * Server-side implementation of {@link VaultService}.
 *
 * <p><b>Phase 2 (RMI Infrastructure) scope only:</b> implements just
 * {@link #ping()} to prove the RMI export/bind/lookup/invoke path works.
 * Authentication, session, vault, lock, encryption, and audit behavior are
 * added in later phases (Implementation.md Phases 3–7) as the corresponding
 * internal services (Architecture.md §2.4–§2.9) are built and wired in here.
 */
public class VaultServiceImpl extends UnicastRemoteObject implements VaultService {

    /**
     * Exports this object on the given fixed port (see RmiConfig.SERVICE_PORT
     * for the production default; tests may pass 0 for an ephemeral port to
     * avoid clashing with a real running instance).
     */
    public VaultServiceImpl(int port) throws RemoteException {
        super(port);
    }

    /** Exports on the production fixed service port, RmiConfig.SERVICE_PORT. */
    public VaultServiceImpl() throws RemoteException {
        this(RmiConfig.SERVICE_PORT);
    }

    @Override
    public String ping() throws RemoteException {
        return "AuthLock VaultService is alive at " + Instant.now();
    }
}
