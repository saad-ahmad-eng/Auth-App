package com.authlock.server.auth;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory user credential store, seeded at startup from a bundled
 * properties file.
 *
 * <p><b>Resolution of Open Question OQ-01</b> (Context.md §7): AuthLock has
 * no self-service registration (PRD.md §4.1 — {@code auth} never describes
 * one). Accounts are instead provisioned out-of-band via
 * {@code seed-users.properties} on the classpath — a fixed, coursework-scale
 * demo user list, not a production credential-management system. Passwords
 * in that file are plaintext <i>only</i> as the seed input; they are hashed
 * once at server startup (see {@link PasswordHasher}) and the plaintext is
 * never retained past the constructor. This is an explicit coursework-scope
 * trade-off — see Security.md §3 and Context.md §14 for the caveat that a
 * real deployment would use a proper provisioning/admin flow instead.
 */
public final class UserStore {

    private static final String SEED_RESOURCE = "/seed-users.properties";

    private final Map<String, User> usersByUsername = new ConcurrentHashMap<>();
    private final Map<String, User> usersById = new ConcurrentHashMap<>();

    public UserStore(PasswordHasher passwordHasher) {
        Properties seed = loadSeedProperties();
        for (String username : seed.stringPropertyNames()) {
            char[] password = seed.getProperty(username).toCharArray();
            HashedPassword hashed = passwordHasher.hash(password);
            java.util.Arrays.fill(password, '\0');
            User user = new User(UUID.randomUUID().toString(), username, hashed, User.Status.ACTIVE);
            usersByUsername.put(username, user);
            usersById.put(user.userId(), user);
        }
    }

    /**
     * Resolves a stable {@code userId} (as stored on {@link User}, sessions,
     * and file ownership records) back to its {@link User} — used to display
     * a human-readable username (e.g. the vault UI's "Owner" column) instead
     * of the opaque internal ID. Found during Phase 8 interactive
     * verification: the Dashboard was showing the raw {@code userId} in the
     * Owner column, which is correct data but unreadable to an end user.
     */
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

    private static Properties loadSeedProperties() {
        Properties props = new Properties();
        try (InputStream in = UserStore.class.getResourceAsStream(SEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Seed user resource not found on classpath: " + SEED_RESOURCE);
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load seed users from " + SEED_RESOURCE, e);
        }
        return props;
    }
}
