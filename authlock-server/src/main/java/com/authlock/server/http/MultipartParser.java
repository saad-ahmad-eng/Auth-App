package com.authlock.server.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Minimal {@code multipart/form-data} parser for {@code POST /api/files/upload}
 * (Phase 13's web UI, {@link AuthLockHttpServer}) — reads the whole body into
 * memory (fine at coursework scale; the Swing client has no size limit either)
 * and pulls out the first part that carries a {@code filename}. Not a general
 * multipart implementation: no streaming, no support for nested multipart, no
 * header beyond {@code Content-Disposition} is inspected.
 */
final class MultipartParser {

    private MultipartParser() {
    }

    record UploadedFile(String filename, byte[] content) {
    }

    /** Extracts the {@code boundary=} parameter from a {@code Content-Type} header value, or {@code null} if absent/not multipart. */
    static String extractBoundary(String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            return null;
        }
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.regionMatches(true, 0, "boundary=", 0, "boundary=".length())) {
                String b = part.substring("boundary=".length());
                if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    /** Finds the first part with a {@code filename} in its {@code Content-Disposition} header and returns its name + raw bytes. */
    static Optional<UploadedFile> parseFirstFile(byte[] body, String boundary) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        byte[] headerEndMarker = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        List<Integer> positions = findAll(body, delimiter);

        for (int p = 0; p < positions.size() - 1; p++) {
            int partStart = positions.get(p) + delimiter.length;
            int partEnd = positions.get(p + 1);
            if (partStart + 1 < partEnd && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            }

            int headerEnd = indexOf(body, headerEndMarker, partStart, partEnd);
            if (headerEnd < 0) {
                continue;
            }
            String headers = new String(body, partStart, headerEnd - partStart, StandardCharsets.UTF_8);
            String filename = extractFilename(headers);
            if (filename == null) {
                continue; // a non-file form field, or a part with no Content-Disposition — skip it
            }

            int contentStart = headerEnd + headerEndMarker.length;
            int contentEnd = partEnd;
            // the CRLF immediately before the next boundary is multipart framing, not file content
            if (contentEnd - 2 >= contentStart && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }
            return Optional.of(new UploadedFile(filename, Arrays.copyOfRange(body, contentStart, contentEnd)));
        }
        return Optional.empty();
    }

    private static String extractFilename(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.regionMatches(true, 0, "content-disposition:", 0, "content-disposition:".length())) {
                int idx = line.indexOf("filename=\"");
                if (idx < 0) {
                    return null;
                }
                int start = idx + "filename=\"".length();
                int end = line.indexOf('"', start);
                if (end < 0) {
                    return null;
                }
                return line.substring(start, end);
            }
        }
        return null;
    }

    private static List<Integer> findAll(byte[] haystack, byte[] needle) {
        List<Integer> result = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = indexOf(haystack, needle, from, haystack.length);
            if (idx < 0) {
                break;
            }
            result.add(idx);
            from = idx + needle.length;
        }
        return result;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from, int upTo) {
        int limit = Math.min(upTo, haystack.length) - needle.length;
        outer:
        for (int i = Math.max(from, 0); i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
