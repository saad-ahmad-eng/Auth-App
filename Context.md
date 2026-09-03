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

**Status: `In Progress` — Documentation Phase `Completed`; Implementation Phase 1 `Completed`; Phase 2 (RMI Infrastructure) `Completed`; Phase 3 `Not Started`.**

## 3. Current Phase

**Implementation Phase 2 — RMI Infrastructure — Completed.** Next up: **Phase 3 — Authentication & Session Management** (not started).

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

---

## 5. Pending Work

**Phases 1 and 2 are complete (§13 Implementation Log). Phases 3 through 12 in [Implementation.md](Implementation.md) are Not Started:**

- Phase 3 — Authentication & Session Management
- Phase 4 — File Vault
- Phase 5 — Distributed Locking
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
| OQ-01 | How are user accounts actually provisioned, since there is no registration flow? | A fixed seed list or an admin-only provisioning path (PRD §4.1) | Phase 3 |
| OQ-02 | What is the numeric performance SLA (NFR-002)? | None fixed — treated qualitatively only | Low priority |
| ~~OQ-03~~ | ~~Final JDK version and build tool~~ | **Resolved (Phase 1):** JDK 17, Gradle (Maven unavailable in target environment) — see TRD.md §4 | — |
| OQ-04 | Should sessions/locks persist across server restarts? | No — in-memory only, acceptable loss on restart (ADR-006) | Phase 3, Phase 5 |
| **OQ-05** | **Final encryption key-management approach** | RMI-over-TLS as primary + AES-GCM on payload as defense-in-depth (Security.md §7) | **Phase 6 — hard blocker** |
| OQ-06 | Which free-tier cloud provider (AWS/Oracle/GCP)? | Not fixed — any satisfies `auth` §8 | Phase 11 |
| OQ-07 | Final metadata storage mechanism (flat file vs. SQLite/H2) | Deferred to Backend.md/implementation judgment | Phase 4 |
| OQ-08 | Session idle-timeout value | ~30 minutes suggested, not finalized | Phase 3 |
| OQ-09 | Can a locked file still be downloaded read-only by a non-owner? | Yes, by default (API-spec.md `downloadFile`) | Phase 4/5 |
| OQ-10 | Is at-rest encryption implemented in this coursework scope? | No — in-transit only is the hard requirement | Phase 4/6 |
| OQ-11 | Upload-with-same-name semantics — new file record vs. versioned update | Default: always a new file record unless an explicit locked "update" path exists | Phase 4 |
| OQ-12 | Are locks owned per-session or per-user (does a user's second concurrent session share their own lock)? | Per-session (Security.md §8) | Phase 5 |
| OQ-13 | Final lock timeout duration | ~15 minutes suggested, not finalized | Phase 5 |
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

## 14. Next Steps

The exact, recommended next action is: **begin [Implementation.md](Implementation.md) Phase 3 — Authentication & Session Management.** Before/while starting it, note two Open Questions with working defaults that Phase 3 will encode into real code: **OQ-01** (credential provisioning — no registration flow, so a seed user list or admin-only provisioning path is needed) and **OQ-08** (session idle-timeout value, default ~30 minutes). Neither blocks starting the phase, but both should be consciously confirmed rather than left as an unstated default once real code exists.

No Open Question blocks Phase 3. OQ-05 must be resolved before Phase 6 specifically, not before continuing implementation generally.
