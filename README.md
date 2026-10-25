# DynamiteDB

An educational relational mini-DBMS for IF3140. This repository currently contains
build scaffolding only; it is not yet runnable as a DBMS.

## Build

Install JDK 21 and Git. The genuine Gradle 9.2.1 wrapper is included; no global
Gradle installation is required. The first build downloads Gradle and needs internet
access. Set `JAVA_HOME` to your JDK 21 installation and put its `bin` directory on
`PATH` (or select JDK 21 as your IDE's Gradle JVM).

Windows PowerShell:

```powershell
.\gradlew.bat projects
.\gradlew.bat clean build
```

Windows Command Prompt: `gradlew.bat projects` and `gradlew.bat clean build`.

macOS/Linux:

```sh
./gradlew projects
./gradlew clean build
```

Empty source sets are intentional. A successful build validates project wiring;
there are no DBMS implementations, mocks, or test scenarios yet. No third-party
dependencies or testing frameworks have been selected.

## Ownership and boundaries

| Module | Responsibility | Reserved package prefix |
| --- | --- | --- |
| `contracts` | Agreed shared types and interfaces | `dynamitedb.contracts` |
| `query-processor` | QP team | `dynamitedb.qp` |
| `query-optimizer` | QO team | `dynamitedb.qo` |
| `storage-manager` | SM team | `dynamitedb.sm` |
| `concurrency-manager` | CCM team | `dynamitedb.ccm` |
| `recovery-manager` | FRM team | `dynamitedb.frm` |
| `app` | Application composition and future entry point | `dynamitedb.app` |
| `integration-tests` | Future tests through contracts and application composition | To be agreed |

Each component depends only on `contracts`. Components must not depend directly
on one another's implementation modules. `contracts` has no project dependencies;
`app` depends on it and all five components. Integration tests depend on `contracts`
and `app`, with component implementations available at runtime through `app`.
Use `dynamitedb` as the base package, with lowercase package names.

Module ownership and dependency direction are stable. Diagrams are **DRAFT —
subject to contract approval**: class names, constructors, interface methods,
recovery adapters, algorithms, and data formats remain provisional. No Java APIs
or launcher classes have been created from them.

## Collaboration

Use small feature branches such as `feat/qp-session`, `feat/qo-parser`,
`feat/sm-bufferpool`, `feat/ccm-locking`, and `feat/frm-wal`. Changes to shared
contracts require review by affected component owners; agree before changing
another team's implementation. Run `clean build` before review and describe
breaking contract changes. Keep secrets, runtime data, build artifacts, and IDE
state out of Git; local database files belong in the ignored `data/` directory.

Next: propose contracts, obtain cross-group approval, then have students write
interfaces and their own mock implementations for Milestone 1. Interface types,
recovery contracts, TCP protocol, CLI behavior, algorithms, and testing tools are
still team decisions. Core DBMS implementation is student work under the course's
generative-AI restriction.

Local supporting references: [draft architecture and contract process](docs/architecture/README.md),
[contract workflow](docs/contracts/README.md), and [contribution guide](CONTRIBUTING.md).
These supporting documents and diagrams are intentionally uncommitted and will
not appear in a fresh clone; the essential conventions are included above.
