# Development-rules.md — Development Standards

**Project:** AuthLock
**Audience:** Any developer (including a future Claude implementation session) working on AuthLock after the documentation phase.
**Related documents:** [Context.md](Context.md) · [Decision.md](Decision.md) · [Security.md](Security.md) · [PRD.md](PRD.md) · [TRD.md](TRD.md)

---

## 1. General Rules

1. **Read [Context.md](Context.md) before making any change.** It is the current source of truth for project state — do not assume prior conversations or memory are accurate.
2. **Read the relevant specification document** (PRD/TRD/Backend/API-spec/Security, as applicable) before implementing a feature — do not infer requirements from the codebase alone.
3. **Never contradict an architectural decision without first updating [Decision.md](Decision.md).** If an ADR needs to change status (e.g., `Proposed` → `Accepted`, or a reversal), update it explicitly with reasoning before writing code that depends on the change.
4. **Never silently change requirements.** If implementation reveals that a requirement in PRD/TRD is wrong, ambiguous, or infeasible, update the requirement document and note the change in Context.md — do not just implement something different.
5. **Keep implementation aligned with PRD and TRD** at all times. A pull request that diverges from them must update them in the same change.
6. **Prefer simple, maintainable solutions.** This is a coursework distributed-systems project — do not introduce infrastructure or abstractions beyond what [p1.md](p1.md) §21 permits.

---

## 2. Code Quality

| Area | Rule |
|---|---|
| **Naming conventions** | Standard Java conventions: `UpperCamelCase` for classes/interfaces, `lowerCamelCase` for methods/fields/variables, `UPPER_SNAKE_CASE` for constants. Interface names describing remote services end in `Service` (e.g., `VaultService`). Exception classes end in `Exception`. |
| **Package structure** | Organize by responsibility, mirroring [Backend.md](Backend.md) modules, e.g.: `com.authlock.server.auth`, `com.authlock.server.session`, `com.authlock.server.vault`, `com.authlock.server.lock`, `com.authlock.server.crypto`, `com.authlock.server.audit`, `com.authlock.common` (shared DTOs/interfaces), `com.authlock.client.ui`, `com.authlock.client.rmi`. |
| **Class responsibilities** | Single Responsibility: e.g., `LockManager` only manages lock state; it does not perform file I/O or authentication. Cross-cutting orchestration (e.g., "upload requires auth + storage + audit") lives in a coordinating service class, not scattered across unrelated classes. |
| **Method size** | Keep methods focused and readable — as a guideline, prefer under ~40 lines; extract helpers rather than nesting deeply. |
| **Exception handling** | Never swallow exceptions silently. Application-level failures use the defined exception/error hierarchy from [API-spec.md](API-spec.md) Error Model; unexpected `RemoteException`s are caught at the client boundary and translated into a user-facing "server unavailable"/"unexpected error" state (FR-012, FR-013) — never an uncaught stack trace shown to the user. |
| **Logging** | Two distinct log streams: (1) the **security audit log** defined in [Security.md](Security.md) §9 (structured, append-only, no secrets), and (2) ordinary **application/debug logging** (e.g., via `java.util.logging` or a simple logger) for development diagnostics — never mix the two, and never log passwords, raw tokens, or encryption keys in either. |
| **Comments** | Comment *why*, not *what*, for non-obvious logic (especially locking/concurrency code and crypto code). Public interfaces (`VaultService` methods) carry Javadoc describing contract, parameters, exceptions, and side effects, matching [API-spec.md](API-spec.md). |
| **Documentation** | Any change to a remote interface, a data model, or a security control must be reflected in the corresponding `.md` document in the same change — see §4 Change Management. |
| **Dependency management** | Keep dependencies minimal. No dependency is added unless it is required to satisfy an explicit requirement or a recorded ADR; standard JDK APIs (RMI, Swing, JCE) are preferred over third-party libraries wherever they suffice, per [p1.md](p1.md) §21. |

---

## 3. Security Rules

These rules are non-negotiable and restate the controls defined in [Security.md](Security.md) as hard implementation constraints:

- Never hardcode secrets (passwords, keys, credentials) in source code — use configuration external to version control.
- Never log credentials, raw session tokens, or encryption keys, in any log stream.
- Never store plaintext passwords — always salted, slow-hashed (SEC-002).
- Validate all user/client input at the server boundary — never trust client-side validation alone.
- Prevent path traversal — never construct a filesystem path from client-supplied filenames (SEC-006); always use server-generated file IDs.
- Validate file names/metadata for well-formedness before accepting them.
- Validate session tokens on every remote call except `login()`.
- Validate authorization (session validity + lock ownership where relevant) **server-side**, never rely on the client to enforce it.
- **Never trust the client.** Every security-relevant decision (auth, session validity, lock ownership, file access) is re-checked server-side even if the client's UI already prevents the action.

---

## 4. Change Management

Any change that affects architecture, security posture, or requirements **must** update the following documents in the same change, before or alongside the code:

| Change type | Documents to update |
|---|---|
| New/changed architectural decision | [Decision.md](Decision.md) (new ADR or status change), [Architecture.md](Architecture.md) if the diagram/components change |
| New/changed remote method or DTO | [API-spec.md](API-spec.md), [flow.md](flow.md) if it introduces/changes a flow, [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md) traceability |
| New/changed security control | [Security.md](Security.md), [Development-rules.md](Development-rules.md) §3 if it becomes a hard rule |
| New/changed requirement | [PRD.md](PRD.md) and/or [TRD.md](TRD.md), plus [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md) |
| Any meaningful implementation step | [Context.md](Context.md) — see its Context Maintenance Rules section |

---

## 5. Git Rules

| Aspect | Rule |
|---|---|
| **Branch strategy** | `main` holds working, buildable code. Feature work happens on short-lived branches named `phase-<n>-<short-description>` (matching [Implementation.md](Implementation.md) phases) or `fix-<short-description>`, merged back via review. |
| **Commit naming** | Imperative mood, concise summary line (≤72 chars), optional body explaining *why*. Prefix with the affected area where helpful, e.g. `lock: enforce atomic putIfAbsent for lockFile()`. |
| **Atomic commits** | Each commit represents one coherent, buildable change (e.g., "add SessionManager" not "WIP" or a mix of unrelated changes). Avoid bundling unrelated fixes into one commit. |
| **Pull-request expectations** | A PR description states which requirement/phase it addresses (reference FR-xxx/ADR-xxx/phase number), what was tested, and which docs were updated per §4. |
| **Documentation updates** | Documentation changes required by §4 are included in the same PR as the code change they describe — not deferred to "later." |

---

## 6. Applicability

These rules govern the **implementation phase**, which has not yet begun (see [Context.md](Context.md) Current Phase). They are recorded now so that whichever developer or agent starts Phase 1 of [Implementation.md](Implementation.md) has an unambiguous standard to follow from the first commit.
