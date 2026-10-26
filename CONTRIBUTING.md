# Contributing to DynamiteDB

Keep each change focused on one component or one shared concern. All changes to
`main`, including documentation and tooling, must go through a pull request.

## Set up once per clone

Install Git and JDK 21, set `JAVA_HOME` to that JDK, and enable the versioned hooks:

```sh
git config --local core.hooksPath .githooks
git config --get core.hooksPath
```

The second command must print `.githooks`. Git does not enable repository hooks
automatically when cloning. On Windows, Git for Windows supplies the shell used
by the hooks; commits from an IDE must also inherit a valid `JAVA_HOME`.

## Contribution flow

1. Agree on the scope with the component owner. Propose shared contract changes
   and obtain affected owners' agreement before implementing them.
2. Start from current `main` and create a focused branch such as `feat/qp-session`,
   `fix/sm-bufferpool`, `feat/ccm-locking`, `feat/frm-wal`, or `build/repo-hooks`.
3. Make the smallest complete change. Update affected callers and relevant
   checks; do not edit another group's implementation without agreement.
4. Run the local commands below. Review formatter changes and stage only files
   belonging to the change. Stage or stash all remaining tracked edits; the hook
   rejects partial staging so that checks match the proposed commit.
5. Commit in atomic batches using the subject convention below. The pre-commit
   hook runs formatting, then lint and build. Formatting changes abort the commit
   without staging anything: review the diff, stage the result, and retry. Tool
   failures also abort. The commit-msg hook rejects invalid commit subjects.
6. Push the feature branch and open a draft PR targeting `main`. Use the same
   subject convention for its title and fill the template's Description and
   Changes sections.
7. Mark the PR ready when local checks pass and the description is complete.
   Request review from `@dynamite-mdbms/group-leaders`. Address feedback in small
   commits. Changes to the diff invalidate previous approvals.
8. Merge only after five distinct eligible leaders approve the latest changes
   and all review threads are resolved. Squash merge using the PR title as the
   final commit subject, then remove the feature branch.

## Local commands

| Purpose | macOS/Linux or Git Bash | Windows PowerShell |
| --- | --- | --- |
| Format files | `./gradlew format` | `.\gradlew.bat format` |
| Check format and Java lint | `./gradlew lint` | `.\gradlew.bat lint` |
| Clean build | `./gradlew clean build` | `.\gradlew.bat clean build` |

Java uses Google Java Format, unused-import removal, and a wildcard-import ban.
Gradle and hook/config files have whitespace and final-newline checks. Java
compilation uses `-Xlint:all -Werror`, so compiler warnings fail lint and builds.
Formatting is explicit; `lint` and `build` do not rewrite files. The first use
downloads the pinned formatting tools. No third-party runtime or testing
framework dependencies have been added.

The hook rejects untracked source/build inputs and unstaged tracked changes
before running tools. It never stashes or stages files for you. Local checks can
be bypassed by Git options, so contributors must keep the hooks enabled and
reviewers should run the local checks when reviewing. No GitHub Actions
workflow or required CI status check runs these tools.

Maintainers can run `python scripts/test_hooks.py -v` to verify the hook behavior
using disposable repositories and a stub Gradle launcher. Python 3.10+ is only
needed for this tooling self-check, not for normal Java development.

## Commit and PR title convention

Use exactly:

```text
<type> (<component>): <description>
```

The space before `(` is intentional. Use lowercase types and component names,
an imperative description, and no trailing period. Aim for 72 characters or
fewer. Keep unrelated changes in separate commits and PRs.

| Type | Use for |
| --- | --- |
| `feat` | New behavior |
| `fix` | Bug fixes |
| `docs` | Documentation |
| `style` | Formatting without behavior changes |
| `refactor` | Restructuring without behavior changes |
| `perf` | Performance improvements |
| `test` | Tests or test fixtures |
| `build` | Build configuration and developer tooling |
| `chore` | Repository maintenance |
| `revert` | Reverting an earlier change |

Components are `contracts`, `qp`, `qo`, `sm`, `ccm`, `frm`, `app`, `integration`,
and `repo`. Use `repo` for changes spanning the repository. Examples:

```text
feat (qp): add session lifecycle
fix (sm): preserve dirty pages after a flush failure
build (repo): add local quality hooks
docs (contracts): explain transaction ownership
```

Use the commit body to explain why when the subject is insufficient. Describe
breaking contract changes in a `BREAKING CHANGE:` paragraph and in the PR
description, with affected modules and migration steps.
Do not rewrite already published shared history to rename older commits.

## Required leadership review

| Role | GitHub user |
| --- | --- |
| Supergroup leader | `@nicholaswisee` |
| FRM, Group 1 | `@BryannPPH` |
| QP, Group 2 | `@keirzka` |
| SM, Group 3 | `@jsndwrd` |
| QO, Group 4 | `@stefaniangeline` |
| CCM, Group 5 | `@PikaProgram` |

Every PR requires approving reviews from at least five distinct members of this
six-person leadership team. An author's own approval never counts. When a leader
authors the PR, the other five leaders review it. Comments or checked boxes in
the PR description do not substitute for GitHub approving reviews.

Each approving leader must read the entire diff, check ownership and contract
impacts, verify local checks, and resolve their concerns before
approving. GitHub can enforce approval counts; reading and substantive review are
human responsibilities. Reviewers must request changes on incomplete PR templates,
failing local checks, invalid PR titles, or unresolved contract changes.

The repository ruleset is versioned in `.github/rulesets/main.json`. It targets
`main`, requires five approvals from `group-leaders`, dismisses stale approvals,
requires approval of the latest push and resolved threads, permits squash merges,
and blocks force pushes and branch deletion. No bypass actors or CI checks are
configured. Team members must have write access for GitHub to count team approvals.
The [live main ruleset](https://github.com/dynamite-mdbms/dynamitedb/rules/25894707)
is configured on GitHub. The JSON is a reviewable snapshot, not an automatic
sync: an administrator must apply future changes through GitHub's ruleset API
and verify the live settings. Leaders must accept any pending organization/team
invitation before their approvals can satisfy the rule.

## Component and course boundaries

Each component may depend on `contracts`, never directly on another component's
implementation. Composition belongs in `app`. Diagrams are provisional; class
names, method signatures, algorithms, and formats require agreement rather than
being copied from draft diagrams.

Milestone 1 interfaces and mocks are student work after contract approval.
Generative AI must not write DBMS implementations, concrete DBMS classes, SQL
logic, mocks, or DBMS test scenarios under the course restrictions. Third-party
testing frameworks still need team approval/QnA clarification.

Never commit secrets, local database files, compiled artifacts, or IDE state.
Store runtime data under ignored `data/`. Keep the supplied bootstrap brief and
supporting architecture/contract docs uncommitted; the root README, this guide,
and the requested PR template are the documentation included in this setup.
