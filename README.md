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

The real server runtime composes the query processor, optimizer, storage manager,
concurrency manager, and recovery manager with persistent storage and WAL-backed
recovery. It seeds the sample database on first startup. A successful build or a
single end-to-end query does not establish that every SQL, isolation, storage, or
recovery requirement in the component specifications is complete; use the
component drivers and tests to verify those behaviors.

## Shared contracts and runtime modes

Shared values have one definition under `dynamitedb.contracts`:

| Package suffix | Contents |
| --- | --- |
| `api` | Cross-component public interfaces |
| `transaction` | Transaction identity, access requests/decisions, and protocols |
| `storage` | RIDs, values, schema/rows, images, indexes, and prepared writes |
| `query` | Session identity, statements, expressions, plans, and results |
| `recovery` | LSNs, changes, recovery, generation, checkpoint, and log values |
| `error` | Shared errors and exceptions |
| `testing` | Shared reports |

Do not add root-package copies of these types or import the nested Java design
reference into production. Module-local interfaces such as `Operator`,
`PlanRewrite`, `EqualityIndex`, and `ConcurrencyProtocol` remain in their modules.
Independent declaration fixtures and structural tests live in `integration-tests`;
the attached architecture sources remain local and uncommitted.

The persistent server connects the actual component implementations and recovers
the data directory before it accepts clients:

```sh
./gradlew :app:run --args='server --real --host 127.0.0.1 --port 7777 --data-dir data'
```

Connect from a second terminal:

```sh
./gradlew :app:run --args='cli --host 127.0.0.1 --port 7777'
```

The explicit stub server remains available to inspect application composition
without relying on operational behavior:

```sh
./gradlew :app:run --args='server --stub --host 127.0.0.1 --port 7777'
```

Stub mode uses fixed default operations and does not establish query correctness,
durability, or recovery. The `server --mock` mode and `driver --suite` remain mock
demonstrations, not evidence of behavior in the persistent runtime.

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
commit. Java uses Google Java Format and compiler warnings as errors. Pull requests
to `main` run `./gradlew lint build` through GitHub Actions. The `main` ruleset does
not currently require that CI check.

## Ownership and boundaries

| Module | Responsibility | Reserved package prefix |
| --- | --- | --- |
| `contracts` | Agreed shared types and interfaces | `dynamitedb.contracts` |
| `query-processor` | QP team | `dynamitedb.qp` |
| `query-optimizer` | QO team | `dynamitedb.qo` |
| `storage-manager` | SM team | `dynamitedb.sm` |
| `concurrency-manager` | CCM team | `dynamitedb.ccm` |
| `recovery-manager` | FRM team | `dynamitedb.frm` |
| `app` | Application composition and entry points | `dynamitedb.app` |
| `integration-tests` | Structural checks through contracts and application composition | `dynamitedb.integration` |

Each component depends only on `contracts`. Components must not depend directly
on one another's implementation modules. `contracts` has no project dependencies;
`app` depends on it and all five components. Integration tests depend on `contracts`
and `app`, with component implementations available at runtime through `app`.
The namespace is an organizational default that the team may revisit.

Module ownership and dependency direction are stable. The structural declaration
snapshot follows the supplied diagrams; behavior, algorithms, and data formats
still require component-owner agreement and implementation. Matching declarations
or constructing a provider graph does not implement those behavioral contracts.

## Collaboration

Follow the [contribution guide](CONTRIBUTING.md) for the full branch-to-merge flow.
Use `<type> (<component>): <description>` for commit subjects and PR titles, for
example `feat (qp): add session lifecycle`. Fill every section of the PR template.
Every PR requires three distinct approving leaders from the six-person leadership
team; an author cannot approve their own PR. Keep shared contract changes agreed
with affected component owners and keep local runtime data in ignored `data/`.

Changes to shared contracts still require affected-owner approval. Core DBMS
implementation is student work under the course's generative-AI restriction;
structural integration does not authorize implementing DBMS algorithms.

Local supporting references: [draft architecture and contract process](docs/architecture/README.md),
[contract workflow](docs/contracts/README.md).
These architecture/contract documents and diagrams are intentionally uncommitted
and will not appear in a fresh clone. The contribution guide and PR template are
versioned with the repository.

Initial ownership for every shared contract is listed in
[contracts/OWNERSHIP.md](contracts/OWNERSHIP.md).
