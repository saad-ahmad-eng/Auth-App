package com.authlock.common;

/**
 * Shared RMI configuration constants used by both server and client.
 *
 * <p>The registry port is the conventional RMI default. The service object
 * port is deliberately <b>fixed</b> (not left to an ephemeral OS-assigned
 * port) so that a cloud security group only ever needs to open one
 * predictable inbound port for the exported {@link VaultService} object, in
 * addition to the registry port — see TRD.md §2.6/§2.7 and Architecture.md
 * §3 (Deployment Architecture).
 */
public final class RmiConfig {

    /** Port the RMI registry listens on. */
    public static final int REGISTRY_PORT = 1099;

    /**
     * Fixed port the exported {@link VaultService} remote object listens on.
     * Confirmed during Implementation Phase 2 (was "TBD" in TRD.md §2.7 /
     * Architecture.md §3 at documentation time).
     */
    public static final int SERVICE_PORT = 5000;

    /** Name the service is bound under in the registry. */
    public static final String SERVICE_NAME = "VaultService";

    private RmiConfig() {
    }
}
