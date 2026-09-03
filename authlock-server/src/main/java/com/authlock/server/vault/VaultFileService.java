package com.authlock.server.vault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns file storage and metadata, per Architecture.md §2.6 and Backend.md
 * §2.3/§4.
 *
 * <p><b>Resolution of Open Question OQ-07</b> (Context.md §7 / ADR-010):
 * files are stored as two per-file entries on the server filesystem —
 * {@code <fileId>.bin} (raw content) and {@code <fileId>.properties} (a
 * flat key=value metadata sidecar) — rather than a database. This is
 * sufficient at coursework scale, requires no new dependency, and matches
 * TRD.md's "File system storage" and [p1.md](p1.md) §21's instruction not
 * to overengineer. The in-memory index is rebuilt from the
 * {@code .properties} sidecars on construction, so the vault survives a
 * server restart (unlike sessions/locks — see ADR-006, OQ-04).
 *
 * <p>Filenames are never used to build a storage path — every file is
 * addressed by its server-generated {@code fileId} (SEC-006, path traversal
 * prevention "by construction," not merely by filtering). Client-supplied
 * filenames are still validated defensively (see {@link #validateFilename}).
 */
public final class VaultFileService {

    private static final String CONTENT_SUFFIX = ".bin";
    private static final String METADATA_SUFFIX = ".properties";

    private final Path storageDir;
    private final Map<String, FileRecord> index = new ConcurrentHashMap<>();

    public VaultFileService(Path storageDir) throws IOException {
        this.storageDir = storageDir;
        Files.createDirectories(storageDir);
        loadExistingMetadata();
    }

    /**
     * Stores a new file. Generates a fresh {@code fileId}; never treats
     * {@code filename} as anything but display metadata.
     *
     * @throws IllegalArgumentException if {@code filename} fails validation (SEC-006)
     * @throws IOException              on a storage/write failure
     */
    public FileRecord store(String filename, byte[] content, byte[] iv, String owner) throws IOException {
        validateFilename(filename);
        String fileId = UUID.randomUUID().toString();
        String checksum = sha256Hex(content);
        Instant now = Instant.now();

        FileRecord record = new FileRecord(
                fileId, filename, owner, content.length, checksum,
                iv == null ? new byte[0] : iv, now, now);

        writeContentAtomically(fileId, content);
        writeMetadataAtomically(record);
        index.put(fileId, record);
        return record;
    }

    /** A stored file's metadata plus its raw content, for {@code downloadFile}. */
    public record StoredFile(FileRecord metadata, byte[] content) {
    }

    /**
     * Retrieves a file's content and metadata, re-verifying the checksum
     * against what was stored at upload time (Security.md §6 "File
     * integrity" — detects corruption or tampering at rest).
     *
     * @return the stored file, or empty if {@code fileId} is unknown
     * @throws IOException          on a read failure
     * @throws ChecksumMismatchException if the stored content no longer matches its recorded checksum
     */
    public Optional<StoredFile> retrieve(String fileId) throws IOException {
        FileRecord record = index.get(fileId);
        if (record == null) {
            return Optional.empty();
        }
        byte[] content = Files.readAllBytes(contentPath(fileId));
        String actualChecksum = sha256Hex(content);
        if (!actualChecksum.equals(record.checksum())) {
            throw new ChecksumMismatchException(fileId);
        }
        return Optional.of(new StoredFile(record, content));
    }

    /** All known file records, for {@code listFiles}. */
    public Collection<FileRecord> listAll() {
        return List.copyOf(index.values());
    }

    /**
     * Cheap existence check (no disk read) — used by {@code lockFile}/
     * {@code unlockFile} (Phase 5) to reject an unknown {@code fileId}
     * without paying the cost of reading its content.
     */
    public boolean exists(String fileId) {
        return index.containsKey(fileId);
    }

    /** Thrown by {@link #retrieve} when stored content no longer matches its recorded checksum. */
    public static final class ChecksumMismatchException extends IOException {
        public ChecksumMismatchException(String fileId) {
            super("Stored content for file " + fileId + " failed checksum verification.");
        }
    }

    /**
     * Rejects filenames that are blank or that could indicate a path-
     * traversal attempt (defense in depth — the storage path is never
     * actually built from this value, see class Javadoc), per SEC-006 and
     * Testing.md TEST-SEC-001.
     */
    private static void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank.");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException("Filename must not contain path separators or '..'.");
        }
    }

    private Path contentPath(String fileId) {
        return storageDir.resolve(fileId + CONTENT_SUFFIX);
    }

    private Path metadataPath(String fileId) {
        return storageDir.resolve(fileId + METADATA_SUFFIX);
    }

    private void writeContentAtomically(String fileId, byte[] content) throws IOException {
        Path target = contentPath(fileId);
        Path temp = storageDir.resolve(fileId + CONTENT_SUFFIX + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            out.write(content);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeMetadataAtomically(FileRecord record) throws IOException {
        Properties props = new Properties();
        props.setProperty("filename", record.filename());
        props.setProperty("owner", record.owner());
        props.setProperty("size", Long.toString(record.size()));
        props.setProperty("checksum", record.checksum());
        props.setProperty("iv", Base64.getEncoder().encodeToString(record.iv()));
        props.setProperty("createdAt", Long.toString(record.createdAt().toEpochMilli()));
        props.setProperty("modifiedAt", Long.toString(record.modifiedAt().toEpochMilli()));

        Path target = metadataPath(record.fileId());
        Path temp = storageDir.resolve(record.fileId() + METADATA_SUFFIX + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            props.store(out, "AuthLock file metadata — do not edit by hand");
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void loadExistingMetadata() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "*" + METADATA_SUFFIX)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                String fileId = fileName.substring(0, fileName.length() - METADATA_SUFFIX.length());
                FileRecord record = readMetadata(fileId, path);
                index.put(fileId, record);
            }
        }
    }

    private FileRecord readMetadata(String fileId, Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        byte[] iv = Base64.getDecoder().decode(props.getProperty("iv", ""));
        return new FileRecord(
                fileId,
                props.getProperty("filename"),
                props.getProperty("owner"),
                Long.parseLong(props.getProperty("size")),
                props.getProperty("checksum"),
                iv,
                Instant.ofEpochMilli(Long.parseLong(props.getProperty("createdAt"))),
                Instant.ofEpochMilli(Long.parseLong(props.getProperty("modifiedAt"))));
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm; this indicates a broken JRE, not a recoverable condition.
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }
}
