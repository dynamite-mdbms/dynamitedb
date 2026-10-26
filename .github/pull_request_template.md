<!-- Use <type> (<component>): <description> as the PR title.
Every section below is required. Replace placeholders and explain N/A values.
Do not mark reviewer approvals on someone else's behalf. -->

## Summary and reason

Describe the problem, the resulting behavior, and why the change is needed.
Link the issue or agreed task; if none exists, explain the agreed scope here.

## Changes and ownership

List the affected components, the concrete changes, and any cross-owner agreement.

## Contracts and compatibility

Describe interface/type changes, affected callers, approval of the proposal,
and migration steps. For no contract impact, write `N/A` and explain why.

## Local validation

Replace this table with the exact commands run and their results. Identify the
JDK version. Explain `NO-SOURCE`, skipped checks, and any known failures honestly.

| Check | Exact command | Result/evidence |
| --- | --- | --- |
| JDK | `java -version` | Fill in |
| Formatting | `./gradlew format` or Windows equivalent | Fill in |
| Lint | `./gradlew lint` or Windows equivalent | Fill in |
| Clean build | `./gradlew clean build` or Windows equivalent | Fill in |
| Relevant behavior checks | Fill in, or explain N/A | Fill in |

## Risks and rollback

Describe meaningful risks, known limits, and how to revert or recover. If there
are none beyond a normal revert, state why. Include data migration implications.

## Author checklist

- [ ] The title follows `<type> (<component>): <description>`.
- [ ] Every section is filled; placeholders are removed and N/A values explained.
- [ ] The local hooks are enabled, and formatting, lint, and build pass.
- [ ] The diff contains one focused change and no secrets or local data/artifacts.
- [ ] Affected owners agreed to contract or cross-component changes, if any.
- [ ] The change respects the course's generative-AI restrictions.
- [ ] Review is requested from `@dynamite-mdbms/group-leaders`.

## Leadership review

Five distinct leaders must submit GitHub approving reviews after reading the
latest diff. Eligible leaders: @nicholaswisee, @BryannPPH, @keirzka, @jsndwrd,
@stefaniangeline, @PikaProgram. The author cannot count toward these five.

Record any review context here, including affected owners' agreement. Approval
counts come from GitHub reviews, not from names or checkboxes in this description.
