package com.authlock.server.audit;

import com.authlock.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuditLogger}, covering Security.md §9's schema and
 * ADR-008's append-only, one-JSON-object-per-line design.
 */
class AuditLoggerTest {

    @Test
    void successEventContainsExpectedFields(@TempDir Path dir) throws IOException {
        AuditLogger logger = new AuditLogger(dir.resolve("audit.log"));

        logger.logSuccess(AuditEventType.LOGIN, "user-1", "login", null, "127.0.0.1");

        String line = onlyLine(dir.resolve("audit.log"));
        assertTrue(line.contains("\"eventType\":\"LOGIN\""));
        assertTrue(line.contains("\"userId\":\"user-1\""));
        assertTrue(line.contains("\"operation\":\"login\""));
        assertTrue(line.contains("\"result\":\"SUCCESS\""));
        assertTrue(line.contains("\"errorCode\":null"));
        assertTrue(line.contains("\"clientInfo\":\"127.0.0.1\""));
        assertTrue(line.contains("\"timestamp\":\""));
    }

    @Test
    void failureEventIncludesTheErrorCode(@TempDir Path dir) throws IOException {
        AuditLogger logger = new AuditLogger(dir.resolve("audit.log"));

        logger.logFailure(AuditEventType.UPLOAD, "user-1", "uploadFile", "file-1", ErrorCode.UPLOAD_FAILED, "127.0.0.1");

        String line = onlyLine(dir.resolve("audit.log"));
        assertTrue(line.contains("\"result\":\"FAILURE\""));
        assertTrue(line.contains("\"errorCode\":\"UPLOAD_FAILED\""));
        assertTrue(line.contains("\"fileId\":\"file-1\""));
    }

    @Test
    void everyEventTypeInTheSchemaIsUsable(@TempDir Path dir) throws IOException {
        // Implementation.md Phase 7 DoD: every event type must be demonstrably
        // producible. LOGIN/LOGOUT/UPLOAD/DOWNLOAD/LOCK/UNLOCK are exercised via
        // real RMI calls in VaultServiceAuditIntegrationTest; ERROR is not
        // currently reachable through the RMI surface (no unclassified server
        // errors occur in normal operation — see Context.md's Derived Decision
        // against blanket defensive wrapping, judged unnecessary complexity for
        // this coursework scope) — proven usable directly here instead.
        AuditLogger logger = new AuditLogger(dir.resolve("audit.log"));

        for (AuditEventType type : AuditEventType.values()) {
            logger.logFailure(type, "user-1", "test-op", null, ErrorCode.SERVER_ERROR, "127.0.0.1");
        }

        List<String> lines = Files.readAllLines(dir.resolve("audit.log"));
        assertEquals(AuditEventType.values().length, lines.size());
        for (AuditEventType type : AuditEventType.values()) {
            assertTrue(lines.stream().anyMatch(l -> l.contains("\"eventType\":\"" + type.name() + "\"")),
                    "missing a record for event type " + type);
        }
    }

    @Test
    void neverLogsNullFieldsAsTheStringNull_theyAreJsonNull(@TempDir Path dir) throws IOException {
        AuditLogger logger = new AuditLogger(dir.resolve("audit.log"));

        logger.logFailure(AuditEventType.LOGIN, null, "login", null, ErrorCode.AUTHENTICATION_FAILED, "127.0.0.1");

        String line = onlyLine(dir.resolve("audit.log"));
        assertTrue(line.contains("\"userId\":null"), "a null userId must serialize as JSON null, not the string \"null\"");
        assertTrue(line.contains("\"fileId\":null"));
    }

    @Test
    void specialCharactersInFieldsAreProperlyEscaped(@TempDir Path dir) throws IOException {
        AuditLogger logger = new AuditLogger(dir.resolve("audit.log"));

        logger.logFailure(AuditEventType.LOGIN, "user\"with\\quotes\nand\nnewlines", "login", null,
                ErrorCode.AUTHENTICATION_FAILED, "127.0.0.1");

        List<String> lines = Files.readAllLines(dir.resolve("audit.log"));
        assertEquals(1, lines.size(), "an unescaped newline in a field would have split this into multiple lines");
    }

    @Test
    void appendsAcrossMultipleCallsRatherThanOverwriting(@TempDir Path dir) throws IOException {
        AuditLogger logger = new AuditLogger(dir.resolve("audit.log"));

        logger.logSuccess(AuditEventType.LOGIN, "user-1", "login", null, "127.0.0.1");
        logger.logSuccess(AuditEventType.LOGOUT, "user-1", "logout", null, "127.0.0.1");

        List<String> lines = Files.readAllLines(dir.resolve("audit.log"));
        assertEquals(2, lines.size());
    }

    @Test
    void concurrentWritesNeverInterleaveOrCorruptLines(@TempDir Path dir) throws Exception {
        AuditLogger logger = new AuditLogger(dir.resolve("audit.log"));
        int writers = 20;
        int eventsPerWriter = 10;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch startingLine = new CountDownLatch(1);

        try {
            for (int i = 0; i < writers; i++) {
                int writerId = i;
                pool.submit(() -> {
                    try {
                        startingLine.await();
                        for (int j = 0; j < eventsPerWriter; j++) {
                            logger.logSuccess(AuditEventType.LOGIN, "writer-" + writerId, "login", null, "127.0.0.1");
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            startingLine.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

            List<String> lines = Files.readAllLines(dir.resolve("audit.log"));
            assertEquals(writers * eventsPerWriter, lines.size(), "no line should be lost or merged under concurrent writes");
            for (String line : lines) {
                assertTrue(line.startsWith("{") && line.endsWith("}"), "every line must be exactly one well-formed record: " + line);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String onlyLine(Path logFile) throws IOException {
        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());
        return lines.get(0);
    }
}
