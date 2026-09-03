# PRD.md — Product Requirements Document

**Project:** AuthLock — RMI-Based Secure File Vault with Distributed Locking
**Module:** CS6006NU – Distributed, Cloud and IoT Systems
**Developer:** Kuamil jeffery
**Source of truth:** `AuthLock_Proposal (1).docx` (referred to throughout the documentation set as `auth`)
**Status:** Draft — Documentation/Planning Phase
**Related documents:** [TRD.md](TRD.md) · [Decision.md](Decision.md) · [Security.md](Security.md) · [Architecture.md](Architecture.md)

---

## 1. Project Overview

### 1.1 Project Name
AuthLock — RMI-Based Secure File Vault with Distributed Locking

### 1.2 Project Purpose
AuthLock is a distributed client/server application that lets multiple authenticated users securely upload, download, and share files through a central vault server. It is built on Java RMI so client-side code can invoke server-side operations as ordinary method calls. Its defining feature is a **distributed locking mechanism** that prevents two users from modifying the same file concurrently, combined with authentication, encrypted transfer, session contextualization, and audit logging.

### 1.3 Problem Statement
Naive file-sharing setups (shared drives, unmanaged FTP, ad-hoc copies) allow two users to edit the same file at once, silently overwriting each other's changes, and typically provide no authentication, no encryption in transit, and no record of who touched what and when. There is no mechanism to serialize concurrent access to a shared resource, and no accountability trail.

### 1.4 Proposed Solution
A central RMI server owns a single vault of files. Every operation requires a valid authenticated session. Before a client may modify a file, it must acquire a server-managed lock on that file; the server is the single arbiter of lock state, so exactly one client can hold a given file's lock at a time. All transferred file bytes are encrypted, every session is bound to a server-issued token, and every significant operation (login, logout, upload, download, lock, unlock, and failures) is written to a server-side audit log.

### 1.5 Target Users
- **Authenticated end users** who upload, download, and edit shared files.
- **Concurrent users** who may attempt to access the same file at the same time as another user.
- **System administrator / server operator** (in this project, the developer acting as operator) who provisions credentials, deploys the server, and reviews audit logs.

### 1.6 Primary Use Cases
1. A user logs in and receives a session token.
2. A user lists the files currently held in the vault.
3. A user uploads a new file to the vault.
4. A user locks a file, downloads it, edits it locally, re-uploads it, then unlocks it.
5. A second user attempts to lock/edit the same file while it is locked and is refused.
6. A user logs out, invalidating their session.
7. The server records every one of the above events in an audit log.

### 1.7 Project Boundaries
AuthLock is a **coursework distributed-systems demonstrator**, not a production file-sharing product. It targets a single RMI server instance, a fixed/pre-provisioned user base, and a Swing desktop client. It intentionally does not attempt to be a general-purpose cloud storage platform (see [Scope](#5-scope)).

---

## 2. Goals

### 2.1 Primary Goals
- Demonstrate a working Java RMI client/server distributed architecture.
- Implement a correct, race-condition-free distributed file-locking mechanism.
- Provide authenticated, session-scoped access to file operations.
- Encrypt file data in transit.
- Maintain a complete, tamper-resistant audit trail of vault activity.

### 2.2 Secondary Goals
- Provide a usable Swing GUI that makes lock state and connection state visible to the user.
- Deploy the server to a cloud VM to prove genuine network-distributed operation (not just localhost).
- Keep the codebase simple enough to explain and defend in an assessment/demo setting.

### 2.3 Academic/Coursework Goals
Directly evidence the CS6006NU learning outcomes: client/server distributed architecture (LO1/LO2), a working RMI implementation (LO3), integration of Java's RMI/Swing/cryptography APIs (LO4), justified design trade-offs (LO5), and security policy via authentication/encryption/auditing (LO6). See [Decision.md](Decision.md) for the trade-off justifications and `auth` §5–6 for the original mapping.

### 2.4 Security Goals
Confidentiality of file contents in transit, integrity and non-repudiation of the audit trail, server-side enforcement of authentication and authorization on every remote call, and prevention of lock-based race conditions. Full detail in [Security.md](Security.md).

### 2.5 Distributed-System Goals
Correct behavior under concurrent RMI calls from multiple clients, server-side serialization of conflicting operations (locking), and demonstrable operation across a real network boundary (cloud VM ↔ local client), not merely `localhost`.

---

## 3. User Personas

| Persona | Description | Primary Needs |
|---|---|---|
| **Authenticated User** | Any user holding valid credentials and an active session. | Reliable login, clear feedback, ability to see file/lock state. |
| **File Owner / Uploader** | A user who uploads a file into the vault. | Confidence the file is stored intact and only editable by the lock holder. |
| **Concurrent User** | A second user attempting to access a file another user currently holds. | Clear, immediate feedback that the file is locked, by whom (or at least that it's unavailable), and when they might retry. |
| **System Administrator / Server Operator** | Operates the RMI server, provisions accounts, reviews audit logs. | Simple deployment, visibility into audit trail, control over the credential store. |

*(No "anonymous visitor" or "guest" persona is defined — `auth` describes no anonymous access path; every file operation in scope requires authentication.)*

---

## 4. Functional Requirements

Each requirement is assigned a traceable ID, reused in [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md), [API-spec.md](API-spec.md), and [Testing.md](Testing.md).

| ID | Requirement | Detail | Source |
|---|---|---|---|
| **FR-001** | User Login | A client authenticates via username and password over RMI. On success the server returns a session token; on failure it returns a distinct authentication-failure error without revealing whether the username or password was wrong. | `auth` §3, §6 |
| **FR-002** | User Logout | An authenticated client can explicitly terminate its session. The server invalidates the session token immediately; the token cannot be reused afterward. | `auth` §3 |
| **FR-003** | Session Creation | On successful login the server creates a server-side session record (token, user ID, created time, expiry) and returns the token to the client. | `auth` §3, §6 |
| **FR-004** | Session Validation | Every remote call other than `login()` must present a session token, which the server validates (existence, ownership, non-expiry) before performing the operation. | `auth` §6 — *Derived Decision: explicit validation step, since `auth` states session tokens "authorise further calls" but does not specify the validation mechanics.* |
| **FR-005** | File Listing | An authenticated client can request the list of files currently in the vault, including per-file metadata (name, size, owner, modified time, lock state). | `auth` §4 (implied by "file browser" in `auth` §6 GUI row) |
| **FR-006** | File Upload | An authenticated client can upload a file's binary contents to the server, which stores it and records metadata. | `auth` §3, §4, §6 |
| **FR-007** | File Download | An authenticated client can download a file's binary contents from the server. | `auth` §3, §4, §6 |
| **FR-008** | File Locking | An authenticated client can request a lock on a specific file. The server grants the lock only if the file is currently unlocked, and records the lock owner. | `auth` §3, §4 |
| **FR-009** | File Unlocking | The current lock owner can release a lock they hold. A non-owner's unlock attempt is rejected. | `auth` §3, §4 — *Derived Decision: non-owner rejection is a necessary consequence of "distributed locking," not separately spelled out in `auth`.* |
| **FR-010** | Concurrent Access Handling | When two or more clients attempt to lock the same file at (near) the same time, the server must grant the lock to exactly one of them and reject the others deterministically. | `auth` §3, §9 |
| **FR-011** | Audit Logging | The server writes a timestamped record for every login, logout, upload, download, lock, and unlock event (success and failure). | `auth` §3, §6 |
| **FR-012** | Error Handling / Client Feedback | Every remote call returns a well-defined success or a well-defined, classified error (see [API-spec.md](API-spec.md) Error Model) that the client can present to the user without exposing internal server detail. | `auth` §9 (testing implies defined failure modes) — *Derived Decision* |
| **FR-013** | Server Availability / Connection Handling | The client must detect and clearly report when the RMI server/registry is unreachable, rather than failing silently or crashing. | *Derived Decision — necessary for a usable distributed client.* |
| **FR-014** | Credential Provisioning | `auth` does not describe a self-service registration screen. User accounts are assumed pre-provisioned (see NFR/Assumption below). | *Engineering Assumption — see §4.1.* |

### 4.1 On Registration
`auth` never mentions a registration flow — only "log in with a username and password" (`auth` §3). Per the instruction in [p1.md](p1.md) §5 ("Do not invent registration if it is not required"), **AuthLock does not implement self-service registration**. User accounts are provisioned out-of-band (e.g., a fixed seed list, or an admin-only account-creation path — see **Open Question OQ-01** in [Context.md](Context.md)).

---

## 5. Non-Functional Requirements

| ID | Category | Requirement |
|---|---|---|
| **NFR-001** | Security | All credentials, session tokens, and file contents in transit must be protected per [Security.md](Security.md). No plaintext password storage. |
| **NFR-002** | Performance | Interactive operations (login, list, lock/unlock) should complete in well under 1 second on a healthy network; file transfer throughput is bounded by network and disk I/O, not by RMI overhead — *Engineering Assumption, no numeric SLA given in `auth`.* |
| **NFR-003** | Reliability | The server must not corrupt file data or lock state under concurrent access; a crashed client must not leave a file permanently locked (see stale-lock recovery, [Security.md](Security.md)). |
| **NFR-004** | Availability | Single-server availability is acceptable for this coursework scope (no HA/failover requirement — see [Architecture.md](Architecture.md) §Scalability). |
| **NFR-005** | Maintainability | Code organized by responsibility (auth, session, vault, lock, crypto, audit) per [Backend.md](Backend.md) and [Development-rules.md](Development-rules.md). |
| **NFR-006** | Scalability | Must handle multiple concurrent client sessions on one server instance; horizontal scaling is explicitly out of scope. |
| **NFR-007** | Usability | Swing client must surface connection state, lock state, and errors clearly (see [UIUX.md](UIUX.md)). |
| **NFR-008** | Portability | Runs on any OS with a compatible JRE/JDK (see [TRD.md](TRD.md) Runtime Requirements). |
| **NFR-009** | Observability | Server-side logging sufficient to diagnose failures during development and demonstration, distinct from the security audit log. |
| **NFR-010** | Auditability | Every security-relevant event is durably recorded per [Security.md](Security.md) Audit Logging. |

---

## 6. Scope

### In Scope
- Java RMI client/server architecture
- Username/password authentication, server-issued session tokens
- File upload, download, listing
- Server-side, per-file distributed locking with ownership and timeout/stale-lock recovery
- Encryption of file data in transit
- Server-side audit logging
- Java Swing GUI client
- Single cloud VM deployment
- Unit, integration, concurrency, and security testing of the above

### Out of Scope
- Self-service user registration (see §4.1)
- Multi-server clustering, load balancing, or high availability/failover
- Byte-range/partial file locking (whole-file locking only — see ADR-005)
- File versioning or version history
- Fine-grained role-based access control beyond "authenticated user" (no admin/guest tiers in the client)
- Mobile or web clients
- Databases beyond simple file-backed persistence (see ADR-010)
- Kubernetes, microservices, message queues, or other infrastructure not required by `auth` (see [p1.md](p1.md) §21)

### Future Enhancements
- Role-based authorization tiers (admin vs. standard user)
- Byte-range locking for large files
- File versioning / conflict resolution beyond exclusive locking
- Web or mobile client via a REST facade over the same vault service
- Centralized log aggregation / SIEM integration

---

## 7. Acceptance Criteria

| Requirement | Acceptance Criteria |
|---|---|
| FR-001 Login | Valid credentials return a session token in <1s under normal conditions; invalid credentials return `AUTHENTICATION_FAILED` and no token; the failure message does not disclose whether the username exists. |
| FR-002 Logout | After logout, any subsequent call using the same token returns `INVALID_SESSION`. |
| FR-003/004 Session | A session token issued at login is required and validated on every other remote call; an expired or unknown token is rejected with `INVALID_SESSION`. |
| FR-005 Listing | An authenticated client sees all vault files with correct name, size, owner, modified time, and current lock state. |
| FR-006 Upload | A file uploaded by client A is retrievable via FR-007 by any authenticated client with byte-identical content (verified by checksum). |
| FR-007 Download | Downloading a locked file (by a non-owner) either succeeds (read-only view) or is rejected, per the decision recorded in ADR-005/Security.md — behavior must be consistent and documented. |
| FR-008/009 Locking | Exactly one of N simultaneous lock requests for the same file succeeds; all others receive `FILE_LOCKED`. Only the owning session can unlock. |
| FR-010 Concurrency | A concurrency test with ≥3 simultaneous clients racing to lock the same file demonstrates exactly one winner every run (see [Testing.md](Testing.md) Concurrency Test). |
| FR-011 Audit | Every login, logout, upload, download, lock, and unlock (success or failure) produces exactly one audit record with the fields defined in [Security.md](Security.md). |
| FR-012 Errors | Every defined error code in [API-spec.md](API-spec.md) Error Model is reachable and produces the documented client-visible behavior. |
| FR-013 Availability | If the server is unreachable, the client displays a clear "server unavailable" state rather than hanging indefinitely or crashing. |

---

## 8. Traceability

Full requirement-to-design-to-test mapping lives in [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md). ID prefixes used across the documentation set:

- `FR-xxx` — Functional Requirement (this document)
- `NFR-xxx` — Non-Functional Requirement (this document)
- `SEC-xxx` — Security Requirement ([Security.md](Security.md))
- `ADR-xxx` — Architectural Decision ([Decision.md](Decision.md))
- `TEST-xxx` — Test Case ([Testing.md](Testing.md))

---

## 9. Document Status Notes

- This PRD is derived from a single-source coursework proposal (`auth`). Where `auth` was silent, items are explicitly marked *Engineering Assumption* or *Derived Decision* inline above.
- Numeric SLAs (NFR-002) are placeholders pending an explicit decision — see **Open Question OQ-02** in [Context.md](Context.md).
