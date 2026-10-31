# DynamiteDB

A Relational mini-DBMS for IF3140 Database Systems.

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
runtime dependencies or testing frameworks have been selected.

## Local quality checks

Enable the versioned Git hooks once per clone:

```sh
git config --local core.hooksPath .githooks
```

| Task | macOS/Linux or Git Bash | Windows PowerShell |
| --- | --- | --- |
| Format | `./gradlew format` | `.\gradlew.bat format` |
| Lint | `./gradlew lint` | `.\gradlew.bat lint` |
| Build | `./gradlew clean build` | `.\gradlew.bat clean build` |

The local pre-commit hook formats first and blocks if files change, so you can
review and re-stage them. It then runs lint and build; any failure blocks the
commit. Java uses Google Java Format and compiler warnings as errors. No GitHub
Actions workflow runs these checks.

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
The namespace is an organizational default that the team may revisit.

Module ownership and dependency direction are stable. Diagrams are **DRAFT —
subject to contract approval**: class names, constructors, interface methods,
recovery adapters, algorithms, and data formats remain provisional. No Java APIs
or launcher classes have been created from them.

## Collaboration

Follow the [contribution guide](CONTRIBUTING.md) for the full branch-to-merge flow.
Use `<type> (<component>): <description>` for commit subjects and PR titles, for
example `feat (qp): add session lifecycle`. Fill every section of the PR template.
Every PR requires five distinct approving leaders from the six-person leadership
team; an author cannot approve their own PR. Keep shared contract changes agreed
with affected component owners and keep local runtime data in ignored `data/`.

Next: propose contracts, obtain cross-group approval, then have students write
interfaces and their own mock implementations for Milestone 1. Interface types,
recovery contracts, TCP protocol, CLI behavior, algorithms, and testing tools are
still team decisions. Core DBMS implementation is student work under the course's
generative-AI restriction.

Local supporting references: [draft architecture and contract process](docs/architecture/README.md),
[contract workflow](docs/contracts/README.md).
These architecture/contract documents and diagrams are intentionally uncommitted
and will not appear in a fresh clone. The contribution guide and PR template are
versioned with the repository.
