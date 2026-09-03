# TRD.md — Technical Requirements Document

**Project:** AuthLock — RMI-Based Secure File Vault with Distributed Locking
**Developer:** Kuamil jeffery
**Related documents:** [PRD.md](PRD.md) · [Decision.md](Decision.md) · [Architecture.md](Architecture.md) · [Backend.md](Backend.md)

---

## 1. Technology Stack

`auth` (§2, §4, §8) explicitly specifies: Java, Java RMI over TCP, Java Swing GUI, and cloud VM deployment. This TRD follows that stack without substitution.

| Layer | Technology | Status |
|---|---|---|
| Language/runtime | Java (JDK — version TBD, see §2.1) | Explicit (`auth` §2) |
| Distributed communication | Java RMI (over TCP) | Explicit (`auth` §2, §4) |
| GUI | Java Swing | Explicit (`auth` §2, §21) |
| Cryptography | `javax.crypto` (JCA/JCE) | Explicit (`auth` §3 "encryption"), primitive choice in [Security.md](Security.md) |
| File storage | Server local filesystem | Derived Decision — see ADR-003/ADR-010 |
| Metadata persistence | Flat file / simple embedded store (not a full RDBMS) | Derived Decision — see ADR-010 |
| Build system | Maven or Gradle | Recommendation — see §5 |
| Deployment target | Single cloud VM (AWS EC2 free tier / Oracle Cloud Always Free / GCP free tier) | Explicit (`auth` §8) |
| IDE | VS Code | Explicit (per [p1.md](p1.md): "we will run this in vs Code not in another code editor") |

No frameworks conflicting with this stack (no Spring, no REST layer, no message broker) are introduced, per [p1.md](p1.md) §21 ("Do Not Overengineer").

---

## 2. Runtime Requirements

### 2.1 Java Version
`auth` does not pin a JDK version. **Recommendation:** target a current LTS release (JDK 17 or JDK 21) for language features and long-term support; any JDK ≥ 11 is technically sufficient for RMI/Swing. **Open Question OQ-03** (final version pin) — see [Context.md](Context.md).

### 2.2 Operating System
Server: any OS with a compatible JRE (Linux recommended for the cloud VM, matching free-tier VM images). Client: any OS with a compatible JRE (Windows/Linux/macOS) — Swing is portable.

### 2.3 Memory
No numeric requirement in `auth`. **Recommendation:** default JVM heap is sufficient for coursework-scale file sizes and user counts; no explicit `-Xmx` tuning required unless testing reveals otherwise.

### 2.4 Disk
Server disk must accommodate the vault's stored files plus metadata and audit logs. No numeric quota specified in `auth` — **Engineering Assumption:** coursework-scale usage (a handful of users, files in the low tens of MB each) fits comfortably on a free-tier VM's default disk.

### 2.5 Network
- RMI traffic between client and server over TCP.
- Two ports required: the **RMI registry port** (conventionally 1099) and the **RMI object/dynamic port** used by the exported remote object (see §2.6).
- `auth` §8 explicitly requires opening both ports in the cloud firewall/security group and setting `java.rmi.server.hostname` to the VM's public IP.

### 2.6 RMI Requirements
- An `rmiregistry` (or `LocateRegistry.createRegistry()` embedded in the server process) must be running and reachable.
- The server exports one primary remote object implementing the vault service interface (see [API-spec.md](API-spec.md)).
- **Derived Decision:** the server binds the remote object's port to a **fixed, known port** (rather than a random ephemeral port) so the cloud firewall/security group can open a single predictable range — random RMI object ports are difficult to firewall correctly on a cloud VM.

### 2.7 Required Ports
| Port | Purpose | Direction |
|---|---|---|
| 1099 (default, configurable) | RMI Registry | Client → Server |
| Fixed application port (e.g. 5000 — TBD, see ADR/Open Question) | RMI remote object (`VaultService`) | Client → Server |

---

## 3. Technical Requirements

| Area | Requirement |
|---|---|
| RMI registry | Server process starts (or connects to) an RMI registry on a known port at startup. |
| Remote interfaces | A `VaultService` interface extends `java.rmi.Remote`; every method declares `throws RemoteException` plus relevant application exceptions. See [API-spec.md](API-spec.md). |
| RMI stubs | Generated implicitly via dynamic stub generation (JDK ≥ 5 behavior — no explicit `rmic` step needed). |
| Serializable objects | All DTOs crossing the RMI boundary (session info, file metadata, request/response objects) implement `java.io.Serializable` with a declared `serialVersionUID`. |
| Binary file transfer | File bytes are transferred as `byte[]` (chunked for large files — see [Backend.md](Backend.md) Concurrency Model) inside a Serializable DTO, not as raw sockets outside RMI. |
| Session handling | Session tokens are opaque, server-generated, and validated on every call except `login()` (FR-004). |
| Threading | RMI's default per-call thread pool handles concurrent client calls; server-side shared state (session table, lock table, file metadata) must be thread-safe. |
| Synchronization | Lock acquisition must be atomic — implemented with a `ConcurrentHashMap` (or equivalent) keyed by file ID, using atomic compare-and-set semantics, not check-then-act. |
| Lock management | See [Backend.md](Backend.md) File Lock model and [Security.md](Security.md) Distributed Lock Security. |
| File persistence | Files written to a server-controlled storage directory, named by an internal file ID (not the client-supplied filename) to prevent path traversal (SEC-006). |
| Audit logging | Append-only log file (or structured log) written synchronously per event, per [Security.md](Security.md). |
| Encryption | File bytes encrypted for transit; primitive and mode specified in [Security.md](Security.md) Encryption. |
| Error handling | All remote methods surface errors via a defined exception hierarchy or a structured error DTO — see [API-spec.md](API-spec.md) Error Model. |

---

## 4. Development Environment

| Item | Value | Classification |
|---|---|---|
| IDE/editor | VS Code (with Java extension pack) | Explicit — [p1.md](p1.md) |
| JDK | JDK 17 LTS | **Confirmed** — installed and verified in the target environment (`openjdk 17.0.20`); resolves Open Question OQ-03 |
| Build system | **Gradle** (multi-project build: `authlock-common`, `authlock-server`, `authlock-client`) | **Confirmed** — Maven is not available in the target environment, Gradle 8.14 is; resolves the build-tool half of OQ-03 |
| Version control | Git, single repository containing client + server modules | Recommendation — adopted at Phase 1 |
| Testing tools | JUnit 5 for unit/integration tests; a small standalone concurrency-test harness (plain Java, multiple threads/processes) for the locking race test | Recommendation |
| Packaging | Two runnable JARs (client, server) per `auth` §10 deliverables | Explicit (`auth` §10) |

**Decision update (Implementation Phase 1):** the original recommendation favored Maven for its predictable, less script-driven project files. However, the actual development/target environment has Maven unavailable and Gradle 8.14 pre-installed. Per [Development-rules.md](Development-rules.md) §1 ("prefer simple maintainable solutions") — introducing a Maven installation step purely to satisfy a soft recommendation would add friction with no benefit — **Gradle is adopted as the confirmed build tool**. Either tool would have satisfied `auth`; this is an environment-driven implementation decision, not a requirements change, and does not need a new ADR (it does not affect architecture, security, or requirements — only tooling).

---

## 5. Notes on Uncertain Choices

Every item in this document that does not trace directly to explicit text in `auth` is marked **Recommendation** or **Derived Decision**, not a hard requirement. Where a recommendation must be finalized before implementation (JDK version, build tool, fixed RMI object port number), it is tracked as an **Open Question** in [Context.md](Context.md) so it isn't silently locked in.
