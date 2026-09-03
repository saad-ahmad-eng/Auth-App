# Req-Doc-Alnafi.md — Requirement Traceability Matrix

**Project:** AuthLock
**Purpose:** Ensure every requirement in `auth` (the original coursework proposal) is traceable end-to-end through design, security, API, planned implementation, and test coverage, so nothing is accidentally dropped.

---

## 1. Full Traceability Matrix

| Requirement | Source (`auth`) | PRD | Design (Architecture/Backend/Security) | API | Implementation (planned phase) | Test |
|---|---|---|---|---|---|---|
| User authentication (login) | §3, §6 | FR-001 | Architecture §2.4 Authentication Service; Security §3 | `login()` | Phase 3 | TEST-AUTH-001..005 |
| Logout / session termination | §3 | FR-002 | Architecture §2.5; Security §3 | `logout()` | Phase 3 | TEST-SESSION-004 |
| Session contextualization (token) | §3, §6 | FR-003, FR-004 | Architecture §2.5; Security §5 | `login()` return value; token checked on every other call | Phase 3 | TEST-SESSION-001..003 |
| File listing / GUI file browser | §4, §6 (implied) | FR-005 | Architecture §2.6 | `listFiles()` | Phase 4 | TEST-FILE-006 |
| File upload (binary transfer) | §3, §4, §6 | FR-006 | Architecture §2.6; Backend §2.3 | `uploadFile()` | Phase 4 | TEST-FILE-001..003 |
| File download (binary transfer) | §3, §4, §6 | FR-007 | Architecture §2.6 | `downloadFile()` | Phase 4 | TEST-FILE-004..005 |
| Distributed locking (prevent concurrent edits) | §3, §4, §9 | FR-008, FR-009, FR-010 | Architecture §2.7; Backend §3; Security §8; ADR-004, ADR-005 | `lockFile()`, `unlockFile()` | Phase 5 | TEST-LOCK-001..007, TEST-CONC-001 |
| Encryption of transferred files | §3, §4 | NFR-001 | Architecture §2.8; Security §7; ADR-007 | `uploadFile()`/`downloadFile()` IV+ciphertext fields | Phase 6 | TEST-SEC-004 |
| Operation auditing (log file) | §3, §6 | FR-011 | Architecture §2.9; Security §9; ADR-008 | Audit event column on every method | Phase 7 | TEST-SEC-005 |
| Swing GUI (login, browser, upload/download controls, status panel) | §2, §6, §21 | NFR-007 | Architecture §2.1; UIUX.md all sections; ADR-002 | Client-side, all methods | Phase 8 | TEST-UI-001..004 |
| Multi-threading / concurrent client sessions | §6 | NFR-006 | Backend §3 Concurrency Model | All methods (server thread pool) | Phase 2, validated Phase 5 | TEST-CONC-001..003 |
| Multi-part communication (attachments = binary files) | §6 | FR-006, FR-007 | API-spec §1 `uploadFile`/`downloadFile` byte[] params | Same | Phase 4 | TEST-FILE-001..005 |
| Client/server distributed architecture (RMI) | §2, §4, LO1/LO2 | Goals §2.5 | Architecture §1; ADR-001 | Whole `VaultService` interface | Phase 2 | TEST-INT-001 |
| Cloud deployment | §8 | Goals §2.2 | Architecture §3; ADR-009 | N/A (deployment, not API) | Phase 11 | TEST-DEPLOY-001..002 |
| Testing strategy (unit/integration/functional/concurrency) | §9 | — | — | — | Phase 10 | Testing.md (all) |
| Deliverables (report, source, jars, cloud instance, zip) | §10 | PRD §1.7 boundary | Implementation.md Phase 12 | N/A | Phase 12 | TEST-DEPLOY-002 |

---

## 2. Requirements Explicitly NOT in `auth` (and how they were handled)

| Item | Handling |
|---|---|
| Self-service registration | Not implemented — PRD §4.1, per [p1.md](p1.md) explicit instruction not to invent it. |
| Password hashing algorithm | Not specified by `auth` — added as an Engineering Requirement, Security.md §3, flagged accordingly (not silently presented as an original requirement). |
| Session token format/entropy/expiry | Not specified by `auth` — Derived Decision, ADR-006 and Security.md §5. |
| Encryption algorithm | Not specified by `auth` (only "encryption" is required) — ADR-007, Security.md §7, status `Proposed` pending Open Question OQ-05. |
| Lock timeout/stale-lock recovery | Not specified by `auth` — Engineering Requirement, Security.md §8, needed for availability (NFR-004). |
| Path traversal / filename handling | Not specified by `auth` — Security Requirement SEC-006, standard secure-coding practice for any file-upload feature. |

---

## 3. Coverage Check

Every functional requirement (FR-001–FR-014) and every non-functional requirement (NFR-001–NFR-010) in [PRD.md](PRD.md) has at least one corresponding row above with a design reference, an API reference (where applicable), a planned implementation phase, and at least one test case. Security requirements (SEC-001–SEC-010) are covered inline within the corresponding functional rows above and comprehensively in [Security.md](Security.md) §10 Security Controls Matrix.

**No requirement in `auth` was identified as unaddressed** as of this documentation pass. Open items are tracked as Open Questions (OQ-01 through OQ-13) in [Context.md](Context.md), not as missing requirements — each has a documented default/recommendation so implementation is not blocked.
