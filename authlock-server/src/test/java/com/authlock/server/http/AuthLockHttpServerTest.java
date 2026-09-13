package com.authlock.server.http;

import com.authlock.server.VaultServiceImpl;
import com.authlock.server.crypto.EncryptionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the cross-session lock-label bug report (Phase 13
 * follow-up): "logged in as bob, a file locked by alice showed as
 * LOCKED (YOU)". {@link com.authlock.server.VaultServiceImpl}'s own
 * {@code toDto} comparison already had RMI-level coverage
 * ({@code VaultServiceLockIntegrationTest#listFilesReflectsRealLockStateAndOwnerHint})
 * and was proven correct — this class exists because {@link AuthLockHttpServer}
 * itself (cookie parsing, session-token plumbing, JSON serialization) had
 * <b>zero</b> test coverage before this bug report, and that HTTP layer is
 * the one a real browser actually exercises. Uses two independent
 * {@link HttpClient} instances, each with its own {@link CookieManager} —
 * the same isolation a real second browser (or an incognito window)
 * provides — specifically to rule out any accidental session-state sharing
 * between concurrent callers.
 */
class AuthLockHttpServerTest {

    private static final String VAULT_DIR_PROPERTY = "authlock.vault.dir";
    private static final String KEY_FILE_PROPERTY = "authlock.crypto.keyfile";
    private static final String AUDIT_FILE_PROPERTY = "authlock.audit.file";
    private static final String LOGIN_MAX_ATTEMPTS_PROPERTY = "authlock.http.loginMaxAttempts";

    private static VaultServiceImpl vaultService;
    private static AuthLockHttpServer httpServer;
    private static String previousVaultDirProperty;
    private static String previousKeyFileProperty;
    private static String previousAuditFileProperty;
    private static String previousLoginMaxAttemptsProperty;

    @TempDir
    static Path tempVaultDir;
    @TempDir
    static Path tempKeyDir;
    @TempDir
    static Path tempAuditDir;

    @BeforeAll
    static void setUpServer() throws Exception {
        previousVaultDirProperty = System.getProperty(VAULT_DIR_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, tempVaultDir.toString());
        previousKeyFileProperty = System.getProperty(KEY_FILE_PROPERTY);
        Path keyFile = tempKeyDir.resolve("test-shared.key");
        System.setProperty(KEY_FILE_PROPERTY, keyFile.toString());
        previousAuditFileProperty = System.getProperty(AUDIT_FILE_PROPERTY);
        System.setProperty(AUDIT_FILE_PROPERTY, tempAuditDir.resolve("audit.log").toString());
        // High, not disabled: every test method in this class shares one server/one
        // client IP (localhost), so the sum of every login() call across the whole
        // class must stay under the real default (5/min) or unrelated tests would
        // start failing on 429s that have nothing to do with what they're testing.
        // The rate limiter's actual behavior is verified in its own isolated server
        // in loginIsRateLimitedAfterTooManyAttempts() below, at the real default.
        previousLoginMaxAttemptsProperty = System.getProperty(LOGIN_MAX_ATTEMPTS_PROPERTY);
        System.setProperty(LOGIN_MAX_ATTEMPTS_PROPERTY, "1000");

        vaultService = new VaultServiceImpl(0); // ephemeral RMI export port; no registry needed — we talk HTTP, not RMI
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

    /** A fresh HttpClient with its own cookie jar — the HTTP-level equivalent of "a separate browser/incognito window". */
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

    private static HttpResponse<String> getFiles(HttpClient client) throws Exception {
        return get(client, "/api/files");
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Every state-changing call needs the CSRF token from the login (or /api/me) response — see AuthLockHttpServer#requireValidCsrf. */
    private static String csrfTokenFrom(HttpResponse<String> loginOrMeResponse) {
        Matcher matcher = Pattern.compile("\"csrfToken\":\"([^\"]+)\"").matcher(loginOrMeResponse.body());
        assertTrue(matcher.find(), "no csrfToken in response: " + loginOrMeResponse.body());
        return matcher.group(1);
    }

    private static HttpResponse<String> postNoBody(HttpClient client, String path, String csrfToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String upload(HttpClient client, String filename, byte[] content, String csrfToken) throws Exception {
        String boundary = "----AuthLockTestBoundary" + System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/files/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, filename, content)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "upload failed: " + response.body());
        Matcher matcher = Pattern.compile("\"fileId\":\"([^\"]+)\"").matcher(response.body());
        assertTrue(matcher.find(), "no fileId in upload response: " + response.body());
        return matcher.group(1);
    }

    private static byte[] multipartBody(String boundary, String filename, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Extracts the JSON object for a given fileId out of a /api/files array response, for a targeted field assertion. */
    private static String fileObjectFor(String filesJsonArray, String fileId) {
        Matcher matcher = Pattern.compile(Pattern.quote("{\"fileId\":\"" + fileId + "\"") + ".*?\\}").matcher(filesJsonArray);
        assertTrue(matcher.find(), "fileId " + fileId + " not found in response: " + filesJsonArray);
        return matcher.group();
    }

    @Test
    void bobsSessionNeverShowsAlicesLockAsHisOwn() throws Exception {
        HttpClient aliceClient = newIsolatedClient();
        HttpClient bobClient = newIsolatedClient();

        HttpResponse<String> aliceLogin = login(aliceClient, "alice", "Alice2026Pass");
        HttpResponse<String> bobLogin = login(bobClient, "bob", "Bob2026Pass");
        assertEquals(200, aliceLogin.statusCode());
        assertEquals(200, bobLogin.statusCode());
        String aliceCsrf = csrfTokenFrom(aliceLogin);
        String bobCsrf = csrfTokenFrom(bobLogin);

        String fileId = upload(aliceClient, "regression.txt", "regression test content".getBytes(StandardCharsets.UTF_8), aliceCsrf);
        assertEquals(200, postNoBody(aliceClient, "/api/files/" + fileId + "/lock", aliceCsrf).statusCode());

        // Alice's own view of her own lock: "you".
        String aliceView = fileObjectFor(getFiles(aliceClient).body(), fileId);
        assertTrue(aliceView.contains("\"lockState\":\"LOCKED\""));
        assertTrue(aliceView.contains("\"lockOwnerHint\":\"you\""), "alice, the actual lock holder, should see 'you': " + aliceView);

        // Bob's INDEPENDENT session (separate cookie jar) viewing the exact same file:
        // must say "another user" and must NEVER say "you" — this is the reported bug.
        String bobView = fileObjectFor(getFiles(bobClient).body(), fileId);
        assertTrue(bobView.contains("\"lockState\":\"LOCKED\""));
        assertTrue(bobView.contains("\"lockOwnerHint\":\"another user\""),
                "bob must see 'another user', not alice's actual identity: " + bobView);
        assertFalse(bobView.contains("\"lockOwnerHint\":\"you\""),
                "REGRESSION: bob's session must never see someone else's lock labeled '(you)': " + bobView);

        // And bob's write attempts on alice's lock must be rejected, not silently allowed
        // (a valid CSRF token of bob's own — this must fail on lock ownership, not CSRF).
        HttpResponse<String> bobUnlock = postNoBody(bobClient, "/api/files/" + fileId + "/unlock", bobCsrf);
        assertEquals(409, bobUnlock.statusCode(), bobUnlock.body());
        assertTrue(bobUnlock.body().contains("LOCK_NOT_OWNED"), bobUnlock.body());
    }

    @Test
    void stateChangingRequestsAreRejectedWithoutAValidCsrfToken() throws Exception {
        HttpClient client = newIsolatedClient();
        HttpResponse<String> loginResponse = login(client, "alice", "Alice2026Pass");
        assertEquals(200, loginResponse.statusCode());
        String realCsrf = csrfTokenFrom(loginResponse);

        String fileId = upload(client, "csrf-test.txt", "x".getBytes(StandardCharsets.UTF_8), realCsrf);

        // No header at all.
        HttpRequest noToken = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/files/" + fileId + "/lock"))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(403, client.send(noToken, HttpResponse.BodyHandlers.ofString()).statusCode());

        // Wrong/forged token.
        HttpResponse<String> forged = postNoBody(client, "/api/files/" + fileId + "/lock", "not-the-real-token");
        assertEquals(403, forged.statusCode());

        // The real token still works.
        assertEquals(200, postNoBody(client, "/api/files/" + fileId + "/lock", realCsrf).statusCode());
    }

    @Test
    void meReturnsOnlyTheCallersOwnIdentityNeverAnotherUsers() throws Exception {
        HttpClient aliceClient = newIsolatedClient();
        HttpClient bobClient = newIsolatedClient();
        assertEquals(200, login(aliceClient, "alice", "Alice2026Pass").statusCode());
        assertEquals(200, login(bobClient, "bob", "Bob2026Pass").statusCode());

        HttpResponse<String> aliceMe = get(aliceClient, "/api/me");
        assertEquals(200, aliceMe.statusCode());
        assertTrue(aliceMe.body().contains("\"username\":\"alice\""));
        assertFalse(aliceMe.body().contains("bob"), "alice's /api/me must never mention bob: " + aliceMe.body());

        HttpResponse<String> bobMe = get(bobClient, "/api/me");
        assertEquals(200, bobMe.statusCode());
        assertTrue(bobMe.body().contains("\"username\":\"bob\""));
        assertFalse(bobMe.body().contains("alice"), "bob's /api/me must never mention alice: " + bobMe.body());

        Matcher aliceId = Pattern.compile("\"userId\":\"([^\"]+)\"").matcher(aliceMe.body());
        Matcher bobId = Pattern.compile("\"userId\":\"([^\"]+)\"").matcher(bobMe.body());
        assertTrue(aliceId.find());
        assertTrue(bobId.find());
        assertFalse(aliceId.group(1).equals(bobId.group(1)), "alice and bob must have distinct userIds");
    }

    @Test
    void meWithoutASessionIsRejected() throws Exception {
        assertEquals(401, get(newIsolatedClient(), "/api/me").statusCode());
    }

    @Test
    void replaceIsGatedOnHoldingTheLockAndActuallyUpdatesContentInPlace() throws Exception {
        HttpClient aliceClient = newIsolatedClient();
        HttpClient bobClient = newIsolatedClient();
        HttpResponse<String> aliceLogin = login(aliceClient, "alice", "Alice2026Pass");
        HttpResponse<String> bobLogin = login(bobClient, "bob", "Bob2026Pass");
        String aliceCsrf = csrfTokenFrom(aliceLogin);
        String bobCsrf = csrfTokenFrom(bobLogin);

        String fileId = upload(aliceClient, "replace-test.txt", "original content".getBytes(StandardCharsets.UTF_8), aliceCsrf);

        // Not locked at all yet: even the uploader can't replace without holding the lock.
        HttpResponse<String> beforeLock = replace(aliceClient, fileId, "too early".getBytes(StandardCharsets.UTF_8), aliceCsrf);
        assertEquals(409, beforeLock.statusCode(), beforeLock.body());
        assertTrue(beforeLock.body().contains("LOCK_NOT_OWNED"), beforeLock.body());

        assertEquals(200, postNoBody(aliceClient, "/api/files/" + fileId + "/lock", aliceCsrf).statusCode());

        // Bob doesn't hold the lock (alice does) — rejected.
        HttpResponse<String> bobAttempt = replace(bobClient, fileId, "bob was here".getBytes(StandardCharsets.UTF_8), bobCsrf);
        assertEquals(409, bobAttempt.statusCode(), bobAttempt.body());
        assertTrue(bobAttempt.body().contains("LOCK_NOT_OWNED"), bobAttempt.body());

        // Alice, the actual lock holder, succeeds — same fileId, content actually changes.
        HttpResponse<String> aliceReplace = replace(aliceClient, fileId, "replaced content".getBytes(StandardCharsets.UTF_8), aliceCsrf);
        assertEquals(200, aliceReplace.statusCode(), aliceReplace.body());
        assertTrue(aliceReplace.body().contains("\"fileId\":\"" + fileId + "\""), "replace must return the SAME fileId: " + aliceReplace.body());

        HttpRequest download = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/files/" + fileId + "/download")).GET().build();
        HttpResponse<byte[]> downloaded = aliceClient.send(download, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, downloaded.statusCode());
        assertEquals("replaced content", new String(downloaded.body(), StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> replace(HttpClient client, String fileId, byte[] content, String csrfToken) throws Exception {
        String boundary = "----AuthLockTestBoundary" + System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/files/" + fileId + "/replace"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, "replace-test.txt", content)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void versionHistoryRecordsEachReplaceInOrderWithCorrectAttributionAndDownloadableContent() throws Exception {
        HttpClient aliceClient = newIsolatedClient();
        HttpClient bobClient = newIsolatedClient();
        String aliceCsrf = csrfTokenFrom(login(aliceClient, "alice", "Alice2026Pass"));
        String bobCsrf = csrfTokenFrom(login(bobClient, "bob", "Bob2026Pass"));

        String fileId = upload(aliceClient, "history-test.txt", "version 0 (original)".getBytes(StandardCharsets.UTF_8), aliceCsrf);

        // No versions yet — never replaced.
        assertEquals("[]", get(aliceClient, "/api/files/" + fileId + "/versions").body());

        // alice locks + replaces (archives "version 0" as version 1, replacedBy=alice)
        assertEquals(200, postNoBody(aliceClient, "/api/files/" + fileId + "/lock", aliceCsrf).statusCode());
        assertEquals(200, replace(aliceClient, fileId, "version 1".getBytes(StandardCharsets.UTF_8), aliceCsrf).statusCode());
        assertEquals(200, postNoBody(aliceClient, "/api/files/" + fileId + "/unlock", aliceCsrf).statusCode());

        // bob locks + replaces (archives "version 1" as version 2, replacedBy=bob)
        assertEquals(200, postNoBody(bobClient, "/api/files/" + fileId + "/lock", bobCsrf).statusCode());
        assertEquals(200, replace(bobClient, fileId, "version 2".getBytes(StandardCharsets.UTF_8), bobCsrf).statusCode());
        assertEquals(200, postNoBody(bobClient, "/api/files/" + fileId + "/unlock", bobCsrf).statusCode());

        // alice locks + replaces again (archives "version 2" as version 3, replacedBy=alice)
        assertEquals(200, postNoBody(aliceClient, "/api/files/" + fileId + "/lock", aliceCsrf).statusCode());
        assertEquals(200, replace(aliceClient, fileId, "version 3 (current)".getBytes(StandardCharsets.UTF_8), aliceCsrf).statusCode());

        String versionsJson = get(aliceClient, "/api/files/" + fileId + "/versions").body();
        Matcher versionNumbers = Pattern.compile("\"versionNumber\":(\\d+)").matcher(versionsJson);
        Matcher replacers = Pattern.compile("\"replacedBy\":\"([^\"]+)\"").matcher(versionsJson);
        List<Integer> numbers = new ArrayList<>();
        List<String> replacedBy = new ArrayList<>();
        while (versionNumbers.find()) {
            numbers.add(Integer.parseInt(versionNumbers.group(1)));
        }
        while (replacers.find()) {
            replacedBy.add(replacers.group(1));
        }
        assertEquals(List.of(1, 2, 3), numbers, "exactly 3 archived versions, in order: " + versionsJson);
        assertEquals(List.of("alice", "bob", "alice"), replacedBy, "each version attributed to whoever replaced it: " + versionsJson);

        // Download version 1 specifically — must be the ORIGINAL content, not current.
        HttpRequest downloadV1 = HttpRequest.newBuilder(
                URI.create(baseUrl() + "/api/files/" + fileId + "/versions/1/download")).GET().build();
        HttpResponse<byte[]> v1 = aliceClient.send(downloadV1, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, v1.statusCode());
        assertEquals("version 0 (original)", new String(v1.body(), StandardCharsets.UTF_8));

        // Current live content is unaffected by the version archive.
        HttpRequest downloadCurrent = HttpRequest.newBuilder(
                URI.create(baseUrl() + "/api/files/" + fileId + "/download")).GET().build();
        HttpResponse<byte[]> current = aliceClient.send(downloadCurrent, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("version 3 (current)", new String(current.body(), StandardCharsets.UTF_8));
    }

    @Test
    void loginIsRateLimitedAfterTooManyAttempts() throws Exception {
        // Deliberately its own server/temp dirs/property scope — isolated from the
        // shared class-level instance above, at the REAL default (5/min), not the
        // 1000 override that instance uses so unrelated tests don't trip it.
        String previous = System.getProperty(LOGIN_MAX_ATTEMPTS_PROPERTY);
        System.setProperty(LOGIN_MAX_ATTEMPTS_PROPERTY, "3");
        Path localVaultDir = tempVaultDir.resolve("rate-limit-test-vault");
        Path localKeyFile = tempKeyDir.resolve("rate-limit-test.key");
        String previousVault = System.getProperty(VAULT_DIR_PROPERTY);
        String previousKey = System.getProperty(KEY_FILE_PROPERTY);
        System.setProperty(VAULT_DIR_PROPERTY, localVaultDir.toString());
        System.setProperty(KEY_FILE_PROPERTY, localKeyFile.toString());

        VaultServiceImpl localVault = new VaultServiceImpl(0);
        AuthLockHttpServer localServer = new AuthLockHttpServer(localVault, new EncryptionService(localKeyFile), 0);
        localServer.start();
        try {
            HttpClient client = newIsolatedClient();
            String url = "http://localhost:" + localServer.port() + "/api/login";
            for (int i = 0; i < 3; i++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\",\"password\":\"wrong\"}"))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(401, response.statusCode(), "attempt " + i + " should be a normal auth failure, not yet throttled");
            }
            HttpRequest fourthAttempt = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\",\"password\":\"Alice2026Pass\"}"))
                    .build();
            HttpResponse<String> throttled = client.send(fourthAttempt, HttpResponse.BodyHandlers.ofString());
            assertEquals(429, throttled.statusCode(),
                    "the 4th attempt within the window must be throttled even with the CORRECT password: " + throttled.body());
            assertTrue(throttled.body().contains("RATE_LIMITED"), throttled.body());
        } finally {
            localServer.stop();
            UnicastRemoteObject.unexportObject(localVault, true);
            restoreProperty(LOGIN_MAX_ATTEMPTS_PROPERTY, previous);
            restoreProperty(VAULT_DIR_PROPERTY, previousVault);
            restoreProperty(KEY_FILE_PROPERTY, previousKey);
        }
    }

    @Test
    void errorResponsesDoNotLeakMoreThanTheRmiPathDoes() throws Exception {
        // SEC-001 (AuthenticationService's Javadoc): unknown username and wrong
        // password must be indistinguishable. Verify the HTTP layer doesn't
        // accidentally add detail the RMI VaultServiceException message doesn't have.
        HttpResponse<String> unknownUser = login(newIsolatedClient(), "no-such-user", "whatever");
        HttpResponse<String> wrongPassword = login(newIsolatedClient(), "alice", "wrong");
        assertEquals(401, unknownUser.statusCode());
        assertEquals(401, wrongPassword.statusCode());
        assertEquals(unknownUser.body(), wrongPassword.body(),
                "an unknown username and a known username/wrong password must produce byte-identical responses");
        assertFalse(unknownUser.body().toLowerCase().contains("no-such-user"),
                "the response must never echo back whether the submitted username was recognized");
    }

    @Test
    void loginResponseCarriesNoStoreSoASharedOrBrowserCacheCanNeverReplaySessionData() throws Exception {
        HttpClient client = newIsolatedClient();
        HttpResponse<String> loginResponse = login(client, "alice", "Alice2026Pass");
        assertEquals(200, loginResponse.statusCode());
        assertEquals("no-store, must-revalidate", loginResponse.headers().firstValue("Cache-Control").orElse(null));

        HttpResponse<String> filesResponse = getFiles(client);
        assertEquals("no-store, must-revalidate", filesResponse.headers().firstValue("Cache-Control").orElse(null));
    }
}
