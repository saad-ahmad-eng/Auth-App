package com.authlock.server.http;

import com.authlock.common.FileMetadata;
import com.authlock.server.auth.User;
import com.authlock.server.vault.VaultFileService;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled JSON encoding/decoding for the small, fixed set of shapes the
 * web UI's HTTP bridge needs (Phase 13, Architecture.md — see
 * {@link AuthLockHttpServer}'s class Javadoc for why this exists instead of
 * a JSON library dependency). Not a general-purpose JSON implementation —
 * {@link #parseFlatObject} only handles a single-level object of string
 * (or {@code null}) values, which is all {@code POST /api/login} needs.
 */
final class Json {

    private Json() {
    }

    /** Escapes a string for embedding inside a JSON string literal. */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Renders a JSON string literal (quotes included), or {@code null} for a Java {@code null}. */
    private static String quoteOrNull(String s) {
        return s == null ? "null" : "\"" + escape(s) + "\"";
    }

    /** Serializes a {@code listFiles} response — the exact fields {@code FileMetadata} already carries. */
    static String toJsonArray(List<FileMetadata> files) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(toJson(files.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Serializes a single {@link FileMetadata} — used for {@code listFiles} array entries and for the "replace" response, which returns the one file it just updated. */
    static String toJson(FileMetadata f) {
        return new StringBuilder()
                .append('{')
                .append("\"fileId\":").append(quoteOrNull(f.fileId())).append(',')
                .append("\"filename\":").append(quoteOrNull(f.filename())).append(',')
                .append("\"size\":").append(f.size()).append(',')
                .append("\"owner\":").append(quoteOrNull(f.owner())).append(',')
                .append("\"createdAt\":").append(quoteOrNull(String.valueOf(f.createdAt()))).append(',')
                .append("\"modifiedAt\":").append(quoteOrNull(String.valueOf(f.modifiedAt()))).append(',')
                .append("\"lockState\":").append(quoteOrNull(f.lockState())).append(',')
                .append("\"lockOwnerHint\":").append(quoteOrNull(f.lockOwnerHint()))
                .append('}')
                .toString();
    }

    /** Serializes a {@code GET /api/files/{id}/versions} response — oldest first, matching {@code VaultFileService.versionsOf}'s own order. */
    static String toVersionsJsonArray(List<VaultFileService.FileVersion> versions) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < versions.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            VaultFileService.FileVersion v = versions.get(i);
            sb.append('{')
                    .append("\"versionNumber\":").append(v.versionNumber()).append(',')
                    .append("\"replacedBy\":").append(quoteOrNull(v.replacedBy())).append(',')
                    .append("\"timestamp\":").append(quoteOrNull(String.valueOf(v.timestamp()))).append(',')
                    .append("\"checksum\":").append(quoteOrNull(v.checksum())).append(',')
                    .append("\"size\":").append(v.size())
                    .append('}');
        }
        return sb.append(']').toString();
    }

    /** Serializes one account for the admin panel — deliberately NEVER includes {@code passwordHash} (salt/hash/iterations). */
    static String toAdminUserJson(User user) {
        return new StringBuilder()
                .append('{')
                .append("\"userId\":").append(quoteOrNull(user.userId())).append(',')
                .append("\"username\":").append(quoteOrNull(user.username())).append(',')
                .append("\"status\":").append(quoteOrNull(user.status().name())).append(',')
                .append("\"role\":").append(quoteOrNull(user.role().name()))
                .append('}')
                .toString();
    }

    static String toAdminUsersJsonArray(Collection<User> users) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (User user : users) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(toAdminUserJson(user));
        }
        return sb.append(']').toString();
    }

    /**
     * Parses a flat JSON object of string (or null) values, e.g.
     * {@code {"username":"alice","password":"Alice2026Pass"}}. Throws
     * {@link IllegalArgumentException} on anything else (nested
     * objects/arrays, numbers, malformed input) — callers treat that as a
     * bad request, which is all this endpoint needs.
     */
    static Map<String, String> parseFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int i = skipWhitespace(json, 0);
        require(i < json.length() && json.charAt(i) == '{', "Expected '{'");
        i = skipWhitespace(json, i + 1);
        if (i < json.length() && json.charAt(i) == '}') {
            return result;
        }
        while (true) {
            i = skipWhitespace(json, i);
            require(i < json.length() && json.charAt(i) == '"', "Expected string key");
            int[] end = new int[1];
            String key = parseString(json, i, end);
            i = skipWhitespace(json, end[0]);
            require(i < json.length() && json.charAt(i) == ':', "Expected ':'");
            i = skipWhitespace(json, i + 1);
            require(i < json.length(), "Unexpected end of input");
            String value;
            if (json.charAt(i) == '"') {
                value = parseString(json, i, end);
                i = end[0];
            } else if (json.regionMatches(i, "null", 0, 4)) {
                value = null;
                i += 4;
            } else {
                throw new IllegalArgumentException("Expected string or null value");
            }
            result.put(key, value);
            i = skipWhitespace(json, i);
            require(i < json.length(), "Unexpected end of input");
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            require(json.charAt(i) == '}', "Expected ',' or '}'");
            break;
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** {@code s.charAt(start)} must be the opening quote. Writes the index just past the closing quote to {@code endOut[0]}. */
    private static String parseString(String s, int start, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                endOut[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\') {
                require(i + 1 < s.length(), "Unterminated escape");
                char esc = s.charAt(i + 1);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        require(i + 5 < s.length(), "Truncated unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape: \\" + esc);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        throw new IllegalArgumentException("Unterminated string");
    }
}
