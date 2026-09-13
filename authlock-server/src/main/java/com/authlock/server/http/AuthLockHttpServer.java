package com.authlock.server.http;

import com.authlock.common.ErrorCode;
import com.authlock.common.FileContent;
import com.authlock.common.FileMetadata;
import com.authlock.common.VaultServiceException;
import com.authlock.common.crypto.AesGcmCipher;
import com.authlock.server.VaultServiceImpl;
import com.authlock.server.auth.User;
import com.authlock.server.crypto.EncryptionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Embedded HTTP bridge exposing {@link VaultServiceImpl} to a browser (Phase
 * 13, Architecture.md's web-UI addendum). Runs in the same process as the
 * RMI server ({@code ServerMain} constructs one of each side by side) and
 * calls {@code VaultServiceImpl}'s methods directly, in-process — not over
 * RMI loopback — so this is a second transport onto the exact same auth/
 * session/lock/vault logic the Swing client uses, not a parallel
 * implementation of it.
 *
 * <p>Built on {@link com.sun.net.httpserver.HttpServer} (JDK-bundled)
 * deliberately, not a framework — Development-rules.md's zero-runtime-
 * dependency rule applies to this project as a whole, not just the RMI
 * path. That also means JSON encoding/decoding ({@link Json}) and
 * {@code multipart/form-data} parsing ({@link MultipartParser}) are
 * hand-rolled here rather than pulled in from a library, scoped to exactly
 * the shapes this bridge needs.
 *
 * <p>Session model: on successful {@code POST /api/login}, the real
 * {@code SessionManager} token returned by {@code VaultServiceImpl.login}
 * becomes the value of an {@code HttpOnly} cookie — there is no second,
 * parallel session table here. Every other endpoint reads that cookie and
 * passes its value straight through as the {@code sessionToken} argument to
 * the corresponding {@code VaultServiceImpl} method, exactly as an RMI
 * caller would; an absent or invalid cookie surfaces as the same
 * {@link ErrorCode#INVALID_SESSION} {@link VaultServiceException} the RMI
 * path already throws; {@link #statusFor} is the only new logic, mapping
 * each {@link ErrorCode} to an HTTP status.
 *
 * <p>File payload encryption (AES-256-GCM, Security.md §7) is unrelated to
 * this HTTP server's own transport and is not this class's concern to skip
 * — {@code uploadFile}/{@code downloadFile} require pre/post-encrypted
 * bytes either way, so this bridge does exactly what the Swing client does:
 * encrypt before {@code uploadFile}, decrypt after {@code downloadFile},
 * using its own {@link EncryptionService} instance over the same shared key
 * file ({@code ServerMain} constructs it the same way
 * {@code VaultServiceImpl} constructs its own).
 */
public final class AuthLockHttpServer {

    private static final String SESSION_COOKIE = "AUTHLOCK_SESSION";
    private static final String CSRF_HEADER = "X-CSRF-Token";

    private final HttpServer httpServer;
    private final VaultServiceImpl vaultService;
    private final EncryptionService encryptionService;
    private final java.util.concurrent.ExecutorService executor;
    // Automatic, not a flag anyone sets: the `Secure` cookie attribute is
    // correct exactly when this listener is itself HTTPS (a `Secure` cookie
    // is simply never sent by the browser over a plain-HTTP connection, so
    // hardcoding it on for an HTTP-only instance would silently break every
    // login, not add protection). See the constructor.
    private final boolean secureCookie;
    // Overridable the same way VaultServiceImpl's authlock.session.* properties are
    // (see its Javadoc): not a real configuration knob, exists so a test can verify
    // throttling actually kicks in without either tripping it accidentally during
    // unrelated tests sharing one server, or waiting out a real 60-second window.
    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter(
            Integer.parseInt(System.getProperty("authlock.http.loginMaxAttempts", "5")),
            Duration.ofSeconds(Long.parseLong(System.getProperty("authlock.http.loginWindowSeconds", "60"))));
    // Per-process secret for deriving CSRF tokens from a session token via
    // HMAC — see computeCsrfToken()'s Javadoc for why this needs no session
    // table of its own.
    private final byte[] csrfSecret = new byte[32];

    /** Plain HTTP — test-only today (see class Javadoc's HTTPS note); production always uses the HTTPS constructor below. */
    public AuthLockHttpServer(VaultServiceImpl vaultService, EncryptionService encryptionService, int port) throws IOException {
        this(vaultService, encryptionService, port, null);
    }

    /**
     * HTTPS, using an {@link SSLContext} built from the SAME keystore
     * RMI-over-TLS uses ({@code DevTlsSetup.currentSslContext()} — see
     * {@code ServerMain}) — not a second, separately generated certificate.
     * {@code sslContext == null} falls back to plain HTTP, used only by
     * this class's own tests, which have no need to exercise a real TLS
     * handshake to test routing/session/CSRF/rate-limit logic.
     */
    public AuthLockHttpServer(VaultServiceImpl vaultService, EncryptionService encryptionService, int port, SSLContext sslContext) throws IOException {
        this.vaultService = vaultService;
        this.encryptionService = encryptionService;
        this.secureCookie = sslContext != null;
        new SecureRandom().nextBytes(csrfSecret);
        if (sslContext != null) {
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            this.httpServer = httpsServer;
        } else {
            this.httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        }
        httpServer.createContext("/api/login", this::handleLogin);
        httpServer.createContext("/api/logout", this::handleLogout);
        httpServer.createContext("/api/me", this::handleMe);
        httpServer.createContext("/api/files", this::handleFiles);
        httpServer.createContext("/api/admin/users", this::handleAdminUsers);
        httpServer.createContext("/", this::handleStatic);
        // Daemon worker threads: safe to leave running at JVM shutdown. The
        // HttpServer's own internal dispatcher thread (started by start(),
        // not created here) is what actually keeps the JVM alive, the same
        // role the RMI runtime's non-daemon threads already play.
        this.executor = Executors.newFixedThreadPool(8, daemonThreadFactory());
        httpServer.setExecutor(executor);
    }

    public void start() {
        httpServer.start();
    }

    /** Stops accepting new requests and shuts down the worker pool — test teardown only; production shutdown is "kill the process". */
    public void stop() {
        httpServer.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread t = new Thread(runnable, "authlock-http-worker");
            t.setDaemon(true);
            return t;
        };
    }

    // ---- /api/login ---------------------------------------------------

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use POST.");
            return;
        }
        String clientIp = clientIp(exchange);
        try {
            String body = readBody(exchange);
            Map<String, String> fields = Json.parseFlatObject(body);
            String username = fields.get("username");
            String password = fields.get("password");

            if (!loginRateLimiter.tryAcquire(clientIp)) {
                vaultService.auditLoginThrottled(username, clientIp);
                sendError(exchange, 429, "RATE_LIMITED", "Too many login attempts. Try again in a minute.");
                return;
            }

            String token = vaultService.login(username, password);
            exchange.getResponseHeaders().add("Set-Cookie", sessionCookieHeader(token));
            sendJson(exchange, 200, "{\"status\":\"ok\",\"username\":\"" + Json.escape(username)
                    + "\",\"csrfToken\":\"" + computeCsrfToken(token) + "\"}");
        } catch (VaultServiceException e) {
            sendVaultServiceException(exchange, e);
        } catch (IllegalArgumentException malformed) {
            sendError(exchange, 400, "BAD_REQUEST", "Malformed login request.");
        }
    }

    // ---- /api/logout ----------------------------------------------------

    private void handleLogout(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use POST.");
            return;
        }
        try {
            String token = sessionTokenFrom(exchange);
            requireValidCsrf(exchange, token);
            vaultService.logout(token);
            exchange.getResponseHeaders().add("Set-Cookie", clearedSessionCookieHeader());
            sendJson(exchange, 200, "{\"status\":\"ok\"}");
        } catch (VaultServiceException e) {
            sendVaultServiceException(exchange, e);
        }
    }

    // ---- /api/me ---------------------------------------------------------

    /**
     * The current session's OWN identity only — {"username":..., "userId":...}.
     * Used both for the nav bar's "username · id" display and (replacing an
     * earlier placeholder) to resume a session correctly on page reload,
     * instead of guessing a display name.
     */
    private void handleMe(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET.");
            return;
        }
        try {
            String token = sessionTokenFrom(exchange);
            User user = vaultService.whoAmI(token);
            sendJson(exchange, 200, "{\"username\":\"" + Json.escape(user.username())
                    + "\",\"userId\":\"" + Json.escape(user.userId())
                    + "\",\"role\":\"" + user.role().name()
                    + "\",\"csrfToken\":\"" + computeCsrfToken(token) + "\"}");
        } catch (VaultServiceException e) {
            sendVaultServiceException(exchange, e);
        }
    }

    // ---- /api/files, /api/files/upload, /api/files/{id}/(download|lock|unlock) ----

    private void handleFiles(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String remainder = path.length() > "/api/files".length() ? path.substring("/api/files".length()) : "";
        List<String> segments = new ArrayList<>();
        for (String s : remainder.split("/")) {
            if (!s.isBlank()) {
                segments.add(s);
            }
        }
        String method = exchange.getRequestMethod();

        try {
            if ("GET".equals(method) && segments.isEmpty()) {
                listFiles(exchange);
            } else if ("POST".equals(method) && segments.size() == 1 && "upload".equals(segments.get(0))) {
                uploadFile(exchange);
            } else if ("POST".equals(method) && segments.size() == 2 && "replace".equals(segments.get(1))) {
                replaceFile(exchange, segments.get(0));
            } else if ("GET".equals(method) && segments.size() == 2 && "download".equals(segments.get(1))) {
                downloadFile(exchange, segments.get(0));
            } else if ("GET".equals(method) && segments.size() == 2 && "versions".equals(segments.get(1))) {
                listVersions(exchange, segments.get(0));
            } else if ("GET".equals(method) && segments.size() == 4 && "versions".equals(segments.get(1)) && "download".equals(segments.get(3))) {
                downloadVersion(exchange, segments.get(0), segments.get(2));
            } else if ("POST".equals(method) && segments.size() == 2 && "lock".equals(segments.get(1))) {
                lockFile(exchange, segments.get(0));
            } else if ("POST".equals(method) && segments.size() == 2 && "unlock".equals(segments.get(1))) {
                unlockFile(exchange, segments.get(0));
            } else {
                sendError(exchange, 404, "NOT_FOUND", "No such endpoint.");
            }
        } catch (VaultServiceException e) {
            sendVaultServiceException(exchange, e);
        }
    }

    private void listFiles(HttpExchange exchange) throws IOException, VaultServiceException {
        List<FileMetadata> files = vaultService.listFiles(sessionTokenFrom(exchange));
        sendJson(exchange, 200, Json.toJsonArray(files));
    }

    private void uploadFile(HttpExchange exchange) throws IOException, VaultServiceException {
        String token = sessionTokenFrom(exchange);
        requireValidCsrf(exchange, token);
        String boundary = MultipartParser.extractBoundary(exchange.getRequestHeaders().getFirst("Content-Type"));
        if (boundary == null) {
            sendError(exchange, 400, "BAD_REQUEST", "Expected multipart/form-data with a boundary.");
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        Optional<MultipartParser.UploadedFile> uploaded = MultipartParser.parseFirstFile(body, boundary);
        if (uploaded.isEmpty()) {
            sendError(exchange, 400, "BAD_REQUEST", "No file part found in the upload.");
            return;
        }
        // Same per-hop AES-256-GCM step the Swing client performs before
        // calling uploadFile() — see class Javadoc.
        AesGcmCipher.Encrypted encrypted = encryptionService.encrypt(uploaded.get().content());
        String fileId = vaultService.uploadFile(token, uploaded.get().filename(), encrypted.ciphertext(), encrypted.iv());
        sendJson(exchange, 200, "{\"fileId\":\"" + Json.escape(fileId) + "\"}");
    }

    /** Lock-gated in-place update — see {@code VaultServiceImpl#replaceFile}'s Javadoc for why this is a separate endpoint from upload, not a parameter on it. */
    private void replaceFile(HttpExchange exchange, String fileId) throws IOException, VaultServiceException {
        String token = sessionTokenFrom(exchange);
        requireValidCsrf(exchange, token);
        String boundary = MultipartParser.extractBoundary(exchange.getRequestHeaders().getFirst("Content-Type"));
        if (boundary == null) {
            sendError(exchange, 400, "BAD_REQUEST", "Expected multipart/form-data with a boundary.");
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        Optional<MultipartParser.UploadedFile> uploaded = MultipartParser.parseFirstFile(body, boundary);
        if (uploaded.isEmpty()) {
            sendError(exchange, 400, "BAD_REQUEST", "No file part found in the upload.");
            return;
        }
        AesGcmCipher.Encrypted encrypted = encryptionService.encrypt(uploaded.get().content());
        FileMetadata updated = vaultService.replaceFile(token, fileId, encrypted.ciphertext(), encrypted.iv());
        sendJson(exchange, 200, Json.toJson(updated));
    }

    private void downloadFile(HttpExchange exchange, String fileId) throws IOException, VaultServiceException {
        String token = sessionTokenFrom(exchange);
        FileContent content = vaultService.downloadFile(token, fileId);
        byte[] plaintext = encryptionService.decrypt(content.iv(), content.fileBytes());

        String filename = queryParam(exchange, "filename").orElse(fileId).replace("\"", "");
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        setNoStore(exchange);
        exchange.sendResponseHeaders(200, plaintext.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(plaintext);
        }
    }

    private void listVersions(HttpExchange exchange, String fileId) throws IOException, VaultServiceException {
        var versions = vaultService.getFileVersions(sessionTokenFrom(exchange), fileId);
        sendJson(exchange, 200, Json.toVersionsJsonArray(versions));
    }

    private void downloadVersion(HttpExchange exchange, String fileId, String versionSegment) throws IOException, VaultServiceException {
        int versionNumber;
        try {
            versionNumber = Integer.parseInt(versionSegment);
        } catch (NumberFormatException notANumber) {
            sendError(exchange, 400, "BAD_REQUEST", "Version must be a number.");
            return;
        }
        byte[] plaintext = vaultService.downloadFileVersion(sessionTokenFrom(exchange), fileId, versionNumber);
        String filename = queryParam(exchange, "filename").orElse(fileId).replace("\"", "");
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"v" + versionNumber + "-" + filename + "\"");
        setNoStore(exchange);
        exchange.sendResponseHeaders(200, plaintext.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(plaintext);
        }
    }

    private void lockFile(HttpExchange exchange, String fileId) throws IOException, VaultServiceException {
        String token = sessionTokenFrom(exchange);
        requireValidCsrf(exchange, token);
        vaultService.lockFile(token, fileId);
        sendJson(exchange, 200, "{\"status\":\"ok\",\"fileId\":\"" + Json.escape(fileId) + "\"}");
    }

    private void unlockFile(HttpExchange exchange, String fileId) throws IOException, VaultServiceException {
        String token = sessionTokenFrom(exchange);
        requireValidCsrf(exchange, token);
        vaultService.unlockFile(token, fileId);
        sendJson(exchange, 200, "{\"status\":\"ok\",\"fileId\":\"" + Json.escape(fileId) + "\"}");
    }

    // ---- /api/admin/users, /api/admin/users/{id}/(disable|enable) — ADMIN role only, enforced in VaultServiceImpl ----

    private void handleAdminUsers(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String remainder = path.length() > "/api/admin/users".length() ? path.substring("/api/admin/users".length()) : "";
        List<String> segments = new ArrayList<>();
        for (String s : remainder.split("/")) {
            if (!s.isBlank()) {
                segments.add(s);
            }
        }
        String method = exchange.getRequestMethod();

        try {
            if ("GET".equals(method) && segments.isEmpty()) {
                adminListUsers(exchange);
            } else if ("POST".equals(method) && segments.isEmpty()) {
                adminCreateUser(exchange);
            } else if ("POST".equals(method) && segments.size() == 2 && "disable".equals(segments.get(1))) {
                adminSetStatus(exchange, segments.get(0), true);
            } else if ("POST".equals(method) && segments.size() == 2 && "enable".equals(segments.get(1))) {
                adminSetStatus(exchange, segments.get(0), false);
            } else {
                sendError(exchange, 404, "NOT_FOUND", "No such endpoint.");
            }
        } catch (VaultServiceException e) {
            sendVaultServiceException(exchange, e);
        }
    }

    private void adminListUsers(HttpExchange exchange) throws IOException, VaultServiceException {
        Collection<User> users = vaultService.adminListUsers(sessionTokenFrom(exchange));
        sendJson(exchange, 200, Json.toAdminUsersJsonArray(users));
    }

    private void adminCreateUser(HttpExchange exchange) throws IOException, VaultServiceException {
        String token = sessionTokenFrom(exchange);
        requireValidCsrf(exchange, token);
        Map<String, String> fields;
        try {
            fields = Json.parseFlatObject(readBody(exchange));
        } catch (IllegalArgumentException malformed) {
            sendError(exchange, 400, "BAD_REQUEST", "Malformed request body.");
            return;
        }
        String username = fields.get("username");
        String password = fields.get("password");
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            sendError(exchange, 400, "BAD_REQUEST", "username and password are required.");
            return;
        }
        User.Role role;
        try {
            role = User.Role.valueOf(fields.getOrDefault("role", "STANDARD").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException badRole) {
            sendError(exchange, 400, "BAD_REQUEST", "role must be ADMIN or STANDARD.");
            return;
        }
        User created = vaultService.adminCreateUser(token, username, password.toCharArray(), role);
        sendJson(exchange, 200, Json.toAdminUserJson(created));
    }

    private void adminSetStatus(HttpExchange exchange, String targetUserId, boolean disable) throws IOException, VaultServiceException {
        String token = sessionTokenFrom(exchange);
        requireValidCsrf(exchange, token);
        User updated = disable ? vaultService.adminDisableUser(token, targetUserId) : vaultService.adminEnableUser(token, targetUserId);
        sendJson(exchange, 200, Json.toAdminUserJson(updated));
    }

    // ---- static frontend (webroot/, bundled as classpath resources) ----

    private void handleStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "METHOD_NOT_ALLOWED", "Use GET.");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String resourcePath = path.equals("/") ? "index.html" : path.substring(1);
        if (resourcePath.contains("..")) {
            sendError(exchange, 400, "BAD_REQUEST", "Invalid path.");
            return;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("webroot/" + resourcePath)) {
            if (in == null) {
                sendError(exchange, 404, "NOT_FOUND", "No such resource.");
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentTypeFor(resourcePath));
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static String contentTypeFor(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    // ---- shared helpers -------------------------------------------------

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static Optional<String> queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            if (pair.substring(0, eq).equals(name)) {
                return Optional.of(java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return Optional.empty();
    }

    /** The session-cookie value if present, or {@code null} — {@code VaultServiceImpl} itself decides what a null/invalid token means. */
    private static String sessionTokenFrom(HttpExchange exchange) {
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (String header : cookieHeaders) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                if (pair.substring(0, eq).trim().equals(SESSION_COOKIE)) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    private static void sendVaultServiceException(HttpExchange exchange, VaultServiceException e) throws IOException {
        sendError(exchange, statusFor(e.getErrorCode()), e.getErrorCode().name(), e.getMessage());
    }

    /** The one piece of new policy this bridge adds: an HTTP status per {@link ErrorCode} (API-spec.md's error model has none, being RMI-native). */
    private static int statusFor(ErrorCode code) {
        return switch (code) {
            case AUTHENTICATION_FAILED, INVALID_SESSION -> 401;
            case UNAUTHORIZED -> 403;
            case FILE_NOT_FOUND, USER_NOT_FOUND -> 404;
            case FILE_LOCKED, LOCK_NOT_OWNED, USER_ALREADY_EXISTS -> 409;
            case UPLOAD_FAILED -> 400;
            case DOWNLOAD_FAILED, SERVER_ERROR -> 500;
            // Never actually thrown as a VaultServiceException today (see ErrorCode's
            // Javadoc) — handleLogin sends 429 directly — but the switch must stay
            // exhaustive over the enum.
            case RATE_LIMITED -> 429;
        };
    }

    private String sessionCookieHeader(String token) {
        return SESSION_COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict" + (secureCookie ? "; Secure" : "");
    }

    private String clearedSessionCookieHeader() {
        return SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (secureCookie ? "; Secure" : "");
    }

    /**
     * CSRF protection (synchronizer-token pattern) for every state-changing
     * endpoint (upload, lock, unlock, logout). Deliberately stateless: the
     * token is {@code HMAC-SHA256(csrfSecret, sessionToken)} rather than a
     * second value stored in a session table, so there is nothing here that
     * can go stale relative to {@code SessionManager}'s own expiry — a
     * session token that's still valid always derives the same CSRF token,
     * and one that's gone (logged out, expired) can't be paired with a
     * valid CSRF token at all. Issued to the client only in the JSON body
     * of {@code /api/login} and {@code /api/me} responses (never as a
     * cookie) — the whole point is that a cross-site form/script can make
     * the browser attach cookies automatically, but cannot read a JSON
     * response body from a different origin to learn this value.
     */
    private void requireValidCsrf(HttpExchange exchange, String sessionToken) throws VaultServiceException {
        if (sessionToken == null) {
            // No session at all: the underlying VaultServiceImpl call this
            // guards is about to throw INVALID_SESSION on its own — let
            // that be the single source of that particular error rather
            // than a different one from here.
            return;
        }
        String provided = exchange.getRequestHeaders().getFirst(CSRF_HEADER);
        String expected = computeCsrfToken(sessionToken);
        if (provided == null || !constantTimeEquals(provided, expected)) {
            throw new VaultServiceException(ErrorCode.UNAUTHORIZED, "Missing or invalid CSRF token.");
        }
    }

    private String computeCsrfToken(String sessionToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(csrfSecret, "HmacSHA256"));
            byte[] signature = mac.doFinal(sessionToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Best-effort client address for the login rate limiter — falls back to a fixed key if unavailable, degrading to one shared bucket rather than throwing. */
    private static String clientIp(HttpExchange exchange) {
        var remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    private static void sendError(HttpExchange exchange, int status, String errorCode, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":\"" + errorCode + "\",\"message\":\"" + Json.escape(message) + "\"}");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        setNoStore(exchange);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Every {@code /api/*} response — JSON or a file download — carries
     * {@code Cache-Control: no-store}. Session-scoped, per-caller data must
     * never be reusable from a shared/browser cache across a different
     * session on the same machine; the response otherwise carries no
     * validator (no {@code ETag}/{@code Last-Modified}), so without an
     * explicit directive a cache is technically permitted to apply
     * heuristic freshness to it (Phase 13 hardening — see the cross-session
     * lock-label bug investigation this closes off as a possible cause,
     * even though the server-side owner comparison itself was already
     * provably correct under two independently-cookied sessions).
     */
    private static void setNoStore(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
    }
}
