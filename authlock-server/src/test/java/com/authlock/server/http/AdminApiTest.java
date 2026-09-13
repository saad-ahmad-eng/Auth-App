package com.authlock.server.http;

import com.authlock.server.VaultServiceImpl;
import com.authlock.server.crypto.EncryptionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.rmi.server.UnicastRemoteObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 13 follow-up: the admin panel — role gating, account creation, and
 * disable/enable's effect on both future logins AND an already-established
 * session. Uses its own server instance (own temp dirs, including {@code
 * authlock.users.file} — the ONE test class that actually exercises
 * account mutation, so the ONE that needs an isolated runtime accounts
 * file; every other test class never calls these endpoints and so never
 * even creates that file, per {@code UserStore}'s lazy-persistence design).
 */
class AdminApiTest {

    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";
    private static final String KEY_FILE_PROPERTY = "authlock.crypto.keyfile";
    private static final String AUDIT_FILE_PROPERTY = "authlock.audit.file";
    private static final String USERS_FILE_PROPERTY = "authlock.users.file";
    private static final String LOGIN_MAX_ATTEMPTS_PROPERTY = "authlock.http.loginMaxAttempts";

    private static VaultServiceImpl vaultService;
    private static AuthLockHttpServer httpServer;
    private static String previousVaultDirProperty;
    private static String previousKeyFileProperty;
    private static String previousAuditFileProperty;
    private static String previousUsersFileProperty;
    private static String previousLoginMaxAttemptsProperty;

    @TempDir
    static Path tempVaultDir;
    @TempDir
    static Path tempKeyDir;
    @TempDir
    static Path tempAuditDir;
    @TempDir
    static Path tempUsersDir;

    @BeforeAll
    static void setUpServer() throws Exception {
        previousVaultDirProperty = System.getProperty(VAULT_DIR_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, tempVaultDir.toString());
        previousKeyFileProperty = System.getProperty(KEY_FILE_PROPERTY);
        Path keyFile = tempKeyDir.resolve("test-shared.key");
        System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
        previousAuditFileProperty = System.getProperty(AUDIT_FILE_PROPERTY);
        System.setProperty(AUDIT_FILE_PROPERTY, tempAuditDir.resolve("audit.log").toString());
        previousUsersFileProperty = System.getProperty(USERS_FILE_PROPERTY);
        System.setProperty(USERS_FILE_PROPERTY, tempUsersDir.resolve("users.properties").toString());
        // This class's several tests share one server/one client IP and between them log
        // in well more than the real 5/min default — see AuthLockHttpServerTest's identical
        // note; the rate limiter's own behavior has its own dedicated, isolated test.
        previousLoginMaxAttemptsProperty = System.getProperty(LOGIN_MAX_ATTEMPTS_PROPERTY);
        System.setProperty(LOGIN_MAX_ATTEMPTS_PROPERTY, "1000");

        vaultService = new VaultServiceImpl(0);
        httpServer = new AuthLockHttpServer(vaultService, new EncryptionService(keyFile), 0);
        httpServer.start();
    }

    @AfterAll
    static void tearDownServer() throws Exception {
        httpServer.stop();
        UnicastRemoteObject.unexportObject(vaultService, true);
        restoreProperty(VAULT_DIR_PROPERTY, previousVaultDirProperty);
        restoreProperty(KEY_FILE_PROPERTY, previousKeyFileProperty);
        restoreProperty(AUDIT_FILE_PROPERTY, previousAuditFileProperty);
        restoreProperty(USERS_FILE_PROPERTY, previousUsersFileProperty);
        restoreProperty(LOGIN_MAX_ATTEMPTS_PROPERTY, previousLoginMaxAttemptsProperty);
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    private static String baseUrl() {
        return "http://localhost:" + httpServer.port();
    }

    private static HttpClient newIsolatedClient() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookieManager).build();
    }

    private static HttpResponse<String> login(HttpClient client, String username, String password) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String csrfTokenFrom(HttpResponse<String> response) {
        Matcher matcher = Pattern.compile("\"csrfToken\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(matcher.find(), "no csrfToken in: " + response.body());
        return matcher.group(1);
    }

    private static String fieldFrom(HttpResponse<String> response, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(matcher.find(), "no " + field + " in: " + response.body());
        return matcher.group(1);
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(HttpClient client, String path, String json, String csrfToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postNoBody(HttpClient client, String path, String csrfToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void nonAdminIsRejectedFromEveryAdminEndpoint() throws Exception {
        HttpClient aliceClient = newIsolatedClient();
        HttpResponse<String> aliceLogin = login(aliceClient, "alice", "Alice2026Pass");
        String aliceCsrf = csrfTokenFrom(aliceLogin);

        assertEquals(403, get(aliceClient, "/api/admin/users").statusCode());
        assertEquals(403, postJson(aliceClient, "/api/admin/users",
                "{\"username\":\"x\",\"password\":\"Whatever2026\"}", aliceCsrf).statusCode());
        assertEquals(403, postNoBody(aliceClient, "/api/admin/users/some-id/disable", aliceCsrf).statusCode());
        assertEquals(403, postNoBody(aliceClient, "/api/admin/users/some-id/enable", aliceCsrf).statusCode());
    }

    @Test
    void adminCanCreateAUserWhoCanImmediatelyLogIn() throws Exception {
        HttpClient adminClient = newIsolatedClient();
        String adminCsrf = csrfTokenFrom(login(adminClient, "admin", "Admin2026Pass"));

        HttpResponse<String> createResponse = postJson(adminClient, "/api/admin/users",
                "{\"username\":\"charlie\",\"password\":\"Charlie2026Pass\",\"role\":\"STANDARD\"}", adminCsrf);
        assertEquals(200, createResponse.statusCode(), createResponse.body());
        assertFalse(createResponse.body().toLowerCase().contains("charlie2026pass"),
                "the response must never echo back the plaintext password: " + createResponse.body());
        assertTrue(createResponse.body().contains("\"username\":\"charlie\""));
        assertTrue(createResponse.body().contains("\"status\":\"ACTIVE\""));

        HttpClient charlieClient = newIsolatedClient();
        assertEquals(200, login(charlieClient, "charlie", "Charlie2026Pass").statusCode());
    }

    @Test
    void creatingADuplicateUsernameIsRejected() throws Exception {
        HttpClient adminClient = newIsolatedClient();
        String adminCsrf = csrfTokenFrom(login(adminClient, "admin", "Admin2026Pass"));
        assertEquals(409, postJson(adminClient, "/api/admin/users",
                "{\"username\":\"alice\",\"password\":\"Whatever2026\"}", adminCsrf).statusCode());
    }

    @Test
    void disablingAUserKillsTheirExistingSessionAndBlocksLoginUntilReEnabled() throws Exception {
        HttpClient adminClient = newIsolatedClient();
        String adminCsrf = csrfTokenFrom(login(adminClient, "admin", "Admin2026Pass"));

        HttpResponse<String> createResponse = postJson(adminClient, "/api/admin/users",
                "{\"username\":\"dana\",\"password\":\"Dana2026Pass\",\"role\":\"STANDARD\"}", adminCsrf);
        assertEquals(200, createResponse.statusCode());
        String danaUserId = fieldFrom(createResponse, "userId");

        HttpClient danaClient = newIsolatedClient();
        assertEquals(200, login(danaClient, "dana", "Dana2026Pass").statusCode());
        // dana's session is good right now.
        assertEquals(200, get(danaClient, "/api/files").statusCode());

        HttpResponse<String> disableResponse = postNoBody(adminClient, "/api/admin/users/" + danaUserId + "/disable", adminCsrf);
        assertEquals(200, disableResponse.statusCode(), disableResponse.body());
        assertTrue(disableResponse.body().contains("\"status\":\"DISABLED\""));

        // dana's PRE-EXISTING session must now be dead — not just future logins blocked.
        assertEquals(401, get(danaClient, "/api/files").statusCode(),
                "a disabled user's existing session must be invalidated immediately");

        // A fresh login attempt must also fail while disabled.
        HttpClient danaRetryClient = newIsolatedClient();
        assertEquals(401, login(danaRetryClient, "dana", "Dana2026Pass").statusCode());

        // Re-enable — login works again.
        HttpResponse<String> enableResponse = postNoBody(adminClient, "/api/admin/users/" + danaUserId + "/enable", adminCsrf);
        assertEquals(200, enableResponse.statusCode());
        assertTrue(enableResponse.body().contains("\"status\":\"ACTIVE\""));
        HttpClient danaAgainClient = newIsolatedClient();
        assertEquals(200, login(danaAgainClient, "dana", "Dana2026Pass").statusCode());
    }
}
