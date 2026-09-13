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
import java.util.ArrayList;
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
 *
 * <p><b>Phase 6 note:</b> storage always holds plaintext. AES-GCM encryption
 * (Security.md §7) is transport-only — the server decrypts an incoming
 * upload before calling {@link #store} and encrypts (with a fresh IV) after
 * calling {@link #retrieve} for a download; see {@code VaultServiceImpl}.
 * There is deliberately no persisted {@code iv} field here — a stored IV
 * would be meaningless for plaintext-at-rest storage, and reusing an
 * upload's IV for a later download would violate AES-GCM's "never reuse an
 * IV with the same key" rule (Security.md §7).
 */
public final class VaultFileService {

    private static final String CONTENT_SUFFIX = ".bin";
    private static final String METADATA_SUFFIX = ".properties";
    private static final String VERSION_CONTENT_INFIX = ".v";
    private static final String VERSIONS_METADATA_SUFFIX = ".versions.properties";

    private final Path storageDir;
    private final Map<String, FileRecord> index = new ConcurrentHashMap<>();
    private final Map<String, List<FileVersion>> versionsIndex = new ConcurrentHashMap<>();

    public VaultFileService(Path storageDir) throws IOException {
        this.storageDir = storageDir;
        Files.createDirectories(storageDir);
        loadExistingMetadata();
        loadExistingVersions();
    }

    /**
     * One archived prior version of a file (Phase 13 follow-up: per-file
     * version history). {@code replacedBy} is the username of whoever
     * performed the {@link #replaceWithHistory} call that superseded this
     * content — i.e. who caused it to stop being the live version, not
     * necessarily who originally created it.
     */
    public record FileVersion(int versionNumber, String replacedBy, Instant timestamp, String checksum, long size) {
    }

    /**
     * Stores a new file. Generates a fresh {@code fileId}; never treats
     * {@code filename} as anything but display metadata.
     *
     * @throws IllegalArgumentException if {@code filename} fails validation (SEC-006)
     * @throws IOException              on a storage/write failure
     */
    public FileRecord store(String filename, byte[] content, String owner) throws IOException {
        validateFilename(filename);
        String fileId = UUID.randomUUID().toString();
        String checksum = sha256Hex(content);
        Instant now = Instant.now();

        FileRecord record = new FileRecord(fileId, filename, owner, content.length, checksum, now, now);

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

    /**
     * Overwrites an EXISTING file's content in place — same {@code fileId},
     * same {@code filename}/{@code owner}/{@code createdAt}, updated
     * {@code size}/{@code checksum}/{@code modifiedAt}. Added for the web
     * UI's lock-gated "Replace" action (Phase 13 follow-up) — this method
     * only performs the overwrite; the caller ({@code VaultServiceImpl}) is
     * responsible for confirming the requester actually holds the file's
     * lock before ever reaching here, the same separation of concerns
     * {@link #store}/{@link #retrieve} already keep from lock/session logic.
     *
     * @return the updated record, or empty if {@code fileId} is unknown
     * @throws IOException on a storage/write failure
     */
    public Optional<FileRecord> replace(String fileId, byte[] newContent) throws IOException {
        FileRecord existing = index.get(fileId);
        if (existing == null) {
            return Optional.empty();
        }
        FileRecord updated = new FileRecord(
                fileId, existing.filename(), existing.owner(),
                newContent.length, sha256Hex(newContent), existing.createdAt(), Instant.now());

        writeContentAtomically(fileId, newContent);
        writeMetadataAtomically(updated);
        index.put(fileId, updated);
        return Optional.of(updated);
    }

    /**
     * Same as {@link #replace}, but first archives the file's CURRENT
     * content as a new numbered {@link FileVersion} (Phase 13 follow-up).
     * Version 1 is always the content that existed before the FIRST
     * replace (which may be the original upload's content); version 2 the
     * content before the second replace; and so on. The live/current
     * content itself is never a numbered version — only what a replace is
     * about to overwrite gets archived.
     */
    public Optional<FileRecord> replaceWithHistory(String fileId, byte[] newContent, String replacedByUsername) throws IOException {
        FileRecord existing = index.get(fileId);
        if (existing == null) {
            return Optional.empty();
        }
        byte[] currentContent = Files.readAllBytes(contentPath(fileId));
        archiveVersion(fileId, currentContent, existing.checksum(), existing.size(), replacedByUsername);
        return replace(fileId, newContent);
    }

    /** All archived versions of a file, oldest first — empty if it's never been replaced (or {@code fileId} is unknown). */
    public List<FileVersion> versionsOf(String fileId) {
        return List.copyOf(versionsIndex.getOrDefault(fileId, List.of()));
    }

    /** A specific archived version's raw bytes, or empty if that {@code fileId}/version combination doesn't exist. */
    public Optional<byte[]> versionContent(String fileId, int versionNumber) throws IOException {
        Path path = versionContentPath(fileId, versionNumber);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(Files.readAllBytes(path));
    }

    private void archiveVersion(String fileId, byte[] content, String checksum, long size, String replacedByUsername) throws IOException {
        List<FileVersion> versions = versionsIndex.computeIfAbsent(fileId, k -> new ArrayList<>());
        synchronized (versions) {
            int versionNumber = versions.size() + 1;
            FileVersion version = new FileVersion(versionNumber, replacedByUsername, Instant.now(), checksum, size);

            Path contentTarget = versionContentPath(fileId, versionNumber);
            Path contentTemp = storageDir.resolve(fileId + VERSION_CONTENT_INFIX + versionNumber + CONTENT_SUFFIX + ".tmp");
            try (OutputStream out = Files.newOutputStream(contentTemp)) {
                out.write(content);
            }
            Files.move(contentTemp, contentTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            versions.add(version);
            writeVersionsMetadataAtomically(fileId, versions);
        }
    }

    private Path versionContentPath(String fileId, int versionNumber) {
        return storageDir.resolve(fileId + VERSION_CONTENT_INFIX + versionNumber + CONTENT_SUFFIX);
    }

    private Path versionsMetadataPath(String fileId) {
        return storageDir.resolve(fileId + VERSIONS_METADATA_SUFFIX);
    }

    private void writeVersionsMetadataAtomically(String fileId, List<FileVersion> versions) throws IOException {
        Properties props = new Properties();
        props.setProperty("count", Integer.toString(versions.size()));
        for (FileVersion v : versions) {
            String prefix = "v" + v.versionNumber() + ".";
            props.setProperty(prefix + "replacedBy", v.replacedBy());
            props.setProperty(prefix + "timestamp", Long.toString(v.timestamp().toEpochMilli()));
            props.setProperty(prefix + "checksum", v.checksum());
            props.setProperty(prefix + "size", Long.toString(v.size()));
        }
        Path target = versionsMetadataPath(fileId);
        Path temp = storageDir.resolve(fileId + VERSIONS_METADATA_SUFFIX + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            props.store(out, "AuthLock file version history — do not edit by hand");
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void loadExistingVersions() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "*" + VERSIONS_METADATA_SUFFIX)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                String fileId = fileName.substring(0, fileName.length() - VERSIONS_METADATA_SUFFIX.length());
                versionsIndex.put(fileId, readVersionsMetadata(path));
            }
        }
    }

    private List<FileVersion> readVersionsMetadata(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        int count = Integer.parseInt(props.getProperty("count", "0"));
        List<FileVersion> versions = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String prefix = "v" + i + ".";
            versions.add(new FileVersion(
                    i,
                    props.getProperty(prefix + "replacedBy"),
                    Instant.ofEpochMilli(Long.parseLong(props.getProperty(prefix + "timestamp"))),
                    props.getProperty(prefix + "checksum"),
                    Long.parseLong(props.getProperty(prefix + "size"))));
        }
        return versions;
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
                // *.properties also matches *.versions.properties (a DIFFERENT sidecar,
                // handled by loadExistingVersions() below) — the glob alone can't tell
                // them apart, so exclude explicitly rather than mis-parsing one as a FileRecord.
                if (fileName.endsWith(VERSIONS_METADATA_SUFFIX)) {
                    continue;
                }
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
        return new FileRecord(
                fileId,
                props.getProperty("filename"),
                props.getProperty("owner"),
                Long.parseLong(props.getProperty("size")),
                props.getProperty("checksum"),
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
