package com.authlock.common.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * RMI-over-TLS bootstrap, per Security.md §7 / ADR-007 — the transport
 * control that protects the <i>whole</i> RMI channel, including
 * {@code login()} credentials and session tokens, complementing the
 * application-layer AES-GCM file encryption ({@code AesGcmCipher}) that
 * only covers file payloads.
 *
 * <p><b>Explicit coursework/dev-only simplification</b> (documented, not
 * hidden — Security.md §7, Context.md OQ-05): both client and server share
 * one self-signed certificate, generated on first run via the JDK-bundled
 * {@code keytool} (no new dependency, no hand-rolled X.509 generation). The
 * same PKCS12 file is used as both keystore (identifies the server) and
 * truststore (client trusts that exact self-signed cert) — a real
 * deployment would use separate keystore/truststore files and a CA-issued
 * certificate. The store password below is a fixed, publicly-documented
 * dev-only value, not a real secret; it protects nothing beyond a throwaway
 * local test certificate.
 *
 * <p>{@link #configure()} must be called before any RMI registry/export/
 * lookup call — it sets the standard JSSE system properties
 * ({@code javax.net.ssl.keyStore} etc.) that {@code SslRMIServerSocketFactory}/
 * {@code SslRMIClientSocketFactory} read via {@code SSLContext.getDefault()}.
 */
public final class DevTlsSetup {

    private static final String DEFAULT_KEYSTORE_PATH = "certs/authlock-dev.p12";
    /** Fixed dev-only password — see class Javadoc. Not a production secret. */
    private static final String STORE_PASSWORD = "authlock-dev-only";
    private static final String KEY_ALIAS = "authlock-dev";

    private DevTlsSetup() {
    }

    /** Configures TLS using the default (or {@code -Dauthlock.tls.keystore=<path>}-overridden) keystore path. */
    public static void configure() throws IOException, InterruptedException {
        configure(Path.of(System.getProperty("authlock.tls.keystore", DEFAULT_KEYSTORE_PATH)));
    }

    public static void configure(Path keystorePath) throws IOException, InterruptedException {
        ensureDevCertificateExists(keystorePath);

        String absolutePath = keystorePath.toAbsolutePath().toString();
        System.setProperty("javax.net.ssl.keyStore", absolutePath);
        System.setProperty("javax.net.ssl.keyStorePassword", STORE_PASSWORD);
        System.setProperty("javax.net.ssl.keyStoreType", "PKCS12");
        // Self-signed dev cert: the same file doubles as the truststore.
        System.setProperty("javax.net.ssl.trustStore", absolutePath);
        System.setProperty("javax.net.ssl.trustStorePassword", STORE_PASSWORD);
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
    }

    /** Generates a self-signed dev certificate via {@code keytool} if none exists yet at {@code path}. */
    private static void ensureDevCertificateExists(Path path) throws IOException, InterruptedException {
        if (Files.exists(path)) {
            return;
        }
        if (path.toAbsolutePath().getParent() != null) {
            Files.createDirectories(path.toAbsolutePath().getParent());
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", KEY_ALIAS,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
                "-keystore", path.toString(),
                "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD,
                "-dname", "CN=localhost, OU=AuthLock Coursework, O=AuthLock, C=US",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1"
        );
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("keytool failed to generate the AuthLock dev TLS certificate (exit "
                    + exitCode + "): " + output);
        }
    }
}
