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

## `weave eval`: a report that always has the same shape, and a by-agent view

- **The report has nine fixed sections** (*Read this first*, *Overall*, **By agent**, *By dimension*, *By slice*, *Cases*, *Cost*, *Cautions*, *How to reproduce*) in the terminal, in `--report` and in `--json` (`sections`). A section with nothing to show says why instead of disappearing. Tests pin the structure.
- **A by-agent view, for every run, with no tag or setting.** Each agent that took part gets a row built from the run: its pass rate on the cases aimed at it (with a 95% interval), calls, tokens, cost, answers cut at the output limit, steps that used a fallback, and its worst outputs with the judge's reason. (Before, the dashboard's By-agent table needed `agent:` tags written by hand and `weave eval` did not feed the dashboard at all.)
- **Outputs are kept and shown.** *Cases* shows each case's input, the output that was judged and every line's verdict with its reason. The output of a case that fails an `expected_output_not_contains` check is withheld, so a report never repeats what the case guards against (a card number, a secret).
- **The judge's cost is in the cost,** shown beside the agents' and the total, with the cost per passing case. Without `--prices` the figure is `unknown`, not zero.
- **`weave eval` writes the dashboard bundle** (`target/eval4j/runs/<id>/` or `eval-runs/runs/<id>/`): all cases, their outputs and verdicts, and a case per agent for the By-agent table. Render it with the `eval4j-report` command line.
- **A rubric line can name its dimension** (`{ text: ..., dimension: tone }`), counted under that dimension only; plain lines still count under every dimension of their case, and the report says how many do.
- **`--repetitions N`, `--judge-samples N`.** The report gives pass@1 beside pass^N and consistency, names unstable cases, and states its resolution (how small a change this many cases can tell). A line whose judge samples disagree widely is left unjudged.
- **A sealed held-out set.** Cases tagged `split:heldout` run only with `--release-gate`, which the report records; the generalisation gap (validation minus held-out) is reported and warned about above 10 points.
- **Datasets and baselines that cannot slide.** The report names the dataset's identity (declared `version:` plus a hash of its cases). `--save-baseline` needs an approver and a reason and never replaces an earlier baseline; `--baseline` compares with the current and the previous baseline and lists regressions; a different dataset is refused unless `--allow-version-change "<why>"`.
- **A workflow's output is defined** (its `return`, else the last agent's answer, else its last `note`) and an empty one is an error case; it is no longer sent to a judge.

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
- **The skills install complete.** The deployer skill's scripts look for each jar in one order (the variable, the editor extension's jar, `bin/`, `~/.llm4j/tools/<version>/`, a build), the same in the shell and PowerShell versions, and newest first. `weave guide --install-skill` ends with what was found and what is missing (`weave tools fetch fabric` for a missing Fabric jar) and never says the scripts are ready when one would stop. New: **`weave tools fetch fabric|inspect|all`** downloads the jars for this weave's version from Maven Central after listing them and asking (`--yes` skips the question), keeps a file only if its published SHA-256 (or, where Central offers only that, SHA-1) matches, writes only under `~/.llm4j/tools/<version>/`, refuses a development build, and never runs what it downloads.
- **`weave` for a coding agent in a plain terminal.** The workflow skill ships `scripts/weave` (and `weave.cmd`): it finds a weave or prints the one install line. `weave doctor` says when `weave` is not on this terminal's PATH, what the editor extension's PATH entry covers (VS Code's own terminals only) and what to do.
- **The editor offers the coding-agent set-up once** (extension 1.2.0): a notification when a folder with a `.loom` file has none; it writes nothing until you choose.
- **Keys in a template file are caught more precisely, and moving them is one command.** `weave check` (and `doctor`, `next`) now watch `.env.example` and any `*.example`, `*.sample` or `*.template` file in the project root, and flag only names that hold a secret (ending in `KEY`, `TOKEN`, `SECRET`, `PASSWORD`, and so on): `PORT=8080` or `LOG_LEVEL=debug` no longer raise it. The warning names the file and the name, never the value, a prefix or its length, and gives the way out: `weave secrets import-env --from <file> --secrets <store>`. **New:** `import-env --from <file>` reads `NAME=value` lines from a file (every secret-named value, or just the names you list), stores them encrypted, and for a template offers to blank the imported values afterwards (`--blank` does it without asking); a real `.env` is never changed. Every starter's `.env.example` now says not to put a key in it and shows the store as the encrypted alternative.
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

## New: `weave preflight`, one real answer before you spend more

- **`weave preflight script.loom`** asks each agent one real, capped question and checks what a run needs: the key works (C1), the model accepts the request (C2), the answer is whole, not cut or empty (C3), the shared prompt reaches the model (C4, with a random canary kept in memory only), grounded agents return sources (C5), the price and context window are known (C6) and the budget is sensible (C7). It asks before spending unless `--yes` or `--mock`, stops at `--max-tokens` / `--max-cost`, and exits `0` (pass or warnings), `1` (a failing check) or `3` (stopped by the cap). `--format json` is for scripts. It writes `.loom/preflight.json`.
- **`weave next` knows the preflight** and offers it before the deploy step (and again when the script changed since or the last one failed). `weave next --format json` lists the steps and the preflight state.
- **`weave next --confirm real-run`** records that a person read one real answer and judged it. After a passing preflight, `weave next` asks for that read before the dataset is built on it; an edited script needs it again.
- **`weave eval --check` says how many drafted cases no person has reviewed.** A case an assistant drafted carries the tag `author:assistant`; the person who accepts or edits it adds `reviewed_by:<name>`.
- **The skills share one `references/conventions.md`** (nine rules: roadmap and checklist, the planner question, co-written datasets, a real answer read early, read-back, tested README commands, keys asked first, selection shares, visible skipping). It is installed with the workflow, deployer and harness-design skills, and a test keeps the three copies identical.
- **`weave next --format json` reports the ten stages of the journey**, each `done`, `failing`, `not-started`, `skipped` or `stale`, read from the project's files and recorded results, and names the one to take now (a failing stage first). `weave eval` now records its last finished run (mock or real, the dataset version and the script) in `.loom/eval.json`, so a real evaluation of an older script shows as `stale` and a mock one does not count. The engine answers the same JSON as `loom/next` ([PROTOCOL.md](serve/PROTOCOL.md)).

