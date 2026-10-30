---
name: steward
description: How an automated agent should look after a pull request in this repository (llm4j: ai-agent4j, eval4j, Loom, loom-inspect, the VS Code extension). Read before acting on CI failures, merge conflicts or review comments.
---

# Stewarding a PR in llm4j

Repository conventions for an agent that owns or drives a PR. They sit under the hard rules the harness gives you (never skip or disable a test, never rewrite someone else's branch, never approve or merge).

## How proactive to be

- **CI red or a merge conflict is work now.** Fix it, validate it locally, push once. Do not wait for a reviewer.
- **Review comments:** do small local asks (renames, a test, a doc line) at once. Put anything that changes a public API, a file format or the language (Loom syntax) to the author first.
- **Do not widen a PR.** A fix for a failing check is the smallest change that makes it pass honestly: add the missing test rather than lowering a threshold, fix the code rather than excluding it.
- Never lower a JaCoCo limit, add a coverage exclusion, or `@Disabled` a test to get green. Add tests for the branches that are not covered.

## Branches and merges

- Merge the base branch in (`git merge origin/main`); do not rebase or force-push.
- When a PR for your branch has been merged, restart the branch from the latest `main` (`git fetch origin main && git checkout -B <branch> origin/main`) and open a new PR; never stack on merged history.
- Commit messages end with the attribution lines the harness gives. A PR description follows `.github/pull_request_template.md` (Summary, Why this change, Validation, Compatibility, Docs); fill every section honestly, and say what you did not run.

## What a green build means here

CI (`Build, Validate, and Deploy`) runs the whole reactor on JDK 17 and JDK 21, a release check (sources, javadoc, metadata), CodeQL and GitGuardian. The reactor order is: `ai-agent4j`, `ai-agent4j-addons`, `ai-agent4j-tools`, `eval4j`, `ai-agent4j-loom`, `eval4j-report`, `loom-inspect-core`, `loom-fabric`, `loom-inspect`. **A failure in `ai-agent4j-loom` skips the four modules after it**, so a green-looking earlier job proves nothing about them.

- Run `mvn verify` (not just `mvn test`) before declaring done: the JaCoCo `coverage-rules` check in `src/loom/ai-agent4j-loom/pom.xml` runs in `verify` and fails the build on a class below its line (0.90) or branch (0.85) ratio. New branches in a covered class (for example `channel.Listener`, `execution.ThreadScope*`, the context classes) need tests.
- Use `-Djacoco.skip=true` for quick test runs; do not use `-o` (offline) because the JaCoCo plugin is not available offline.
- `loom-inspect*` and `loom-fabric` compile against the installed Loom jar. After changing Loom classes they use, run `mvn -pl src/loom/ai-agent4j-loom -DskipTests install` first, or their tests will not see the change.
- The Loom module takes about four minutes to test. Run a targeted `-Dtest=` while iterating and the full module once before pushing.

## Things that are tested against each other (change one, change the other)

| If you change | Also update, or a test fails |
|---|---|
| Loom syntax or a new construct | `src/loom/ai-agent4j-loom/LOOM_GUIDE.md` (its ```` ```loom ```` blocks are parse-tested), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md`, the `llm4j-workflow-guide` skill, the VS Code grammar/hover/snippets, a CTK scenario. Docs ship with the parser change, never before it. |
| An agent setting or a graph node/edge kind | `graph/Kinds`, `graph-result.schema.json`, `GraphJson`, then `src/loom/graph-render/graph-render.js` and run `bash scripts/sync-graph-render.sh` (it copies the renderer into the VS Code extension and the eval4j report); `bash scripts/check-graph-render-sync.sh` must report in sync. |
| The Loom tutorial or a starter | `docs/tutorial/*.md`: a ```` ```loom ```` block must pass `weave check` with no warning (`loom-error` must fail, `loom-parse` must parse, a Java block after `<!-- compiles -->` must compile) and every link and heading anchor must resolve (`TutorialExamplesTest`). `src/loom/starters/NN-*`: it must check and pass its own tests with at least 80% of steps reached, and its README must show the real `main.loom` and the real graph (`StartersTest`; redraw with `bash scripts/make-starter-diagrams.sh`). |
| A `weave audit` rule | `docs/security/weave-audit.md` must list the rule id (`SecurityDocTest`). |
| A script anywhere in the repo | `RepositoryScriptsTest` loads every `.loom` file and requires it to pass the checks; a deliberately broken one must be named in its exclusion list. |
| A public API or file format | `docs/API_COMPATIBILITY.md`; keep the old constructor of a record when adding a component. |
| The VS Code extension | `src/loom/vscode-loom/README.md` must list every snippet prefix and node kind; add a CHANGELOG line. |

## Verifying the VS Code extension

- `cd src/loom/vscode-loom && npm ci && npm test`. The snippet tests need `bin/weave.jar` (git-ignored): build it with `mvn -Djacoco.skip=true -pl src/loom/ai-agent4j-loom -DskipTests package` and copy `src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar` to `src/loom/vscode-loom/bin/weave.jar`. Without it those tests skip rather than fail.
- Browser tests use Playwright's Chromium, which is preinstalled in the cloud container.
- In the cloud container four tests fail on the base branch as well (the two logo-asset tests and the two renderer-sync tests, because they look for scripts under a different directory). If the same four fail in CI too, say so; if CI is green on them, ignore them locally.
- `graph-render` has a timing test (`VP.3`, 400 nodes under 300 ms) that fails on a loaded machine; it is not a regression unless it fails in CI repeatedly.

## Reporting

Reply on a review thread only when a reply is needed (why a suggestion cannot be done, or a one-line answer to an optional finding). Do not narrate each fix; the diff is the record. Say plainly what was verified and what was not.
