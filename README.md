# AuthLock — RMI-Based Secure File Vault with Distributed Locking

**Developer:** Kuamil jeffery
**Module:** CS6006NU – Distributed, Cloud and IoT Systems
**Status:** Implementation Phase 1 (Project Skeleton) complete. See [Context.md](Context.md) for full project state.

This repository is the implementation of the AuthLock coursework project. The full specification package — requirements, architecture, security design, API contract, flows, test plan, and implementation roadmap — lives in the `.md` files at the repository root. **Read [Context.md](Context.md) before making any change.**

## Documentation Index

| Document | Purpose |
|---|---|
| [PRD.md](PRD.md) | Product requirements |
| [TRD.md](TRD.md) | Technical requirements, stack, dev environment |
| [Decision.md](Decision.md) | Architectural decision records (ADR-001..010) |
| [Security.md](Security.md) | Threat model, authN/authZ, encryption, lock security, audit |
| [Development-rules.md](Development-rules.md) | Coding, security, and Git standards |
| [Architecture.md](Architecture.md) | Components, deployment, scalability |
| [flow.md](flow.md) | Sequence diagrams for every system flow |
| [Backend.md](Backend.md) | Backend modules, data models, concurrency model |
| [API-spec.md](API-spec.md) | `VaultService` RMI contract, DTOs, error model |
| [Req-Doc-Alnafi.md](Req-Doc-Alnafi.md) | Requirement traceability matrix |
| [Implementation.md](Implementation.md) | Phased implementation roadmap (this build follows it) |
| [Testing.md](Testing.md) | QA strategy and test matrix |
| [UIUX.md](UIUX.md) | Swing UI design |
| [Context.md](Context.md) | **Current project state — read first** |

## Project Layout

```
authlock/
├── authlock-common/   shared VaultService interface + DTOs (Phase 2+)
├── authlock-server/   RMI server: auth, session, vault, lock, crypto, audit modules
├── authlock-client/   Swing RMI client
├── settings.gradle
└── build.gradle
```

Package structure follows [Development-rules.md](Development-rules.md) §2.

## Build

Requires JDK 17 (bundled wrapper handles Gradle itself — no local Gradle install needed):

```bash
./gradlew build
```

## Run (current skeleton stubs — see Implementation.md Phase 2+ for real functionality)

```bash
./gradlew :authlock-server:run
./gradlew :authlock-client:run
```

## Test

```bash
./gradlew test
```

## Current Implementation Status

Phase 1 (Project Skeleton) only: the build compiles, both modules produce runnable stub JARs, and the package structure for every planned component exists (documented via `package-info.java` placeholders). No RMI, authentication, vault, locking, encryption, audit, or UI logic has been implemented yet — see [Context.md](Context.md) §5 Pending Work and [Implementation.md](Implementation.md) for what comes next.
