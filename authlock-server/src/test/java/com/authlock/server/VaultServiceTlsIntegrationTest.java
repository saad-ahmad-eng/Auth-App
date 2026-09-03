package com.authlock.server;

import com.authlock.common.RmiConfig;
import com.authlock.common.VaultService;
import com.authlock.common.tls.DevTlsSetup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.rmi.ssl.SslRMIClientSocketFactory;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dedicated proof that RMI-over-TLS actually works (Security.md §7 / ADR-007
 * "primary transport control" resolution of OQ-05), separate from the rest
 * of the RMI integration suite (which deliberately stays on plain RMI — see
 * {@link VaultServiceImpl}'s class Javadoc for why: it keeps business-logic
 * tests from also having to re-verify the transport layer on every run).
 *
 * <p>Uses an isolated {@code @TempDir} keystore (via
 * {@link DevTlsSetup#configure(Path)}), a dedicated registry port, and a
 * TLS-enabled {@code VaultServiceImpl(port, true)} export.
 */
class VaultServiceTlsIntegrationTest {

    private static final int TEST_REGISTRY_PORT = 21599;
    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";
    private static final String KEY_FILE_PROPERTY = "authlock.crypto.keyfile";

    private static Registry registry;
    private static VaultServiceImpl serviceImpl;
    private static String previousVaultDirProperty;
    private static String previousKeyFileProperty;

    @TempDir
    static Path tempVaultDir;
    @TempDir
    static Path tempKeyDir;
    @TempDir
    static Path tempTlsDir;

    @BeforeAll
    static void setUp() throws Exception {
        // See ServerMain's Javadoc: without this, RMI embeds the machine's
        // actual LAN IP in exported stubs, and the dev cert's SAN
        // (localhost/127.0.0.1 only) then fails TLS hostname verification.
        if (System.getProperty("java.rmi.server.hostname") == null) {
            System.setProperty("java.rmi.server.hostname", "localhost");
        }

        previousVaultDirProperty = System.getProperty(VAULT_DIR_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, tempVaultDir.toString());
        previousKeyFileProperty = System.getProperty(KEY_FILE_PROPERTY);
        System.setProperty(KEY_FILE_PROPERTY, tempKeyDir.resolve("test-shared.key").toString());

        DevTlsSetup.configure(tempTlsDir.resolve("test-tls.p12"));

        registry = LocateRegistry.createRegistry(
                TEST_REGISTRY_PORT, new SslRMIClientSocketFactory(), new javax.rmi.ssl.SslRMIServerSocketFactory());
        serviceImpl = new VaultServiceImpl(0, true);
        registry.rebind(RmiConfig.SERVICE_NAME, serviceImpl);
    }

    @AfterAll
    static void tearDown() throws Exception {
        try {
            registry.unbind(RmiConfig.SERVICE_NAME);
        } catch (Exception ignored) {
            // best-effort
        }
        UnicastRemoteObject.unexportObject(serviceImpl, true);
        UnicastRemoteObject.unexportObject(registry, true);

        restoreProperty(VAULT_DIR_PROPERTY, previousVaultDirProperty);
        restoreProperty(KEY_FILE_PROPERTY, previousKeyFileProperty);
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    @Test
    void clientCanCompleteARealRoundTripOverTls() throws Exception {
        Registry clientRegistryView = LocateRegistry.getRegistry(
                "localhost", TEST_REGISTRY_PORT, new SslRMIClientSocketFactory());
        VaultService client = (VaultService) clientRegistryView.lookup(RmiConfig.SERVICE_NAME);

        String response = client.ping();

        assertNotNull(response);
        assertTrue(response.contains("AuthLock VaultService is alive"));
    }

    @Test
    void loginSucceedsOverTls_credentialsNeverTravelInThePlain() throws Exception {
        Registry clientRegistryView = LocateRegistry.getRegistry(
                "localhost", TEST_REGISTRY_PORT, new SslRMIClientSocketFactory());
        VaultService client = (VaultService) clientRegistryView.lookup(RmiConfig.SERVICE_NAME);

        String token = client.login("alice", "AliceP@ss1");

        assertNotNull(token);
        client.logout(token);
    }

    @Test
    void aPlainNonTlsClientCannotConnectToTheTlsOnlyRegistry() {
        // Proves TLS is actually being enforced by the transport, not just
        // configured-but-bypassable: a lookup without the matching
        // SslRMIClientSocketFactory must fail, not silently succeed in the clear.
        assertThrows(Exception.class, () -> {
            Registry plainView = LocateRegistry.getRegistry("localhost", TEST_REGISTRY_PORT);
            // The connection failure may surface on getRegistry() or on the
            // first actual call, depending on JDK/registry implementation
            // details — either is acceptable proof that plain access fails.
            plainView.lookup(RmiConfig.SERVICE_NAME);
        });
    }
}
