# Security.md — Security Specification

**Project:** AuthLock
**Related documents:** [PRD.md](PRD.md) · [Decision.md](Decision.md) (ADR-004, ADR-006, ADR-007, ADR-008) · [Architecture.md](Architecture.md) · [API-spec.md](API-spec.md)

---

## 1. Threat Model

| Threat Actor | Description | Primary Concern |
|---|---|---|
| **External attacker** | Party with network access to the RMI server but no valid credentials. | Attempting login, exploiting exposed ports/services. |
| **Malicious client** | A modified/custom RMI client sending crafted or malformed requests. | Bypassing client-side validation, sending invalid tokens, oversized payloads, path-traversal filenames. |
| **Compromised client** | A legitimate user's machine/session under attacker control. | Abusing valid session tokens; acting outside the user's intent. |
| **Network attacker (on-path)** | Party able to observe or tamper with traffic between client and cloud VM. | Eavesdropping on credentials/file contents; replay or tampering with RMI calls. |
| **Unauthorized user** | Party without valid credentials attempting to reach protected operations. | Any call other than `login()` without a valid session. |
| **Concurrent/racing client** | A legitimate but poorly-timed client racing another for the same lock. | Not malicious, but must not be able to corrupt lock/file state through timing. |
| **Malicious file uploader** | An authenticated user uploading a crafted file (oversized, path-traversal filename, malicious content). | Server storage abuse, path traversal, disk exhaustion. |
| **Malicious downloader** | An authenticated user attempting to download a file they should not access, or another user's locked file inappropriately. | Data confidentiality/authorization boundary. |
| **Server compromise** | The server host itself is compromised. | Out of full mitigation scope for a coursework project, but audit logs, key handling, and password hashing should limit blast radius (defense in depth) — see §7 Key Management and §3 Password Storage. |

---

## 2. Security Objectives

| Objective | How AuthLock Addresses It |
|---|---|
| **Confidentiality** | File payloads encrypted in transit (ADR-007); credentials never transmitted or stored in plaintext. |
| **Integrity** | Authenticated encryption (AES-GCM) detects tampering with file payloads in transit; audit log records provide a tamper-evidence trail for operations. |
| **Authentication** | Every session begins with server-verified username/password (SEC-001). |
| **Authorization** | Every non-login call requires a valid session token, and lock-protected operations require lock ownership (SEC-007, SEC-009). |
| **Accountability** | Every security-relevant event is attributed to a session/user and durably logged (SEC-008, Audit Logging §6). |
| **Availability** | Stale-lock recovery and session expiry prevent a single failed/crashed client from permanently denying service to others (§5). |

---

## 3. Authentication (SEC-001, SEC-002)

- **Credential handling:** Username and password are submitted together in a single `login()` RMI call, protected in transit by RMI-over-TLS (§7.2, resolved Phase 6 — the whole channel, not just this call). They must never be logged (see §6, "Do not log passwords").
- **Password storage (SEC-002):** `auth` does not specify a hashing scheme — this is an **Engineering Requirement**, not optional. Passwords **must never** be stored in plaintext. **Recommendation:** bcrypt or PBKDF2WithHmacSHA256 (available via Java's `javax.crypto` without external dependencies) with a per-user random salt and a work factor tuned to keep verification under ~250ms on the deployment VM.
- **Password verification:** The server hashes the submitted password with the stored user's salt/parameters and compares digests using a constant-time comparison (`MessageDigest.isEqual` or the library's built-in verifier) to avoid timing side-channels.
- **Authentication failures:** A failed login (bad username OR bad password) returns the same generic `AUTHENTICATION_FAILED` error and the same approximate response time, so the server does not leak whether a given username exists (username enumeration prevention). Every failure is audited (§6).
- **Session creation:** On success, the server creates a session record and returns an opaque token (see §4). No password material is echoed back.
- **Session expiration:** **Resolved (Implementation Phase 3, `SessionManager`):** sessions expire after a 30-minute sliding idle timeout (renewed on every successful validation) **or** an 8-hour absolute maximum lifetime, whichever comes first — closing OQ-08.
- **Logout:** Immediately and irrevocably invalidates the session token server-side.
- **Token validation:** Every non-`login()` remote call validates the token against the live session table before performing any other work (fail closed).

---

## 4. Authorization (SEC-007)

| Action | Authorization Rule |
|---|---|
| `login()` | No prior authentication required (this *is* the authentication step). |
| `logout()`, `listFiles()`, `uploadFile()` | Requires a valid, non-expired session token. |
| `downloadFile()` | Requires a valid session token. **Resolved (Phase 5), closing OQ-09:** download of a file currently locked by another session is **allowed, read-only** — `downloadFile()` performs no lock check at all. A lock protects writes (`uploadFile`'s implicit "you must hold nothing to create a new file" aside — see PRD.md Future Enhancements for versioned/locked updates), not reads. Verified by `VaultServiceLockIntegrationTest.oq009_downloadByANonOwnerStillSucceedsWhileFileIsLocked`. |
| `lockFile()` | Requires a valid session token; fails if the file is already locked by a different, non-expired session (FR-010). |
| `unlockFile()` | Requires a valid session token **and** that the requesting session is the current lock owner (FR-009). A non-owner unlock attempt returns `LOCK_NOT_OWNED` and is audited as an authorization failure. |

There is a single authenticated-user authorization tier in scope (no admin/guest roles in the client — see PRD §6 Out of Scope). Every authenticated user has equal rights over vault files except lock ownership, which is exclusive per file.

---

## 5. Session Security (SEC-003)

| Aspect | Design |
|---|---|
| **Token generation** | Generated server-side using a cryptographically secure random source (`java.security.SecureRandom`), never derived from predictable data (username, timestamp alone, sequence numbers). |
| **Token entropy** | **Recommendation:** ≥128 bits of randomness (e.g., a 32-byte `SecureRandom` value, Base64/hex-encoded), making brute-force guessing infeasible. |
| **Token storage** | Server holds tokens only in the in-memory (or lightly persisted, per ADR-006) session table, keyed by token value; the token itself is never written to the audit log — only a session/user identifier is logged (§6). |
| **Token expiration** | Idle timeout and absolute max lifetime as in §3; expired tokens are purged from the session table and rejected on use. |
| **Invalid token handling** | Any call with an unknown, malformed, or expired token returns `INVALID_SESSION` and is audited as an authorization failure; no operation is attempted. |
| **Logout invalidation** | Token removed from the session table synchronously on `logout()` — no grace period. |
| **Session cleanup** | A periodic sweep (or lazy check-on-access) removes expired sessions from the table to bound memory growth. |

---

## 6. File Security

| Aspect | Design |
|---|---|
| **Encryption at rest** | `auth` requires encryption "during transfer" (in-transit); at-rest encryption is **not explicitly required**. **Recommendation** (not a hard requirement): consider at-rest encryption as a future enhancement if time allows — tracked as **Open Question OQ-10**. Default posture: rely on VM-level disk access controls for at-rest protection in the initial scope. |
| **Encryption in transit** | Mandatory — see §7 Encryption. |
| **File integrity** | A checksum (e.g., SHA-256) of the original file is computed at upload time, stored in file metadata, and re-verified at download time so any corruption (accidental or malicious) is detectable. |
| **File naming** | Client-supplied filenames are treated as untrusted display metadata only. Stored files are named/keyed by a server-generated internal file ID (ADR-010); the original filename is preserved only as a metadata field, never used to construct a filesystem path directly. |
| **Path traversal protection (SEC-006)** | Because storage never uses the client-supplied name as a path component, path traversal (`../../etc/passwd`-style names) is structurally prevented rather than merely filtered. Defense in depth: also reject/sanitize filenames containing path separators or `..` before even accepting them as metadata. |
| **Unauthorized file access** | Every `downloadFile()`/`uploadFile()` call is authorization-checked per §4; the vault directory is not directly exposed to clients (no direct filesystem/network share access, only via RMI calls). |
| **File overwrite protection** | **Resolved (Phase 4):** every `uploadFile()` call always creates a brand-new file record/`fileId`, regardless of whether the declared filename matches an existing file — there is no in-place "update existing file" operation, so nothing can ever silently overwrite another user's stored content. Duplicate display names may coexist as distinct files (closes OQ-11). |
| **Temporary files** | Any temporary files created during upload/download (e.g., partial-transfer staging) are written to a server-controlled temp directory, cleaned up after the operation completes or fails, and never left readable by other processes beyond normal OS file permissions. |

---

## 7. Encryption

**Resolved (Implementation Phase 6), closing OQ-05 — ADR-007 `Accepted`.** Two complementary controls, both implemented:

### 7.1 Application-layer AES-256-GCM (file payloads)

| Question | Answer |
|---|---|
| **What gets encrypted** | File payload bytes transferred between client and server (upload and download). |
| **When** | At the point of transfer — the client encrypts before `uploadFile()`, the server decrypts on receipt; the server encrypts (fresh IV) before returning from `downloadFile()`, the client decrypts. Never held as plaintext on the wire. |
| **Where** | Application-layer, per-hop (not end-to-end) — client-side in `authlock-client` (using shared `AesGcmCipher`), server-side in `authlock-server.crypto.EncryptionService`. Storage itself remains plaintext (`VaultFileService`) — this matches the "rely on VM-level disk access controls" at-rest posture below, not a change to it. |
| **Algorithm** | AES-256-GCM via `javax.crypto` (`AesGcmCipher`, shared by both modules). |
| **Key management** | A **pre-shared 256-bit AES key**: the server generates it on first startup if absent, persists it Base64-encoded to a local file (default `authlock-shared.key`, `-Dauthlock.crypto.keyfile=<path>`); the client only ever reads it (`SharedKeyProvider`). **Explicit coursework-scope simplification** — no rotation, no per-user keys, no KMS — chosen because it needs no in-band key exchange and is honestly inspectable rather than hiding the limitation behind a protocol. Its blast radius (if the key file is copied) is "file contents," not "everything," because §7.2 below separately protects credentials/session tokens. |
| **IV/nonce requirements** | A fresh, random 96-bit IV per encryption operation (`AesGcmCipher.encrypt`), never reused with the same key — verified by test (`AesGcmCipherTest.everyEncryptionUsesAFreshIv`). Transmitted alongside ciphertext (not secret). |
| **Authentication/integrity protection** | GCM's authentication tag is verified on decryption; a failed check throws `TamperDetectedException`, mapped to `UPLOAD_FAILED` — verified by test (TEST-SEC-003, both at the cipher-unit level and over real RMI with genuinely corrupted ciphertext). |

### 7.2 RMI-over-TLS (whole channel, including credentials)

| Question | Answer |
|---|---|
| **What gets protected** | The entire RMI channel — registry lookups, `login()` credentials, session tokens, and (redundantly, defense-in-depth) file payloads already covered by §7.1. Closes the gap application-layer file encryption alone left open: `login(username, password)` previously traveled in the clear. |
| **How** | `SslRMIClientSocketFactory`/`SslRMIServerSocketFactory` (JDK-bundled, `javax.rmi.ssl`) on both the registry and the exported `VaultService` object. On by default (`-Dauthlock.tls.enabled=false` to disable, e.g. for local troubleshooting). |
| **Certificate** | A self-signed certificate, auto-generated via the JDK-bundled `keytool` (no new dependency) on first run if absent (`authlock-common.tls.DevTlsSetup`), scoped to `CN=localhost` with SAN `dns:localhost,ip:127.0.0.1`. The same PKCS12 file doubles as both keystore and truststore — a real deployment would separate these and use a CA-issued (or at least properly distributed) certificate. |
| **Store password** | A fixed, publicly-documented dev-only value (`DevTlsSetup`'s Javadoc) — not a real secret; it protects nothing beyond a throwaway local test certificate. |
| **A well-known pitfall this ran into** | RMI embeds the server machine's *actual detected LAN IP* in exported stubs by default (not `localhost`), which then fails TLS hostname verification against a cert scoped only to `localhost`/`127.0.0.1`. Fixed by defaulting `java.rmi.server.hostname=localhost` unless the launcher already sets it (`ServerMain`) — the same property Architecture.md §3 already documents needing an explicit value for cloud deployment. |
| **Cloud deployment implication (resolved Phase 11)** | The dev certificate's SAN defaults to `localhost`-only. `DevTlsSetup` now accepts an optional `-Dauthlock.tls.extraSan=<entry>[,<entry>...]` system property, appended to the SAN list on first generation — the Phase 11 provisioning/setup scripts set this to the VM's discovered public IP/DNS name before the server's first run, so the certificate is correct without a manual regenerate-and-redeploy step. Still a self-signed cert (unchanged coursework-scope trade-off, not a CA-issued one) — only the SAN scope changed. |
| **Proof it's real, not cosmetic** | `VaultServiceTlsIntegrationTest` includes a test asserting a *plain* (non-TLS) client cannot connect to the TLS-only registry/export — confirming enforcement, not just configuration. |

**Explicitly prohibited throughout:** any custom/home-rolled cipher or protocol (per [p1.md](p1.md) §8). Only standard JCE/JSSE primitives are used.

---

## 8. Distributed Lock Security (SEC-009)

| Aspect | Design |
|---|---|
| **Who can acquire locks** | Any authenticated session, on any unlocked file, via `lockFile()`. A session re-locking a file it already holds succeeds idempotently rather than erroring. |
| **Lock ownership** | **Resolved (Phase 5), closing OQ-12:** a lock record stores the owning **session** ID, not user ID — a user's second concurrent session does not implicitly share a lock held by their first session. This falls out naturally from `LockManager` using the same session-token identity `SessionManager` already established (Backend.md §2.4). |
| **Lock release** | Only the owning session may call `unlockFile()` successfully (FR-009); the server verifies ownership before releasing. |
| **Lock timeout** | **Resolved (Phase 5), closing OQ-13:** every lock has a fixed 15-minute maximum hold duration (`LockManager.LOCK_TIMEOUT`) — not sliding/renewed by activity — after which it is eligible for automatic reclamation. |
| **Stale lock recovery** | If a session holding a lock expires (idle/absolute timeout) or is explicitly logged out, `SessionManager`'s session-ended listener immediately notifies `LockManager.releaseAllOwnedBySession`, releasing every lock that session held — no need to wait for the lock's own 15-minute timeout. A lock is also transparently treated as unlocked the moment it passes its own deadline, even before the periodic cleanup sweep physically removes it, and independently swept every 60s. |
| **Concurrent requests** | Lock acquisition is a single atomic `ConcurrentHashMap.compute()` call (not `putIfAbsent` alone, since it also has to atomically reclaim an expired lock in the same step) — never a separate check-then-set across two steps, closing the race window. See `LockManager.acquire()`. |
| **Race-condition prevention** | See [TRD.md](TRD.md) §3 "Synchronization" and [Testing.md](Testing.md) §3 Concurrency Test. **Validated:** TEST-CONC-001, a 5-client real-thread race over real RMI, repeated 30 rounds per run and re-run several times during development — exactly one winner every single round, with zero failures observed. |

---

## 9. Audit Logging (SEC-008, NFR-010)

**Resolved (Implementation Phase 7).** Every event below is written as a structured, one-JSON-object-per-line, append-only record (`AuditLogger`, ADR-008).

| Event | Trigger |
|---|---|
| Login (success) | Valid credentials verified, session created. |
| Login (failure) | Invalid credentials submitted. |
| Logout | Explicit `logout()` call (success or an already-invalid token). |
| Upload | `uploadFile()` completes (success or failure). |
| Download | `downloadFile()` completes (success or failure). |
| Lock | `lockFile()` attempted (granted or denied). |
| Unlock | `unlockFile()` attempted (granted or denied — including non-owner attempts). |
| Error | Any unexpected, unclassified server-side error during a remote call. |

**Derived Decision (Phase 7):** this documentation originally listed "Authentication failure" and "Authorization failure" as separate rows. Implementation revealed these are redundant with the per-operation rows above: a failed `login()` *is* the authentication-failure event (`LOGIN`/`FAILURE`/`AUTHENTICATION_FAILED`), and an `INVALID_SESSION`/`LOCK_NOT_OWNED` rejection on any method *is* that method's own authorization-failure event (e.g. `UPLOAD`/`FAILURE`/`INVALID_SESSION`). Every event type now maps 1:1 to a `VaultService` method (except `ERROR`, which has none), and `result` + `errorCode` carry the failure detail — the same information the original two generic rows would have carried, without a duplicate way of recording it. `listFiles()` is deliberately **not** audited (read-only, low information value — a decision already recorded in API-spec.md during the documentation phase, reaffirmed here rather than silently revisited).

### Event Metadata Schema

| Field | Description |
|---|---|
| `timestamp` | ISO-8601 UTC timestamp of the event. |
| `eventType` | One of the event names above. |
| `userId` | Identifier of the acting user — for a failed login specifically, the *attempted username* (no authenticated identity exists yet at that point; a username is not a secret, and logging attempted usernames on failed logins is standard practice for spotting brute-force/enumeration attempts). Never the raw session token — see §5. |
| `operation` | The remote method invoked (e.g. `"uploadFile"`). |
| `fileId` | Target file identifier, where applicable, else `null`. |
| `result` | `SUCCESS` / `FAILURE` plus, on failure, the error code from [API-spec.md](API-spec.md) Error Model. |
| `clientInfo` | Remote client host/IP as seen by the RMI server (`RemoteServer.getClientHost()`), where available. |

**Explicitly prohibited from the audit log, and verified by test (TEST-SEC-005):** plaintext passwords, password hashes, raw session tokens, encryption keys, and full file contents.

**Write reliability:** writes are synchronous, so no event is lost even if the server crashes immediately after (ADR-008). A failed *write itself* (e.g. disk full) is logged to stderr but does not fail the underlying operation — a deliberate fail-open choice on the audit trail's own durability, not on the vault's availability; see `AuditLogger`'s Javadoc for the trade-off reasoning.

---

## 10. Security Controls Matrix

| Control | Threat Mitigated | Requirement ID |
|---|---|---|
| Server-side password hashing (salted, slow KDF) | Credential theft from storage compromise | SEC-002 |
| Constant-time credential comparison | Timing side-channel username/password enumeration | SEC-001 |
| Generic authentication-failure message | Username enumeration | SEC-001 |
| Opaque, high-entropy session tokens | Session hijacking via token guessing | SEC-003 |
| Session validation on every non-login call | Unauthorized access | SEC-007 |
| Session/lock expiry + cleanup sweep | Denial of service via abandoned sessions/locks | SEC-003, SEC-009 |
| Atomic lock acquisition | Lock race condition / concurrent modification | SEC-009 |
| Lock-ownership check on unlock | Unauthorized lock release | SEC-009 |
| AES-256-GCM (and/or RMI-over-TLS) | Eavesdropping and tampering in transit | SEC-004 |
| Server-generated file IDs, no client path input | Path traversal | SEC-006 |
| Checksum verification on upload/download | Data corruption/tampering detection | — (Reliability/Integrity) |
| Structured, append-only audit log excluding secrets | Lack of accountability; secret leakage via logs | SEC-008 |
| Input validation on all RMI parameters | Malformed/malicious client input | SEC-010 |

---

## 11. Summary of Open Security Decisions

The following are explicitly **not finalized** and must be resolved before implementation of the affected component (tracked centrally in [Context.md](Context.md)):

- ~~OQ-05~~ **Resolved (Phase 6):** AES-256-GCM (pre-shared key file) on file payloads + RMI-over-TLS (self-signed dev cert) for the whole channel — both implemented, see §7.
- ~~OQ-08~~ **Resolved (Phase 3):** 30-minute sliding idle timeout + 8-hour absolute max lifetime.
- ~~OQ-09~~ **Resolved (Phase 5):** locked files remain downloadable read-only by non-owners.
- **OQ-10:** Whether at-rest encryption is implemented in this coursework scope — remains open; not required by `auth`, and RMI-over-TLS + AES-GCM in transit already exceed the literal requirement.
- ~~OQ-12~~ **Resolved (Phase 5):** locks are owned per-session.
- ~~OQ-13~~ **Resolved (Phase 5):** 15-minute fixed lock timeout.
