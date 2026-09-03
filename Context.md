# Context.md — Persistent Project Context

**This is the single most important file for any future session (human or Claude) working on AuthLock. Read this before touching anything else.**

---

## 1. Project Identity

- **Name:** AuthLock — RMI-Based Secure File Vault with Distributed Locking
- **Module:** CS6006NU – Distributed, Cloud and IoT Systems
- **Developer of record (per explicit instruction in [p1.md](p1.md)):** Kuamil jeffery — *note: the original proposal document `auth` lists the developer as "Saad Ahmad" (email `sayedsaadahmad254@gmail.com`); [p1.md](p1.md) explicitly overrides this to "Kuamil jeffery." This discrepancy was not silently resolved — see §7 Open Questions, OQ-14.*
- **Purpose:** A distributed client/server Java application letting authenticated users securely upload, download, and share files through a central RMI vault server, with server-enforced distributed locking preventing concurrent edits, encrypted file transfer, session-scoped authorization, and full audit logging.
- **Stack:** Java, Java RMI (over TCP), Java Swing, `javax.crypto` (JCE), server filesystem storage, single cloud VM deployment. See [TRD.md](TRD.md).
- **Architecture:** Single RMI server exposing one `VaultService` remote interface, composed internally of Authentication, Session, Vault/File, Lock Manager, Encryption, and Audit Logger components, backed by server-local file + metadata storage. See [Architecture.md](Architecture.md).

---

## 2. Current Status

**Status: `In Progress` — Documentation Phase `Completed`; Implementation Phases 1–5 `Completed`; Phase 6 `Not Started`.**

## 3. Current Phase

**Implementation Phase 5 — Distributed Locking — Completed.** The project's headline feature (`auth` §3) is implemented and its mandatory concurrency proof (TEST-CONC-001) is passing reliably. Next up: **Phase 6 — Encryption** (not started) — the one phase with a hard-blocking Open Question (OQ-05).

The documentation package (§4) is finished and remains the source of truth. Implementation has now begun, following [Implementation.md](Implementation.md) phase-by-phase, with [Development-rules.md](Development-rules.md) governing every change.

---

## 4. Completed Work

All 14 required documents have been created, in the dependency order specified by [p1.md](p1.md) §25:

1. [PRD.md](PRD.md) — Product Requirements (FR-001–FR-014, NFR-001–NFR-010, personas, scope, acceptance criteria)
2. [TRD.md](TRD.md) — Technical Requirements (stack, runtime, RMI/ports, dev environment)
3. [Decision.md](Decision.md) — ADR-001 through ADR-010
4. [Security.md](Security.md) — Threat model, SEC-001–SEC-010, encryption, locking security, audit schema, controls matrix
5. [Development-rules.md](Development-rules.md) — Code quality, security, git, and change-management rules
6. [Architecture.md](Architecture.md) — Component diagram, responsibilities, deployment architecture, scalability limits
7. [flow.md](flow.md) — 18 Mermaid sequence diagrams covering every required flow
8. [Backend.md](Backend.md) — Backend modules, logical data models, concurrency model
9. [API-spec.md](API-spec.md) — Full `VaultService` contract, DTOs, error model
10. [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md) — Full requirement traceability matrix
11. [Implementation.md](Implementation.md) — 13 phases (0–12), each with objective/tasks/DoD
12. [Testing.md](Testing.md) — Test levels, test cases, mandatory concurrency test, test matrix (all `Not Run`)
13. [UIUX.md](UIUX.md) — Login screen, dashboard, notifications, UX rules, accessibility
14. [Context.md](Context.md) — this document

Source materials consulted: `p1.md` (master prompt) and `AuthLock_Proposal (1).docx` (the `auth` proposal, converted via LibreOffice headless to text for analysis).

**Plus, Implementation Phase 1 — Project Skeleton (see §13 Implementation Log for full detail):** Gradle multi-module build (`authlock-common`/`authlock-server`/`authlock-client`) scaffolded, full package structure created, stub entry points added, Git repository initialized with an initial commit, build verified working (`./gradlew build`).

**Plus, Implementation Phase 2 — RMI Infrastructure:** real `VaultService` interface with `ping()`, registry bootstrap, client-side lookup, verified over a genuine cross-process `localhost` round trip and an automated test (TEST-INT-001).

**Plus, Implementation Phase 3 — Authentication & Session Management:** salted PBKDF2 password hashing, seeded user store (resolves OQ-01), `SessionManager` with SecureRandom tokens and sliding/absolute expiry (resolves OQ-08), `login()`/`logout()` wired into `VaultService` with a shared `VaultServiceException`/`ErrorCode` model, 23 passing tests (unit + real-RMI integration) covering TEST-AUTH-001..005 and TEST-SESSION-001..004.

**Plus, Implementation Phase 4 — File Vault:** `VaultFileService` (flat `<fileId>.properties` sidecar + `<fileId>.bin` storage, resolves OQ-07; metadata rebuilt from disk at startup so the vault survives a restart), SHA-256 checksum computed at upload and re-verified at download, filename validation rejecting path-traversal attempts (SEC-006), `listFiles()`/`uploadFile()`/`downloadFile()` wired into `VaultService` (also resolves OQ-11: uploads always create a new `fileId`, never an in-place overwrite). 38 passing tests total; manually verified against a live cross-process server (upload → list → download, byte-identical, checksum confirmed, and files verified physically persisted on disk).

**Plus, Implementation Phase 5 — Distributed Locking:** `LockManager` (atomic per-file `ConcurrentHashMap.compute()` acquire/release, resolves OQ-12/OQ-13/OQ-09), `lockFile()`/`unlockFile()` wired into `VaultService` (contention reported via `FILE_LOCKED` exception, not a `LockResult` DTO — a Derived Decision finalized this phase), real lock state now populates `listFiles()`'s `FileMetadata` (with a "you"/"another user" hint, never a raw identity), and stale-lock recovery closed the loop via a new `SessionManager` session-ended listener feeding `LockManager.releaseAllOwnedBySession`. **The mandatory concurrency proof, TEST-CONC-001** (5 real concurrent sessions, real RMI, 30 rounds per run) **passed with exactly one winner every round, across 4 separate runs (120+ total race rounds, zero failures).** 61 passing tests total; manually verified against a live cross-process server, including watching bob's lock/unlock attempts get correctly rejected while alice held the lock.

---

## 5. Pending Work

**Phases 1 through 5 are complete (§13 Implementation Log). Phases 6 through 12 in [Implementation.md](Implementation.md) are Not Started:**

- Phase 6 — Encryption *(blocked on OQ-05, see §7)*
- Phase 7 — Audit Logging
- Phase 8 — Swing UI
- Phase 9 — Integration
- Phase 10 — Testing (execution)
- Phase 11 — Cloud Deployment
- Phase 12 — Packaging & Demonstration

---

## 6. Architecture Summary

Client (Swing) ↔ RMI Registry + `VaultService` remote object ↔ [Authentication | Session Manager | Vault Service | Lock Manager | Encryption Service | Audit Logger] ↔ server-local Secure File Storage. Single server, no clustering. Full detail: [Architecture.md](Architecture.md). Full decision rationale: [Decision.md](Decision.md).

---

## 7. Open Questions (must be resolved before the affected implementation phase)

| ID | Question | Default/Recommendation on file | Blocks |
|---|---|---|---|
| ~~OQ-01~~ | ~~How are user accounts provisioned?~~ | **Resolved (Phase 3):** fixed seed list (`seed-users.properties`), hashed at server startup — see PRD.md §4.1, Backend.md §2.1 | — |
| OQ-02 | What is the numeric performance SLA (NFR-002)? | None fixed — treated qualitatively only | Low priority |
| ~~OQ-03~~ | ~~Final JDK version and build tool~~ | **Resolved (Phase 1):** JDK 17, Gradle (Maven unavailable in target environment) — see TRD.md §4 | — |
| ~~OQ-04~~ | ~~Should sessions/locks persist across server restarts?~~ | **Confirmed as implemented (Phases 3 & 5):** no — `SessionManager` and `LockManager` are both purely in-memory; a restart drops all sessions and locks (file *content*, unlike sessions/locks, does survive via `VaultFileService` — see OQ-07) | — |
| **OQ-05** | **Final encryption key-management approach** | RMI-over-TLS as primary + AES-GCM on payload as defense-in-depth (Security.md §7) | **Phase 6 — hard blocker** |
| OQ-06 | Which free-tier cloud provider (AWS/Oracle/GCP)? | Not fixed — any satisfies `auth` §8 | Phase 11 |
| ~~OQ-07~~ | ~~Final metadata storage mechanism~~ | **Resolved (Phase 4):** flat `<fileId>.properties` sidecar per file, no embedded DB — see Decision.md ADR-010, `VaultFileService` | — |
| ~~OQ-08~~ | ~~Session idle-timeout value~~ | **Resolved (Phase 3):** 30-minute sliding idle timeout + 8-hour absolute max lifetime — see `SessionManager`, Security.md §5 | — |
| ~~OQ-09~~ | ~~Can a locked file still be downloaded read-only by a non-owner?~~ | **Resolved (Phase 5):** yes — `downloadFile()` performs no lock check | — |
| OQ-10 | Is at-rest encryption implemented in this coursework scope? | No — in-transit only is the hard requirement | Phase 4/6 |
| ~~OQ-11~~ | ~~Upload-with-same-name semantics~~ | **Resolved (Phase 4):** every `uploadFile()` call always creates a brand-new `fileId`, even if the filename matches an existing file — there is no in-place "update" operation in the current API surface. Duplicate display names can coexist as distinct files. Revisit only if a "replace/version a file" feature is ever wanted (PRD.md Future Enhancements) | — |
| ~~OQ-12~~ | ~~Are locks owned per-session or per-user?~~ | **Resolved (Phase 5):** per-session — see `FileLock`, Security.md §8 | — |
| ~~OQ-13~~ | ~~Final lock timeout duration~~ | **Resolved (Phase 5):** fixed 15 minutes (`LockManager.LOCK_TIMEOUT`) | — |
| OQ-14 | Developer-name discrepancy between `auth` ("Saad Ahmad") and `p1.md` ("Kuamil jeffery") | p1.md's explicit instruction followed as authoritative for documentation; flagged here for the user to confirm before the coursework report is finalized | Phase 12 (report authorship) |

None of these block the *documentation* phase — each has a working default. OQ-05 is the only one that hard-blocks an *implementation* phase (Phase 6) if left unresolved.

---

## 8. Current Risks

| Risk | Category | Mitigation on file |
|---|---|---|
| Lock race condition implemented incorrectly (check-then-act instead of atomic) | Technical | Explicit atomic-operation requirement (Backend.md §3) + mandatory TEST-CONC-001 |
| Encryption key management left unresolved into Phase 6 | Security | Flagged as a hard blocker (OQ-05) rather than allowed to default silently |
| RMI dynamic port behavior breaks cloud firewalling | Technical | Fixed object port decision (TRD §2.6, Architecture §3) |
| `java.rmi.server.hostname` misconfigured on the VM, breaking remote stub resolution | Technical/Deployment | Explicitly called out in Architecture §3 as a "well-known RMI pitfall" |
| Coursework timeline (6 weeks per `auth` §7) vs. 12 implementation phases | Academic/Schedule | Phases map cleanly onto the original weekly milestones in `auth` §7; Phase granularity allows partial-credit progress tracking |
| Password hashing / session token design treated as optional because `auth` doesn't mandate specifics | Security/Academic | Explicitly elevated to a hard Engineering Requirement, not left implicit (Security.md §3, §5) |

---

## 9. Requirements Summary

See [PRD.md](PRD.md) (FR-001–FR-014, NFR-001–NFR-010) and [TRD.md](TRD.md) for full detail. Every requirement traces to `auth` or is explicitly labeled Derived Decision/Engineering Assumption/Recommendation — none are silently invented. Full traceability: [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md).

## 10. Security Summary

See [Security.md](Security.md) for the complete threat model, SEC-001–SEC-010 controls, encryption design (status `Proposed`, pending OQ-05), lock security, and audit schema. Headline principle: **never trust the client** — every authentication, authorization, and lock-ownership decision is enforced server-side.

---

## 11. Cross-Document Consistency Audit (performed per [p1.md](p1.md) §19)

| Check | Result |
|---|---|
| Does every `auth` requirement appear in PRD? | Yes — see [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md) §1, all `auth` §3/§4/§6 items mapped to FR-xxx/NFR-xxx. |
| Does Architecture.md satisfy TRD and PRD? | Yes — every TRD technical requirement (RMI registry, stubs, serialization, threading) and every PRD functional area has a named component in Architecture §2. |
| Does Security.md align with Architecture.md? | Yes — every Architecture component (§2.4–§2.9) has a corresponding Security.md section (Authentication, Session, Lock, Encryption, Audit). |
| Does API-spec.md support every functional requirement? | Yes — `login/logout/listFiles/uploadFile/downloadFile/lockFile/unlockFile` cover FR-001–FR-010; audit column covers FR-011. |
| Does Backend.md implement the architecture conceptually? | Yes — module list in Backend §1 is a 1:1 mapping to Architecture §2 components. |
| Do all flows match API behavior? | Yes — flow.md §Consistency Note cross-checks every method name and error code against API-spec.md. |
| Does every important requirement have a test? | Yes — Testing.md §5 Test Matrix covers FR-001–FR-011, SEC-003/004/006/007/008/009, ADR-001/009. |
| Does UIUX.md cover all client functionality? | Yes — every `VaultService` method has a corresponding UI control in UIUX §1–§2. |
| Can Implementation.md realistically implement everything specified? | Yes — 12 phases collectively cover every component in Architecture.md and every requirement in PRD.md; the single hard blocker (OQ-05) is explicitly called out rather than hidden. |
| Does Context.md (this document) accurately summarize project state? | Yes, as of this writing — must be kept current per §12 below. |
| Are architectural decisions documented? | Yes — 10 ADRs in Decision.md, statuses `Accepted` except ADR-007 (`Proposed`, pending OQ-05). |

**Audit conclusion:** No contradictions found between documents. One decision (ADR-007/OQ-05) is intentionally left `Proposed` rather than `Accepted` because `auth` does not specify an encryption mechanism and this must be a conscious implementation-time choice, not a silent default.

---

## 12. Context Maintenance Rules

**Future implementation agents MUST update this file after completing any meaningful work.** After every implementation phase, update:

1. Completed tasks (§4)
2. Current status (§2) and current phase (§3)
3. Files created/modified (add a §13 "Implementation Log" entry)
4. Known issues (§8 Current Risks)
5. Test status (cross-reference [Testing.md](Testing.md) §5 matrix)
6. Deployment status (once Phase 11 begins)
7. Architectural decisions, if changed (update [Decision.md](Decision.md) first, then reflect here)
8. Next steps (§5 Pending Work)
9. Blockers (§7 Open Questions — resolve or carry forward)
10. Deviations from requirements (append to §11 or a new "Deviations" entry, never silently drop them)

**This file must never be allowed to go stale.** If you are an implementation agent and this file's "Current Status" says anything other than what you just observed to be true, fix it before doing anything else.

---

## 13. Implementation Log

### Phase 1 — Project Skeleton — `Completed`

**Current Phase:** Implementation Phase 1
**Current Status:** Completed
**Completed:**
- Verified target environment: JDK 17 (`openjdk 17.0.20`) present; Maven absent; Gradle 8.14.4 present.
- Updated [TRD.md](TRD.md) §4 to confirm JDK 17 + Gradle (was: Maven recommendation) — resolves OQ-03.
- Created Gradle multi-project build: `authlock-common`, `authlock-server`, `authlock-client`, root `build.gradle`/`settings.gradle` applying a shared Java 17 toolchain and JUnit 5.
- Created the full package structure from [Development-rules.md](Development-rules.md) §2 (`com.authlock.common`; `com.authlock.server.{auth,session,vault,lock,crypto,audit}`; `com.authlock.client.{ui,rmi}`), each documented with a `package-info.java` pointing at its owning spec section and target phase.
- Added stub `ServerMain` / `ClientMain` entry points — print-only placeholders, no RMI/UI/business logic (per [p1.md](p1.md) "do not implement yet" for anything beyond skeleton).
- Generated the Gradle wrapper (`./gradlew`) so the build doesn't depend on a local Gradle install.
- Added `.gitignore` and `README.md` (documentation index + build/run/test instructions).
- Initialized the Git repository (`git init`) and made the first commit.
**Files Created:** `settings.gradle`, `build.gradle`, `authlock-{common,server,client}/build.gradle`, all `package-info.java` files listed above, `ServerMain.java`, `ClientMain.java`, `.gitignore`, `README.md`, `gradlew`/`gradlew.bat`/`gradle/wrapper/*`.
**Files Modified:** [TRD.md](TRD.md) (§4 Development Environment — Maven→Gradle decision recorded inline, no new ADR needed as it's tooling-only).
**Tests Added:** None yet (no test sources — JUnit 5 wired into the build but Phase 1 has no logic to test).
**Tests Passed / Failed:** N/A.
**Known Issues:** The `application` plugin's plain `jar` task does not set a `Main-Class` manifest attribute, so `java -jar authlock-server.jar` does not work directly yet — use `./gradlew :authlock-server:run` (or `:authlock-client:run`) instead. A properly packaged, directly-runnable JAR (per `auth` §10 deliverables) is Phase 12's responsibility (Packaging & Demonstration); not a Phase 1 defect.
**Architecture Changes:** None — Phase 1 is pure scaffolding, no design deviation from Architecture.md/Backend.md.
**Security Changes:** None.
**Open Decisions:** OQ-03 resolved (see §7 above). All other Open Questions unchanged.
**Next Steps:** Begin Phase 2 — RMI Infrastructure (define `VaultService` in `authlock-common`, implement registry bootstrap in `ServerMain`, implement lookup in `ClientMain`, verify over `localhost`).
**Blockers:** None for Phase 2. (OQ-05 remains a blocker for Phase 6 only.)

Build verification performed this session: `./gradlew build` → `BUILD SUCCESSFUL`; `./gradlew :authlock-server:run` and `./gradlew :authlock-client:run` both print their expected placeholder output.

### Phase 2 — RMI Infrastructure — `Completed`

**Current Phase:** Implementation Phase 2
**Current Status:** Completed
**Completed:**
- Defined `VaultService` (extends `Remote`) in `authlock-common`, currently declaring only `ping()` — a deliberate Phase 2 scope limit; `login/logout/listFiles/uploadFile/downloadFile/lockFile/unlockFile` are added incrementally as their owning phases (3–5) are implemented, per Development-rules.md §1.
- Added `RmiConfig` (common) with the registry port (1099), service name, and the previously-TBD **fixed RMI object port, now confirmed as 5000** — updated TRD.md §2.7 and Architecture.md §3 accordingly (both were marked "TBD" in the documentation phase).
- Implemented `VaultServiceImpl` (server) — `UnicastRemoteObject` subclass exporting on a configurable port (production: `RmiConfig.SERVICE_PORT`; tests: ephemeral port 0 to avoid clashing with a real instance).
- Implemented `ServerMain` — creates/attaches to the registry, rebinds the service, returns (RMI's own non-daemon threads keep the JVM alive).
- Implemented client-side RMI plumbing in `authlock-client.rmi`: `RmiConnection` (registry lookup) and `ServerUnavailableException` (translates lookup/transport failures into a single client-facing exception — the mechanism FR-013 will surface through the Swing UI in Phase 8).
- Updated `ClientMain` to connect, call `ping()`, and print the result; host is overridable via `-Dauthlock.server.host=<host>` or a CLI arg, anticipating Phase 11's cloud VM connection without further code changes.
- Added `VaultServiceRmiIntegrationTest` (TEST-INT-001) — automated, uses a dedicated test registry port + ephemeral object port for isolation.
**Files Created:** `RmiConfig.java`, `VaultService.java`, `VaultServiceImpl.java`, `RmiConnection.java`, `ServerUnavailableException.java`, `VaultServiceRmiIntegrationTest.java`.
**Files Modified:** `ServerMain.java`, `ClientMain.java` (real logic replacing Phase 1 stubs); `TRD.md` §2.7, `Architecture.md` §3 (port TBD → confirmed); `Testing.md` §5 (TEST-INT-001 → Pass).
**Tests Added:** `VaultServiceRmiIntegrationTest` (TEST-INT-001).
**Tests Passed:** TEST-INT-001 — both the automated JUnit test and a manual real cross-process run (`./gradlew :authlock-server:run` in the background, then a separate `./gradlew :authlock-client:run`) succeeded; the client received `"AuthLock VaultService is alive at <timestamp>"`. The server-unavailable path was also manually verified: with no server running, the client printed `Server unavailable: Could not reach AuthLock server at localhost:1099` and exited non-zero, confirming FR-013's console-level precursor works before Phase 8 gives it a UI.
**Tests Failed:** None.
**Known Issues:** None new. The Phase 1 "no direct `java -jar`" limitation (application plugin doesn't set `Main-Class`) still applies — use `./gradlew :module:run`.
**Architecture Changes:** None beyond confirming the previously-open RMI object port number (5000) — not a design change, just resolving a documented TBD.
**Security Changes:** None — Phase 2 carries no authentication/session/encryption logic by design.
**Open Decisions:** No Open Questions were resolved or newly raised by Phase 2.
**Next Steps:** Begin Phase 3 — Authentication & Session Management (user credential store, password hashing, `SessionManager`, wire `login()`/`logout()` into `VaultService`, add session validation to every future method).
**Blockers:** None for Phase 3.

---

### Phase 3 — Authentication & Session Management — `Completed`

**Current Phase:** Implementation Phase 3
**Current Status:** Completed
**Completed:**
- Resolved **OQ-01**: added `seed-users.properties` (demo accounts `alice`/`bob`) loaded and hashed by `UserStore` at server startup — no self-service registration exists, per PRD.md §4.1.
- Implemented `PasswordHasher` (salted PBKDF2WithHmacSHA256, 120k iterations, constant-time verification) and `AuthenticationService` (timing-equalized for unknown usernames — SEC-001).
- Resolved **OQ-08**: implemented `SessionManager` — SecureRandom 256-bit tokens, 30-minute sliding idle timeout, 8-hour absolute max lifetime, daemon-thread cleanup sweep.
- Added a shared error model to `authlock-common`: `ErrorCode` enum + single `VaultServiceException` (per API-spec.md §3's design note), so the `VaultService` interface's throws clauses stay simple as methods are added phase by phase.
- Extended `VaultService`/`VaultServiceImpl` with `login()`/`logout()`, plus a private `requireValidSession()` helper ready for Phase 4/5 methods to call (FR-004).
- Extended `ClientMain`'s console demo to exercise login/logout against a seeded account (Phase 3 demo only — replaced by the Swing UI in Phase 8).
- Updated documentation to close the two resolved Open Questions: PRD.md §4.1, Security.md §3/§11, Testing.md §5 (TEST-AUTH-001..005, TEST-SESSION-001..004 → Pass).
**Files Created:** `ErrorCode.java`, `VaultServiceException.java` (common); `HashedPassword.java`, `User.java`, `PasswordHasher.java`, `UserStore.java`, `AuthenticationService.java`, `seed-users.properties`, `Session.java`, `SessionManager.java` (server); `PasswordHasherTest.java`, `AuthenticationServiceTest.java`, `SessionManagerTest.java`, `VaultServiceAuthIntegrationTest.java` (tests).
**Files Modified:** `VaultService.java` (added `login`/`logout`), `VaultServiceImpl.java` (auth/session wiring), `ClientMain.java` (login/logout demo), `ServerMain.java` (startup banner text), `PRD.md`, `Security.md`, `Testing.md`.
**Tests Added:** 15 new tests (3 `PasswordHasherTest` + 4 `AuthenticationServiceTest` + 6 `SessionManagerTest` + a growth from 1→9 `Vault*IntegrationTest` methods — see exact count below).
**Tests Passed:** All 23 tests in `authlock-server` pass (`./gradlew clean build`): `PasswordHasherTest` (3), `AuthenticationServiceTest` (4), `SessionManagerTest` (6), `VaultServiceRmiIntegrationTest` (1), `VaultServiceAuthIntegrationTest` (9, including `TEST-AUTH-005`). Also manually verified with a real backgrounded server process and a separate client process: login returned a live session token, logout succeeded.
**Tests Failed:** None (an earlier draft of `VaultServiceAuthIntegrationTest` used per-method `@BeforeEach`/`@AfterEach`, which fails on the 2nd+ test method because `LocateRegistry.createRegistry(port)` exports the registry itself as a long-lived object — `unbind()` doesn't release the port. Fixed by moving to class-level `@BeforeAll`/`@AfterAll`; documented in the test's Javadoc so the pitfall isn't rediscovered in Phase 4/5's tests.)
**Known Issues:** None new.
**Architecture Changes:** None — Phase 3 implements exactly the Authentication Service and Session Manager components already specified in Architecture.md §2.4/§2.5.
**Security Changes:** OQ-05 (encryption) remains open and unaffected. No plaintext password is ever stored or logged; audit logging itself is still Phase 7 (not yet wired in) — login/logout currently have no audit trail, which is expected at this point in the roadmap, not a defect.
**Open Decisions:** OQ-01 and OQ-08 resolved (see §7). All other Open Questions unchanged.
**Next Steps:** Begin Phase 4 — File Vault (storage, metadata, `listFiles()`/`uploadFile()`/`downloadFile()`, filename sanitization per SEC-006, using `requireValidSession()` for authorization).
**Blockers:** None for Phase 4.

---

### Phase 4 — File Vault — `Completed`

**Current Phase:** Implementation Phase 4
**Current Status:** Completed
**Completed:**
- Resolved **OQ-07**: `VaultFileService` stores each file as `<fileId>.bin` (raw content) + `<fileId>.properties` (flat key=value metadata sidecar) under a configurable vault directory (`-Dauthlock.vault.dir`, default `vault-storage/`, already `.gitignore`d since Phase 1). No database. Metadata index rebuilt from disk at startup — verified the vault survives a service restart.
- Resolved **OQ-11**: every `uploadFile()` creates a brand-new `fileId`; there is no in-place update/overwrite path, so nothing can silently clobber another file.
- Added `FileMetadata`/`FileContent` DTOs to `authlock-common`, matching API-spec.md §2 exactly (including the `iv`/lock-state fields that later phases will populate for real).
- Added `FileRecord` (internal model) and `VaultFileService` (storage/retrieval, SHA-256 checksum at upload, re-verified at download — detects at-rest corruption/tampering) to `authlock-server.vault`.
- Extended `VaultService`/`VaultServiceImpl` with `listFiles()`/`uploadFile()`/`downloadFile()`, each guarded by the existing `requireValidSession()` (Phase 3), with `lockState` hardcoded to `"UNLOCKED"` until Phase 5 provides a real Lock Manager.
- Filename validation rejects blank names and path-separator/`..` sequences (SEC-006 defense in depth — storage never uses the filename as a path component in the first place, so this is belt-and-suspenders, not the only protection).
- Extended `ClientMain`'s console demo to upload/list/download a file after login.
**Files Created:** `FileMetadata.java`, `FileContent.java` (common); `FileRecord.java`, `VaultFileService.java` (server); `VaultFileServiceTest.java`, `VaultServiceFileIntegrationTest.java` (tests).
**Files Modified:** `VaultService.java` (added 3 methods), `VaultServiceImpl.java` (vault wiring, constructor now `throws IOException` too), `ClientMain.java` (upload/list/download demo), `ServerMain.java` (banner text); `Decision.md` ADR-010, `Backend.md` §4, `Testing.md` §5, `Security.md` (File overwrite protection).
**Tests Added:** 15 new (`VaultFileServiceTest` ×6, `VaultServiceFileIntegrationTest` ×9).
**Tests Passed:** All 38 tests in `authlock-server` (`./gradlew clean build`), covering TEST-FILE-001..006 and TEST-SEC-001 at both storage-layer-unit and real-RMI levels. Manually verified against a live backgrounded server + separate client process: upload → list (1 file) → download (byte-identical, checksum printed) → logout; inspected the actual `.bin`/`.properties` files on disk afterward to confirm real persistence, not just in-memory state.
**Tests Failed:** None.
**Known Issues:** `./gradlew :authlock-server:run` uses the `authlock-server/` subproject directory as its working directory (Gradle default), so the default `vault-storage/` lands at `authlock-server/vault-storage/`, not the repo root — cosmetic only, already covered by `.gitignore`'s `vault-storage/` pattern regardless of location; not a defect, just worth knowing when looking for the demo files.
**Architecture Changes:** None — implements exactly the Vault (File) Service component already specified in Architecture.md §2.6.
**Security Changes:** SEC-006 (path traversal) now actively enforced and tested. File checksums now real (were undefined pre-Phase-4). Audit logging for upload/download is still Phase 7 (not yet wired in) — expected at this point, not a defect.
**Open Decisions:** OQ-07 and OQ-11 resolved (see §7). OQ-09 (whether a locked file may still be downloaded read-only by a non-owner) remains genuinely open — it was moot in Phase 4 since no lock concept exists yet; it becomes a real decision point once Phase 5 adds the Lock Manager.
**Next Steps:** Begin Phase 5 — Distributed Locking (the project's headline feature): `LockManager` with atomic acquire/release, wire `lockFile()`/`unlockFile()`, wire real lock state into `listFiles()`'s `FileMetadata.lockState()`, wire stale-lock release into the Phase 3 session-cleanup sweep, and the mandatory N-client concurrent-lock race test (TEST-CONC-001).
**Blockers:** None for Phase 5. OQ-12 (per-session vs. per-user lock ownership) and OQ-13 (lock timeout value) have working defaults (per-session; ~15 minutes) that Phase 5 will encode into real code, same pattern as OQ-01/OQ-08 in Phase 3.

### Phase 5 — Distributed Locking — `Completed`

**Current Phase:** Implementation Phase 5
**Current Status:** Completed
**Completed:**
- Resolved **OQ-12**: `FileLock` records the owning **session** ID, not user ID — falls out naturally from reusing `SessionManager`'s existing session-token identity.
- Resolved **OQ-13**: fixed 15-minute lock timeout (`LockManager.LOCK_TIMEOUT`), not sliding.
- Resolved **OQ-09**: `downloadFile()` performs no lock check — locked files remain downloadable read-only (confirmed by a new test, not just asserted in docs).
- **Finalized the API-spec.md design note left open since documentation phase:** `lockFile()`/`unlockFile()` report failure by throwing `VaultServiceException` (e.g. `FILE_LOCKED`), matching every other method — no `LockResult` DTO exists; removed it from API-spec.md.
- Built `LockManager`: atomic `ConcurrentHashMap.compute()`-based `acquire()`/`release()` (never check-then-set), `currentOwner()` for display, `releaseAllOwnedBySession()` for stale-lock recovery, a 60s daemon cleanup sweep, and a `Clock`-injectable test constructor (same pattern as `SessionManager`).
- Closed the stale-lock-recovery loop end to end: added a session-ended listener to `SessionManager` (Architecture.md §2.7's documented but previously unbuilt input), invoked on idle/absolute expiry (lazy and via the sweep) *and* explicit logout, wired in `VaultServiceImpl`'s constructor to `lockManager::releaseAllOwnedBySession`.
- Wired `lockFile()`/`unlockFile()` into `VaultServiceImpl`, each guarded by `requireValidSession()` + a new `requireFileExists()` helper (reusing `VaultFileService.exists()`, a cheap no-disk-read check added this phase).
- `listFiles()` now reports real lock state: `"LOCKED"`/`"UNLOCKED"` plus a `"you"`/`"another user"` hint that never discloses another session's identity.
- Extended `ClientMain`'s demo to show alice locking a file, bob's lock/unlock attempts being correctly rejected, then alice unlocking.
**Files Created:** `FileLock.java`, `LockManager.java` (server.lock); `LockManagerTest.java`, `VaultServiceLockIntegrationTest.java`, `VaultServiceLockConcurrencyTest.java` (tests).
**Files Modified:** `VaultService.java` (added `lockFile`/`unlockFile`, removed the deferred design note), `VaultServiceImpl.java` (lock wiring, `toDto` now takes the caller's session), `SessionManager.java` (session-ended listener), `VaultFileService.java` (added `exists()`), `ClientMain.java`, `ServerMain.java`; `API-spec.md` (finalized lockFile contract, removed `LockResult` DTO), `Security.md` §4/§8/§11, `Testing.md` §5, `Decision.md`/`Backend.md` untouched (ADR-004/ADR-005 already described exactly what got built).
**Tests Added:** 23 new (`LockManagerTest` ×10, `VaultServiceLockIntegrationTest` ×9 including the OQ-09 confirmation, `VaultServiceLockConcurrencyTest` ×3 covering TEST-CONC-001/002/003).
**Tests Passed:** All 61 tests in `authlock-server` (`./gradlew clean build`). **TEST-CONC-001 specifically re-run 4 separate times** (once in the full suite, three standalone `--rerun` invocations) — 30 rounds × 5 concurrent sessions each time, **exactly one winner every round, zero failures across 120+ total race rounds.** Manually verified against a live cross-process server: alice locks, bob's lock attempt → `FILE_LOCKED`, bob's unlock attempt → `LOCK_NOT_OWNED`, alice unlocks successfully.
**Tests Failed:** None.
**Known Issues:** None new.
**Architecture Changes:** None — implements exactly the Lock Manager component and Session Manager → Lock Manager notification path already specified in Architecture.md §2.5/§2.7.
**Security Changes:** SEC-009 (lock security) now fully implemented and tested, including the stale-lock-recovery path that was previously only designed on paper. `listFiles()`'s minimal-disclosure lock-owner hint ("you"/"another user") is now enforced in code, not just documented intent.
**Open Decisions:** OQ-09, OQ-12, OQ-13 resolved this phase (see §7). OQ-04 additionally confirmed as correctly implemented (sessions and locks are both in-memory-only, as designed). Only OQ-02 (SLA, low priority), OQ-05 (encryption, hard blocker for Phase 6), OQ-06 (cloud provider, Phase 11), OQ-10 (at-rest encryption), and OQ-14 (developer-name discrepancy) remain open.
**Next Steps:** Begin Phase 6 — Encryption. **This phase cannot start without first resolving OQ-05** (final key-management approach: RMI-over-TLS vs. application-layer AES-GCM key distribution vs. hybrid) — see Security.md §7 for the options and the standing recommendation (RMI-over-TLS as primary transport control, AES-GCM on payload as defense-in-depth/LO4 demonstration).
**Blockers:** **OQ-05 must be explicitly confirmed (or a different option chosen) before writing any Phase 6 code** — this is the one genuine go/no-go decision point in the entire roadmap.

---

## 14. Next Steps

The exact, recommended next action is: **resolve OQ-05, then begin [Implementation.md](Implementation.md) Phase 6 — Encryption.** Unlike every Open Question resolved so far, this one is flagged as a hard blocker (Implementation.md Phase 6 prerequisites) because it is a real architectural fork, not a value that can be quietly defaulted: RMI-over-TLS protects the whole channel (including credentials) with modest certificate-management overhead, while pure application-layer AES-GCM needs a key-distribution story of its own. The standing recommendation (Security.md §7) is **both** — RMI-over-TLS as the primary transport control, AES-GCM on file payloads as an explicit, defense-in-depth demonstration of Java's cryptography APIs (module LO4) — but this should be confirmed with the developer rather than silently locked in, given it is the project's one remaining security-critical design fork.

No other Open Question blocks Phase 6. OQ-06 (cloud provider) only matters at Phase 11.
