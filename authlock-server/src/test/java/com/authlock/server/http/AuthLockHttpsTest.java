package com.authlock.server.http;

import com.authlock.common.tls.DevTlsSetup;
import com.authlock.server.VaultServiceImpl;
import com.authlock.server.crypto.EncryptionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.rmi.server.UnicastRemoteObject;
import java.security.KeyStore;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actually exercises a real TLS handshake against {@link AuthLockHttpServer}'s
 * HTTPS constructor and {@link HttpsRedirectServer}'s plain-HTTP redirect —
 * not just "it compiles". Uses a real {@link SSLContext} built from a fresh
 * dev keystore (via {@link DevTlsSetup}, the same code path {@code
 * ServerMain} uses in production) on the client side too, configured to
 * trust exactly that self-signed cert — the same trust relationship a
 * browser gets after clicking through the "not secure" warning.
 */
class AuthLockHttpsTest {

    private static VaultServiceImpl vaultService;
    private static AuthLockHttpServer httpsServer;
    private static HttpsRedirectServer redirectServer;
    private static HttpClient trustingClient;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        Path vaultDir = tempDir.resolve("vault");
        Path keyFile = tempDir.resolve("shared.key");
        Path keystorePath = tempDir.resolve("test-tls.p12");
        System.setProperty("authlock.vault.dir", vaultDir.toString());
        System.setProperty("authlock.crypto.keyfile", keyFile.toString());
        System.setProperty("authlock.audit.file", tempDir.resolve("audit.log").toString());

        DevTlsSetup.configure(keystorePath); // generates the cert + sets javax.net.ssl.keyStore* system properties
        SSLContext serverSslContext = DevTlsSetup.currentSslContext();

        vaultService = new VaultServiceImpl(0);
        httpsServer = new AuthLockHttpServer(vaultService, new EncryptionService(keyFile), 0, serverSslContext);
        httpsServer.start();
        redirectServer = new HttpsRedirectServer(0, httpsServer.port());
        redirectServer.start();

        // Client trusts exactly the keystore's own cert (same file, used as a truststore
        // this time) — this IS the self-signed trust relationship, not a bypass of it.
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystorePath)) {
            trustStore.load(in, "authlock-dev-only".toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext clientSslContext = SSLContext.getInstance("TLS");
        clientSslContext.init(null, tmf.getTrustManagers(), null);
        trustingClient = HttpClient.newBuilder().sslContext(clientSslContext).build();
    }

    @AfterAll
    static void tearDown() throws Exception {
        httpsServer.stop();
        redirectServer.stop();
        UnicastRemoteObject.unexportObject(vaultService, true);
    }

    @Test
    void httpsListenerCompletesARealTlsHandshakeAndServesTheApi() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + httpsServer.port() + "/api/files")).GET().build();
        HttpResponse<String> response = trustingClient.send(request, HttpResponse.BodyHandlers.ofString());
        // 401 (no session) is fine here — the point is the TLS handshake succeeded
        // and the request reached the real routing/handler, not that it's authenticated.
        assertEquals(401, response.statusCode());
    }

    @Test
    void plainHttpListenerOnlyRedirectsNeverServesTheApi() throws Exception {
        HttpClient nonFollowingClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + redirectServer.port() + "/api/files")).GET().build();
        HttpResponse<String> response = nonFollowingClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(301, response.statusCode());
        String location = response.headers().firstValue("Location").orElseThrow();
        assertTrue(location.startsWith("https://"), "must redirect to https://, got: " + location);
        assertTrue(location.contains(":" + httpsServer.port() + "/api/files"), "must preserve the path: " + location);
    }
}
