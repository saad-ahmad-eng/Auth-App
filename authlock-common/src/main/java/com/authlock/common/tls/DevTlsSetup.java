package com.authlock.common.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

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
 *
 * <p><b>Phase 11 addition:</b> the generated certificate's Subject
 * Alternative Names default to {@code dns:localhost,ip:127.0.0.1} —
 * correct for local development, but a real remote client verifying TLS
 * against a cloud VM's public IP/hostname needs that address in the SAN
 * too (RMI's stub-hostname pitfall already fixed in {@code ServerMain} has
 * a TLS-certificate equivalent — Architecture.md §3, Security.md §7 both
 * flagged this as an explicit, not-yet-done Phase 11 task before now).
 * Set {@code -Dauthlock.tls.extraSan=<entry>[,<entry>...]} (each entry a
 * {@code keytool}-style {@code dns:<host>} or {@code ip:<addr>}) before the
 * <i>first</i> run on a given keystore path — the certificate is generated
 * once and reused thereafter, so this only takes effect while the file
 * doesn't exist yet (delete an existing keystore to regenerate with a
 * changed SAN). Unset (the default, and every existing local/test usage)
 * behaves exactly as before this addition.
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

    /**
     * Builds an {@link SSLContext} from whatever keystore {@link #configure()}
     * last set via the {@code javax.net.ssl.keyStore*} system properties.
     * Added for the web UI's HTTPS listener (Phase 13 follow-up) to reuse the
     * exact same certificate RMI-over-TLS uses — confirmed safe: {@code
     * keytool -genkeypair} here sets no restrictive {@code KeyUsage}/{@code
     * ExtendedKeyUsage} extension (only {@code SAN}), so the cert isn't
     * scoped to one TLS role. {@link #configure()} must have already run.
     */
    public static SSLContext currentSslContext() throws GeneralSecurityException, IOException {
        String path = System.getProperty("javax.net.ssl.keyStore");
        String password = System.getProperty("javax.net.ssl.keyStorePassword");
        String type = System.getProperty("javax.net.ssl.keyStoreType", "PKCS12");
        if (path == null || password == null) {
            throw new IllegalStateException("DevTlsSetup.configure() must run before currentSslContext().");
        }
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            keyStore.load(in, password.toCharArray());
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
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
                "-ext", "SAN=" + subjectAlternativeNames()
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

    /**
     * Base SAN entries ({@code dns:localhost,ip:127.0.0.1}), plus whatever
     * {@code -Dauthlock.tls.extraSan} contributes (see class Javadoc) — e.g.
     * a cloud VM's public IP so a genuinely remote client's TLS handshake
     * can verify the certificate against the host it actually connected to.
     */
    private static String subjectAlternativeNames() {
        String base = "dns:localhost,ip:127.0.0.1";
        String extra = System.getProperty("authlock.tls.extraSan", "").trim();
        return extra.isEmpty() ? base : base + "," + extra;
    }
}
