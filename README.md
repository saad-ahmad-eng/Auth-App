# AuthLock — RMI-Based Secure File Vault with Distributed Locking

**Developer:** Kuamil jeffery
**Module:** CS6006NU – Distributed, Cloud and IoT Systems
**Status:** Implementation Phases 1–10 complete; Phases 11–12 in progress, both blocked on the same single remaining step — running `terraform apply` against a real AWS account (this development environment has none). See [Context.md](Context.md) for full, current project state.

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
| [report/AuthLock-Report.pdf](report/AuthLock-Report.pdf) / [.docx](report/AuthLock-Report.docx) | The written coursework report (design, development, deployment, walkthrough, testing) |
| [terraform/README.md](terraform/README.md) | Cloud deployment: how to actually run `terraform apply` |

## Project Layout

```
authlock/
├── authlock-common/   shared VaultService interface, DTOs, crypto (AesGcmCipher,
│                       SharedKeyProvider), TLS bootstrap (DevTlsSetup)
├── authlock-server/   RMI server: auth, session, vault, lock, crypto, audit modules
├── authlock-client/   Swing RMI client (LoginFrame, DashboardFrame)
├── terraform/         AWS free-tier deployment (EC2, security group)
├── scripts/           provision-vm.sh, setup-authlock.sh, package-submission.sh
├── report/            the written coursework report (PDF + DOCX)
├── settings.gradle
└── build.gradle
```

Package structure follows [Development-rules.md](Development-rules.md) §2.

## Build

Requires JDK 17 (bundled wrapper handles Gradle itself — no local Gradle install needed):

```bash
./gradlew build
```

## Run (local)

Both `run` tasks execute from the repository root (not their subproject directory) so the server and client agree on the same generated files (vault storage, shared encryption key, TLS certificate):

```bash
./gradlew :authlock-server:run
./gradlew :authlock-client:run
```

On first run the server auto-generates two local files (both `.gitignore`d, regenerate freely):

- `authlock-shared.key` — the AES-256 key used to encrypt file payloads (Security.md §7.1). The client must be able to read this same file (`-Dauthlock.crypto.keyfile=<path>` to override its location).
- `certs/authlock-dev.p12` — a self-signed TLS certificate (Security.md §7.2), used by both server and client for RMI-over-TLS. Dev-scoped SAN by default (`localhost`/`127.0.0.1`); `-Dauthlock.tls.extraSan=ip:<addr>[,dns:<name>]` extends it for a real deployment — see `terraform/README.md`.

Useful system properties (pass as `-D<name>=<value>` to the JVM — see each class's Javadoc for details):

| Property | Default | Purpose |
|---|---|---|
| `authlock.server.host` | `localhost` | Client: which host to connect to (e.g. a cloud VM's public IP). |
| `authlock.tls.enabled` | `true` | Enable/disable RMI-over-TLS (both server and client must agree). |
| `authlock.tls.keystore` | `certs/authlock-dev.p12` | TLS keystore/truststore path. |
| `authlock.tls.extraSan` | *(unset)* | Extra `dns:`/`ip:` SAN entries for the cert, needed for a real remote deployment (Phase 11). |
| `authlock.crypto.keyfile` | `authlock-shared.key` | Shared AES key file path. |
| `authlock.vault.dir` | `vault-storage` | Server: where uploaded files are stored. |
| `authlock.session.idleTimeoutSeconds` / `authlock.session.maxLifetimeSeconds` | 1800 / 28800 | Session timeout overrides — testing/demo use only, never a production config. |

Seed accounts (`seed-users.properties`): `alice`/`AliceP@ss1`, `bob`/`BobP@ss1`.

## Run (deployable jars)

```bash
./gradlew :authlock-server:fatJar :authlock-client:fatJar
java -jar authlock-server/build/libs/authlock-server-all.jar
java -jar authlock-client/build/libs/authlock-client-all.jar [host]
```

Each jar is self-contained — no classpath setup, no Gradle needed at runtime.

## Deploy to the cloud

See [terraform/README.md](terraform/README.md) — provisions an AWS free-tier EC2 instance via Terraform, or run `scripts/provision-vm.sh` / `scripts/setup-authlock.sh` directly on any Linux VM (not AWS-specific).

## Package a submission

```bash
./scripts/package-submission.sh
```

Builds both jars, assembles the report + jars + full source tree into `build/submission/AuthLock-Submission.zip`.

## Test

```bash
./gradlew test
```

88 automated tests (unit, integration, security, RMI-communication, concurrency — every integration test against a real RMI server, not a mock). The mandatory concurrency test (`VaultServiceLockConcurrencyTest`) races 5 real concurrent client sessions for the same lock, 30 rounds per run — 270+ cumulative rounds across development with zero collisions.

## Current Implementation Status

Phases 1–10 complete: project skeleton, RMI infrastructure, authentication/sessions, the file vault, distributed locking, AES-256-GCM + RMI-over-TLS encryption, structured audit logging, the full Swing UI (interactively verified against a live server, including a real two-user session), end-to-end integration across all 18 documented system flows, and a full QA sign-off pass. Phases 11 (Cloud Deployment) and 12 (Packaging & Demonstration) are in progress: the AWS Terraform config, deployment scripts, deployable jars, submission zip, and written report are all complete — the one remaining step for both phases is running `terraform apply` against a real AWS account and demonstrating a genuinely remote client connection, which this development environment cannot do (no AWS credentials). See [Context.md](Context.md) §5 Pending Work for the exact next action.
