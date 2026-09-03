# AuthLock — RMI-Based Secure File Vault with Distributed Locking

**Developer:** Kuamil jeffery
**Module:** CS6006NU – Distributed, Cloud and IoT Systems
**Status:** Implementation Phases 1–6 complete (authentication, file vault, distributed locking, encryption). See [Context.md](Context.md) for full project state.

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
├── authlock-common/   shared VaultService interface, DTOs, crypto (AesGcmCipher,
│                       SharedKeyProvider), TLS bootstrap (DevTlsSetup)
├── authlock-server/   RMI server: auth, session, vault, lock, crypto modules
├── authlock-client/   Swing RMI client (console demo until Phase 8)
├── settings.gradle
└── build.gradle
```

Package structure follows [Development-rules.md](Development-rules.md) §2.

## Build

Requires JDK 17 (bundled wrapper handles Gradle itself — no local Gradle install needed):

```bash
./gradlew build
```

## Run

Both `run` tasks execute from the repository root (not their subproject directory) so the server and client agree on the same generated files (vault storage, shared encryption key, TLS certificate):

```bash
./gradlew :authlock-server:run
./gradlew :authlock-client:run
```

On first run the server auto-generates two local files (both `.gitignore`d, regenerate freely):

- `authlock-shared.key` — the AES-256 key used to encrypt file payloads (Security.md §7.1). The client must be able to read this same file (`-Dauthlock.crypto.keyfile=<path>` to override its location).
- `certs/authlock-dev.p12` — a self-signed TLS certificate (Security.md §7.2), used by both server and client for RMI-over-TLS. Dev-only; not valid beyond `localhost`.

Useful system properties (pass as `-D<name>=<value>` to the JVM — see each class's Javadoc for details):

| Property | Default | Purpose |
|---|---|---|
| `authlock.server.host` | `localhost` | Client: which host to connect to (e.g. a cloud VM's public IP in Phase 11). |
| `authlock.tls.enabled` | `true` | Enable/disable RMI-over-TLS (both server and client must agree). |
| `authlock.tls.keystore` | `certs/authlock-dev.p12` | TLS keystore/truststore path. |
| `authlock.crypto.keyfile` | `authlock-shared.key` | Shared AES key file path. |
| `authlock.vault.dir` | `vault-storage` | Server: where uploaded files are stored. |

The client's console demo logs in as both seeded accounts (`alice`/`bob`, see `seed-users.properties`), uploads/lists/downloads a file (encrypted end-to-end), and demonstrates lock contention between them.

## Test

```bash
./gradlew test
```

## Current Implementation Status

Phases 1–6 complete: project skeleton, RMI infrastructure, authentication/sessions, the file vault, distributed locking (with the mandatory concurrent-lock race test passing reliably), and encryption (AES-256-GCM on file payloads + RMI-over-TLS on the whole channel). 76+ automated tests pass; every phase has also been manually verified against a live, separately-running server and client process. Audit logging (Phase 7), the Swing UI (Phase 8), and cloud deployment (Phase 11) remain — see [Context.md](Context.md) §5 Pending Work and [Implementation.md](Implementation.md) for what comes next.
