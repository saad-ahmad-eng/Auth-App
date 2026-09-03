# Implementation.md — Implementation Roadmap

**Project:** AuthLock
**Status:** Planning only — **no implementation has started** (see [Context.md](Context.md) Current Phase).
**Related documents:** all other documents in this package; this roadmap sequences work that draws on every one of them.

This is a **blueprint for future work**, not a build log. Each phase lists objective, prerequisites, tasks, expected files, requirements satisfied, required tests, definition of done, and documentation to update — so a future implementation session (human or Claude) can pick up any phase with full context.

---

### Phase 0 — Documentation *(this phase)*

- **Objective:** Produce the complete specification package.
- **Prerequisites:** `auth` proposal available.
- **Tasks:** PRD, TRD, Decision, Security, Development-rules, Architecture, flow, Backend, API-spec, Req-Doc-Alnafi, Implementation, Testing, UIUX, Context.
- **Files expected:** the 14 `.md` files in this directory.
- **Requirements satisfied:** N/A (documentation of requirements, not implementation).
- **Tests required:** None (self-review checklist, [p1.md](p1.md) §26).
- **Definition of Done:** All 14 documents exist, are internally consistent (§Cross-document consistency audit in [Context.md](Context.md)), and every `auth` requirement is traceable ([Req-Doc-Alnafi.md](Req-Doc-Alnafi.md)).
- **Docs to update:** N/A — this phase produces them.

---

### Phase 1 — Project Skeleton

- **Objective:** Create a buildable, empty Java project shell for client and server.
- **Prerequisites:** Phase 0 complete; TRD build-tool/JDK decisions finalized (Open Question OQ-03).
- **Tasks:** Initialize Maven (or Gradle) project(s) — either a single multi-module project (`authlock-common`, `authlock-server`, `authlock-client`) or two sibling projects sharing a `common` dependency; set up package structure per [Development-rules.md](Development-rules.md) §2; configure VS Code Java project settings; initialize Git repository.
- **Files expected:** `pom.xml`/`build.gradle`, empty package directories, `.gitignore`, `README.md` (build/run instructions).
- **Requirements satisfied:** TRD §4 Development Environment.
- **Tests required:** Project builds successfully (`mvn compile` / `gradle build`) with no source files beyond placeholders.
- **Definition of Done:** `mvn package` (or equivalent) succeeds and produces empty/stub JARs.
- **Docs to update:** [Context.md](Context.md) (status, completed work).

---

### Phase 2 — RMI Infrastructure

- **Objective:** Establish the RMI communication channel with a minimal no-op remote method, proving connectivity end-to-end.
- **Prerequisites:** Phase 1.
- **Tasks:** Define `VaultService` interface (`extends Remote`) with a placeholder `ping()`/health method; implement server bootstrap that creates the registry and binds the service; implement client bootstrap that looks up the stub and calls it; verify over `localhost`.
- **Files expected:** `VaultService.java` (common), `VaultServiceImpl.java` (server), `ServerMain.java`, `ClientMain.java` (or client bootstrap class).
- **Requirements satisfied:** ADR-001 (Java RMI), TRD §2.6/§3 RMI requirements.
- **Tests required:** TEST-INT-001 (basic RMI round trip).
- **Definition of Done:** Client successfully invokes a remote method on the server over `localhost` RMI and receives a response.
- **Docs to update:** [Context.md](Context.md).

---

### Phase 3 — Authentication & Session Management

- **Objective:** Implement `login()`, `logout()`, and session validation.
- **Prerequisites:** Phase 2; Open Question OQ-04 (session persistence across restarts) and OQ-08 (idle timeout value) resolved or explicitly deferred with an interim default.
- **Tasks:** User credential store (seeded per PRD §4.1, since no registration exists); password hashing per Security.md §3; `SessionManager` with `SecureRandom` token generation, expiry, and cleanup sweep; wire `login()`/`logout()` into `VaultService`; add session-token validation to every other method stub.
- **Files expected:** `AuthenticationService.java`, `SessionManager.java`, `UserStore.java` (or equivalent), seed user data file.
- **Requirements satisfied:** FR-001–FR-004, SEC-001, SEC-002, SEC-003.
- **Tests required:** TEST-AUTH-001..005, TEST-SESSION-001..004.
- **Definition of Done:** Valid/invalid login both behave per [flow.md](flow.md) §1–§2; a call with an invalid/expired token is uniformly rejected across all guarded methods.
- **Docs to update:** [Context.md](Context.md); [Security.md](Security.md) if any Open Question default changes.

---

### Phase 4 — File Vault (Storage, Listing, Upload, Download)

- **Objective:** Implement the file storage subsystem.
- **Prerequisites:** Phase 3 (all vault methods require a valid session); ADR-010 storage-mechanism choice finalized (Open Question OQ-07).
- **Tasks:** `VaultFileService` for storage/retrieval; server-generated `fileId` scheme; metadata index (list/create/update); checksum computation/verification; `listFiles()`, `uploadFile()`, `downloadFile()` wired into `VaultService`; filename sanitization (SEC-006).
- **Files expected:** `VaultFileService.java`, `FileMetadata.java`, metadata storage file(s)/directory layout.
- **Requirements satisfied:** FR-005–FR-007, SEC-006.
- **Tests required:** TEST-FILE-001..006.
- **Definition of Done:** A file uploaded by one client is listed and downloadable byte-identical (checksum match) by any authenticated client, per [flow.md](flow.md) §5–§7.
- **Docs to update:** [Context.md](Context.md).

---

### Phase 5 — Distributed Locking

- **Objective:** Implement the project's core feature — atomic, server-authoritative file locking.
- **Prerequisites:** Phase 4; Open Question OQ-12 (per-session vs per-user ownership) and OQ-13 (timeout value) resolved or defaulted.
- **Tasks:** `LockManager` with atomic acquire (`putIfAbsent`-style) and owner-checked release; wire `lockFile()`/`unlockFile()`; wire lock-state into `listFiles()` metadata; wire stale-lock release into the Session Manager's cleanup sweep (Phase 3's sweep now also releases orphaned locks).
- **Files expected:** `LockManager.java`, `FileLock.java`.
- **Requirements satisfied:** FR-008–FR-010, SEC-009, ADR-004, ADR-005.
- **Tests required:** TEST-LOCK-001..007, **TEST-CONC-001 (the mandatory multi-client concurrent-lock race test — must show exactly one winner every run)**.
- **Definition of Done:** TEST-CONC-001 passes reliably across repeated runs (no flaky race).
- **Docs to update:** [Context.md](Context.md); [Decision.md](Decision.md) if ADR-005/ADR-004 assumptions change during implementation.

---

### Phase 6 — Encryption

- **Objective:** Implement in-transit encryption per the final resolution of ADR-007.
- **Prerequisites:** Phase 4; **Open Question OQ-05 (key management) must be resolved before this phase starts** — this is a hard blocker, not a soft recommendation.
- **Tasks:** Implement AES-256-GCM helper (`EncryptionService`) and/or configure `SslRMIClientSocketFactory`/`SslRMIServerSocketFactory` for RMI-over-TLS, per whichever option ADR-007 is finalized to; wire encryption into `uploadFile()`/`downloadFile()` client and server paths.
- **Files expected:** `EncryptionService.java`, TLS keystore/truststore config if RMI-over-TLS is chosen.
- **Requirements satisfied:** NFR-001, SEC-004, ADR-007 (status → `Accepted`).
- **Tests required:** TEST-SEC-004.
- **Definition of Done:** File bytes are never observable in plaintext on the wire (verified by a packet capture or equivalent check in testing); tampered ciphertext is rejected (GCM tag failure) rather than silently accepted.
- **Docs to update:** [Decision.md](Decision.md) (ADR-007 status), [Security.md](Security.md) (resolve OQ-05).

---

### Phase 7 — Audit Logging

- **Objective:** Implement the structured, append-only audit log across every module.
- **Prerequisites:** Phases 3–6 (there must be events to log).
- **Tasks:** `AuditLogger` with the schema from Security.md §9; call sites added at every method's success/failure exit point across Authentication, Session, Vault, and Lock modules.
- **Files expected:** `AuditLogger.java`, `audit.log` output.
- **Requirements satisfied:** FR-011, SEC-008, NFR-010, ADR-008.
- **Tests required:** TEST-SEC-005.
- **Definition of Done:** Every event type in Security.md §9's table is demonstrably produced by exercising the corresponding operation; log contains no prohibited fields (passwords/tokens/keys).
- **Docs to update:** [Context.md](Context.md).

---

### Phase 8 — Swing UI

- **Objective:** Build the client GUI per [UIUX.md](UIUX.md).
- **Prerequisites:** Phases 3–7 (UI exercises the full API surface).
- **Tasks:** Login screen; main dashboard (file list, upload/download/lock/unlock/refresh/logout, status indicator); notifications/dialogs per UIUX.md; wire each control to the corresponding `VaultService` call with client-side AES step (if applicable) around upload/download.
- **Files expected:** `LoginFrame.java`, `DashboardFrame.java`, supporting Swing components/listeners.
- **Requirements satisfied:** NFR-007, ADR-002, all of [UIUX.md](UIUX.md).
- **Tests required:** TEST-UI-001..004.
- **Definition of Done:** A user can complete the full login → list → upload → lock → download → unlock → logout journey through the GUI alone.
- **Docs to update:** [Context.md](Context.md).

---

### Phase 9 — Integration

- **Objective:** Exercise client and server together as a whole system, over `localhost` first.
- **Prerequisites:** Phases 2–8.
- **Tasks:** End-to-end manual and scripted runs of every flow in [flow.md](flow.md); fix integration gaps found.
- **Files expected:** Integration test harness/scripts.
- **Requirements satisfied:** Cross-cutting — validates the whole API-spec.md and flow.md.
- **Tests required:** TEST-INT-001 and beyond (full integration suite).
- **Definition of Done:** All 18 flows in [flow.md](flow.md) demonstrated working end-to-end locally.
- **Docs to update:** [Context.md](Context.md); [Testing.md](Testing.md) test matrix status.

---

### Phase 10 — Testing

- **Objective:** Execute the full QA strategy from [Testing.md](Testing.md), including the mandatory concurrency test, and record results.
- **Prerequisites:** Phase 9.
- **Tasks:** Run all unit, integration, security, and concurrency test cases; fix defects found; re-run to confirm.
- **Files expected:** Test source files, populated Test Matrix (status column filled in).
- **Requirements satisfied:** All — this phase verifies the whole requirement set.
- **Tests required:** N/A — this *is* the testing phase.
- **Definition of Done:** Test Matrix in [Testing.md](Testing.md) shows `Pass` for every test case (or documented, justified exceptions).
- **Docs to update:** [Testing.md](Testing.md), [Context.md](Context.md) (test status).

---

### Phase 11 — Cloud Deployment

- **Objective:** Deploy the server to a cloud VM and validate genuine remote client connectivity, per `auth` §8.
- **Prerequisites:** Phase 10 passing locally; Open Question OQ-06 (cloud provider) resolved.
- **Tasks:** Provision free-tier VM; install JRE; set `java.rmi.server.hostname`; open firewall/security-group ports; start server via `nohup`/systemd; connect local Swing client to the VM's public IP; capture screenshots for the report.
- **Files expected:** Deployment scripts/notes, systemd unit file (if used).
- **Requirements satisfied:** ADR-009, `auth` §8 deliverable.
- **Tests required:** TEST-DEPLOY-001..002.
- **Definition of Done:** A client on a different machine/network than the server successfully completes the full user journey against the cloud-hosted server.
- **Docs to update:** [Context.md](Context.md), [Architecture.md](Architecture.md) if deployment specifics change from what's documented.

---

### Phase 12 — Packaging & Demonstration

- **Objective:** Produce final deliverables per `auth` §10.
- **Prerequisites:** Phase 11.
- **Tasks:** Build final client/server JARs; write the coursework report (design, development, deployment, walkthrough, testing, screenshots); package source + JARs + report into the submission `.zip`.
- **Files expected:** `authlock-client.jar`, `authlock-server.jar`, final report (Word/PDF), submission `.zip`.
- **Requirements satisfied:** `auth` §10 Deliverables (all).
- **Tests required:** Final smoke test of the packaged JARs against the deployed cloud server.
- **Definition of Done:** Submission `.zip` assembled per module submission guidelines.
- **Docs to update:** [Context.md](Context.md) (mark project Completed).

---

## Phase Sequencing Summary

Phases 0–2 are strictly sequential. Phases 3 and onward mostly build linearly (auth → vault → locking → encryption → audit → UI → integration → testing → deployment → packaging) because each later phase's UI/tests exercise earlier phases' APIs. Phase 6 (Encryption) has a hard prerequisite: **Open Question OQ-05 must be resolved before Phase 6 begins** — this is the one blocking decision in the entire roadmap.
