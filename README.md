# DynamiteDB

A Relational mini-DBMS for IF3140 Database Systems.

## Open a local database

With JDK 21 installed, run this from the repo root in Fish, Bash, or Zsh:

```sh
./dynamitedb
```

This builds the app if needed and opens the real database in `./data`. No alias,
second terminal, or global Gradle installation is needed. On first use, the
runtime creates the database and seeds `student`, `course`, and `enrollment`.
The first build needs internet access. For a separate database, pass a new or
empty directory outside the default database; pass the **same directory** next
time to reopen it:

```sh
./dynamitedb --data-dir "$HOME/dynamitedb-practice"
```

At the database prompt, end SQL with `;`. Try `SELECT * FROM student;`.
Shell commands go on their own line without a semicolon:

```text
:help
:read reports/grades.sql
:quit
```

**A directory is a database; a `.sql` file is a script.** `:read` runs the
file's SQL in the currently open database, relative to the directory where you
launched the app. It stops at the first error and leaves the shell open. It
does not undo earlier commits. Use a disposable database when trying a dump:
`CREATE TABLE` and `DROP TABLE` are not executable on this branch, and
`CREATE VIEW` is not supported. `:quit` closes the local server as well as the
client. To work with a separately managed server, use the remote CLI below.

On Windows PowerShell, use the same shell through the installed application:

```powershell
.\gradlew.bat :app:installDist
.\app\build\install\app\bin\app.bat shell
```

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

The real server defaults to strict 2PL and runs a checkpoint every 60 seconds after
startup recovery completes. Use `--protocol strict-timestamp` to select timestamp
ordering, or `--checkpoint-interval-seconds N` to set a positive checkpoint interval
in seconds. These options are also available in the installed application script
from `./gradlew :app:installDist`. Manual checkpoint and historical recovery remain
available through the `PersistentRuntime` Java API.

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
