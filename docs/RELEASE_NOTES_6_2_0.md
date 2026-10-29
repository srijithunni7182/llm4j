# Release notes: 6.2.0

Status: **in progress** (the field findings program, [docs/specs/FIELD_FINDINGS.md](specs/FIELD_FINDINGS.md)). This page lists what is finished so far; each item says what changed for you.

## Fixed: answers are no longer cut short without a word

- **No more silent 1,024-token limit.** With a token or cost budget and no `per_call`, every model call was capped at 1,024 output tokens (`BudgetSet.DEFAULT_OUTPUT`), so long answers ended mid-sentence while the run
  reported success. The limit is now the model's known maximum (from the seeded model list; 8,192 for a model not on it), bounded by what the budget still affords. **Behaviour change:** answers that were clipped
  become whole, so token use rises to what the agent really writes, still inside the budget. A script that relied on the clipping to save cost should set `per_call`. Only up to 8,192 tokens per call are held back
  from the budget while the call runs (`BudgetSet.UNSPECIFIED_HOLD`), so parallel branches are not starved.
- **A cut answer is named.** When a provider stops an answer at its limit, the run prints `✂ N answers were cut at the output limit: Writer (2)`. The count was already in the trace as `truncated`.
- **`weave explain` states each agent's output limit** and where it comes from (its `per_call`, the model's maximum, or the default).
- **A cost that is an estimate says so.** The spend table marks estimated rows `~$` and names the agents whose provider reported no usage, instead of the generic note.
- **Gemini grounding with `recency:` works again on clocks with sub-second precision.** The times sent are whole seconds. (Verified by a unit test; a recorded request from the real API is still owed.)

## Which build is this? (identity and snapshot versions)

- **`weave --version` says what it is**: `weave 6.2.0-SNAPSHOT (development, 3d683a35, built 2026-10-29T15:30:00Z), serve protocol 1`, or `release` for a published build. Every module jar carries the same facts in its manifest
  (`Implementation-Version`, `Build-Kind`, `Build-Commit`, `Build-Dirty`, `Build-Time`). A bug report that quotes the line is enough to find the commit. The `serve protocol` suffix the editor reads is unchanged.
- **Between releases `main` carries `X.Y.Z-SNAPSHOT`.** Two jars that both said `6.0.0` but behaved differently were one release and one build from the repository. A development build can no longer carry a release's number.
  What must name a downloadable version (the JBang catalogue, `weave init`'s generated test project, a deployment's Fabric image) uses the last published release, kept in one property (`llm4j.released`); the release step is
  `scripts/release-version.sh` ([PUBLISHING.md](PUBLISHING.md)). **For contributors:** jar file names now carry the snapshot version (`ai-agent4j-loom-6.2.0-SNAPSHOT-cli.jar`).
- **`weave doctor` names two Loom jars that share a version but are different builds** (the running one, copies in `~/.m2`, the editor extension's bundled jar, `WEAVE_JAR`) in one sentence with the fix, and says nothing
  when they agree. Local files only.
- **The inspect and Fabric add-ons refuse a mismatched core in one sentence.** `loom-inspect` checks at start-up that it is the same build as the `ai-agent4j-loom` it runs against; `loom-fabric` does the same against the
  inspect core it bundles. A development build must match by commit; two releases match by version. Before, the mismatch showed up later as a missing class.
- **A jar with a half-compiled class cannot be released.** `make release-check` and the CI release job run `scripts/CheckJars.java`, which refuses a jar holding an editor's `Unresolved compilation problem` stub or lacking its
  identity; `scripts/verify-vsix.sh` does the same for the jar the extension bundles; `scripts/doctor.sh` warns when `target/classes` holds a stub. See CONTRIBUTING.md.

## Sources: a missing set is now said, not skipped

- **A grounded agent that returns no sources is named.** When a grounded agent's answer comes back without any sources (the model did not search), the run prints `⚠ Grounded but no sources came back: Researcher (1)`.
  Before, `{research_citations}` was simply not set and a script's own fallback hid it. Checked against the answer recorded from the real Gemini API: sources do reach the workflow from a plain delegate, from inside
  `parallel` and from a step with `expecting`, so the field report's "citations never arrived" is explained by the model not searching, not by the workflow dropping them (the cause there is still unconfirmed).
- **`weave check` warns when a script reads `{x_citations}` for an agent that is not grounded**, since that variable can never be set.

## Fixed: a run that fell back says so, and what it returned is shown

- **A degraded run is named.** A run that finished by using `on_failure` fallbacks prints `⚠ Degraded: Researcher, Editor took their fallback (2 of 5 model steps)`. The exit code stays `0` (no existing script
  changes meaning); `weave run --exit-degraded` exits `7`. **Behaviour change:** a run in which *no* model step succeeded (every step fell back) is now a failure, exit `1`, not "completed successfully": it
  ended with a result no model contributed to. `weave test` can assert `degraded:` and `fallbacks:`.
- **`weave run` prints what the workflow returned** under `Result:` (`--quiet` leaves it out). A workflow that returns nothing says so. Scripts that parse `weave run`'s output should use `--quiet`.
- **`weave eval` never judges an empty output.** A workflow or agent with nothing to judge is an `ERROR` case with the reason, no judge call is spent on it, and the evaluation exits non-zero. A workflow's output
  is its `return`; else (as before) the answer of the last agent it asked; else its last `note`.

## Fixed: what a script declares now reaches the run

- **`shared_prompt` is applied.** In 6.1.0 and earlier no `weave` command (`run`, `eval`, `test`, `serve`) told the agents the shared prompt, in either form (`shared_prompt: "text"` or
  `shared_prompt: prompt "house-rules"`). It is now put first in every agent's system text. If your workflow relied on rules in the shared prompt, they were not in force before; check a run's behaviour
  after upgrading. See the correction in [RELEASE_NOTES_6_1_0.md](RELEASE_NOTES_6_1_0.md).
- **`weave check` no longer calls a named shared prompt file "not used by any agent".** It was the same fault.
- **`supervise` and `intervention` blocks are applied.** A script's own `supervise Agent { ... }` rules and `intervention { ... }` defaults were dropped when the script was loaded, so a supervised workflow ran
  unsupervised and `weave explain` did not list them. They now apply, including `supervise` blocks in imported files. An imported file's `shared_prompt` is not imposed on the file that imports it.
