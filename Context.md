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

**Status: `Completed` (Documentation Phase only) — implementation has not begun.**

## 3. Current Phase

**Documentation / Architecture / Planning Phase.**

Per [p1.md](p1.md), this phase explicitly excludes writing application code, Java source files, the Swing UI, RMI services, authentication, encryption, locking, or cloud deployment. None of that has been done, and none should be started without an explicit go-ahead to begin [Implementation.md](Implementation.md) Phase 1.

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

---

## 5. Pending Work

**Everything in [Implementation.md](Implementation.md) — Phases 1 through 12 — is Not Started:**

- Phase 1 — Project Skeleton
- Phase 2 — RMI Infrastructure
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
| OQ-03 | Final JDK version to target | JDK 17 or 21 LTS (TRD §2.1) | Phase 1 |
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

*(Empty — no implementation work has occurred yet. The first entry here should be made when Phase 1 of [Implementation.md](Implementation.md) begins.)*

---

## 14. Next Steps

The exact, recommended next action is: **begin [Implementation.md](Implementation.md) Phase 1 — Project Skeleton**, after the developer confirms:
- OQ-03 (JDK version) — needed to configure the build.
- Build tool choice (Maven recommended, TRD §4) — needed to scaffold the project.

No other Open Question blocks Phase 1 or Phase 2. OQ-05 must be resolved before Phase 6 specifically, not before starting implementation generally.
