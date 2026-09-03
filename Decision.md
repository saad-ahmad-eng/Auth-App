# Decision.md — Architectural Decision Records

**Project:** AuthLock
**Related documents:** [PRD.md](PRD.md) · [TRD.md](TRD.md) · [Architecture.md](Architecture.md) · [Security.md](Security.md)

Each ADR records context, the problem, options considered, the selected option, reasoning, trade-offs, and consequences. Status values used: `Accepted`, `Proposed`, `Pending`, `Rejected`.

---

## ADR-001 — Why Java RMI?

**Status:** Accepted
**Context:** `auth` §2 explicitly mandates Java RMI as the distributed-communication mechanism, over TCP.
**Problem:** Choose a client/server communication technology that lets the Swing client invoke server-side vault operations.

**Options considered:**
| Option | Pros | Cons |
|---|---|---|
| Java RMI | Native Java object-call semantics; minimal boilerplate; built-in serialization; matches module's RMI learning outcome (LO3) | Java-only clients; less portable than HTTP-based protocols |
| Raw TCP sockets | Full control; language-agnostic | Requires hand-rolled framing, serialization, and dispatch — much more implementation risk for a coursework timeline |
| REST (HTTP/JSON) | Ubiquitous, language-agnostic, easy to test with curl/Postman | Does not satisfy the module's explicit RMI requirement; would misrepresent the assignment brief |
| gRPC | Fast, strongly typed, streaming support | Adds a code-generation toolchain and a dependency not required by `auth`; overkill for the stated scope |

**Selected option:** Java RMI.
**Reason:** Directly mandated by `auth` and the module's learning outcomes (LO3/LO4). No technical justification is needed beyond compliance — but RMI is also a reasonable fit: it is the lowest-friction way to expose typed server methods to a Java Swing client without hand-building a wire protocol.
**Trade-offs:** Locks the project into Java-only clients; RMI's classic dynamic-port behavior complicates cloud firewalling (mitigated — see §2.6 in [TRD.md](TRD.md), fixed object port).
**Consequences:** All remote-call design in [API-spec.md](API-spec.md) follows RMI interface conventions (`Remote`, `RemoteException`, `Serializable` DTOs).

---

## ADR-002 — Why Java Swing?

**Status:** Accepted
**Context:** `auth` §2 and §21 explicitly mandate a Swing GUI client.
**Problem:** Choose a client UI toolkit.
**Options considered:** Swing (explicit requirement) vs. JavaFX vs. a web front-end.
**Selected option:** Java Swing.
**Reason:** Directly mandated by `auth`. Swing also ships with the JDK (no extra runtime module needed, unlike JavaFX post-JDK 11), simplifying packaging of the client `.jar`.
**Trade-offs:** Dated look-and-feel compared to JavaFX or a web client; acceptable for a coursework demonstrator.
**Consequences:** UI design in [UIUX.md](UIUX.md) is scoped to Swing components only (no web/mobile UI patterns per [p1.md](p1.md) §18).

---

## ADR-003 — Why Centralized Server-Side File Storage?

**Status:** Accepted
**Context:** `auth` describes one RMI server hosting "the file vault" — a single central store, not a peer-to-peer or client-distributed model.
**Problem:** Decide where uploaded file bytes physically live.
**Options considered:**
| Option | Pros | Cons |
|---|---|---|
| Centralized server-side storage | Single source of truth; trivial to lock, encrypt, and audit centrally | Single point of failure (acceptable — see NFR-004/Scalability) |
| Client-distributed / P2P storage | No central bottleneck | Makes locking and auditing far harder to reason about; not what `auth` describes |
| Cloud object storage (e.g., S3-style bucket) | Offloads durability concerns | Introduces a dependency/service not mentioned in `auth`; conflicts with [p1.md](p1.md) §21 "do not overengineer" |

**Selected option:** Centralized server-side filesystem storage under the RMI server's control.
**Reason:** Matches `auth`'s architecture description directly; makes locking, encryption, and audit logging tractable to implement and reason about within the coursework timeline.
**Trade-offs:** Server is a single point of failure/bottleneck — explicitly acceptable per PRD NFR-004 and scope.
**Consequences:** See ADR-010 (storage architecture) for the concrete layout.

---

## ADR-004 — Why Server-Side Distributed Locking?

**Status:** Accepted
**Context:** `auth` §3 states the server locks a file when a user opens it "so no other user can modify it concurrently," explicitly drawing an analogy to database record locking.
**Problem:** Decide where lock state is owned and enforced.
**Options considered:** Server-side lock manager (single authority) vs. client-side optimistic locking (compare-and-swap on save) vs. no locking (last-write-wins).
**Selected option:** Server-side, server-authoritative lock manager.
**Reason:** `auth` explicitly requires preventing concurrent modification, not just detecting it after the fact. A server-side authority is the only design that can guarantee mutual exclusion across independent, untrusted clients.
**Trade-offs:** Adds server-side state and complexity (lock table, timeouts, stale-lock recovery) versus a simpler optimistic approach.
**Consequences:** Drives FR-008/FR-009/FR-010 and the entire Distributed Lock Security section of [Security.md](Security.md).

---

## ADR-005 — Lock Granularity

**Status:** Accepted
**Context:** `auth` describes locking "a file," not parts of a file.
**Problem:** Choose the unit a lock protects.
**Options considered:**
| Option | Pros | Cons |
|---|---|---|
| Whole-file locking | Simple to implement and reason about; matches `auth`'s description; sufficient to demonstrate the required concurrency-control learning outcome | Coarser — blocks all concurrent edits to a file, even non-overlapping ones |
| Byte-range locking | Finer-grained concurrent access | Significant added complexity (range tracking, merge conflicts); not requested by `auth`; risks overengineering per [p1.md](p1.md) §21 |
| Metadata-only locking (lock the record, not the bytes) | Very lightweight | Does not actually prevent concurrent byte-level writes — insufficient for the stated goal |

**Selected option:** Whole-file locking.
**Reason:** Directly matches `auth`'s description and is the simplest design that fully satisfies the "prevent concurrent modification" requirement.
**Trade-offs:** No fine-grained concurrent editing — acceptable; out of scope per PRD §6.
**Consequences:** [Backend.md](Backend.md) File Lock model keys locks by file ID only.

---

## ADR-006 — Session Token Architecture

**Status:** Accepted
**Context:** `auth` §3/§6 requires each logged-in client to hold a session token used to authorize subsequent calls, but does not specify token format, generation, or lifecycle.
**Problem:** Design a session token scheme.
**Options considered:**
| Option | Pros | Cons |
|---|---|---|
| Server-generated opaque random token (e.g., UUID or CSPRNG-derived string), server-side session table | Simple; server retains full control (can revoke instantly); no crypto key management needed | Server must hold session state (acceptable — single server, in scope) |
| Signed stateless token (JWT-style) | No server-side session table needed | Requires key management and signature verification machinery not otherwise needed by this project; overengineering per [p1.md](p1.md) §21; harder to instantly revoke on logout |

**Selected option:** Server-generated opaque token backed by an in-memory (or lightweight persisted) server-side session table.
**Reason:** Simplest design that satisfies FR-002/FR-003/FR-004 and supports instant logout invalidation, which a self-contained signed token does not do cleanly.
**Trade-offs:** Session state does not survive a server restart unless explicitly persisted — acceptable for coursework scope (**Engineering Assumption**, flagged as **Open Question OQ-04** in [Context.md](Context.md): should sessions persist across server restarts?).
**Consequences:** Token generation/entropy/expiry rules defined in [Security.md](Security.md) Session Security.

---

## ADR-007 — Encryption Strategy

**Status:** Proposed
**Context:** `auth` §3 requires files to be "encrypted during transfer so intercepted data cannot be read without authorisation," and `auth` §4 references Java's cryptography APIs. It does not specify an algorithm.
**Problem:** Choose a concrete, standard, authenticated encryption approach for file data in transit (and decide whether at-rest encryption is also required).
**Options considered:**
| Option | Pros | Cons |
|---|---|---|
| AES-GCM (symmetric, authenticated encryption) via `javax.crypto` | Confidentiality + integrity in one primitive; standard, well-vetted; native JCE support | Requires key distribution/management design |
| Custom/home-rolled cipher | None | Explicitly forbidden — [p1.md](p1.md) §8 "Do not choose an unsafe custom cryptographic protocol" |
| RMI over TLS (`SslRMIClientSocketFactory`/`SslRMIServerSocketFactory`) for transport-level encryption | Encrypts the entire RMI channel, not just file payloads; simpler application code | Adds certificate management for a coursework project; still worth considering as a complementary/alternative layer |

**Selected option (proposed, pending final confirmation):** AES-256-GCM applied to file payloads at the application layer, with **RMI-over-TLS as a recommended complementary/alternative transport control** to protect the whole channel (including credentials).
**Reason:** Satisfies `auth`'s explicit requirement using standard, authenticated Java cryptography APIs, and avoids the custom-crypto prohibition.
**Trade-offs:** Key management (see [Security.md](Security.md) Encryption §Key Management) must be designed even for a coursework project — this is flagged as **Open Question OQ-05**.
**Consequences:** Full design in [Security.md](Security.md) Encryption section. Status remains `Proposed` (not `Accepted`) until key-management approach is finalized.

---

## ADR-008 — Audit Logging Strategy

**Status:** Accepted
**Context:** `auth` §3/§6 requires a timestamped server-side log of logins, uploads, downloads, lock/unlock events, and logouts.
**Problem:** Decide how and where audit events are recorded.
**Options considered:** Append-only local log file (structured, e.g. one JSON line per event) vs. a full logging database vs. a third-party log-aggregation service.
**Selected option:** Append-only local structured log file on the server (e.g., `audit.log`, one JSON object per line).
**Reason:** Matches `auth`'s "timestamped log file" description exactly; avoids introducing infrastructure (databases, external services) not required — per [p1.md](p1.md) §21.
**Trade-offs:** No built-in query/search UI — acceptable for coursework demonstration (log can be inspected directly or with simple `grep`/scripting for the report).
**Consequences:** Full event schema in [Security.md](Security.md) Audit Logging; write path defined in [Backend.md](Backend.md).

---

## ADR-009 — Cloud VM Deployment

**Status:** Accepted
**Context:** `auth` §8 explicitly specifies a single low-cost/free-tier cloud VM, static public IP, opened registry + object ports, `java.rmi.server.hostname` set to the public IP, and the server run in the background (`nohup`/systemd).
**Problem:** Choose the deployment target and topology.
**Options considered:** Single free-tier VM (explicit) vs. managed container platform vs. multi-instance/load-balanced deployment.
**Selected option:** Single cloud VM (AWS EC2 free tier, Oracle Cloud Always Free, or GCP free tier — provider left open, see Open Question OQ-06), exactly as `auth` describes.
**Reason:** Directly mandated; also consistent with [p1.md](p1.md) §21/§23 (no Kubernetes, no load balancers).
**Trade-offs:** No redundancy/failover — explicitly acceptable, matches stated scope.
**Consequences:** Full deployment steps in [Architecture.md](Architecture.md) Deployment Architecture.

---

## ADR-010 — Storage Architecture

**Status:** Accepted
**Context:** Following ADR-003 (centralized server-side storage), a concrete on-disk layout is needed for file bytes and metadata.
**Problem:** Decide the physical/logical storage layout for vault files, metadata, sessions, and locks.
**Options considered:** Filesystem directory + lightweight metadata file/embedded store vs. full relational database (e.g., PostgreSQL/MySQL) vs. embedded database (e.g., H2/SQLite).
**Selected option:** Server filesystem directory for file bytes (named by internal file ID, not client-supplied name — SEC-006), plus a lightweight metadata store. **Resolved (Implementation Phase 4):** a flat `<fileId>.properties` sidecar file per stored file (key=value pairs: filename, owner, size, checksum, iv, timestamps), read back into an in-memory index at server startup — not an embedded database (SQLite/H2). This closes OQ-07: a per-file properties sidecar needed no new dependency, is trivially human-readable for the coursework report/demo, and was sufficient at the tested scale (verified: metadata and content both survive a service restart — `VaultFileServiceTest.metadataAndContentSurviveServiceRestart_resolvesOQ07`).
**Reason:** `auth` never mentions a database; a full RDBMS would be overengineering per [p1.md](p1.md) §21. A lightweight approach is sufficient for coursework-scale data volumes.
**Trade-offs:** Less robust than a real DB for concurrent metadata writes — mitigated by the same in-process synchronization used for locks (single JVM, shared in-memory structures backed by periodic/append-only persistence).
**Consequences:** Concrete data models in [Backend.md](Backend.md); storage mechanism implemented in `authlock-server.vault.VaultFileService`.

---

## Decision Log Summary

| ADR | Topic | Status |
|---|---|---|
| ADR-001 | Java RMI | Accepted |
| ADR-002 | Java Swing | Accepted |
| ADR-003 | Centralized server storage | Accepted |
| ADR-004 | Server-side distributed locking | Accepted |
| ADR-005 | Whole-file lock granularity | Accepted |
| ADR-006 | Opaque server-side session tokens | Accepted |
| ADR-007 | AES-GCM encryption + optional RMI/TLS | Proposed |
| ADR-008 | Append-only audit log file | Accepted |
| ADR-009 | Single cloud VM deployment | Accepted |
| ADR-010 | Filesystem + lightweight metadata store | Accepted |
