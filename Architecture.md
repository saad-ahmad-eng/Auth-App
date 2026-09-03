# Architecture.md — System Architecture

**Project:** AuthLock
**Related documents:** [PRD.md](PRD.md) · [TRD.md](TRD.md) · [Decision.md](Decision.md) · [Backend.md](Backend.md) · [API-spec.md](API-spec.md)

---

## 1. High-Level Architecture

```text
+-------------------------+
|   Swing RMI Client      |
|  (login UI, dashboard)  |
+------------+-------------+
             |
             |  Java RMI over TCP
             |  (registry port + fixed object port)
             |
+------------v-------------+
|        RMI Server        |
|                           |
|  +---------------------+  |
|  | VaultService (Remote)|  |
|  +----------+----------+  |
|             |             |
|  +----------v----------+  |
|  | Authentication Svc  |  |
|  +---------------------+  |
|  | Session Manager     |  |
|  +---------------------+  |
|  | Vault (File) Service|  |
|  +---------------------+  |
|  | Lock Manager        |  |
|  +---------------------+  |
|  | Encryption Service  |  |
|  +---------------------+  |
|  | Audit Logger        |  |
|  +---------------------+  |
+------------+--------------+
             |
             v
+---------------------------+
|  Secure File Storage      |
|  (server filesystem +     |
|   metadata store)         |
+---------------------------+
```

This refines the `auth`-derived skeleton from [p1.md](p1.md) §10 by naming the concrete internal components (Authentication Service, Session Manager, Vault Service, Lock Manager, Encryption Service, Audit Logger) that sit behind the single `VaultService` remote interface. **Only one remote interface is exposed to the client** — the internal services are composition, not separately-remoted objects — keeping the RMI surface small and the design simple, per [p1.md](p1.md) §21.

---

## 2. Components

### 2.1 Swing RMI Client
- **Purpose:** User-facing desktop application.
- **Responsibilities:** Render login screen and dashboard (see [UIUX.md](UIUX.md)); look up the `VaultService` stub via the RMI registry; invoke remote methods; encrypt/decrypt file payloads at the client boundary if application-layer AES is used (ADR-007); present errors and lock/connection state.
- **Inputs:** User interaction (credentials, file selection, lock/unlock clicks).
- **Outputs:** RMI calls to the server; local file system reads/writes (for files the user opens/saves).
- **Dependencies:** `VaultService` stub, RMI registry lookup, [API-spec.md](API-spec.md) DTOs.
- **Security considerations:** Never persists the session token to disk beyond the running session; never trusts server responses without validating expected shape; does not perform authorization decisions itself (server is authoritative per Development-rules §3 "Never trust the client" — this cuts both ways: the client also must not assume its own UI-level checks are sufncient).

### 2.2 RMI Registry
- **Purpose:** Name service the client uses to locate the `VaultService` remote object.
- **Responsibilities:** Bind the exported `VaultService` stub under a known name at server startup; resolve lookups from clients.
- **Inputs:** `bind()`/`rebind()` from the server process at startup; `lookup()` from clients.
- **Outputs:** Serialized stub reference to the client.
- **Dependencies:** Runs in-process with the server (via `LocateRegistry.createRegistry()`) or as a standalone `rmiregistry` process — **Recommendation:** in-process, so the server's single startup script/JAR fully owns its lifecycle (simpler cloud deployment, one process to run under `nohup`/systemd per ADR-009).
- **Security considerations:** Registry itself performs no authentication — it merely hands out a stub reference; all real authorization happens inside `VaultService` methods (§2.3).

### 2.3 VaultService (Remote Interface / RMI Service Layer)
- **Purpose:** The single point of entry for all client-initiated remote operations.
- **Responsibilities:** Declare the remote contract (see [API-spec.md](API-spec.md)); validate session tokens on every call except `login()`; delegate to the internal services below; translate internal exceptions into the defined error model.
- **Inputs:** RMI calls from clients (credentials, session tokens, file DTOs, file IDs).
- **Outputs:** DTOs / primitive results, or declared exceptions.
- **Dependencies:** Authentication Service, Session Manager, Vault (File) Service, Lock Manager, Audit Logger.
- **Security considerations:** This is the sole enforcement boundary the client cannot bypass — every authorization check in [Security.md](Security.md) is implemented here or in the services it calls, never left to the client.

### 2.4 Authentication Service
- **Purpose:** Verify credentials.
- **Responsibilities:** Hash/verify passwords (SEC-002); look up user records; report generic failure to avoid enumeration.
- **Inputs:** username, password.
- **Outputs:** authentication success/failure + user identity on success.
- **Dependencies:** User credential store (part of the metadata store, ADR-010).
- **Security considerations:** Constant-time comparison; no plaintext password ever leaves this component; failures logged via Audit Logger, not the password itself.

### 2.5 Session Manager
- **Purpose:** Own session lifecycle.
- **Responsibilities:** Generate opaque tokens (SecureRandom); create/validate/expire/invalidate sessions; periodic or lazy cleanup of expired sessions (also triggering stale-lock recovery, §2.6).
- **Inputs:** authenticated user identity (on create); token (on validate/invalidate).
- **Outputs:** session token (on create); validity + owning user (on validate).
- **Dependencies:** none beyond an in-memory/lightly-persisted session table.
- **Security considerations:** Full detail in [Security.md](Security.md) §5.

### 2.6 Vault (File) Service
- **Purpose:** Own file storage and metadata.
- **Responsibilities:** Store uploaded bytes under a server-generated file ID; retrieve bytes for download; maintain/return file metadata list (FR-005); compute/verify checksums.
- **Inputs:** file bytes + declared filename (upload); file ID (download, list).
- **Outputs:** file bytes, metadata records.
- **Dependencies:** Secure File Storage (§2.9), Encryption Service (if server-side encryption/decryption is used rather than pure client-side), Lock Manager (to check lock state before allowing writes).
- **Security considerations:** Path-traversal prevention (SEC-006); checksum verification.

### 2.7 Lock Manager
- **Purpose:** Own distributed lock state (the project's core innovation, per `auth` §3).
- **Responsibilities:** Atomically grant/deny locks (FR-008/FR-010); track owning session and acquisition time; enforce timeout; release locks on explicit unlock or on owning-session expiry (stale lock recovery, [Security.md](Security.md) §8).
- **Inputs:** file ID + requesting session (lock/unlock calls); session-expiry notifications from Session Manager.
- **Outputs:** lock granted/denied; current lock state for a file (used by Vault Service's listing/metadata).
- **Dependencies:** Thread-safe map (e.g., `ConcurrentHashMap<FileId, LockRecord>`) — no external dependency.
- **Security considerations:** Full detail in [Security.md](Security.md) §8; this is the component most directly tested by the mandatory concurrency test ([Testing.md](Testing.md)).

### 2.8 Encryption Service
- **Purpose:** Provide the AES-GCM encrypt/decrypt operations (ADR-007) and the RMI-over-TLS socket-factory configuration — **both implemented, Implementation Phase 6.**
- **Responsibilities:** Server-side half (`authlock-server.crypto.EncryptionService`): decrypt an incoming upload's ciphertext before it reaches `VaultFileService`; encrypt (fresh IV) the plaintext read back for a download. Client-side half (thin, direct use of the shared `AesGcmCipher` in `ClientMain`): the mirror image. Both share `authlock-common.crypto.AesGcmCipher`/`SharedKeyProvider`. Separately, `authlock-common.tls.DevTlsSetup` configures RMI-over-TLS (self-signed dev cert, auto-generated via `keytool`) for the whole channel, used by both `ServerMain` and `ClientMain`.
- **Inputs:** plaintext bytes + key (encrypt); ciphertext + IV + tag + key (decrypt).
- **Outputs:** ciphertext + IV (encrypt); plaintext or a `TamperDetectedException` (decrypt).
- **Dependencies:** `javax.crypto` (JCE) for AES-GCM; `javax.rmi.ssl` (JSSE, JDK-bundled) for RMI-over-TLS; key material via `SharedKeyProvider` (pre-shared key file — resolution of OQ-05, Security.md §7).
- **Security considerations:** Entire component is governed by [Security.md](Security.md) §7; no custom cryptography. RMI-over-TLS's dev certificate is `localhost`-scoped; see §3 Deployment Architecture for the Phase 11 cloud-deployment implication.

### 2.9 Audit Logger — **implemented, Implementation Phase 7**
- **Purpose:** Durable, structured recording of every security-relevant event.
- **Responsibilities:** Append one structured record per event (Security.md §9) synchronously so no event is lost even if the server crashes immediately after. `VaultServiceImpl` calls it at every method's success/failure exit point except `listFiles()` (deliberately unaudited, Security.md §9).
- **Inputs:** event type, actor, operation, target, result, metadata — via `AuditLogger.logSuccess`/`logFailure` (`authlock-server.audit`).
- **Outputs:** append to `audit.log` (configurable via `-Dauthlock.audit.file=<path>`), one JSON object per line, hand-serialized (no JSON library dependency).
- **Dependencies:** A configurable log path (default the server's working directory); no dependency on Secure File Storage/`VaultFileService` — audit and vault storage are independent concerns even though they may share a disk.
- **Security considerations:** Never receives passwords, tokens, or keys as loggable fields (enforced by only accepting the sanitized schema in §9 of Security.md) — verified by test (TEST-SEC-005). A failed audit write does not block the underlying operation (fail-open on the log's own durability, not on vault availability).

### 2.10 Secure File Storage
- **Purpose:** Physical persistence layer.
- **Responsibilities:** Store file bytes (by internal file ID) and the metadata/session/lock-supporting store (per ADR-010).
- **Inputs:** writes from Vault Service, Audit Logger, and (if persisted) Session/Lock Manager.
- **Outputs:** reads for the same.
- **Dependencies:** VM local disk.
- **Security considerations:** Directory permissions restricted to the server process's OS user; not directly network-exposed (only reachable via `VaultService`).

---

## 3. Deployment Architecture

Per `auth` §8 and ADR-009:

```text
                 Internet
                    |
        (RMI over TCP, ports 1099 + fixed object port)
                    |
        +-----------v------------+
        |     Cloud VM (Linux)    |
        |  Static Public IP       |
        |  Security Group:        |
        |    allow 1099 (registry)|
        |    allow <objPort>      |
        |                         |
        |  java -Djava.rmi.server.hostname=<publicIP> \
        |       -jar authlock-server.jar             |
        |  (run via nohup or systemd, survives        |
        |   client disconnects)                        |
        |                                              |
        |  Vault storage dir + audit.log on local disk |
        +----------------------------------------------+

  Local developer machine
        +----------------------------+
        |  java -jar authlock-client.jar |
        |  connects to <publicIP>:1099   |
        +----------------------------+
```

| Aspect | Detail |
|---|---|
| **Local development** | Server and client both run on `localhost` (`java.rmi.server.hostname=localhost` or omitted) during development/unit testing, before cloud deployment. |
| **Cloud VM** | A single free-tier instance (AWS EC2 t2.micro / Oracle Cloud Always Free / GCP free tier — provider choice is Open Question OQ-06), running a Linux distribution with a compatible JRE installed. |
| **Public IP** | The VM is assigned a static public IP; `-Djava.rmi.server.hostname=<publicIP>` is set on the server JVM so stubs handed to remote clients embed the correct routable address (a well-known RMI pitfall otherwise — stubs default to embedding an internal/loopback address). |
| **RMI registry** | Started in-process on the server (see §2.2) on port 1099 (or a configured alternative). |
| **RMI object port** | Fixed (not ephemeral) so the security group can open a single predictable port — see TRD §2.6. Confirmed as **5000** (`RmiConfig.SERVICE_PORT`) in Implementation Phase 2. |
| **Firewall/security group** | Inbound rules opened only for the registry port and the fixed object port, from the client's expected source (or `0.0.0.0/0` for coursework demo convenience, documented as a conscious, reviewed trade-off). |
| **Client-to-cloud communication** | The Swing client connects using the VM's public IP and the registry port, identical code path to the localhost case — proving genuine distributed operation per `auth` §8. |
| **Process management** | Server started via `nohup java -jar authlock-server.jar &` or a systemd unit, so it survives SSH session termination and individual client disconnects. |
| **TLS certificate (Phase 6 addition)** | The auto-generated dev certificate (`DevTlsSetup`) is scoped to `CN=localhost`, SAN `dns:localhost,ip:127.0.0.1` — valid for local development, **not** for the cloud VM's public IP. Before Phase 11 client connections will succeed over TLS, the certificate must be regenerated with the VM's public IP/hostname in its SAN (delete the old `certs/authlock-dev.p12` on the VM and let `DevTlsSetup` regenerate it with an updated SAN, or supply a properly issued certificate) — tracked as an explicit Phase 11 task, not yet done. |

---

## 4. Scalability

This is intentionally a **single-server architecture** (ADR-003, ADR-009), consistent with `auth`'s scope and [p1.md](p1.md) §21's explicit instruction not to overengineer. Known limitations, accepted as out of scope:

- **Single point of failure:** if the VM or server process goes down, all clients lose service until it is restarted. No failover/replica is provided.
- **Vertical-only scaling:** the design does not shard files or sessions across multiple server instances; a free-tier VM's CPU/memory/disk bounds the number of concurrent users and total vault size the system can practically serve.
- **In-memory session/lock state:** if persisted only in memory (per ADR-006/pending OQ-04), a server restart drops active sessions and locks (mitigated by clients needing to simply log in again; no data loss for stored files).

These limitations are appropriate for a coursework demonstrator whose goal is to prove distributed-system *correctness* (RMI communication, locking, security), not production-grade scale. Any future move toward multi-instance deployment would require re-opening ADR-003/ADR-004 (e.g., moving lock state to a shared coordination service) — explicitly deferred, see PRD §6 Future Enhancements.
