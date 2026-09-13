package com.authlock.server.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User credential store, seeded at startup from a bundled properties file.
 *
 * <p><b>Resolution of Open Question OQ-01</b> (Context.md §7): AuthLock
 * originally had no self-service registration (PRD.md §4.1). Phase 13's
 * admin panel adds the one exception — an {@link User.Role#ADMIN} account
 * can {@link #createUser} at runtime, and {@link #setStatus} can
 * disable/re-enable any account. Passwords in {@code seed-users.properties}
 * are plaintext only as seed input; they (and any admin-set password) are
 * hashed via {@link PasswordHasher} and the plaintext is never retained.
 *
 * <p><b>Persistence (Phase 13 follow-up):</b> lazy and additive to the
 * existing flat-file convention (matches {@code audit.log}/{@code
 * authlock-shared.key}): this class reads the bundled classpath seed on
 * every startup regardless, but ALSO checks a separate, writable runtime
 * file ({@code -Dauthlock.users.file=<path>}, default
 * {@code authlock-users.properties} in the working directory). If that
 * file exists, its contents — the full current state (role/status/hash)
 * of every account, seeded or admin-created — take priority; if it
 * doesn't exist yet, nothing is written until the first admin mutation
 * ({@link #createUser}/{@link #setStatus}), so a server that never touches
 * the admin panel never creates this file at all (existing tests/deployments
 * are unaffected).
 */
public final class UserStore {

    private static final String SEED_RESOURCE = "/seed-users.properties";
    private static final String DEFAULT_RUNTIME_FILE = "authlock-users.properties";
    /** The one seed username treated as the initial admin — see class Javadoc; a coursework-scope bootstrap choice, not a hardcoded backdoor (its password is set, and hashed, exactly like any other seed user's). */
    private static final String INITIAL_ADMIN_USERNAME = "admin";

    private final Map<String, User> usersByUsername = new ConcurrentHashMap<>();
    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final PasswordHasher passwordHasher;
    private final Path runtimeFile;

    public UserStore(PasswordHasher passwordHasher) throws IOException {
        this(passwordHasher, Path.of(System.getProperty("authlock.users.file", DEFAULT_RUNTIME_FILE)));
    }

    public UserStore(PasswordHasher passwordHasher, Path runtimeFile) throws IOException {
        this.passwordHasher = passwordHasher;
        this.runtimeFile = runtimeFile;
        if (Files.exists(runtimeFile)) {
            loadRuntimeFile(runtimeFile);
        } else {
            seedFromClasspath(passwordHasher);
        }
    }

    public Optional<User> findByUserId(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(usersById.get(userId));
    }

    public Optional<User> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(usersByUsername.get(username));
    }

    /** Every account, for the admin panel's user list. */
    public Collection<User> listAll() {
        return java.util.List.copyOf(usersByUsername.values());
    }

    /**
     * Creates a new account (Phase 13 admin panel). The password is hashed
     * immediately, the same way {@link PasswordHasher} hashes any other
     * password — never accepted or stored as plaintext beyond this call.
     *
     * @throws IllegalArgumentException if {@code username} is already taken
     * @throws IOException              if persisting the new account fails
     */
    public User createUser(String username, char[] password, User.Role role) throws IOException {
        if (usersByUsername.containsKey(username)) {
            throw new IllegalArgumentException("Username already exists.");
        }
        HashedPassword hashed = passwordHasher.hash(password);
        Arrays.fill(password, '\0');
        User user = new User(UUID.randomUUID().toString(), username, hashed, User.Status.ACTIVE, role);
        usersByUsername.put(username, user);
        usersById.put(user.userId(), user);
        persist();
        return user;
    }

    /** Enables or disables an existing account (Phase 13 admin panel). Empty if {@code userId} is unknown. */
    public Optional<User> setStatus(String userId, User.Status status) throws IOException {
        User existing = usersById.get(userId);
        if (existing == null) {
            return Optional.empty();
        }
        User updated = new User(existing.userId(), existing.username(), existing.passwordHash(), status, existing.role());
        usersByUsername.put(updated.username(), updated);
        usersById.put(updated.userId(), updated);
        persist();
        return Optional.of(updated);
    }

    private void seedFromClasspath(PasswordHasher passwordHasher) throws IOException {
        Properties seed = new Properties();
        try (InputStream in = UserStore.class.getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Seed user resource not found on classpath: " + SEED_RESOURCE);
            }
            seed.load(in);
        }
        for (String username : seed.stringPropertyNames()) {
            char[] password = seed.getProperty(username).toCharArray();
            HashedPassword hashed = passwordHasher.hash(password);
            Arrays.fill(password, '\0');
            User.Role role = INITIAL_ADMIN_USERNAME.equals(username) ? User.Role.ADMIN : User.Role.STANDARD;
            User user = new User(UUID.randomUUID().toString(), username, hashed, User.Status.ACTIVE, role);
            usersByUsername.put(username, user);
            usersById.put(user.userId(), user);
        }
    }

    private void loadRuntimeFile(Path path) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        // Usernames are derived from ".userId" keys rather than a separate
        // index list — one properties file, no redundant bookkeeping to
        // drift out of sync with the entries it's supposed to index.
        Map<String, User> loaded = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.endsWith(".userId")) {
                continue;
            }
            String username = key.substring(0, key.length() - ".userId".length());
            String prefix = username + ".";
            byte[] salt = Base64.getDecoder().decode(props.getProperty(prefix + "salt"));
            byte[] hash = Base64.getDecoder().decode(props.getProperty(prefix + "hash"));
            int iterations = Integer.parseInt(props.getProperty(prefix + "iterations"));
            User.Status status = User.Status.valueOf(props.getProperty(prefix + "status"));
            User.Role role = User.Role.valueOf(props.getProperty(prefix + "role"));
            User user = new User(props.getProperty(prefix + "userId"), username,
                    new HashedPassword(salt, hash, iterations), status, role);
            loaded.put(username, user);
        }
        for (User user : loaded.values()) {
            usersByUsername.put(user.username(), user);
            usersById.put(user.userId(), user);
        }
    }

    private void persist() throws IOException {
        Properties props = new Properties();
        for (User user : usersByUsername.values()) {
            String prefix = user.username() + ".";
            props.setProperty(prefix + "userId", user.userId());
            props.setProperty(prefix + "salt", Base64.getEncoder().encodeToString(user.passwordHash().salt()));
            props.setProperty(prefix + "hash", Base64.getEncoder().encodeToString(user.passwordHash().hash()));
            props.setProperty(prefix + "iterations", Integer.toString(user.passwordHash().iterations()));
            props.setProperty(prefix + "status", user.status().name());
            props.setProperty(prefix + "role", user.role().name());
        }
        if (runtimeFile.toAbsolutePath().getParent() != null) {
            Files.createDirectories(runtimeFile.toAbsolutePath().getParent());
        }
        Path temp = runtimeFile.resolveSibling(runtimeFile.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            props.store(out, "AuthLock user accounts — do not edit by hand (hashed passwords, not plaintext)");
        }
        Files.move(temp, runtimeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
