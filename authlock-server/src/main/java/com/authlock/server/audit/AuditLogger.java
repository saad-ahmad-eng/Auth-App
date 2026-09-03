package com.authlock.server.audit;

import com.authlock.common.ErrorCode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Structured, append-only audit log, per Architecture.md §2.9,
 * Security.md §9, and ADR-008 (Accepted — append-only local structured log
 * file, one JSON object per line).
 *
 * <p>Writes are synchronous (Decision.md ADR-008: "so no event is lost even
 * if the server crashes immediately after") and serialized under a single
 * lock — the audit log is low-volume enough (one line per security-relevant
 * operation, not per byte transferred) that this is not a throughput
 * concern, and it guarantees lines are never interleaved/corrupted under
 * concurrent RMI calls (Backend.md §3).
 *
 * <p><b>Engineering Decision:</b> a failed audit write is logged to stderr
 * but does <i>not</i> fail the underlying operation — a full disk taking
 * down the entire vault (fail-closed) was judged worse for this coursework
 * project's availability than a coursework-scope best-effort audit trail
 * (fail-open on the write, loudly, to stderr where an operator will see
 * it). A production system handling higher-stakes data might reasonably
 * choose the opposite trade-off.
 *
 * <p>No JSON library dependency is introduced — the field set is small,
 * fully known in advance, and hand-rolled serialization with proper string
 * escaping is simpler and more auditable than pulling in Jackson/Gson for
 * this ([p1.md](p1.md) §21, avoid unnecessary dependencies).
 */
public final class AuditLogger {

    private final Path logFile;
    private final Object writeLock = new Object();

    public AuditLogger(Path logFile) throws IOException {
        this.logFile = logFile;
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public void logSuccess(AuditEventType eventType, String userId, String operation, String fileId, String clientInfo) {
        write(new AuditRecord(Instant.now(), eventType, userId, operation, fileId, AuditResult.SUCCESS, null, clientInfo));
    }

    public void logFailure(AuditEventType eventType, String userId, String operation, String fileId,
            ErrorCode errorCode, String clientInfo) {
        write(new AuditRecord(Instant.now(), eventType, userId, operation, fileId, AuditResult.FAILURE, errorCode, clientInfo));
    }

    private void write(AuditRecord record) {
        String line = toJsonLine(record);
        synchronized (writeLock) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(line);
                writer.newLine();
            } catch (IOException e) {
                System.err.println("AUDIT LOG WRITE FAILED (event not persisted): " + e.getMessage());
            }
        }
    }

    private static String toJsonLine(AuditRecord r) {
        StringBuilder json = new StringBuilder(128);
        json.append('{');
        field(json, "timestamp", r.timestamp().toString(), true);
        field(json, "eventType", r.eventType().name(), true);
        field(json, "userId", r.userId(), true);
        field(json, "operation", r.operation(), true);
        field(json, "fileId", r.fileId(), true);
        field(json, "result", r.result().name(), true);
        field(json, "errorCode", r.errorCode() == null ? null : r.errorCode().name(), true);
        field(json, "clientInfo", r.clientInfo(), false);
        json.append('}');
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value, boolean trailingComma) {
        json.append('"').append(name).append("\":");
        json.append(value == null ? "null" : ('"' + escape(value) + '"'));
        if (trailingComma) {
            json.append(',');
        }
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
