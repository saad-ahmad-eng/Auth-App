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

**Status: `In Progress` — Documentation Phase `Completed`; Implementation Phases 1–10 `Completed`; Phase 11 `In Progress — infrastructure/scripts ready, live deployment execution pending` (see §3).**

## 3. Current Phase

**Implementation Phase 11 — Cloud Deployment — `In Progress`.** OQ-06 is resolved (AWS). Terraform (`terraform/`) and two deployment scripts (`scripts/provision-vm.sh`, `scripts/setup-authlock.sh`) are written, code-reviewed, and locally verified everywhere this sandbox allows without a real target VM — see §13 for exactly what was and wasn't testable here. A real, pre-existing gap this work surfaced and fixed: `DevTlsSetup`'s self-signed certificate SAN was hardcoded to `localhost`-only (flagged as an explicit, not-yet-done Phase 11 task in Architecture.md/Security.md since Phase 6) — now configurable via `-Dauthlock.tls.extraSan`, and both scripts set it automatically from the VM's discovered public address. **Honestly not yet done:** this sandbox has no AWS credentials, so `terraform apply` has never actually been run, no real VM exists, and TEST-DEPLOY-001/002 are still `Not Run` — that's the concrete next action for whoever has an AWS account. Full account in §13.

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

**Plus, Implementation Phase 6 — Encryption (resolves OQ-05, the roadmap's one hard blocker):** AES-256-GCM file-payload encryption (`AesGcmCipher`, `SharedKeyProvider` — a pre-shared key file, honestly documented as a coursework-scope simplification), applied per-hop, not end-to-end — client encrypts before upload, server decrypts on receipt/re-encrypts before download, storage stays plaintext, exactly matching the design recorded in Security.md §7 during the documentation phase. **Plus RMI-over-TLS** (`DevTlsSetup`, self-signed dev cert auto-generated via `keytool`), on by default, protecting the whole channel including `login()` credentials — closing a real gap the payload-only encryption would have left open. Hit and fixed one genuine, instructive bug along the way: RMI embeds the server's actual detected LAN IP in exported stubs by default, which broke TLS hostname verification against a `localhost`-scoped cert — fixed by defaulting `java.rmi.server.hostname=localhost`. Every existing upload/download call site across the whole test suite (~25 methods) was updated to encrypt/decrypt properly, since encryption is now mandatory, not optional. 76 passing tests total (15 new); manually verified with both encryption and TLS active simultaneously against a live cross-process server — including confirming the file on disk is genuinely plaintext (per the documented at-rest posture) while the wire bytes are genuinely ciphertext.

**Plus, Implementation Phase 7 — Audit Logging:** `AuditLogger` (`authlock-server.audit`) — structured, one-JSON-object-per-line, append-only, hand-serialized (no JSON library dependency), synchronous writes, serialized under a single lock (proven safe under 20 concurrent writers × 10 events with zero lost/corrupted lines). Wired into every `VaultService` method's every success/failure exit point — `login`, `logout`, `uploadFile`, `downloadFile`, `lockFile`, `unlockFile` — except `listFiles()` (deliberately unaudited, a Phase 0 decision reaffirmed, not silently revisited). Simplified Security.md §9's original schema along the way: merged the separate "Authentication failure"/"Authorization failure" event rows into each triggering operation's own event type + `FAILURE` result + `ErrorCode`, since they were redundant with information already being recorded (Derived Decision). `SessionManager.invalidate()` changed to return the removed `Session` (not just a boolean) so `logout()` can attribute its success record to the right user. 87 passing tests total (11 new); manually verified against a live cross-process server — read the real `audit.log` afterward and confirmed every event was recorded correctly, in order, with the right `fileId`/`userId` correlation, and grepped it for both passwords and session tokens to confirm neither ever appeared.

**Plus, Implementation Phase 8 — Swing UI (Completed — see §13 for the full account across two sessions):** `LoginFrame`/`DashboardFrame`/`FileTableModel`/`SwingAsync` (`authlock-client.ui`) fully implement UIUX.md §1–§2 — every remote call runs off the EDT via `SwingWorker`, buttons disable correctly during in-flight calls, lock/unlock enablement follows real lock state, session expiry triggers a dialog and returns to login, `JFileChooser` handles all file selection. `ClientMain` reduced to pure process bootstrap (TLS config, key loading, host resolution) handing off to `LoginFrame`. The full interactive walkthrough was completed end-to-end against a live server in a follow-up session (connect → login → Dashboard → upload → lock → unlock → download → logout, plus invalid-login and session-expiry error paths), with screenshots, an audit-log cross-check, and a SHA-256 file-integrity check as evidence. A real Owner-column bug (raw internal `userId` shown instead of the username) was found and fixed along the way; the full 87-test suite still passes.

**Plus, Implementation Phase 9 — Integration:** all 18 [flow.md](flow.md) flows demonstrated working end-to-end locally. Closed the one genuine remaining gap, TEST-INT-002 (server down mid-session): added `VaultServiceRmiIntegrationTest.clientCallOnAnAlreadyObtainedStubFailsClearlyAfterServerStops` (force-unexports a live server object out from under an already-obtained stub, asserts a clean `RemoteException`, not a hang) — 88 tests total now — plus a live demonstration starting a client against a genuinely dead server (correctly shows `"Server unavailable — Connection refused"`, flow.md §17). The headline new evidence this phase: a real two-independent-process, two-actor session — alice and bob each running their own live `ClientMain`/`LoginFrame`/`DashboardFrame` against the one shared server — with alice uploading and locking a file, bob's independent session correctly showing it as `"Locked (another user)"` (Lock/Unlock buttons correctly disabled for him even with the row selected), and bob successfully downloading it anyway (confirming OQ-09's "download isn't lock-gated" live, cross-user, byte-identical to alice's original). `audit.log` cross-checked afterward: two distinct real `userId`s, every event in the correct order. Every other flow (login, auth failure, session validation/expiry, file listing/upload/download, locking/unlock, the mandatory concurrency race, invalid session, unauthorized-unlock, upload/download failure paths, audit logging) was already covered by the 88-test automated suite and/or Phase 8's interactive verification — re-confirmed as still passing rather than redundantly re-demonstrated. No integration gaps found requiring an application-code fix.

**Plus, Implementation Phase 10 — Testing:** executed the full QA strategy fresh and recorded results in [Testing.md](Testing.md)'s matrix. A from-scratch clean build (`./gradlew clean test`) confirmed all 88 tests pass across 15 test classes, 0 failures/errors. The mandatory concurrency test (TEST-CONC-001) was explicitly re-run 4 more times end-to-end (5 concurrent clients × 30 rounds each = 150 additional race rounds this phase alone, on top of the 120+ from earlier phases) with zero winner-collisions in every single round. Ran a consistency audit — spot-checked a sample of the matrix's test-method citations (TEST-SEC-003/004, TEST-TLS-002, TEST-SEC-005's actual assertions) directly against the test source to confirm the matrix reflects real, current code rather than stale or aspirational claims; all checked out accurate. Gave TEST-DEPLOY-001/002 (the only remaining `Not Run` rows) an explicit, documented justification — both correctly require Phase 11's not-yet-provisioned cloud VM per Implementation.md's own phase scoping, and everything each test would exercise *except* genuine network separation is already independently proven locally. **Testing.md's matrix now shows `Pass` for every test case except those two documented, justified exceptions — meeting Phase 10's Definition of Done exactly.** No defects found this phase; nothing required an application-code fix — Phase 10 was a genuine execution/sign-off pass building on work already done thoroughly across Phases 1–9, not fresh test-writing.

**Plus, Implementation Phase 11 — Cloud Deployment (in progress — infrastructure/scripts ready, live execution pending):** resolved OQ-06 (AWS). Wrote `terraform/` (an EC2 `t2.micro` instance, a security group opening exactly 22/1099/5000, an AMI data source rather than a hardcoded ID) and two deployment scripts: `scripts/provision-vm.sh` (root, boot-time, OS/Java/systemd/firewall provisioning — plain portable bash, no Terraform templating inside it, so it's independently usable on any VM, not just via Terraform's `user_data`) and `scripts/setup-authlock.sh` (the "use it on a VM instead of the cloud" script — self-sufficient, installs Java itself if missing, builds via `./gradlew :authlock-server:installDist`, discovers the machine's public address, installs/starts the systemd service, prints exactly what a remote client needs). **Found and fixed a real, pre-existing gap along the way:** `DevTlsSetup`'s certificate SAN was hardcoded `localhost`-only — already flagged in Architecture.md §3/Security.md §7 since Phase 6 as an explicit, not-yet-done Phase 11 task, not a surprise. Added an optional `-Dauthlock.tls.extraSan=<entry>[,...]` system property (default behavior completely unchanged when unset), verified directly via `keytool -list -v` that a generated certificate genuinely carries the extra SAN entries. Both deployment scripts set this automatically from the discovered public address, alongside `java.rmi.server.hostname` (the already-known, separate RMI-stub pitfall). Verified everything testable without a real target VM: the build step (`installDist` produces exactly the launcher path the systemd unit expects), the public-address discovery fallback chain (correctly skips the unreachable AWS metadata service and falls through to a generic public-IP echo service in this non-AWS sandbox, returning a real address), both scripts' bash syntax, and Terraform's HCL brace-balance/variable-consistency by hand (no `terraform` binary available in this sandbox to run `validate`/`plan`). **Honestly not done:** no AWS credentials exist in this environment, so `terraform apply` has never been run — no real VM, no real remote client connection, TEST-DEPLOY-001/002 still `Not Run`. That live run is the concrete next action for whoever has an AWS account (see `terraform/README.md`).

---

## 5. Pending Work

**Phases 1 through 10 are complete (§13 Implementation Log). Phase 11 is in progress (infrastructure/scripts ready; live deployment execution is the remaining step). Phase 12 is Not Started:**

- Phase 11 — Cloud Deployment: **remaining step is `terraform apply` against a real AWS account** (this environment has none) — see `terraform/README.md`
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
| ~~OQ-05~~ | ~~Final encryption key-management approach~~ | **Resolved (Phase 6):** both implemented — AES-256-GCM on file payloads (pre-shared key file, `SharedKeyProvider`) + RMI-over-TLS on the whole channel (self-signed dev cert, `DevTlsSetup`) — see Security.md §7 | — |
| ~~OQ-06~~ | ~~Which free-tier cloud provider (AWS/Oracle/GCP)?~~ | **Resolved (Phase 11):** AWS (EC2 `t2.micro` free tier) — see `terraform/` and `terraform/README.md`. `scripts/provision-vm.sh`/`scripts/setup-authlock.sh` are themselves cloud-agnostic bash, so this choice doesn't lock out using the same scripts on Oracle/GCP/a bare VM later | — |
| ~~OQ-07~~ | ~~Final metadata storage mechanism~~ | **Resolved (Phase 4):** flat `<fileId>.properties` sidecar per file, no embedded DB — see Decision.md ADR-010, `VaultFileService` | — |
| ~~OQ-08~~ | ~~Session idle-timeout value~~ | **Resolved (Phase 3):** 30-minute sliding idle timeout + 8-hour absolute max lifetime — see `SessionManager`, Security.md §5 | — |
| ~~OQ-09~~ | ~~Can a locked file still be downloaded read-only by a non-owner?~~ | **Resolved (Phase 5):** yes — `downloadFile()` performs no lock check | — |
| OQ-10 | Is at-rest encryption implemented in this coursework scope? | No — in-transit protection (now: AES-GCM on payloads + RMI-over-TLS on the whole channel, both Phase 6) already exceeds `auth`'s literal requirement; storage remains plaintext | — (open, low priority; not required) |
| ~~OQ-11~~ | ~~Upload-with-same-name semantics~~ | **Resolved (Phase 4):** every `uploadFile()` call always creates a brand-new `fileId`, even if the filename matches an existing file — there is no in-place "update" operation in the current API surface. Duplicate display names can coexist as distinct files. Revisit only if a "replace/version a file" feature is ever wanted (PRD.md Future Enhancements) | — |
| ~~OQ-12~~ | ~~Are locks owned per-session or per-user?~~ | **Resolved (Phase 5):** per-session — see `FileLock`, Security.md §8 | — |
| ~~OQ-13~~ | ~~Final lock timeout duration~~ | **Resolved (Phase 5):** fixed 15 minutes (`LockManager.LOCK_TIMEOUT`) | — |
| OQ-14 | Developer-name discrepancy between `auth` ("Saad Ahmad") and `p1.md` ("Kuamil jeffery") | p1.md's explicit instruction followed as authoritative for documentation; flagged here for the user to confirm before the coursework report is finalized | Phase 12 (report authorship) |

Only OQ-02 (SLA, low priority), OQ-10 (at-rest encryption, low priority), and OQ-14 (developer-name discrepancy, Phase 12) remain open — each has a working default and none blocks further implementation. OQ-05, the one Open Question ever flagged as a hard blocker, was resolved in Phase 6.

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

See [Security.md](Security.md) for the complete threat model, SEC-001–SEC-010 controls, encryption design (AES-256-GCM + RMI-over-TLS, both implemented — ADR-007 `Accepted`), lock security, and audit schema. Headline principle: **never trust the client** — every authentication, authorization, and lock-ownership decision is enforced server-side.

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

### Phase 6 — Encryption — `Completed`

**Current Phase:** Implementation Phase 6
**Current Status:** Completed
**Completed:**
- Resolved **OQ-05** (the roadmap's one hard-blocking Open Question) by implementing **both** options rather than choosing one: AES-256-GCM on file payloads (application-layer, per-hop) and RMI-over-TLS (whole channel). Confirmed the user's approval of this exact plan before starting.
- **Part A — AES-256-GCM:** Added shared `AesGcmCipher` (encrypt/decrypt, fresh IV per operation, `TamperDetectedException` on a failed auth tag) and `SharedKeyProvider` (pre-shared key file; server generates on first run, client only reads) to `authlock-common.crypto`. Added `EncryptionService` (server-side decrypt-on-receipt / encrypt-on-send) to `authlock-server.crypto`. **Re-confirmed the exact design already recorded in Security.md §7 during the documentation phase** — encryption is per-hop, not end-to-end; storage stays plaintext. This meant `VaultFileService.store()` correctly dropped its (until-now-unused) `iv` parameter, and `FileRecord` correctly dropped its persisted `iv` field — a stored IV would have been meaningless for plaintext-at-rest storage and would have violated "never reuse an IV with the same key" if reused across downloads.
- Wired `uploadFile()`/`downloadFile()` to decrypt/encrypt; broadened error handling so malformed (not just tampered) ciphertext also degrades to `UPLOAD_FAILED` rather than leaking a raw crypto exception through RMI.
- **Encryption became mandatory**, not optional, matching `auth`'s literal requirement — meaning every existing upload/download call site across the whole test suite (`VaultServiceFileIntegrationTest`, `VaultServiceLockIntegrationTest`, `VaultServiceLockConcurrencyTest`, `VaultFileServiceTest` — roughly 25 methods) needed updating to properly encrypt/decrypt. Centralized the boilerplate in a new test-only `CryptoTestSupport` helper so each call site's change stayed a one-line swap.
- Added real tamper-detection coverage: `TEST-SEC-003` (corrupted ciphertext → `UPLOAD_FAILED`, both at the cipher-unit level and over real RMI) and `TEST-SEC-004` (wire bytes provably ≠ plaintext, no substring recoverable, yet decrypting those exact bytes does recover the original) — both were previously untestable placeholders since no real crypto existed before this phase.
- **Part B — RMI-over-TLS:** Added `DevTlsSetup` (`authlock-common.tls`) — auto-generates a self-signed dev certificate via the JDK-bundled `keytool` (no new dependency) on first run, sets the standard JSSE keystore/truststore system properties. Gave `VaultServiceImpl` a new `(port, tlsEnabled)` constructor using `SslRMIClientSocketFactory`/`SslRMIServerSocketFactory` — **deliberately kept the existing plain-RMI constructors unchanged**, so the entire pre-Phase-6 test suite (business-logic tests) needed zero changes to keep validating correctly without also re-verifying the transport layer on every run. `ServerMain`/`ClientMain` both default to TLS on (`-Dauthlock.tls.enabled=false` to disable).
- **Hit and fixed a genuine, instructive bug:** RMI embeds the server's actual detected LAN IP (not `localhost`) in exported stubs by default, which broke TLS hostname verification against a cert scoped only to `localhost`/`127.0.0.1` (`CertificateException: No subject alternative names matching IP address 192.168.1.10 found`). Diagnosed by isolating the failure with a minimal raw-`SSLSocket` repro (which worked) to prove the cert/trust setup itself was fine, then comparing against the failing RMI path. Fixed by defaulting `java.rmi.server.hostname=localhost` unless already set — the exact property Architecture.md §3 already flagged as "a well-known RMI pitfall," now hit in a new context.
- Also fixed a real (if minor) practical issue while wiring this up: `authlock-server`/`authlock-client`'s Gradle `run` tasks used their own subproject directories as working directory by default, so the shared key file and vault storage would land in two different places for the two processes. Fixed by pinning both `run` tasks' `workingDir` to the repo root — this also resolved a "Known Issue" noted (but left unfixed) back in Phase 1/4.
- Added `VaultServiceTlsIntegrationTest`: a real TLS round trip (`ping`, `login`) succeeds, **and** a plain non-TLS client is genuinely rejected connecting to the TLS-only registry/export — proving TLS is enforced, not merely configured.
**Files Created:** `AesGcmCipher.java`, `TamperDetectedException.java`, `SharedKeyProvider.java`, `DevTlsSetup.java` (common); `EncryptionService.java` (server); `AesGcmCipherTest.java`, `SharedKeyProviderTest.java` (common tests); `CryptoTestSupport.java`, `VaultServiceTlsIntegrationTest.java` (server tests).
**Files Modified:** `VaultService.java` (login javadoc), `VaultServiceImpl.java` (encryption + TLS constructor), `VaultFileService.java`/`FileRecord.java` (dropped the unused `iv`), `ServerMain.java`, `ClientMain.java`, `RmiConnection.java` (TLS wiring + banners), `authlock-server/build.gradle`, `authlock-client/build.gradle` (`workingDir`), `.gitignore` (key file, `certs/`); every file/lock integration test touched by mandatory encryption; `Decision.md` ADR-007 (→ `Accepted`), `Security.md` §3/§7/§11, `Architecture.md` §2.8/§3, `TRD.md`, `Testing.md` §4/§5, `README.md` (full refresh, was stale since Phase 1).
**Tests Added:** 15 new (`AesGcmCipherTest` ×6, `SharedKeyProviderTest` ×4, `VaultServiceTlsIntegrationTest` ×3, plus TEST-SEC-003/004 added to `VaultServiceFileIntegrationTest`).
**Tests Passed:** All 76 tests (`./gradlew clean build`). TLS test class specifically re-run 4 times (once in the full suite, three standalone) with zero flakiness after the hostname fix. Manually verified against a live cross-process server with **both** AES-GCM and TLS active simultaneously: encrypted upload/list/download/lock demo succeeded; separately confirmed the file on disk is genuinely plaintext (`cat vault-storage/*.bin` showed the original text) while the wire payload was genuinely ciphertext (53 bytes for a 38-byte plaintext, GCM tag overhead).
**Tests Failed:** None in the final state. One real failure diagnosed and fixed during development (the hostname/SAN mismatch above) — documented rather than silently worked around.
**Known Issues:** None new. Phase 11 cloud deployment will need to regenerate the dev TLS certificate with the VM's public IP/hostname in its SAN — flagged in Architecture.md §3 and Security.md §7.2 as an explicit Phase 11 task.
**Architecture Changes:** None beyond what Architecture.md §2.8 already specified (Encryption Service) — this phase implemented it, plus added the TLS bootstrap that §2.8 also anticipated ("and/or configure the RMI-over-TLS socket factories").
**Security Changes:** SEC-004 (encryption in transit) now fully implemented and tested for both file payloads and the whole RMI channel. Credentials/session tokens are no longer sent in the clear (previously flagged as a known gap in Security.md §3/§7).
**Open Decisions:** OQ-05 resolved (see §7) — the roadmap's only hard blocker. Only OQ-02 (SLA, low priority), OQ-06 (cloud provider, Phase 11), OQ-10 (at-rest encryption, low priority, arguably moot now), and OQ-14 (developer-name discrepancy, Phase 12) remain open — none block further implementation.
**Next Steps:** Begin Phase 7 — Audit Logging: `AuditLogger` (structured, append-only, per Security.md §9's schema), call sites added at every method's success/failure exit point across Authentication, Session, Vault, and Lock modules (all currently have `// Audit logging ... is Phase 7's responsibility` comments marking exactly where).
**Blockers:** None for Phase 7.

### Phase 7 — Audit Logging — `Completed`

**Current Phase:** Implementation Phase 7
**Current Status:** Completed
**Completed:**
- Built `AuditLogger`/`AuditRecord`/`AuditEventType`/`AuditResult` (`authlock-server.audit`) per Security.md §9's schema — structured, append-only, one JSON object per line, hand-serialized (properly escapes quotes/backslashes/newlines/control characters — verified by test), synchronous writes serialized under a single lock.
- **Simplified the schema along the way (Derived Decision):** merged Security.md §9's original separate "Authentication failure"/"Authorization failure" rows into each triggering operation's own event type + `FAILURE` result + `ErrorCode` — every event type now maps 1:1 to a `VaultService` method (except `ERROR`, which has none), eliminating a redundant way of recording the same fact. Documented and reasoned through in Security.md §9 and Decision.md ADR-008, not silently changed.
- Wired `AuditLogger` into every method's every success/failure exit: `login` (including logging the *attempted username*, not a resolved identity, on a failed login — standard brute-force/enumeration-detection practice, not a secret), `logout`, `uploadFile` (including the tamper-detection and malformed-ciphertext failure paths from Phase 6), `downloadFile`, `lockFile`, `unlockFile`. `listFiles()` stays deliberately unaudited (reaffirmed the Phase 0/4 decision).
- Changed `SessionManager.invalidate()` to return the removed `Session` (was: `boolean`) so `logout()`'s success audit record can be attributed to the right `userId` — a small, mechanical, well-contained signature change (two call sites: `VaultServiceImpl`, `SessionManagerTest`).
- Added two new session-validation/file-existence guard overloads in `VaultServiceImpl` (auditing and non-auditing) rather than forcing every caller through one path — keeps `listFiles()`'s "no audit" decision naturally enforced by which overload it calls, not by an extra conditional.
- **Considered and deliberately declined** a blanket `catch (RuntimeException)` wrapper around every method purely to force artificial `ERROR`-type audit coverage — judged unnecessary invasive complexity for this coursework scope (no such unclassified error path is currently reachable in normal operation). Proved `ERROR` is a usable, correctly-serializing event type via a direct unit test instead of contriving one through the RMI stack.
- Real client IP/host captured via `RemoteServer.getClientHost()`, falling back to `"unknown"` defensively.
**Files Created:** `AuditEventType.java`, `AuditResult.java`, `AuditRecord.java`, `AuditLogger.java` (server.audit); `AuditLoggerTest.java` (server.audit tests); `VaultServiceAuditIntegrationTest.java` (server tests).
**Files Modified:** `VaultServiceImpl.java` (audit wiring throughout), `SessionManager.java` (`invalidate()` return type), `SessionManagerTest.java` (matching call-site update), `ServerMain.java` (banner); `Decision.md` ADR-008, `Security.md` §9, `Architecture.md` §2.9, `API-spec.md` (`listFiles` audit-event note), `Testing.md` §5 (TEST-SEC-005 → Pass).
**Tests Added:** 11 new (`AuditLoggerTest` ×7 including a 20-thread concurrent-write stress test, `VaultServiceAuditIntegrationTest` ×4).
**Tests Passed:** All 87 tests (`./gradlew clean build`). Manually verified against a live cross-process server: ran the full login/upload/download/lock/unlock/logout demo, then read the real `audit.log` — 10 correctly-ordered, correctly-correlated records (matching `fileId`/`userId` across events), and confirmed by `grep` that neither the plaintext password nor either raw session token appeared anywhere in the file.
**Tests Failed:** None.
**Known Issues:** None new.
**Architecture Changes:** None — implements exactly the Audit Logger component already specified in Architecture.md §2.9.
**Security Changes:** SEC-008 (audit logging) and NFR-010 (auditability) now fully implemented and tested, including the explicit no-secrets-in-log guarantee (TEST-SEC-005).
**Open Decisions:** None resolved or newly raised this phase. Same four remain open: OQ-02 (SLA, low priority), OQ-06 (cloud provider, Phase 11), OQ-10 (at-rest encryption, low priority), OQ-14 (developer-name discrepancy, Phase 12).
**Next Steps:** Begin Phase 8 — Swing UI: replace `ClientMain`'s console demo with the real login screen and dashboard (UIUX.md §1–§2) — file list, upload/download/lock/unlock/refresh/logout controls, connection-state and lock-state indicators, all wired to the same `VaultService` calls the console demo already exercises correctly.
**Blockers:** None for Phase 8.

### Phase 8 — Swing UI — `Completed`

**Current Phase:** Implementation Phase 8
**Current Status:** Completed. Full interactive GUI verification finished across two sessions (initial walkthrough + a follow-up session that closed the two remaining gaps, TEST-UI-001 and TEST-UI-004) — see "Verification" below for the full, honest account.
**Completed:**
- Built `SwingAsync` (`authlock-client.ui`) — runs any blocking call (every `VaultService` RMI call, plus local encrypt/decrypt/file I/O around it) off the Swing EDT via `SwingWorker`, delivering the result or failure back on the EDT. The one shared plumbing piece behind every async action, replacing what would otherwise be repeated `SwingWorker` boilerplate at every call site.
- Built `LoginFrame` (UIUX.md §1): username/password fields, Login button, status/connection-state label. Attempts the RMI connection in the background as soon as the window opens (not just on first submit), so connection state is visible immediately — Login button stays disabled until connected. Enter in either field submits (keyboard accessibility, UIUX.md §7).
- Built `DashboardFrame` (UIUX.md §2): file table (`FileTableModel` — filename, size, owner, modified time, lock state as plain text: "Unlocked"/"Locked (you)"/"Locked (another user)", never color-only per UIUX.md §7), Upload/Download/Lock/Unlock/Refresh/Logout buttons, connection-state + status labels. Lock button enabled only when unlocked; Unlock only when locked by *this* session — enforced client-side for UX only, the server re-checks ownership regardless (Development-rules §3 "never trust the client" cuts both ways). Every button disables during its own in-flight call (UIUX.md §5 "Loading state").
- `doUpload`/`doDownload` use `JFileChooser` for both source and destination (UIUX.md §5 "safe file selection" — no free-text path entry), with AES-GCM encrypt/decrypt happening inline around the file I/O, off the EDT.
- `handleFailure` centralizes error → UI mapping: `INVALID_SESSION` triggers a "Session Expired" dialog and returns to `LoginFrame` (UIUX.md §4); other `ErrorCode`s get friendly inline status messages (e.g. `FILE_LOCKED` → "this file is locked by another user."); `RemoteException`/`ServerUnavailableException` updates the connection-state indicator.
- Rewrote `ClientMain` down to pure process bootstrap (TLS config via `DevTlsSetup`, shared-key loading via `SharedKeyProvider`, host resolution) — startup failures now show a `JOptionPane` dialog rather than printing to a console a GUI app may not have — then hands off to `LoginFrame` on the EDT via `SwingUtilities.invokeLater`. The old console demo (`runDemo`/`demoFileRoundTrip`/`demoLocking`) is fully removed, as anticipated in comments left since Phase 3.
- **Follow-up session:** added an opt-in `authlock.session.idleTimeoutSeconds` / `authlock.session.maxLifetimeSeconds` system-property override (`SessionManager(Duration, Duration)` public constructor, `VaultServiceImpl.createSessionManager()`) purely so a real server process could be run with a short session lifetime to interactively prove expiry behavior without waiting 30 real minutes. Unset in the default/production path, so `SessionManager()`'s OQ-08 defaults (30 min idle / 8 hr absolute) are completely unchanged — this is a testability addition, not a security-relevant behavior change.
**Verification — full, honest account (spans two sessions):**
- *Session 1:* the client compiled cleanly and the server-side test suite (87 tests) passed. Started a real server and client in the sandbox's X display and captured one genuine screenshot of the Login screen showing "Connected. Enter your credentials." — proving the RMI-over-TLS connection worked. A `Robot`-based automation attempt to drive the rest of the walkthrough destabilized the sandbox's X display partway through, so the session stopped rather than fabricate further "verified" steps, and substituted a careful manual code review instead. Phase 8 was left as `Code-complete`, not `Completed`, and TEST-UI-001..004 were marked "Implemented, manual confirmation pending" in Testing.md.
- *Session 2 (this one):* the X display had recovered. Ran a real server + client end-to-end and drove the full journey with `java.awt.Robot`, capturing screenshots at each step: connect → login (alice) → Dashboard renders → upload → lock → unlock → re-lock → download → logout, plus Login↔Dashboard navigation. **Found and fixed a genuine bug in the process** (not an automation artifact): the Dashboard's "Owner" column was showing the raw internal `userId` UUID instead of the username — `VaultServiceImpl.uploadFile()` was passing the session's `userId` straight through as file ownership. Fixed by adding `UserStore.findByUserId()` and resolving the display username at upload time; audit-log entries deliberately keep logging the stable `userId` (Security.md §9 already documents that field as "identifier of the acting user"), only the UI-facing `owner` field changed. Full 87-test suite re-run and still green after the fix.
- Verified file integrity end-to-end, not just "no exception thrown": uploaded the actual `AuthLock_Proposal (1).docx`, downloaded it back, and its SHA-256 matched the git-committed original exactly (`git show HEAD:"AuthLock_Proposal (1).docx" | sha256sum`) — proof the AES-256-GCM encrypt/upload → TLS transit → decrypt/download round trip is byte-perfect, not merely "didn't crash."
- Cross-checked the real `audit.log` against every click made: LOGIN, UPLOAD, LOCK, UNLOCK, LOCK, DOWNLOAD, UNLOCK, LOGOUT — every entry present, correctly ordered, same `userId` throughout.
- Finished the two remaining gaps from Session 1's Testing.md matrix:
  - **TEST-UI-001 (invalid login):** driven via keyboard (Tab/type/Enter) rather than mouse clicks, since mouse-click automation on this specific button proved unreliable in the sandbox (a GUI-automation quirk, addressed by using keyboard input instead of chasing pixel coordinates, per the same accessibility path UIUX.md §7 already documents). Confirmed: stayed on Login, exact error text `"Invalid username or password."`, password field cleared, app remained fully usable (verified by immediately logging in successfully in the same window), and `audit.log` recorded exactly one `LOGIN FAILURE`/`AUTHENTICATION_FAILED` entry with no session ever created.
  - **TEST-UI-004 (session expiry):** ran a real server with `authlock.session.idleTimeoutSeconds=12`, logged in, let the session idle out for real, then triggered a real RMI call. Server genuinely rejected it with `INVALID_SESSION`; client showed the exact "Session Expired" dialog and returned to a fully usable Login screen; a fresh login immediately succeeded, proving clean recovery. Reproduced twice with identical results. One dismissal attempt via a mis-clicked mouse coordinate closed the whole client outright — diagnosed as pure GUI-automation flakiness (not a code defect) by reproducing the identical scenario and dismissing correctly via Enter instead, which left the app fully alive; documented as such rather than "fixed" in application code, per the instruction not to change working code for automation-only misses.
- **What this means concretely:** every UI workflow in scope for this phase (Testing.md §4 TEST-UI-001..004, plus the wider login/dashboard/upload/lock/unlock/download/logout journey) has now actually been clicked through against a live server, not just code-reviewed.
**Files Created:** `SwingAsync.java`, `FileTableModel.java`, `LoginFrame.java`, `DashboardFrame.java` (authlock-client.ui) — Session 1. None new in Session 2.
**Files Modified:** `ClientMain.java` (console demo → pure bootstrap, Session 1); `VaultServiceImpl.java` (Owner-column fix + `createSessionManager()`, Session 2); `UserStore.java` (`findByUserId()`, Session 2); `SessionManager.java` (public `IDLE_TIMEOUT`/`MAX_LIFETIME` constants + `SessionManager(Duration, Duration)` constructor, Session 2); `Architecture.md` §2.1 (Session 1); `Testing.md` §5 (TEST-UI-001..004 all updated to **Pass** with verification method/evidence, Session 2).
**Tests Added:** None this phase (Swing UI has no automated test harness in this project — TEST-UI-* were always scoped as manual/interactive per Testing.md §1, consistent with a coursework Swing client). The `SessionManager(Duration, Duration)` constructor is exercised indirectly (via `VaultServiceImpl`'s live server run) rather than by a new unit test, since its behavior is identical to the already-unit-tested package-private test constructor.
**Tests Passed:** All 87 automated tests pass, including after the Owner-column fix and the `SessionManager` constructor addition (re-run via `./gradlew test`). Every TEST-UI-* case is now interactively confirmed against a live server (see Testing.md §5) — screenshots, audit-log cross-checks, and a SHA-256 file-integrity check obtained as evidence.
**Tests Failed:** None.
**Known Issues:** None remaining for this phase. (Historical note: Session 1's X-display instability during `Robot` automation did not recur in Session 2, and this session's automation stayed stable throughout an even longer interactive run — see §13 for what was tried.)
**Architecture Changes:** None — implements exactly the Swing RMI Client component already specified in Architecture.md §2.1.
**Security Changes:** The new `authlock.session.idleTimeoutSeconds`/`authlock.session.maxLifetimeSeconds` system properties are opt-in only, default to the unchanged OQ-08 production values (30 min / 8 hr) when unset, and exist solely to make session-expiry interactively testable — not a production security posture change. Otherwise none new: client-side security posture (session token held only in memory, password `char[]` zeroed after use) matches what was already designed; UI button enablement remains UX convenience only, not a security boundary.
**Open Decisions:** None resolved or newly raised. Same four remain open: OQ-02, OQ-06, OQ-10, OQ-14.
**Next Steps:** Begin Phase 9 — Integration: exercise the rest of [flow.md](flow.md)'s 18 flows end-to-end now that the UI layer is fully verified.
**Blockers:** None. Phase 8 is genuinely `Completed` — every exit criterion (all workflows implemented, code-reviewed, and interactively verified against a live server; all four TEST-UI-* cases passing with evidence; full automated suite green) is satisfied.

### Phase 9 — Integration — `Completed`

**Current Phase:** Implementation Phase 9
**Current Status:** Completed.
**Completed:**
- Mapped every one of [flow.md](flow.md)'s 18 flows to concrete evidence — either the existing 88-test automated suite (real RMI throughout, no mocks), Phase 8's interactive verification, or fresh work this phase — and closed the one genuine gap found: **TEST-INT-002** (Testing.md §4, "server stopped mid-session, client attempts a call"), previously `Not Run`.
- **Automated fix for TEST-INT-002:** added `VaultServiceRmiIntegrationTest.clientCallOnAnAlreadyObtainedStubFailsClearlyAfterServerStops` — obtains a real stub over real RMI, force-unexports the live `VaultServiceImpl` out from under it (`UnicastRemoteObject.unexportObject(service, true)`, simulating the server process dying mid-session), then asserts the next call on that stub throws a clean `RemoteException` rather than hanging or failing silently. Discovered and fixed a genuine test-infrastructure bug along the way: this class's `@AfterEach` only ever called `registry.unbind(...)`, never unexported the registry object itself — `LocateRegistry.createRegistry(port)` exports the registry as a long-lived remote object in its own right, so the port stayed bound after the first test, and a second `@Test` method reusing it threw `ExportException`. Fixed by also `UnicastRemoteObject.unexportObject`-ing the registry in teardown (same root-cause pattern already documented elsewhere in this project's test history — `SessionManagerTest` et al. — just not yet applied to this specific class, since it only ever had one test method before). 88 tests total now (87 + 1), full suite still green.
- **Live demonstration, flow.md §17 "Server Unavailable":** started a real client against a genuinely dead server (a server was run once to generate `authlock-shared.key`/`certs/`, then killed outright before the client ever attempted to connect). Client's Login screen correctly showed `"Server unavailable — Connection refused"`, Login button stayed disabled, no crash, no operation attempted — exactly matching the flow's documented behavior and `RmiConnection`'s `ServerUnavailableException` design.
- **Headline new evidence — a genuine two-actor integration session:** ran the *real* server plus two fully independent `ClientMain` processes simultaneously, logged in as alice and bob respectively (two distinct live windows, two distinct real `userId`s confirmed via `audit.log`). Alice uploaded a file and locked it; bob's completely separate session, on its own `Refresh`, correctly showed the file as `"Locked (another user)"` (never alice's raw identity/session token, per API-spec.md's minimal-disclosure design) with Lock and Unlock both correctly disabled even with the row selected — proving the client's lock-state-aware UI reflects genuinely concurrent cross-process server state, not just a single session re-observing itself (which is all Phase 8 had shown). Bob then downloaded the still-locked file anyway — confirming OQ-09 ("download performs no lock check") live and cross-user, with the downloaded bytes verified identical (`diff`) to alice's original upload. Wrapped up with alice unlocking and both users logging out; the resulting `audit.log` shows the exact interleaved sequence (alice: LOGIN/UPLOAD/LOCK/UNLOCK/LOCK/…/UNLOCK/LOGOUT; bob: LOGIN/DOWNLOAD/LOGOUT) with correct ordering and correct per-user attribution throughout.
- Every other flow (login, authentication failure, session creation/validation/expiry, file listing, upload, download, file locking, file unlock, the mandatory concurrent-lock race, logout, invalid session, unauthorized lock-not-owned access, upload/download failure paths, cross-cutting audit logging) already had solid, real evidence from the 88-test suite and/or Phase 8's own interactive verification — deliberately **not** redundantly re-demonstrated through the GUI a second time; re-confirmed still passing instead. Two flows (10 — concurrent lock race, 11 — stale-lock timeout) specifically were left to their existing automated coverage (TEST-CONC-001's 120+ real-RMI race rounds; `LockManagerTest`'s deterministic-clock timeout tests) rather than re-run live, since redoing either through the Swing UI would add wall-clock time (a 15-minute lock timeout) or redundant risk without new information — a deliberate, documented scope decision, not an oversight.
- One flow (14 — Unauthorized Access / Lock Not Owned) is **not** reachable through the well-behaved Swing client at all by design: `DashboardFrame`'s Unlock button is client-side disabled whenever `lockOwnerHint != "you"`, so bob's GUI never even offers the doomed action — confirmed live this phase (bob's Unlock stayed disabled while viewing alice's lock). The server-side enforcement this flow actually tests (a malicious/buggy client attempting it anyway) is correctly and only testable by going around the friendly client, which is exactly what the existing `TEST-LOCK-004` automated test does directly against a raw `VaultService` stub — the right layer for this specific check, not a gap.
**Files Created:** None.
**Files Modified:** `VaultServiceRmiIntegrationTest.java` (new TEST-INT-002 test + registry-unexport teardown fix); `Testing.md` §4/§5 (TEST-INT-002 → **Pass**, with both forms of evidence).
**Tests Added:** 1 (`clientCallOnAnAlreadyObtainedStubFailsClearlyAfterServerStops`).
**Tests Passed:** All 88 automated tests (`./gradlew clean test`, full rebuild from scratch). All 18 flow.md flows demonstrated working, per the mapping above.
**Tests Failed:** None.
**Known Issues:** None found this phase. No integration gap required an application-code fix — the one real gap (TEST-INT-002) was a missing *test*, not a defect in the RMI/error-handling code, which already behaved correctly (`RemoteException` propagates cleanly; `ServerUnavailableException` wraps lookup failures) — confirmed once actually tested.
**Architecture Changes:** None.
**Security Changes:** None.
**Open Decisions:** None resolved or newly raised. Same four remain open: OQ-02, OQ-06, OQ-10, OQ-14.
**Next Steps:** Begin Phase 10 — Testing: execute/sign off the full [Testing.md](Testing.md) matrix (it is now nearly entirely `Pass` already, as a natural byproduct of how thoroughly Phases 1–9 tested along the way — Phase 10's remaining job is mostly a final consolidated pass/audit, not fresh test-writing) plus a from-scratch full-suite run and a final review that Testing.md and Context.md fully agree.
**Blockers:** None for Phase 10. The only genuinely `Not Run` rows left in Testing.md's matrix are TEST-DEPLOY-001/002, which correctly require Phase 11's cloud VM and are out of scope until then.

### Phase 10 — Testing — `Completed`

**Current Phase:** Implementation Phase 10
**Current Status:** Completed.
**Completed:**
- Executed the full QA strategy from [Testing.md](Testing.md) fresh, as this phase's own actual work — not re-citing prior phases' results without re-checking them.
- **From-scratch clean build:** `./gradlew clean test` — every module recompiled from nothing, all 88 tests across 15 test classes passed, 0 failures, 0 errors.
- **Mandatory concurrency test re-executed explicitly, 4 more times** (`VaultServiceLockConcurrencyTest` — 5 concurrent real client sessions × 30 rounds per run = 150 additional race rounds this phase, on top of the 120+ already accumulated across Phases 5–9): exactly one winner every single round, zero collisions, zero flakes. This is the project's headline distributed-systems guarantee and now has 270+ cumulative real-RMI race rounds behind it with a perfect record.
- **Consistency audit:** spot-checked a sample of Testing.md's test-method citations directly against the actual test source (`testSec003_tamperedCiphertextIsRejected`, `testSec004_ciphertextOnTheWireNeverEqualsOrContainsThePlaintext`, `aPlainNonTlsClientCannotConnectToTheTlsOnlyRegistry`, and TEST-SEC-005's actual `assertFalse(line.contains(...))` secret-leakage assertions) — every citation checked out accurate, none stale or aspirational.
- **Gave the two remaining `Not Run` rows an explicit, documented justification** (Implementation.md's DoD explicitly allows "documented, justified exceptions," not just blank `Pass`/`Not Run`): TEST-DEPLOY-001/002 both require a provisioned cloud VM and OQ-06 resolved, both correctly scoped to Phase 11, not Phase 10 — and everything each test would exercise *except* genuine network separation and public-IP reachability is already independently proven locally (the full user journey, TLS, encryption, locking, audit — every other row in this same matrix, plus Phase 9's live two-actor session).
- **Result: [Testing.md](Testing.md)'s Test Matrix (§5) shows `Pass` for every test case except those two documented, justified exceptions — Phase 10's Definition of Done is met exactly as specified**, not approximately.
**Files Created:** None.
**Files Modified:** `Testing.md` §5 (TEST-DEPLOY-001/002 given explicit justification text instead of a bare `—`; added a summary line recording this phase's from-scratch run and audit results).
**Tests Added:** None — Phase 10 is an execution/sign-off phase per its own Implementation.md scope ("Tests required: N/A — this *is* the testing phase"), not a test-authoring phase. All test-writing happened during Phases 1–9, where each capability was tested as it was built.
**Tests Passed:** All 88 (100%), fresh from a clean build. TEST-CONC-001 specifically: 150 additional rounds this phase, 0 failures.
**Tests Failed:** None.
**Known Issues:** None found. No defect surfaced during this phase's execution — everything already worked as documented; Phase 10's value was in the fresh, independent confirmation and the consistency audit, not in fixing anything.
**Architecture Changes:** None.
**Security Changes:** None.
**Open Decisions:** None resolved or newly raised. Same four remain open: OQ-02, OQ-06, OQ-10, OQ-14. (OQ-06 specifically now gates Phase 11's very first task.)
**Next Steps:** Begin Phase 11 — Cloud Deployment: resolve OQ-06 (which free-tier cloud provider), provision a VM, set `java.rmi.server.hostname` to the VM's address, open the required firewall/security-group ports, start the server via `nohup`/systemd, and connect a genuinely remote client to prove TEST-DEPLOY-001/002.
**Blockers:** OQ-06 (cloud provider choice) must be resolved before Phase 11 can begin provisioning — the one thing standing between here and Phase 11's first concrete step.

### Phase 11 — Cloud Deployment — `In Progress` (infrastructure/scripts ready; live execution pending)

**Current Phase:** Implementation Phase 11
**Current Status:** In Progress. Infrastructure-as-code and deployment scripts are written, code-reviewed, and locally verified wherever this sandbox allows without an actual target VM. **Not yet executed against a real AWS account** — no AWS credentials exist in this environment.
**Completed:**
- Resolved OQ-06: **AWS**, EC2 `t2.micro` (12-month free tier, 750 hrs/month).
- `terraform/` — `versions.tf`, `variables.tf` (every value defaulted except `key_name`, which is deliberately required so Terraform never manages unmanaged key material), `main.tf` (a security group opening exactly 22/1099/5000 — nothing broader; an `aws_ami` data source resolving the latest Ubuntu 22.04 rather than a hardcoded, region-specific AMI ID; `user_data` built from `scripts/provision-vm.sh`'s raw content via `${file(...)}`, deliberately not `templatefile()`, so the script's own bash `${VAR}` syntax passes through untouched instead of being mis-parsed as Terraform interpolation), `outputs.tf` (public IP/DNS, a ready-to-paste SSH command, and a full "what to do next" block), `README.md` (full walkthrough, both the manual-finish and fully-automated paths, plus a section on using the same scripts on a non-AWS VM).
- `scripts/provision-vm.sh` — root, boot-time (EC2 `user_data` or manual `sudo bash`), idempotent: installs Java 17 + curl/git/unzip (apt/dnf/yum auto-detected), creates a dedicated `authlock` system account, configures the local firewall (ufw/firewalld, belt-and-suspenders alongside the cloud security group — matters most for the non-AWS case), discovers the machine's public IP/DNS (AWS IMDSv2 token-based metadata request first, falling back to a generic public-IP echo service so the same script works unmodified off AWS), installs (but does not yet start) the `authlock-server` systemd unit, and — if `AUTHLOCK_SOURCE_URL` is set — downloads/extracts a source tarball and chain-invokes `setup-authlock.sh` for a fully hands-off `terraform apply`.
- `scripts/setup-authlock.sh` — the "run it on a VM instead of the cloud" script the user specifically asked for: self-sufficient (installs Java itself if `provision-vm.sh` never ran), builds the real deployable artifact via `./gradlew :authlock-server:installDist` (Gradle's `application` plugin, already configured in `authlock-server/build.gradle` — not a hand-rolled classpath), re-discovers the public address independently if needed, writes `/opt/authlock/authlock-server.env` with `JAVA_OPTS` setting both `-Djava.rmi.server.hostname=<addr>` and the new `-Dauthlock.tls.extraSan=...`, installs/starts the systemd service if not already present, and prints the exact `scp` commands + client run command needed to connect a genuinely remote Swing client.
- **Found and fixed a real, pre-existing gap:** `DevTlsSetup`'s self-signed certificate SAN was hardcoded to `dns:localhost,ip:127.0.0.1` — already flagged as an explicit, not-yet-done Phase 11 task in Architecture.md §3 and Security.md §7 since Phase 6 (not a surprise discovered now; a known, tracked item now actually resolved). Added an optional `-Dauthlock.tls.extraSan=<entry>[,<entry>...]` system property, additive to the base SAN, taking effect only the first time a given keystore path is generated — completely unchanged default behavior when unset, so every existing local/test usage is untouched. **Verified directly, not just by code review:** generated a real certificate with `authlock.tls.extraSan=ip:203.0.113.55,dns:ec2-...` set and confirmed via `keytool -list -v` that its `SubjectAlternativeName` block genuinely contains all four entries (localhost, 127.0.0.1, the IP, the DNS name).
- Updated `Architecture.md` §3 and `Security.md` §7's TLS-certificate rows from "tracked as a Phase 11 task, not yet done" to describing the actual resolution.
- **Verified everything testable without a real target VM:** full 88-test suite still green after the `DevTlsSetup` change; `./gradlew :authlock-server:installDist` confirmed to produce exactly the launcher path (`authlock-server/build/install/authlock-server/bin/authlock-server`) both scripts' systemd unit expects, and confirmed it reads `JAVA_OPTS` (Gradle `application`-plugin generated launchers support this by convention); both scripts pass `bash -n` syntax checks; the public-address discovery fallback chain, run for real in this sandbox, correctly found the AWS metadata service unreachable (empty token, as expected off-AWS) and fell through to a generic public-IP echo service, returning a genuine real IP; all four `.tf` files' `{`/`}` counts balance and every `var.*`/`resource.*` reference was checked by hand for consistency across files (no `terraform` binary available in this sandbox to run `terraform validate`).
**Files Created:** `terraform/versions.tf`, `terraform/variables.tf`, `terraform/main.tf`, `terraform/outputs.tf`, `terraform/README.md`, `scripts/provision-vm.sh`, `scripts/setup-authlock.sh`.
**Files Modified:** `DevTlsSetup.java` (`authlock.tls.extraSan` support); `Architecture.md` §3, `Security.md` §7 (TLS-certificate rows updated from open task to resolved); `Context.md` (OQ-06 resolved, this log entry).
**Tests Added:** None (no existing automated test exercises `DevTlsSetup`'s keytool-shelling-out behavior at all, before or after this change — it was already, and remains, verified manually/by direct inspection rather than a unit test, consistent with how it was left in Phase 6).
**Tests Passed:** All 88 pre-existing tests still pass after the `DevTlsSetup` change (`./gradlew clean test`). The new SAN behavior itself was verified via a standalone `keytool -list -v` inspection (see above), not a JUnit test.
**Tests Failed:** None.
**Known Issues:** **TEST-DEPLOY-001/002 remain `Not Run`** — this is the one honest, material gap in this phase. This sandbox has no AWS credentials, so `terraform apply` has never actually been executed; no real EC2 instance exists; no genuinely remote client has connected. Everything short of that live execution is done and locally verified. This is not being papered over as "basically done" — it's the explicit, named next action.
**Architecture Changes:** None to the running system's architecture — `DevTlsSetup`'s change is additive/opt-in. Deployment topology matches exactly what Architecture.md §3 already specified before this phase (single VM, fixed RMI ports, `java.rmi.server.hostname` set explicitly) — this phase implements that existing design, doesn't change it.
**Security Changes:** The TLS certificate's SAN can now legitimately include a real deployment address — closes a real gap (a remote client's TLS handshake would otherwise fail against a `localhost`-only cert) rather than opening one; still a self-signed, coursework-scope certificate, per OQ-05's already-accepted trade-off.
**Open Decisions:** OQ-06 resolved this phase (AWS). Two remain open: OQ-02 (SLA, low priority), OQ-10 (at-rest encryption, low priority), OQ-14 (developer-name discrepancy, Phase 12).
**Next Steps:** Whoever has an AWS account: `cd terraform && terraform init && terraform apply -var="key_name=<your-key-pair>"`, then follow the printed `next_steps` output (or `terraform/README.md`) to finish the app-layer deployment and connect a real remote client — that run is what actually proves TEST-DEPLOY-001/002 and lets Phase 11 be marked `Completed`. After that: Phase 12 — Packaging & Demonstration.
**Blockers:** Real AWS credentials, which this environment does not have. Everything else needed to run `terraform apply` successfully is in place.

---

## 14. Next Steps

The exact, recommended next action is: **run the Phase 11 infrastructure for real.** Everything code-side is ready — `terraform/` (AWS, resolves OQ-06), `scripts/provision-vm.sh`, `scripts/setup-authlock.sh`, and the `DevTlsSetup` TLS-SAN fix these scripts depend on — but this sandbox has no AWS credentials, so `terraform apply` has never actually been run. Whoever has an AWS account should: `cd terraform && terraform init && terraform apply -var="key_name=<your-key-pair>"`, then follow `terraform/README.md` to finish the app-layer deployment and connect a genuinely remote Swing client. That live run is what proves TEST-DEPLOY-001/002 and lets Phase 11 be marked `Completed`, after which Phase 12 — Packaging & Demonstration is next.

No Open Question blocks Phase 11's remaining step. OQ-06 (the one that did) is resolved.
