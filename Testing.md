# Testing.md — QA Strategy

**Project:** AuthLock
**Related documents:** [PRD.md](PRD.md) · [API-spec.md](API-spec.md) · [Security.md](Security.md) · [flow.md](flow.md)

---

## 1. Testing Levels

| Level | Scope |
|---|---|
| **Unit testing** | Individual classes in isolation (e.g., `LockManager.acquire()`, `SessionManager.validate()`, password-hashing helper), with mocked/stubbed collaborators. |
| **Integration testing** | `VaultService` methods exercised through the RMI stub against a real (test-instance) server, over `localhost`. |
| **RMI communication testing** | Registry lookup, stub serialization, `RemoteException` handling when the server is stopped mid-session. |
| **Functional testing** | Full user journeys (login → list → upload → lock → download → unlock → logout) matching [flow.md](flow.md). |
| **Security testing** | Auth bypass attempts, path traversal, tampered/invalid tokens, credential/secret leakage in logs. |
| **Concurrency testing** | Multiple simultaneous clients racing for the same lock (mandatory, see §3). |
| **Negative testing** | Invalid input, missing files, malformed requests, expired sessions. |
| **UI testing** | Swing client behavior — button state, error dialogs, lock/connection indicators (see [UIUX.md](UIUX.md)). |
| **Deployment testing** | Cloud-hosted server reachable and fully functional from a genuinely remote client. |
| **Regression testing** | Re-run the full suite after any change touching Auth, Session, Vault, or Lock modules (per [Development-rules.md](Development-rules.md) §4). |

---

## 2. Test Cases

### 2.1 Authentication (`TEST-AUTH-xxx`)

| ID | Case | Expected Result |
|---|---|---|
| TEST-AUTH-001 | Valid credentials | Returns a session token; audit log records `LOGIN SUCCESS`. |
| TEST-AUTH-002 | Invalid username | `AUTHENTICATION_FAILED`; same error/timing profile as TEST-AUTH-003. |
| TEST-AUTH-003 | Invalid password (valid username) | `AUTHENTICATION_FAILED`; audit log records `LOGIN FAILURE`, no password logged. |
| TEST-AUTH-004 | Empty username/password | `AUTHENTICATION_FAILED` (rejected before hitting the credential store). |
| TEST-AUTH-005 | Repeated failures (e.g., 5 consecutive) | Each attempt independently audited; no lockout is required by `auth`, but confirm no crash/resource leak under repeated failures — **Open Question:** should repeated failures trigger throttling/lockout? Not required by `auth`; tracked as a Recommendation, not a blocking test. |

### 2.2 Session (`TEST-SESSION-xxx`)

| ID | Case | Expected Result |
|---|---|---|
| TEST-SESSION-001 | Valid token on a protected call | Call proceeds normally. |
| TEST-SESSION-002 | Expired token | `INVALID_SESSION`. |
| TEST-SESSION-003 | Unknown/malformed token | `INVALID_SESSION`. |
| TEST-SESSION-004 | Logout then reuse same token | Second call returns `INVALID_SESSION`. |

### 2.3 Files (`TEST-FILE-xxx`)

| ID | Case | Expected Result |
|---|---|---|
| TEST-FILE-001 | Upload a valid small file | Returns `fileId`; `listFiles()` shows it; checksum matches on later download. |
| TEST-FILE-002 | Upload an empty (0-byte) file | Accepted (or a defined, documented rejection) — behavior must match whatever [API-spec.md](API-spec.md) ultimately specifies; test asserts consistency, not a specific outcome not yet fixed by `auth`. |
| TEST-FILE-003 | Upload a large file (near practical VM/network limits) | Succeeds without corruption; verifies chunking/streaming approach if one is implemented. |
| TEST-FILE-004 | Download a valid file | Bytes match the originally uploaded content (checksum equality). |
| TEST-FILE-005 | Download a missing `fileId` | `FILE_NOT_FOUND`. |
| TEST-FILE-006 | Unauthorized download attempt (invalid session) | `INVALID_SESSION` (no bytes returned). |

### 2.4 Locking (`TEST-LOCK-xxx`)

| ID | Case | Expected Result |
|---|---|---|
| TEST-LOCK-001 | User acquires a lock on an unlocked file | `granted = true`; `listFiles()` reflects `LOCKED`. |
| TEST-LOCK-002 | Second user attempts to lock the same file | `FILE_LOCKED`. |
| TEST-LOCK-003 | Owner unlocks | Succeeds; file becomes lockable again. |
| TEST-LOCK-004 | Non-owner attempts unlock | `LOCK_NOT_OWNED`. |
| TEST-LOCK-005 | Concurrent lock race (single-pair case) | Exactly one of two simultaneous requests succeeds — see §3 for the full N-client version. |
| TEST-LOCK-006 | Stale lock (owning session expires without unlocking) | Lock is released by the cleanup sweep; a subsequent lock attempt by another user succeeds. |
| TEST-LOCK-007 | Lock timeout exceeded | Lock is reclaimable after the configured timeout even if the owning session is still nominally active — per [Security.md](Security.md) §8 (final timeout value per Open Question OQ-13). |

### 2.5 Security (`TEST-SEC-xxx`)

| ID | Case | Expected Result |
|---|---|---|
| TEST-SEC-001 | Path traversal filename (e.g., `../../etc/passwd`) on upload | Rejected or safely neutralized — file is never written outside the vault storage directory (SEC-006). |
| TEST-SEC-002 | Unauthorized request (no/garbage session token) on any protected method | `INVALID_SESSION`, no side effects performed. |
| TEST-SEC-003 | Tampered request (e.g., corrupted ciphertext/IV on upload or download) | Rejected — GCM authentication-tag failure, or checksum mismatch, is detected and surfaced as an error, not silently accepted. |
| TEST-SEC-004 | Sensitive data on the wire | Packet capture (or equivalent inspection) during upload/download shows no plaintext file content or plaintext password. |
| TEST-SEC-005 | Sensitive information in logs | Inspect both the audit log and the application/debug log after a full test run; assert no password, raw session token, or encryption key appears anywhere. |

---

## 3. Concurrency Test (Mandatory — `TEST-CONC-001`)

This is the project's headline distributed-systems demonstration and receives the strongest coverage, per [p1.md](p1.md) §17.

**Design:**
1. Upload one file and note its `fileId`.
2. Launch **N ≥ 3 concurrent client threads/processes**, each with its own valid, distinct session, all issuing `lockFile(sessionToken_i, fileId)` as close to simultaneously as the test harness can arrange (e.g., a `CountDownLatch`/barrier releasing all threads at once).
3. Collect all N results.

**Expected result (the invariant under test):** **exactly one** of the N requests returns `granted = true`; all other N−1 requests return `FILE_LOCKED`.

**Additional assertions:**
- Re-run the race at least 20–50 times in a loop (or with increasing N) to rule out a flaky/rare race window that a single run might miss.
- After the winning lock is released, a fresh race among the same clients again produces exactly one winner (repeatability, not a one-shot fluke).
- The audit log shows exactly one `LOCK SUCCESS` and N−1 `LOCK FAILURE` entries for the same `fileId` per race round.

**Related cases:** TEST-CONC-002 (concurrent uploads of *different* files do not contend with each other — sanity check that only same-file operations race); TEST-CONC-003 (concurrent `listFiles()` calls during an in-progress lock/unlock do not crash or return inconsistent/corrupt data).

---

## 4. Other Levels — Representative Cases

| ID | Level | Case | Expected Result |
|---|---|---|---|
| TEST-INT-001 | Integration/RMI | Client looks up and invokes `VaultService` over `localhost` | Round trip succeeds. |
| TEST-INT-002 | RMI communication | Server stopped mid-session, client attempts a call | Client surfaces a clear "server unavailable" state (FR-013), not a crash. |
| TEST-TLS-001 | Security/RMI | Client completes a real round trip (`ping`, `login`) over RMI-over-TLS | Succeeds — `VaultServiceTlsIntegrationTest`. **Pass.** |
| TEST-TLS-002 | Security/RMI | Plain (non-TLS) client attempts to connect to the TLS-only registry/export | Rejected — proves TLS is enforced, not merely configured. `VaultServiceTlsIntegrationTest`. **Pass.** |
| TEST-UI-001 | UI | Login with invalid credentials | Error message shown; login button re-enabled; no crash. |
| TEST-UI-002 | UI | File list refresh while a file is locked by another user | Lock state visibly indicated (per [UIUX.md](UIUX.md)). |
| TEST-UI-003 | UI | Upload button disabled state | Disabled while no file is selected / while an upload is in progress. |
| TEST-UI-004 | UI | Session expiry while client is idle | Client detects `INVALID_SESSION` on next action and prompts re-login. |
| TEST-DEPLOY-001 | Deployment | Client on a separate network connects to the cloud VM's public IP | Full login → upload → lock → download → unlock → logout journey succeeds remotely. |
| TEST-DEPLOY-002 | Deployment | Server restarted via systemd/nohup after a VM reboot | Server resumes accepting connections (session/lock state loss on restart is acceptable per ADR-006, Open Question OQ-04). |

---

## 5. Test Matrix

| Test ID | Requirement | Scenario | Expected Result | Actual Result | Status |
|---|---|---|---|---|---|
| TEST-AUTH-001 | FR-001 | Valid login | Token issued | Token issued over real RMI call (`VaultServiceAuthIntegrationTest`) | **Pass** |
| TEST-AUTH-002 | FR-001, SEC-001 | Invalid username | `AUTHENTICATION_FAILED` | Confirmed, and error identical to TEST-AUTH-003's (enumeration-prevention check) | **Pass** |
| TEST-AUTH-003 | FR-001, SEC-001 | Invalid password | `AUTHENTICATION_FAILED` | Confirmed | **Pass** |
| TEST-AUTH-004 | FR-001 | Empty credentials | `AUTHENTICATION_FAILED` | Confirmed | **Pass** |
| TEST-AUTH-005 | FR-001 | Repeated failures | No crash/leak | 5 consecutive failures handled cleanly; correct login still succeeds afterward | **Pass** |
| TEST-SESSION-001 | FR-004 | Valid token | Proceeds | Unit-tested (`SessionManagerTest`, deterministic clock) | **Pass** |
| TEST-SESSION-002 | FR-004 | Expired token | `INVALID_SESSION` | Unit-tested with a mutable test `Clock` (idle timeout and absolute max lifetime both verified) | **Pass** |
| TEST-SESSION-003 | FR-004 | Invalid token | `INVALID_SESSION` | Confirmed at both unit level and over real RMI (`logout` with a garbage token) | **Pass** |
| TEST-SESSION-004 | FR-002 | Reuse after logout | `INVALID_SESSION` | Confirmed at both unit level and over real RMI | **Pass** |
| TEST-FILE-001 | FR-006 | Upload valid file | Stored, listed | Real RMI round trip: upload→list→download byte-identical; also manually verified against a live server, checksum confirmed | **Pass** |
| TEST-FILE-002 | FR-006 | Upload empty file | Documented behavior | 0-byte upload accepted and downloads back as 0 bytes | **Pass** |
| TEST-FILE-003 | FR-006 | Upload large file | No corruption | 5 MB random-content upload/download verified byte-identical | **Pass** |
| TEST-FILE-004 | FR-007 | Download valid file | Checksum match | Server-returned checksum matches independently computed SHA-256 | **Pass** |
| TEST-FILE-005 | FR-007 | Download missing file | `FILE_NOT_FOUND` | Confirmed | **Pass** |
| TEST-FILE-006 | FR-007, SEC-007 | Unauthorized download | `INVALID_SESSION` | Confirmed (also `listFiles`/`uploadFile` with an invalid token) | **Pass** |
| TEST-LOCK-001 | FR-008 | Acquire lock | Granted | Confirmed at unit level (`LockManagerTest`) and over real RMI | **Pass** |
| TEST-LOCK-002 | FR-008 | Second user locks | `FILE_LOCKED` | Confirmed at both levels; lock remains with original owner | **Pass** |
| TEST-LOCK-003 | FR-009 | Owner unlocks | Succeeds | Confirmed; file re-lockable by another session immediately after | **Pass** |
| TEST-LOCK-004 | FR-009 | Non-owner unlocks | `LOCK_NOT_OWNED` | Confirmed at both levels (also: unlocking an already-unlocked file → same error) | **Pass** |
| TEST-LOCK-005 | FR-010 | Pairwise lock race | One winner | Sequential sanity check at unit level; full N-way race is TEST-CONC-001 | **Pass** |
| TEST-LOCK-006 | SEC-009 | Stale lock recovery | Released after expiry | Deterministic-clock unit test; also confirmed a session's locks release the instant that session ends (expiry or logout), via `SessionManager`'s session-ended listener → `LockManager.releaseAllOwnedBySession` | **Pass** |
| TEST-LOCK-007 | SEC-009 | Lock timeout | Reclaimable after timeout | Confirmed with a deterministic clock: not reclaimable at 14 min, reclaimable at 16 min (15-min timeout) | **Pass** |
| **TEST-CONC-001** | **FR-010** | **N-way concurrent lock race** | **Exactly one winner, every run** | **5 real concurrent client sessions, real RMI, 30 rounds/run — re-run 4 times during development (120+ total race rounds), exactly one winner every single round, zero failures** | **Pass** |
| TEST-CONC-002 | NFR-006 | Concurrent uploads, different files | No contention | 8 concurrent uploaders, real RMI, all succeed with 8 distinct fileIds | **Pass** |
| TEST-CONC-003 | NFR-006 | Concurrent listFiles during lock/unlock | Consistent, no crash | 200 concurrent `listFiles()` reads against a continuous background lock/unlock cycle — no errors, always consistent | **Pass** |
| TEST-SEC-001 | SEC-006 | Path traversal filename | Neutralized | Rejected with `UPLOAD_FAILED` at both the storage-layer unit test and over real RMI (`../../etc/passwd`, `nested/dir/file.txt`) | **Pass** |
| TEST-SEC-002 | SEC-003, SEC-007 | Unauthorized/garbage token | `INVALID_SESSION` | Covered across every method's dedicated invalid-session test (auth, file, lock suites) — consistently `INVALID_SESSION`, no side effects | **Pass** |
| TEST-SEC-003 | SEC-004 | Tampered ciphertext | Rejected | Confirmed at the cipher-unit level (`AesGcmCipherTest`) and over real RMI with genuinely corrupted ciphertext (`VaultServiceFileIntegrationTest.testSec003_...`) — `UPLOAD_FAILED`, not silently accepted | **Pass** |
| TEST-SEC-004 | SEC-004 | Wire inspection | No plaintext content | `VaultServiceFileIntegrationTest.testSec004_...` inspects the actual `FileContent` DTO returned over RMI: bytes ≠ plaintext, no substring of the plaintext recoverable; decrypting those exact bytes does recover the original | **Pass** |
| TEST-SEC-005 | SEC-008 | Log inspection | No secrets present | `VaultServiceAuditIntegrationTest` exercises every audited operation (success + failure) over real RMI, including a real login with a real password, then greps the actual `audit.log` for the plaintext password and both raw session tokens — absent. Also manually verified against a live server's real `audit.log`. | **Pass** |
| TEST-INT-001 | ADR-001 | Basic RMI round trip | Succeeds | Automated (`VaultServiceRmiIntegrationTest`) and manual cross-process `localhost` run both succeeded — client received `ping()` response | **Pass** |
| TEST-INT-002 | FR-013 | Server down mid-session | Clear client-side error | Two forms of evidence (Phase 9): (1) automated — `VaultServiceRmiIntegrationTest.clientCallOnAnAlreadyObtainedStubFailsClearlyAfterServerStops` obtains a real stub, force-unexports the live server object out from under it (simulating the process dying mid-session), and asserts the next call throws a clean `RemoteException` rather than hanging or failing silently; (2) interactively — a client started against a shared-key/cert pair generated by an earlier (now-killed) server run showed exactly `"Server unavailable — Connection refused"` on the Login screen (login disabled, no crash) — the connect-time half of this same failure mode (flow.md §17) | **Pass** |
| TEST-TLS-001 | ADR-007 | RMI-over-TLS round trip | Succeeds | `VaultServiceTlsIntegrationTest` — `ping()` and `login()` both succeed over real TLS; also manually verified against a live cross-process server | **Pass** |
| TEST-TLS-002 | ADR-007, SEC-004 | Plain client vs. TLS-only server | Connection rejected | `VaultServiceTlsIntegrationTest.aPlainNonTlsClientCannotConnectToTheTlsOnlyRegistry` | **Pass** |
| TEST-UI-001 | NFR-007 | Invalid login in UI | Error shown, no crash | **Interactively verified** against a real server+client (TLS RMI), driven via keyboard (Tab/type/Enter — mouse-click automation on this button was unreliable in the sandbox, so keyboard navigation was used instead, per the same UIUX.md §7 accessibility path a real user could use). Entered `alice` / a deliberately wrong password, submitted with Enter. Result: stayed on the Login screen (never navigated to Dashboard), status label showed exactly `"Invalid username or password."`, password field cleared, Login button remained enabled/usable (confirmed by immediately retrying with valid credentials in the same window — succeeded). Server-side: `audit.log` recorded exactly one `LOGIN FAILURE` entry with `errorCode=AUTHENTICATION_FAILED` and `userId="alice"` (the attempted username, per Security.md §9 — proving no session/userId was ever created for the failed attempt) | **Pass** |
| TEST-UI-002 | NFR-007 | Lock state visibility | Indicated in list | **Interactively verified**: uploaded a file, then screenshotted the Dashboard through Lock → Unlock → Lock again. `Lock State` column read exactly `"Unlocked"` and `"Locked (you)"` at each corresponding step (plain text, per UIUX.md §7), and the Lock/Unlock buttons' enabled state tracked it correctly each time | **Pass** |
| TEST-UI-003 | NFR-007 | Upload button disabled state | Correct enable/disable | **Interactively verified** the selection-dependent half directly: Download/Lock/Unlock buttons enabled/disabled correctly as table-row selection changed (screenshots before/after each click). The busy-during-call half (`setBusy(true)` disabling `uploadButton` etc. for an async call's duration) uses the identical `SwingAsync`/`setBusy` gate exercised correctly across ~10 real RMI calls this session (upload, lock, unlock, download, refresh) with no stuck or incorrect button state observed on any of them; the literal mid-flight disabled frame itself wasn't caught by a screenshot, since a local-RMI call completes in single-digit milliseconds — too fast to reliably poll. Code-reviewed and behaviorally consistent every time it fired | **Pass** |
| TEST-UI-004 | FR-004, NFR-007 | Session expiry mid-use | Prompts re-login | **Interactively verified against a real server+client**, using a genuine (not simulated) short idle-timeout server run — `authlock.session.idleTimeoutSeconds=12` via a new opt-in system property (`VaultServiceImpl.createSessionManager()` / `SessionManager(Duration, Duration)`, unset in production so the shipped 30-min/8-hr OQ-08 defaults are unchanged; see Security Changes in Context.md). Logged in as alice, let the session idle past 12s untouched, then clicked Refresh (a real `listFiles` RMI call). Server correctly returned `INVALID_SESSION`; client showed a modal dialog reading exactly `"Your session has expired. Please log in again."` (title "Session Expired"), dismissed via Enter (the default-focused OK button — again keyboard, since a stray mis-click on this dialog once closed the whole client outright: a GUI-automation miss, reproduced and confirmed **not** a code defect by re-running the identical scenario and dismissing correctly, which left the app fully alive). App returned to a fully usable Login screen and a fresh login immediately succeeded, proving clean recovery. Reproduced twice with identical results. Bonus confirmation: in one run the session expired again before Logout was clicked, and the server correctly returned `INVALID_SESSION` for that too (`audit.log`: `LOGOUT FAILURE errorCode=INVALID_SESSION`), which the client still routed back to Login (per `doLogout()`'s documented "even a failed logout still returns to login" behavior) | **Pass** |
| TEST-DEPLOY-001 | ADR-009 | Remote client → cloud VM | Full journey succeeds | **Infrastructure ready, execution pending (Phase 11):** OQ-06 is resolved (AWS) and `terraform/` + `scripts/provision-vm.sh`/`scripts/setup-authlock.sh` are written, code-reviewed, and locally verified everywhere possible without a real target VM (Context.md's Phase 11 log has the full account — build-output path confirmed, address-discovery fallback chain confirmed live, `DevTlsSetup`'s new TLS-SAN parameter confirmed via direct `keytool -list -v` inspection). **Not yet executed:** this environment has no AWS credentials, so `terraform apply` has never been run — no real VM exists yet to test against. Everything this test would exercise *except* genuine network separation and a public IP — the full login→upload→lock→download→unlock→logout journey, RMI-over-TLS, encryption, locking, audit — is already independently proven locally (every other row in this matrix, plus Phase 9's two-actor integration session). | Not Run (infra ready — see note; awaiting a real AWS account to execute against) |
| TEST-DEPLOY-002 | ADR-009 | Server restart on VM | Resumes service | **Infrastructure ready, execution pending (Phase 11):** same status as TEST-DEPLOY-001 — the systemd unit `scripts/provision-vm.sh`/`scripts/setup-authlock.sh` install (`Restart=on-failure`, `WantedBy=multi-user.target`) is written and reviewed but not yet exercised against a real VM reboot. The underlying behavior it targets — no persistent session/lock state, so a restart cleanly drops both rather than resuming in a corrupt state — is already confirmed by design and by OQ-04 (`SessionManager`/`LockManager` are purely in-memory, confirmed Phase 3/5) and by every local server restart performed across every phase of this project without incident. What specifically remains untested is systemd process supervision across an actual VM reboot, which needs the real VM from TEST-DEPLOY-001 to exist first. | Not Run (infra ready — see note; awaiting a real AWS account to execute against) |

All other rows in this matrix are `Pass`, confirmed fresh in Phase 10 with a from-scratch clean build (`./gradlew clean test`, 88/88 tests, 0 failures/errors across 15 test classes) plus 4 additional full re-runs of the mandatory concurrency test (TEST-CONC-001) — zero winner-collisions across every round. A sample of this matrix's test-method citations was spot-checked directly against the test source to confirm they are not stale or aspirational (Phase 10 consistency audit). TEST-DEPLOY-001/002 are the only `Not Run` rows: as of Phase 10 they were a documented, justified deferral (Phase 11 didn't exist yet); as of Phase 11 the infrastructure to run them now exists and is ready, but this environment's lack of real AWS credentials means the live execution — the one thing that would actually flip these two rows to `Pass` — hasn't happened yet. See `terraform/README.md` and Context.md's Phase 11 log for the concrete next step.
