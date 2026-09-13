package com.authlock.server;

import com.authlock.common.RmiConfig;
import com.authlock.common.tls.DevTlsSetup;
import com.authlock.server.crypto.EncryptionService;
import com.authlock.server.http.AuthLockHttpServer;
import com.authlock.server.http.HttpsRedirectServer;

import javax.net.ssl.SSLContext;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;

/**
 * RMI server bootstrap. Starts (or attaches to) the RMI registry, exports and
 * binds the {@link VaultServiceImpl}, then returns — the RMI runtime's own
 * non-daemon threads keep the JVM alive to serve requests.
 *
 * <p>Implementation Phase 2 scope: registry + export + bind. Defaults
 * {@code java.rmi.server.hostname} to {@code localhost} unless already set
 * by the launcher — cloud deployment (Implementation.md Phase 11,
 * Architecture.md §3) overrides this with the VM's public IP via
 * {@code -Djava.rmi.server.hostname=<publicIP>}, no code change needed.
 *
 * <p><b>Phase 6 addition:</b> RMI-over-TLS is on by default
 * ({@code -Dauthlock.tls.enabled=false} to disable — e.g. for local
 * troubleshooting), protecting the whole channel including {@code login()}
 * credentials and session tokens (Security.md §7's transport-layer
 * complementary control to the AES-GCM file-payload encryption).
 * <b>The {@code java.rmi.server.hostname} default above is not just
 * cosmetic under TLS</b> — without it, RMI embeds the machine's actual
 * detected LAN IP in exported stubs, and the dev certificate's Subject
 * Alternative Names (only {@code localhost}/{@code 127.0.0.1} by default)
 * then fails TLS hostname verification against that IP. <b>Resolved in
 * Phase 11:</b> {@link DevTlsSetup} accepts {@code -Dauthlock.tls.extraSan}
 * to extend the certificate's SAN with a real deployment address —
 * {@code scripts/provision-vm.sh}/{@code scripts/setup-authlock.sh} set both
 * this and {@code java.rmi.server.hostname} together from the VM's
 * discovered public address, so a cloud deployment needs no manual
 * certificate regeneration step.
 */
public final class ServerMain {

    private static final int DEFAULT_HTTP_PORT = 8080;
    private static final int DEFAULT_HTTPS_PORT = 8443;

    private ServerMain() {
    }

    public static void main(String[] args) {
        try {
            if (System.getProperty("java.rmi.server.hostname") == null) {
                System.setProperty("java.rmi.server.hostname", "localhost");
            }

            boolean tlsEnabled = Boolean.parseBoolean(System.getProperty("authlock.tls.enabled", "true"));
            // Unconditional (not gated on tlsEnabled): the web UI's HTTPS
            // listener below always needs a keystore, regardless of whether
            // RMI-over-TLS itself is on — idempotent (generates the cert
            // only if it doesn't already exist), so this costs nothing on
            // the normal tlsEnabled=true path where it used to be gated.
            DevTlsSetup.configure();

            Registry registry = getOrCreateRegistry(RmiConfig.REGISTRY_PORT, tlsEnabled);

            VaultServiceImpl service = new VaultServiceImpl(RmiConfig.SERVICE_PORT, tlsEnabled);
            registry.rebind(RmiConfig.SERVICE_NAME, service);

            // Phase 13: a browser-facing HTTPS bridge onto the same VaultServiceImpl
            // instance, in the same process — see AuthLockHttpServer's class Javadoc.
            // Its own EncryptionService instance is built the same way VaultServiceImpl
            // builds its (private) one, over the same shared key file, so both
            // transports encrypt/decrypt file payloads identically. Its TLS certificate
            // is the SAME one RMI-over-TLS uses (DevTlsSetup.currentSslContext()), not a
            // second one — see that method's Javadoc for why that's safe to reuse.
            int httpsPort = Integer.parseInt(System.getProperty("authlock.https.port", String.valueOf(DEFAULT_HTTPS_PORT)));
            int httpPort = Integer.parseInt(System.getProperty("authlock.http.port", String.valueOf(DEFAULT_HTTP_PORT)));
            Path keyFile = Path.of(System.getProperty("authlock.crypto.keyfile", "authlock-shared.key"));
            SSLContext webSslContext = DevTlsSetup.currentSslContext();
            AuthLockHttpServer httpsServer = new AuthLockHttpServer(service, new EncryptionService(keyFile), httpsPort, webSslContext);
            httpsServer.start();

            // Plain HTTP stays up only as a redirect to HTTPS — see HttpsRedirectServer's
            // Javadoc for why credentials must never be submittable on this listener.
            HttpsRedirectServer redirectServer = new HttpsRedirectServer(httpPort, httpsPort);
            redirectServer.start();

            System.out.println("AuthLock server ready.");
            System.out.println("  Registry port : " + RmiConfig.REGISTRY_PORT);
            System.out.println("  Service port  : " + RmiConfig.SERVICE_PORT);
            System.out.println("  Bound as      : " + RmiConfig.SERVICE_NAME);
            System.out.println("  Transport     : " + (tlsEnabled
                    ? "RMI-over-TLS (dev self-signed cert — see DevTlsSetup)"
                    : "plaintext RMI (authlock.tls.enabled=false)"));
            System.out.println("  Web UI        : https://<host>:" + httpsServer.port()
                    + "/ (self-signed cert — browser will warn on first visit, expected)");
            System.out.println("  (http://<host>:" + redirectServer.port() + "/ redirects to the above; no login accepted there)");
            System.out.println("Full VaultService surface: ping, login/logout, listFiles,");
            System.out.println("  uploadFile/downloadFile, lockFile/unlockFile — all AES-256-GCM +");
            System.out.println("  RMI-over-TLS encrypted, session-authorized, and audit-logged.");
            System.out.println("See Context.md for full project status (Phases 1-10 complete;");
            System.out.println("  11-12 blocked only on a real AWS deployment).");
        } catch (Exception e) {
            System.err.println("Failed to start AuthLock server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Creates a registry on the given port (matching the client/server
     * socket factories to the TLS setting), or attaches to one already
     * running in this JVM/host on that port. Keeps repeated local runs
     * (and the Phase 2 integration test) from failing with
     * "port already in use" when a registry is already up.
     */
    private static Registry getOrCreateRegistry(int port, boolean tlsEnabled) throws Exception {
        try {
            if (tlsEnabled) {
                return LocateRegistry.createRegistry(port, new SslRMIClientSocketFactory(), new SslRMIServerSocketFactory());
            }
            return LocateRegistry.createRegistry(port);
        } catch (ExportException alreadyBound) {
            if (tlsEnabled) {
                return LocateRegistry.getRegistry(null, port, new SslRMIClientSocketFactory());
            }
            return LocateRegistry.getRegistry(port);
        }
    }
}
