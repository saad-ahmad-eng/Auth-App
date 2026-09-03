package com.authlock.client.rmi;

/**
 * Thrown when the client cannot reach the RMI registry or the exported
 * {@code VaultService} — covers registry lookup failure, an unbound service
 * name, and transport-level {@link java.rmi.RemoteException}s.
 *
 * <p>Satisfies FR-013 (Server Availability / Connection Handling, PRD.md §4):
 * the client must detect and clearly report an unreachable server rather
 * than failing silently or crashing. In Phase 8 (Swing UI) this becomes the
 * trigger for the "Server Unavailable" connection-state indicator
 * (UIUX.md §6); for now (Phase 2) it is caught and printed by
 * {@link com.authlock.client.ClientMain}.
 */
public class ServerUnavailableException extends RuntimeException {

    public ServerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
