# API-spec.md — Java RMI API Contract

**Project:** AuthLock
**Related documents:** [Backend.md](Backend.md) · [flow.md](flow.md) · [Security.md](Security.md) · [Architecture.md](Architecture.md)

The complete remote contract exposed by the server is a single interface, `VaultService`, extending `java.rmi.Remote`. This is a **conceptual/logical contract** — exact Java signatures are an implementation-phase detail, but method names, parameters, return semantics, and error behavior below are binding.

---

## 1. VaultService — Method Contracts

### `login(username, password) → sessionToken`

| Aspect | Detail |
|---|---|
| **Purpose** | Authenticate a user and establish a session. |
| **Parameters** | `username: String`, `password: String` |
| **Return type** | `sessionToken: String` (opaque) |
| **Exceptions** | `RemoteException` (transport failure); application error `AUTHENTICATION_FAILED` |
| **Authentication requirement** | None (this call *establishes* authentication). |
| **Authorization requirement** | None. |
| **Side effects** | Creates a session record on success. |
| **Audit event** | `LOGIN` (`SUCCESS` or `FAILURE`) — FR-011 |
| **Concurrency behavior** | Stateless with respect to other calls; safe under concurrent logins from different or the same user. |
| **Failure behavior** | Wrong username or wrong password both return `AUTHENTICATION_FAILED` (no distinction — SEC-001 enumeration prevention). |

### `logout(sessionToken) → void`

| Aspect | Detail |
|---|---|
| **Purpose** | Terminate a session. |
| **Parameters** | `sessionToken: String` |
| **Return type** | `void` (acknowledgement) |
| **Exceptions** | `RemoteException`; `INVALID_SESSION` if token is already invalid |
| **Authentication requirement** | Valid session token. |
| **Authorization requirement** | Caller may only invalidate their own token (implicit — the token itself is the credential). |
| **Side effects** | Removes/invalidates the session record; any locks held by this session are released (see [Security.md](Security.md) §8 stale-lock recovery — logout is a clean, immediate trigger of the same release path). |
| **Audit event** | `LOGOUT` — FR-011 |
| **Concurrency behavior** | Idempotent-safe: a second logout on an already-invalidated token yields `INVALID_SESSION` rather than an error. |
| **Failure behavior** | Invalid/unknown/expired token → `INVALID_SESSION`. |

### `listFiles(sessionToken) → List<FileMetadata>`

| Aspect | Detail |
|---|---|
| **Purpose** | Retrieve the vault's file listing with metadata and lock state (FR-005). |
| **Parameters** | `sessionToken: String` |
| **Return type** | `List<FileMetadata>` (see §2 DTOs) |
| **Exceptions** | `RemoteException`; `INVALID_SESSION` |
| **Authentication requirement** | Valid session token. |
| **Authorization requirement** | Any authenticated user may list all files (no per-file ACL beyond lock state, per PRD §6 scope). |
| **Side effects** | None (read-only). |
| **Audit event** | **Resolved (Phase 7):** not audited — read-only, low information value, would dominate log volume relative to the security-relevant write/auth operations. |
| **Concurrency behavior** | Safe under concurrent calls; reflects a point-in-time snapshot of metadata + lock state. |
| **Failure behavior** | Invalid session → `INVALID_SESSION`. |

### `uploadFile(sessionToken, filename, fileBytes, iv) → fileId`

| Aspect | Detail |
|---|---|
| **Purpose** | Store a new file in the vault (FR-006). |
| **Parameters** | `sessionToken: String`, `filename: String` (display name only), `fileBytes: byte[]` (ciphertext if client-side AES is used per ADR-007), `iv: byte[]` (nonce, if applicable) |
| **Return type** | `fileId: String` |
| **Exceptions** | `RemoteException`; `INVALID_SESSION`; `UPLOAD_FAILED` |
| **Authentication requirement** | Valid session token. |
| **Authorization requirement** | Any authenticated user may upload. |
| **Side effects** | Persists file bytes under a new server-generated `fileId`; creates a `FileMetadata` record; computes checksum. |
| **Audit event** | `UPLOAD` (`SUCCESS`/`FAILURE`) — FR-011 |
| **Concurrency behavior** | Each upload creates an independent new `fileId` — concurrent uploads from different clients never contend with each other. |
| **Failure behavior** | Invalid session → `INVALID_SESSION`; storage/I/O error or invalid filename → `UPLOAD_FAILED`. |

### `downloadFile(sessionToken, fileId) → FileContent`

| Aspect | Detail |
|---|---|
| **Purpose** | Retrieve a file's bytes (FR-007). |
| **Parameters** | `sessionToken: String`, `fileId: String` |
| **Return type** | `FileContent` DTO: `{ fileBytes: byte[], iv: byte[], checksum: String }` |
| **Exceptions** | `RemoteException`; `INVALID_SESSION`; `FILE_NOT_FOUND`; `DOWNLOAD_FAILED` |
| **Authentication requirement** | Valid session token. |
| **Authorization requirement** | Any authenticated user may download — **resolved (Phase 5), closing OQ-09:** locked files remain downloadable read-only, no lock check performed. |
| **Side effects** | None (read-only). |
| **Audit event** | `DOWNLOAD` (`SUCCESS`/`FAILURE`) — FR-011 |
| **Concurrency behavior** | Safe under concurrent downloads, including concurrent with an in-progress upload of a *different* file; concurrent with a lock held by another session (read is not blocked by a write lock). |
| **Failure behavior** | Invalid session → `INVALID_SESSION`; unknown `fileId` → `FILE_NOT_FOUND`; read/integrity error → `DOWNLOAD_FAILED`. |

### `lockFile(sessionToken, fileId) → void`

| Aspect | Detail |
|---|---|
| **Purpose** | Acquire an exclusive lock on a file before editing (FR-008). |
| **Parameters** | `sessionToken: String`, `fileId: String` |
| **Return type** | `void` — success means the caller now holds the lock |
| **Exceptions** | `RemoteException`; `INVALID_SESSION`; `FILE_NOT_FOUND`; `FILE_LOCKED` |
| **Authentication requirement** | Valid session token. |
| **Authorization requirement** | Any authenticated user may attempt to lock any unlocked file. |
| **Side effects** | On success, creates a `FileLock` record owned by the caller's session. A session re-locking a file it already holds succeeds idempotently (no error) rather than throwing `FILE_LOCKED` against itself — Derived Decision, resolved in Implementation Phase 5. |
| **Audit event** | `LOCK` (`SUCCESS`/`FAILURE`) — FR-011 |
| **Concurrency behavior** | Atomic acquisition (Backend.md §3) — exactly one concurrent caller for the same `fileId` succeeds (FR-010, validated by TEST-CONC-001). |
| **Failure behavior** | Invalid session → `INVALID_SESSION`; unknown file → `FILE_NOT_FOUND`; already locked by a different, still-live session → `FILE_LOCKED`. *(Resolved Implementation Phase 5: contention is reported by throwing `FILE_LOCKED`, not by a `granted:false` DTO field — this keeps every `VaultService` method using the same exception-based error model, so client code needs only one catch block, not a special case for locking. No `LockResult` DTO exists in the final interface.)* |

### `unlockFile(sessionToken, fileId) → void`

| Aspect | Detail |
|---|---|
| **Purpose** | Release a lock the caller holds (FR-009). |
| **Parameters** | `sessionToken: String`, `fileId: String` |
| **Return type** | `void` (acknowledgement) |
| **Exceptions** | `RemoteException`; `INVALID_SESSION`; `FILE_NOT_FOUND`; `LOCK_NOT_OWNED` |
| **Authentication requirement** | Valid session token. |
| **Authorization requirement** | Caller's session must be the current lock owner. |
| **Side effects** | Removes the `FileLock` record. |
| **Audit event** | `UNLOCK` (`SUCCESS`/`FAILURE`) — FR-011 |
| **Concurrency behavior** | Atomic check-owner-then-release. |
| **Failure behavior** | Invalid session → `INVALID_SESSION`; unknown file → `FILE_NOT_FOUND`; not the lock owner (including "not locked at all") → `LOCK_NOT_OWNED`. |

---

## 2. Data Transfer Objects

All DTOs crossing the RMI boundary implement `Serializable` with a declared `serialVersionUID` (TRD §3).

### Request-side (implicit as method parameters above — no separate wrapper objects required for this scope, keeping the API simple per [p1.md](p1.md) §21).

### `FileMetadata` (response)
| Field | Type | Notes |
|---|---|---|
| `fileId` | `String` | |
| `filename` | `String` | Original display name. |
| `size` | `long` | |
| `owner` | `String` | **Resolved:** the uploader's username (human-readable display value, resolved from the internal `userId` at upload time — Context.md Phase 8), not the raw internal `userId`. |
| `createdAt` | `long`/`Instant`-equivalent | |
| `modifiedAt` | `long`/`Instant`-equivalent | |
| `lockState` | `String` (`UNLOCKED` / `LOCKED`) | Denormalized view — Lock Manager remains source of truth (Backend.md §2.3). |
| `lockOwnerHint` | `String?` | **Resolved (Phase 5):** `"you"` if the requesting session holds the lock, `"another user"` if held by someone else, `null` if unlocked — never a raw session token or another user's identity (minimal-disclosure). |

### `FileContent` (response, from `downloadFile`)
| Field | Type | Notes |
|---|---|---|
| `fileBytes` | `byte[]` | Ciphertext if application-layer AES is used. |
| `iv` | `byte[]` | Nonce for AES-GCM, if applicable. |
| `checksum` | `String` | For client-side integrity verification after decryption. |

*(No `LockResult` DTO — `lockFile` reports contention by throwing `FILE_LOCKED`, per its Resolved design note above. `FileMetadata.lockOwnerHint`, above, already carries the display-facing "you"/"another user" hint for locked files shown in `listFiles()`.)*

### Session information (returned implicitly as the `sessionToken` string; no richer session DTO is exposed to the client — the client only ever needs the opaque token, per SEC-003 minimal-disclosure principle).

### Error information — see §3 Error Model.

---

## 3. Error Model

Standardized application-level error codes, each mapped from the failure conditions described per-method above:

| Code | Meaning | Raised By |
|---|---|---|
| `AUTHENTICATION_FAILED` | Invalid username or password. | `login` |
| `INVALID_SESSION` | Session token missing, unknown, or expired. | Every method except `login` |
| `UNAUTHORIZED` | Authenticated but not permitted to perform this specific action (reserved for future role-based checks — currently subsumed by `LOCK_NOT_OWNED` for the one authorization boundary this project has). | Reserved |
| `FILE_NOT_FOUND` | Referenced `fileId` does not exist. | `downloadFile`, `lockFile`, `unlockFile` |
| `FILE_LOCKED` | File is currently locked by a different session. | `lockFile` |
| `LOCK_NOT_OWNED` | Caller attempted to unlock a file they do not hold the lock on. | `unlockFile` |
| `UPLOAD_FAILED` | Server-side I/O or validation error during upload. | `uploadFile` |
| `DOWNLOAD_FAILED` | Server-side I/O or integrity-check error during download. | `downloadFile` |
| `SERVER_ERROR` | Unclassified internal server error (catch-all — never exposes stack traces or internal detail to the client). | Any method |

**Transport-level failures** (`RemoteException` — registry unreachable, connection dropped mid-call) are distinct from the application error codes above and are handled client-side per FR-013 (§Architecture.md §2.1) — displayed as a "server unavailable" state rather than mapped to one of these codes.

**Design note:** these names may be refined during implementation as long as (a) the set remains consistent across [flow.md](flow.md), [Testing.md](Testing.md), and this document, and (b) any rename is reflected in all three per [Development-rules.md](Development-rules.md) §4.
