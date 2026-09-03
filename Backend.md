# Backend.md — Backend Architecture

**Project:** AuthLock
**Related documents:** [Architecture.md](Architecture.md) · [API-spec.md](API-spec.md) · [Security.md](Security.md) · [TRD.md](TRD.md)

This document defines the backend's internal modules and logical data models conceptually. **No implementation code is created here** — data models are logical field lists, not Java class definitions, per [p1.md](p1.md) §2/§13.

---

## 1. Backend Modules

| Module | Corresponds to (Architecture.md) | Responsibility Summary |
|---|---|---|
| RMI Server (bootstrap) | §2.2/§2.3 | Starts the RMI registry, constructs and binds the `VaultService` implementation, wires the modules below together, reads server configuration. |
| Authentication | §2.4 | Verifies credentials against the user store; never handles plaintext beyond the single verification step. |
| Session Management | §2.5 | Issues, validates, expires, and invalidates session tokens. |
| Vault / File Service | §2.6 | Owns file bytes and metadata; enforces path-safety; computes/verifies checksums. |
| Lock Manager | §2.7 | Owns per-file lock state; atomic acquire/release; timeout and stale-lock recovery. |
| Encryption | §2.8 | AES-GCM encrypt/decrypt helpers and/or RMI-over-TLS socket factory configuration. |
| Audit Logging | §2.9 | Structured, append-only event recording. |
| Persistence | §2.10 | Filesystem + lightweight metadata store access shared by Vault Service, Audit Logging, and (if persisted) Session/Lock state. |
| Error Handling | Cross-cutting, lives at the `VaultService` boundary | Translates internal exceptions/results into the [API-spec.md](API-spec.md) Error Model before returning to the client. |

---

## 2. Data Models

These are **logical models** — the authoritative field list each entity must carry. Concrete Java types (e.g., `record` vs. `class`, exact serialization) are an implementation-phase decision, not fixed here.

### 2.1 User

| Field | Description |
|---|---|
| `userId` | Unique, stable identifier (server-generated). |
| `username` | Login identifier, unique. |
| `passwordHash` | Salted hash per [Security.md](Security.md) §3 — **never** the plaintext password. |
| `status` | e.g., `ACTIVE` / `DISABLED` — supports an admin-provisioned account being disabled without deletion. |
| `metadata` | Optional free-form fields (e.g., display name) not otherwise security-relevant. |

*(No self-service registration fields — accounts are provisioned per PRD §4.1.)*

### 2.2 Session

| Field | Description |
|---|---|
| `sessionToken` | Opaque, high-entropy identifier (SEC-003). |
| `userId` | Owning user. |
| `createdAt` | Session creation timestamp. |
| `expiresAt` | Absolute/idle expiry timestamp (whichever triggers first, per [Security.md](Security.md) §3). |
| `status` | e.g., `ACTIVE` / `EXPIRED` / `LOGGED_OUT`. |

### 2.3 File Metadata

| Field | Description |
|---|---|
| `fileId` | Server-generated internal identifier — used for storage path, never the client-supplied name (SEC-006). |
| `originalFilename` | Client-supplied display name, treated as untrusted metadata only. |
| `storedFilename` / `storagePath` | Internal path derived from `fileId`, not from `originalFilename`. |
| `owner` | `userId` of the uploader. |
| `size` | Byte size of the stored (ciphertext, if applicable) content. |
| `checksum` | Integrity hash of the plaintext content, per [Security.md](Security.md) §6. |
| `createdAt` | Upload timestamp. |
| `modifiedAt` | Last successful re-upload/update timestamp. |
| `lockStatus` | Denormalized view of current Lock Manager state for display in `listFiles()` — the Lock Manager remains the source of truth. |

### 2.4 File Lock

| Field | Description |
|---|---|
| `fileId` | The locked file. |
| `ownerSessionToken` (or `ownerUserId`, per resolution of Open Question OQ-12) | Who holds the lock. |
| `acquiredAt` | Timestamp the lock was granted. |
| `expiresAt` | Timeout deadline per [Security.md](Security.md) §8. |
| `lockState` | e.g., `LOCKED` / `RELEASED` (a record can be dropped entirely on release rather than tombstoned — implementation detail). |

---

## 3. Concurrency Model

| Aspect | Design |
|---|---|
| **RMI request threads** | The RMI runtime dispatches each incoming remote call on its own thread from an internal thread pool — multiple clients' calls execute concurrently by default. Backend code must therefore treat every method as potentially re-entered concurrently for different (or the same) file/session. |
| **Synchronization** | Shared mutable state (session table, lock table, in-memory file metadata index) is held in concurrency-safe structures (`java.util.concurrent.ConcurrentHashMap` and friends), not plain `HashMap`s guarded ad hoc. |
| **Thread safety** | Each module (Session Manager, Lock Manager, Vault Service) is individually thread-safe; cross-module operations (e.g., "upload requires checking nothing, but modifying an existing locked file requires checking lock ownership first") compose safely because each module's own state transitions are already atomic. |
| **Shared state** | Three principal shared structures: the session table (Session Manager), the lock table (Lock Manager), and the file metadata index (Vault Service). Each is owned by exactly one module — no other module mutates another's table directly (they call its methods). |
| **Lock manager** | The correctness-critical component. Lock acquisition is implemented as a single atomic operation (e.g., `locks.putIfAbsent(fileId, newLockRecord)` — succeeds only if absent) rather than a "check if locked, then set" sequence, which would reintroduce the exact race condition the feature exists to prevent. |
| **Race-condition prevention** | Because acquisition is atomic at the data-structure level, no additional coarse-grained server-wide lock is needed — different files can be locked concurrently by different clients with no contention between them; only requests for the *same* file ID actually race, and the atomic map operation resolves that race deterministically. Validated explicitly by the concurrency test in [Testing.md](Testing.md). |

---

## 4. Notes

- Metadata storage technology (ADR-010, OQ-07) is resolved as of Implementation Phase 4: a flat `<fileId>.properties` sidecar per file, implemented in `VaultFileService`. This document remains implementation-agnostic on exact hashing/KDF library calls and exact class names beyond what's already implemented (left to [Development-rules.md](Development-rules.md) §2 naming conventions).
- Any change to these logical models that affects the wire format must be reflected in [API-spec.md](API-spec.md) DTOs in the same change.
