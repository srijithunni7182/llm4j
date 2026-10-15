---
name: babysit
description: Triage runbook for failing CI on llm4j pull requests. Read when a check on a PR goes red, to find the cause quickly. Conventions and proactivity live in the steward skill, which takes precedence.
---

# Triage a red check on llm4j

`steward/` has the conventions; this is the symptom-to-cause list. Start by reading the failed job's log tail (`get_job_logs` with `failed_only`), not the whole log: the cause is in the last `[ERROR]` lines of the reactor summary.

## Read the Maven reactor summary first

The module marked `FAILURE` is the one to fix; everything after it is `SKIPPED` and says nothing about your change.

| What the log says | Cause | Fix |
|---|---|---|
| `Rule violated for class io.github.llm4j...: branches (or lines) covered ratio is X, but expected minimum is Y` | The JaCoCo `coverage-rules` check in the Loom module (runs at `verify`). A new branch has no test. | Add tests that reach the new branches. Check locally: `mvn -q test -Dtest=<the class's tests>` then read `target/site/jacoco/jacoco.csv` for the class (columns: missed then covered, instructions, branches, lines). Never lower the limit. |
| `Tests run: N, Failures: 1` in one class | A real test failure | Open `target/surefire-reports/<class>.txt` locally (or the log) for the assertion; reproduce with `-Dtest=<class>`. |
| `cannot find symbol` in `loom-inspect`, `loom-fabric` or `loom-inspect-core` | They compile against an installed Loom jar that is older than your change | `mvn -pl src/loom/ai-agent4j-loom -DskipTests install`, then build them. In CI the reactor builds in order, so this means a real missing symbol. |
| A guide/doc test: `this guide example doesn't load`, `no checkpoint named ...`, a missing rule row | A docs example no longer parses, or an audit rule is not in `docs/security/weave-audit.md` | Fix the example or the doc; the tests (`StoppingGuideTest`, `RewindGuideTest`, `GuideExamplesTest`, `SecurityDocTest`) are the contract. |
| `RepositoryScriptsTest`: a script under `src/loom/ctk/scripts` or a sample fails the checks | A new `.loom` file is invalid, or a deliberately invalid one is not in the test's exclusion list | Fix the script, or exclude it by name if it is meant to be wrong. |
| Graph tests / golden JSON differ | A node kind, edge label or agent flag changed | Update the schema and, if intended, regenerate the golden file deliberately; check `GraphGoldenTest`. |
| `graph-render copies are NOT in sync` | `src/loom/graph-render/graph-render.js` was edited without syncing | `bash scripts/sync-graph-render.sh`. |
| VS Code: `agent-xxx is in the README` | A snippet prefix is missing from `src/loom/vscode-loom/README.md` | Add it to the README list. |
| `No space left on device` (cloud container) | The container's disk allowance is spent | Delete build output (`target/`, `node_modules/`, `/tmp` logs); do not blame the code. |

## Fails only on one JDK

CI runs JDK 17 and 21. A failure on only one points to a JDK-specific API or a timing-sensitive test, not to the change in general. Reproduce with that JDK if the container has it; otherwise read the stack trace for the API.

## Is it this PR's?

- **Red on `main` too:** check the latest `Build, Validate, and Deploy` run on `main` first. If it is red there with the same error, it is not this PR's; say so in one PR comment and port the base fix if one exists.
- **Skipped checks** ("Extended tests", "OWASP Dependency Check") are skipped by design on PRs; they are not failures.
- **CodeQL "neutral"** is not a failure.
- **A job that died before any test ran** (checkout, dependency download, runner loss) may be re-run once; a second failure is real.

## Before pushing a fix

Reproduce the failure, show the same check passing, run `mvn verify` for the module you touched (plus `mvn -pl src/loom/ai-agent4j-loom -DskipTests install` if downstream modules use it), and re-read the diff for what else CI would reject.
