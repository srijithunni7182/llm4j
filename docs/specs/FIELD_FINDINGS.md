# Field findings program (phases 17 to 23): from a first real build to results you can trust

Status: **decisions D1 to D13 answered 2026-10-29; specs otherwise draft**. Nothing here is built. This page is the overview of seven phases, [17](17-honest-results/DESIGN.md) to [23](23-eval-design-skill/DESIGN.md), written
from what happened when a person used the llm4j skills, `weave`, the Loom extension, eval4j and Fabric to build a real workflow (`article-flow`) from scratch. Each phase has the usual
three documents (`DESIGN.md`, `TEST_STRATEGY.md`, `VERIFICATION.md`); this page holds what they share: the evidence, the register of every finding, the order, and the decisions the owner
must make before building starts.

## 1. Why this program exists

The Loom developer experience (phases 9 to 16) was verified against its own tests. A real session then showed that **largely, things work**, and also that a developer can finish a build with
confidence that is not earned: a safety prompt that never reached the model, answers cut short while the run said "success", an evaluation that judged empty text. Each of those is cheap to find
once you know where to look and expensive to find any other way. The program has one aim:

> **A developer who follows the documented path gets either a working result or a clear, early message about why not. Never a quiet wrong result.**

The second aim is smaller and matters for adoption: the path should not need the developer to already know the tools (which jar, which flag, which recipe).

## 2. The evidence, and how much to trust each piece

Sources:

| Tag | Source |
|---|---|
| **S1** | The first session report and project report for `article-flow` (a build with the workflow skill and the extension's bundled jar), 2026-10-29. |
| **S2** | The final session report (a longer session that went on to a real evaluation, Fabric and the deployer), 2026-10-29, with its own sections 2 (observations), 3 (findings by component), 6 (priorities) and 8 (the assistant's mistakes). |
| **U** | What the owner showed directly: screenshots of the blank *Loom: Workflow Outline* and of the Flow view (`set` boxes, parallel drawn as one box). |
| **M** | Checks made while writing these specs: code read, and one reproduction on the current jar (2026-10-29). |
| **Q** | The owner's guide *Quality Assurance for AI agentic systems* (47 pages: oracles, golden datasets, quality dimensions, security testing, the evaluation pipeline). A method, not a bug report: its findings are **capability gaps** between what the method needs and what the stack offers. |

Evidence labels used in the register:

| Label | Meaning |
|---|---|
| **V** | Verified by the reporter in the session by an experiment (for example a canary test). Not yet reproduced by us. |
| **O** | Observed by the owner in VS Code. Not exercised by the reporter. |
| **C** | The code that would cause it was read here, and it matches the report. |
| **R** | Reproduced here on the current build. |
| **I** | Inferred: a likely cause the reporter could not confirm. |
| **X** | Contradicted by something we know, so it must be settled before any design is final. |

**No requirement in a phase is built until its finding has been reproduced (or knowingly accepted without reproduction).** Every `VERIFICATION.md` starts with that step (§0, "Reproduce first").

### 2.1 A correction to our own record

The published 6.1.0 release notes (`docs/RELEASE_NOTES_6_1_0.md`, "Not in this release, and known issues") say of the `shared_prompt` warning: "The prompt is applied at run time (a test shows its text
reaching the model); only the warning is wrong." That sentence was written from a unit test (`SharedPromptTest`), not from a real run, and **F01 contradicts it** (a canary instruction in a shared
prompt was ignored by the model in a real run; the same instruction in the agent's own prompt was obeyed). The code reads as if it works (`AgentPrompts.resolveSystemPrompt` puts the shared text
first), so the truth is not known yet. Until [Phase 17](17-honest-results/VERIFICATION.md) settles it, that sentence is **unverified**, and Phase 17 requires the release notes and the guide to be
corrected whichever way the answer goes (R1.6).

## 3. The register

Every finding has a stable id. The last column is the phase that owns it. A finding appears in exactly one phase; where another phase depends on it the phase's DESIGN says so.

### Phase 17: honest results (P0: a wrong result that looks right)

| ID | Finding | Source | Evidence |
|---|---|---|---|
| F01 | `shared_prompt` (file and inline forms) is accepted but its text did not reach the model in a real run: a canary instruction was ignored there and obeyed in the agent's own prompt. The injection defences the project relied on were not in force for hours, and three agents then obeyed injected orders. | S2 3.1.1 | V, **R** 2026-10-29 (cause: `LoomScript.merge` dropped it; X resolved, the 6.1.0 note was false) |
| F02 | `weave check` says "prompt house-rules is not used by any agent" for a file named by `shared_prompt: prompt "house-rules"`. | S1, S2 | V, **R** 2026-10-29 (same cause as F01) |
| F03 | With a token or cost budget active and no `per_call` on an agent, every model call is capped at 1,024 output tokens. Articles were cut mid-sentence (about 780 words) and the run reported success; several agents showed a completion count of about 1,020. | S2 3.1.2 | V, **R** 2026-10-29, C |
| F04 | Gemini grounding with `recency:` fails with HTTP 400 "time_range_filter: Granularity of nano is not supported"; both grounded agents failed. | S2 3.1.3 | V, **R** 2026-10-29 (unit; live pending), C |
| F05 | Fallbacks (`on_failure`) can hide total failure: a run in which four of five agents failed ended "completed successfully" and saved a 42-word stub. There is no first-class degraded status or exit code. | S2 3.1.4 | V, **R** 2026-10-29 |
| F06 | `weave eval` reported "no actual output" to three different judges for agent scenarios whose agents returned good text when run directly. Its report does not store the agents' outputs. | S2 obs 6, 3.4 | V (cause unknown; see Phase 17 R5 for the leading hypothesis) |
| F07 | `weave run` prints `note` lines but not a workflow's `return` value. | S2 3.1.6 | V, **R** 2026-10-29 |
| F08 | Token accounting looks estimated for Gemini ("some usage was estimated: a provider reported none"); several agents showed the same completion count. Cost figures are approximate and not labelled as such. | S2 3.1.7 | I |
| F09 | Gemini grounding citations never arrived: every real article lacked its Sources section because `{research_citations}` was missing and a fallback skipped it silently. | S2 3.1.8 | V (symptom), cause unknown |
| F66 | Found while tracing F01: `LoomScript.merge` also dropped a script's own `supervise` and `intervention` blocks, so a supervised script ran unsupervised in every `weave` command, and `weave explain` omitted them. | M (17a) | **R** 2026-10-29 |

### Phase 18: install, identity and environment (P1: the first ten minutes)

| ID | Finding | Source | Evidence |
|---|---|---|---|
| F10 | Two jars both report `6.0.0` and behave differently (the older rejects `grounding:` and `budget searches:`, has no YAML-case runner); an add-on built from source failed with a missing class against the extension's jar. | S1, S2 3.2 | V; **C** (development builds carry the last release's version) |
| F11 | `weave --version` carries no build identity that tells such jars apart in a bug report; `weave doctor` does not compare the jars on the class path; core and add-ons can disagree without a clear refusal. | S2 3.2 | V |
| F12 | Between releases `main` carries the last release's version, so a build from the repository and the release are indistinguishable by number (the cause of F10). | M | C |
| F13 | `weave guide --install-skill` installs skill text without the jars and scripts the deployer skill expects (`bin/`: `weave`, Fabric, inspect add-ons); the user had to find and build them. | S1, S2 obs 1, 3.5 | V |
| F14 | A coding agent working in a plain terminal has no `weave` command; the extension's PATH entry (extension 1.1.0) covers VS Code's integrated terminals only. | S1, M | V |
| F15 | The extension does not offer to install the llm4j skills into a project on first open. | S2 obs 1 | O |
| F16 | An editor's Java language server leaves "Unresolved compilation problem" stubs in `target/classes`, which `mvn package` then packages; a concurrent IDE compile makes `mvn test` fail with `NoClassDefFoundError`. | S2 3.2 | V |
| F17 | Template and example default models can be unavailable to a new account (`gemini-2.5-flash-lite` returned 404); nothing in `weave check` can tell, and current model names are not offered. | S2 3.2 | V |
| F18 | The shipped price and window catalogue covers Claude models only; Gemini windows and prices had to be looked up by hand; there is no `weave prices update`. | S2 3.2 | V |
| F19 | Secrets: the skill offered only `.env`/`.env.example`, never `weave secrets`; keys were typed into `.env.example`, a file meant to be committed. Nothing warns about a non-empty `.env.example`; `weave init` prints a `.env` step, not the secret-store steps. | S2 obs 3 | V |

### Phase 19: evaluation that works (P1; F55 to F65 are capability gaps found by comparing the stack with the QA guide, and stay unconfirmed until the inventory in its VERIFICATION §0)

| ID | Finding | Source | Evidence |
|---|---|---|---|
| F20 | eval4j's "By agent" dashboard view and the way to switch it on (`agent:` tags, `breakdowns: [agent]`, `declareAgent`/`declareJudge`) are not in the user guide; it was found by reading source. | S2 3.4 | V |
| F21 | Using eval4j properly needs a lot of knowledge (an opt-in JUnit class, labelled scenarios, `bindTest`/`bindScenario`, `MetricRef`, `EvalRecorder`, budgets); there is no generator. | S2 3.4 | V |
| F22 | A dataset cannot say which rubric line belongs to which dimension; a scenario's dimensions are shared by all its lines and each verdict is filed under every dimension, which double counts. | S2 3.4 | V |
| F23 | Judge reliability is not measured: one judge sample per line, and a one-point move between runs is noise. | S2 3.4 | V |
| F24 | The judge's cost is not in the cost the dashboard shows, which understates a run. | S2 3.4 | V |
| F25 | `weave eval` writes an HTML report that stores no agent outputs, has no per-dimension scores and no per-agent view, and is not the bundle the eval4j dashboard reads. It took four attempts to reach a real evaluation. | S2 obs 6, 7 | V |
| F26 | The real evaluation caught what no free test did (padding, prompt-injection obedience, missing source names). The free tests said "ready". | S2 3.4 | V (a design input, not a defect) |
| F55 | There are no dataset **splits** (development, validation, a sealed held-out set) and no held-out run mode, so a team that tunes prompts against the cases it can see cannot be told. The **generalisation gap** (validation minus held-out) is not reported. | Q §3 | I, inventory 2026-10-29: **needed** (no split in scenarios; tags can carry it) |
| F56 | There is no **judge validation**: no human-scored reference set, no agreement between humans and between judge and humans (Cohen's kappa), and the known judge biases (verbosity, position, self-preference, sycophancy, anchoring) are not designed around in one place. | Q §6 | I, inventory 2026-10-29: **narrowed** (kappa exists as test code only) |
| F57 | A case cannot state a **set** of acceptable answers: one correct conclusion plus accepted equivalents, explicitly rejected answers, acceptable and unacceptable actions, required citations. There is no `exact / acceptable / rejected / unmatched` verdict and no warning when the unmatched rate passes about 10%. | Q §2, §6 | I, inventory 2026-10-29: **needed** |
| F58 | There are no **metamorphic** cases (paraphrase invariance, irrelevant-addition invariance, order invariance, monotonicity, negation sensitivity) derived from an existing dataset. | Q §2 | I, inventory 2026-10-29: **needed** |
| F59 | A dataset cannot mark or require the **three case classes** nobody builds (unanswerable, obvious answer is wrong, correct action is the conservative one; at least 10% each), and a report does not flag a slice with fewer than about 10 cases. | Q §3 | I, inventory 2026-10-29: **needed** |
| F60 | Reports do not show **consistency and resolution**: pass@1 beside pass^N, consistency@N, unstable cases (a pass rate between 0.4 and 0.6 usually means an under-specified case), confidence intervals, and a plain statement of the smallest change the run could detect. | Q §8 | I, inventory 2026-10-29: **narrowed** (Wilson exists; no repetitions, pass^N, unstable cases) |
| F61 | **Cost per successful task**, the p95 cost, the runaway rate and the graceful-termination rate are not reported; they are the efficiency numbers that matter, and the naive median cost rewards a system that abandons hard cases. | Q §4 | I, inventory 2026-10-29: **needed** |
| F62 | **Dataset version pinning**: results from different dataset versions should never be compared, every run should record the version, and a new baseline should need a named approver and a written reason; a comparison with both the current and the previous baseline shows slow decline. | Q §3, §8 | I, inventory 2026-10-29: **narrowed** (BaselineGate exists; no version, approver, previous baseline) |
| F63 | There is no **security corpus runner**: a versioned corpus of injection payloads (60 or more direct, 100 or more indirect, across realistic placements) run against a workflow, with the check that matters most: the **system-level controls held** for every payload that fooled the model. | Q §5 | I, inventory 2026-10-29: **needed** |
| F64 | A **run protocol** (a core subset three times on every change, the validation set five times nightly, validation plus held-out ten times at a release gate, a weekly drift check at a fixed configuration) cannot be written down in one place; the weekly drift check, the only catch for provider-side change, has no home. | Q §6 | I, inventory 2026-10-29: **needed** |
| F65 | **Trajectory and multi-agent probes** are scattered or missing: a routing confusion matrix, a handoff context-loss probe, loop induction (each bound fires independently and keeps the work), context-eviction probes, and turn-level versus session-level metrics. `weave test` covers part of this. | Q §6.1 | I, inventory 2026-10-29: **partly met** by `weave test` |

### Phase 20: the guided path and the skills (P1)

| ID | Finding | Source | Evidence |
|---|---|---|---|
| F28 | The workflow skill did not show the roadmap up front, did not print the stage checklist after each stage, and did not use `weave next`, so the user did not know the whole process. | S2 obs 2 | V |
| F29 | The golden dataset was filled in by the assistant after three multiple-choice questions, not co-written through the skill's five questions per agent, including scenarios the user had not chosen. | S1, S2 3.6 | V |
| F30 | No cheap live **preflight** exists between "the script checks clean" and "build the dataset": is the model available, is one real answer whole, does a shared prompt reach the model, do grounded agents return sources, are the price and window known. Every real problem in the session would have shown up here. | S2 3.6 | V |
| F31 | A real answer was not read under a cap until after the dataset and tests existed. | S2 3.6 | V |
| F32 | Key advice: the skill should ask first whether the person will run the workflow themselves or deploy it, offer both key routes side by side, and say that keys never go in `.env.example`. | S2 obs 3, 3.6 | V |
| F33 | Selecting text in the editor shares it with the conversation: a secret store master key and the Fabric admin password were exposed this way. The skill does not say so or suggest deny rules. | S2 3.6, §7 | V |
| F34 | Skill process steps were skipped: `weave explain` was not read back for confirmation, `weave generate --gate-only` was not run, and a dashboard was described from the skill's wording before it was read. | S1 §4, S2 §8 | V |
| F35 | A planner model call was built without asking whether the planner should be a model or the script. | S1 §4, S2 §8 | V |
| F36 | README commands for the built project were not run once (even in mock form) before the build was presented as ready; `-i overwrite=""` was unproven. | S1 §5 | V |

### Phase 21: editor polish (P2)

| ID | Finding | Source | Evidence |
|---|---|---|---|
| F37 | In the Review panel, clicking a finding opens the `.loom` file, which is distracting; it should only do that from an explicit "Go to source". | S2 obs 4 | O |
| F38 | The *Loom: Workflow Outline* view is blank when a workspace has `.loom` files but no `.loom` editor is active. The welcome message shows only when the workspace has no `.loom` file (`!loom.hasLoom`), and the tree returns nothing when the active editor is not Loom. | U (screenshot), S2 obs 5 | O, **C** |
| F39 | The Outline should show how far along the project is (decided, dataset, check, audit, tests, real run, evaluation, deploy) with the next command, above the workflow's own tree. | S2 obs 5 | O |
| F40 | The graph draws every `set` as its own note box, which weakens the picture (a run of six `set` nodes in the screenshot). | U (screenshot), S2 obs 8 | O |
| F41 | Parallel work is drawn as one grey "in parallel: 3 branches" box, not as lanes with a fork and a join, and the branches' `on_failure` paths are not shown against their branch. | U (screenshot), S2 obs 9 | O |
| F42 | There is no "run a capped real run" action: the first real spend should be one click with the cap visible. | S2 3.3 | I (the reporter's wish) |

### Phase 22: CLI and language ergonomics (P2)

| ID | Finding | Source | Evidence |
|---|---|---|---|
| F43 | `weave test` has no `--classes`, unlike `weave run` and `weave eval`, so it cannot see a project's task classes without `java -cp …`. | S1, S2 3.2 | V |
| F44 | `weave observe rules` requires an add-on on the class path and a rules file even when empty. | S2 3.2 | V |
| F45 | `weave review` reports five HIGH findings for a missing API key, which is an environment state, not a defect of the script. | S2 3.2 | V |
| F46 | Each run prints several `INFO: Loading Loom script…` lines and a log header per scripted case, which buries the real output. | S2 3.2 | V, **C** (`LoomLoader` logs at INFO) |
| F47 | `expecting {...}` must come before `retry`, and the parse error does not say so. | S1, S2 3.1.6 | V |
| F48 | In a scripted task result the word `value` is reserved, and the error does not say what to write instead. | S1, S2 3.1.6 | V |
| F49 | A string cannot contain `\"`. | S2 3.1.6 | V |
| F50 | A variable's name is replaced wherever the bare word appears in a string, not only as `{name}`. The lint (`wordsThatAreVariables`) caught two cases and missed `out` inside `length-out-of-range(...)`, which printed a file path into a message. | S2 3.1.5 | V, **C** (the code documents "the older bare-name form") |
| F51 | Parallel branches that fall back by `set`ting a shared variable need a separate variable each; there is no join or collect construct. | S2 3.1.6 | V |
| F52 | The scripted-test harness scripts every task, so real task code is never exercised inside a workflow test. | S1 §4 | V |
| F53 | Fabric and the deployer: scripts assume jars in the skill's `bin/` (F13); `run-observed` expects the store's master key beside it as `<store>.key`; no `weave fabric up`; `fabric-status` does not show when each client last reported. | S2 3.5 | V |
| F54 | The review cost estimate was thin (about 11.6k tokens typical for six agents with two grounded searches; no dollar figure without a price table) and its worst case (179k) was above the run cap (150k) without saying the cap would bite first. | S1 §5 | V |

### Phase 23: the evaluation design skill (P1)

| ID | Finding | Source | Evidence |
|---|---|---|---|
| F27 | There is no evaluation skill: the path from "I have a script" to "I have a trustworthy per-agent report" is not written down anywhere a coding agent will read. The harness has a design-first skill (`llm4j-harness-design`); evaluation has none. The owner's QA guide is the method such a skill would carry. | S2 obs 7, §5; Q | V |

66 findings (F66 was found by Phase 17a and is owned by Phase 17; F27 is counted once; it moved here from Phase 19 when the QA guide arrived). The reporter's own list of open items (the Sources section, StyleScout's length, Fabric pages not checked, two secrets to rotate) is project work, not product work, and is in §6.

## 4. Order of work and the release plan

```
17 honest results ──► 18 install and identity ──┬──► 23 evaluation design skill ──► 20 guided path and skills ──► (release)
        │                                       │                ▲
        └──────────────► 19 evaluation ─────────┴────────────────┘
21 editor polish ─────────────────────────────────────────────────►  (independent)
22 CLI and language ergonomics ───────────────────────────────────►  (independent; F50 waits for decision D3)
```

- **17 first**: silent wrong results undermine every other result. Within it, F01 first (a safety property), then F03, F05 and F06.
- **18 before 23 and 20**: the skills should install complete and version-matched before they are rewritten around the preflight.
- **19 and 23 together**: 23 (the design skill) can start once 19's inventory (VERIFICATION §0) says which capabilities exist, because the skill's mapping page must say, truthfully, what the stack can and cannot do today. Where the stack lacks a capability, the skill names the workaround and the gap is Phase 19's work.
- **23 before 20**: Phase 20 inserts a "design the evaluation" stage into the workflow skill that hands over to the new skill, and builds the preflight stage on a working `weave eval` (F06, F25) and the By-agent recipe.
- **21 and 22** do not block the rest and can be built in parallel by different hands.
- **Release plan (decision D9, answered: one release):** everything ships as **6.2.0**. The earlier proposal of a 6.1.1 with Phase 17 and the cheap parts of 18 (F11, F12, F19) was declined; the wrong-result fixes wait for the release, so the 6.1.0 release notes' known issues carry the F01 correction (R1.7) until then. The extension is versioned separately and its CI publishes it when a version bump merges ([PUBLISHING_VSCODE.md](../PUBLISHING_VSCODE.md)).

## 5. Decisions needed from the owner before building

Each is also recorded in the DESIGN of the phase it affects. A recommendation is given so the owner can answer with one word.

| # | Decision | Options | Recommendation | Owner's answer |
|---|---|---|---|---|
| D1 | What a model call's output limit is when a budget is active and the agent has no `per_call` (F03) | (a) keep 1,024; (b) use the model's known maximum output, bounded by what the budget still affords; (c) require `per_call` and fail `weave check` without it | **(b)**, plus a run-summary line when any answer hit the limit, and a `weave check` note naming the effective limit | **(b)** Answered 2026-10-29. |
| D2 | The exit code of a run that finished but used a fallback (F05) | (a) always 0; (b) a new code (7) by default; (c) 0 by default, 7 with an option | **(c)** now, so no existing script changes meaning; revisit at 7.0 | **(c)** Answered 2026-10-29. |
| D3 | Variable replacement outside `{braces}` (F50) | (a) keep and widen the lint; (b) `{name}` only from now on; (c) `{name}` only behind a script-level setting, default off, default on at 7.0 | **(c)** | **(c)** Answered 2026-10-29. |
| D4 | A `shared_prompt` that cannot be resolved at run time (F01) | (a) log a warning; (b) refuse to start | **(b)**: a rule that was meant to apply must not quietly not apply | **(a) log a warning.** Answered 2026-10-29, **against the recommendation**. Phase 17 R1.4 is dropped; the visibility work (R1.5, R1.6) stays, so a missing shared prompt is visible but does not stop a run. |
| D5 | Version of development builds (F12) | (a) as now; (b) `X.Y.Z-SNAPSHOT` on `main` between releases | **(b)**, with a build id in `weave --version` regardless | **(b)** Answered 2026-10-29. |
| D6 | How the skill install carries the jars (F13) | (a) copy the jars in (big); (b) fetch with a checksum on first use; (c) rely on `weave` on PATH (JBang) and the extension's jar | **(c) first**, (b) as the fallback; never a silent download | **(b) fetch with a checksum**, against the recommendation. Answered 2026-10-29. Never silent: the fetch lists the URL and size and asks first. |
| D7 | A preflight spends tokens (F30) | (a) a command that spends a few cents under a default cap and asks first; (b) a command that never spends | **(a)**, cap 0.05 by default, `--yes` to skip the question | **(a)** Answered 2026-10-29. |
| D8 | The canonical real-evaluation path (F21, F25) | (a) `weave eval` writes the eval4j bundle; (b) a generated JUnit class; (c) both | **(c)**: `weave eval` writes the bundle (one command), and the generator exists for projects that want the full API | **(c)** Answered 2026-10-29. |
| D9 | The release plan | one 6.2.0 or 6.1.1 then 6.2.0 | **6.1.1 then 6.2.0** (§4) | **One 6.2.0**, against the recommendation. Answered 2026-10-29. No 6.1.1: Phase 17 and 18 ship with the rest. |
| D10 | What the Outline's progress is computed from (F39) | (a) `weave next` run in the editor; (b) the extension inspects files itself | **(a)**: one source of truth for the CLI, the skill and the editor | **(a)** Answered 2026-10-29. |
| D11 | Using the owner's QA guide as the source of the evaluation design skill (F27) | (a) distil it into the skill's reference pages with credit and a link; (b) ship the document as is; (c) reference it only | **(a)**, with the owner confirming the licence and what may be quoted; the cited external works (Husain, Willison, OWASP, Zheng et al.) are linked, never copied | **(a)** Answered 2026-10-29. Still owed by the owner: the licence and attribution wording, before any reference page is written. |
| D12 | How strictly a sealed held-out set is protected (F55) | (a) a naming and tag convention only; (b) `weave eval` refuses to run held-out cases unless asked for a release gate, and records that it did; (c) cryptographic sealing | **(b)**: a guard, not a fence; the point is that it cannot happen by accident | **(b)** Answered 2026-10-29. |
| D13 | The language of the skill's examples (F27) | The guide's examples are Python, pytest and DeepEval; this stack is Java, Loom and eval4j | Rewrite every example in Loom YAML and eval4j terms; keep the guide's method and numbers; name the Python tools only where the reader may meet them | **Rewrite in Loom and eval4j.** Answered 2026-10-29. |

## 6. Operational follow-ups for the reporter (not product work)

- Rotate the two secrets that appeared in the conversation through editor selections: the store's master key (`weave secrets rekey`) and the Fabric admin password.
- Stop Fabric (`fabric-down`) when finished.
- `PROJECT_REPORT.md` of `article-flow` is out of date; the final session report supersedes it.
- The Sources section, StyleScout's length, the Editor's behaviour on the coffee injection test, and checking Fabric's pages are the project's open items.

## 7. Success measures for the whole program

1. **The field replay passes.** A fixture project shaped like `article-flow` (five agents, four tasks, grounding, a shared prompt, budgets) runs in CI against a fake provider that records every request. It reproduces each P0 symptom before the fix and shows each gone after it (Phase 17 TEST_STRATEGY §3).
2. **A newcomer reaches a real, whole answer** with the preflight and a cap in under ten minutes of their time (Phase 20 A1), and is never first told something false by a tool (every warning that a test proves can be wrong is removed or fixed).
3. **No finding in §3 is closed by documentation alone** when a tool could have stopped the mistake. Documentation is added as well, never instead.

## 8. How these specs are used

1. The owner reviews this page and answers D1 to D13.
2. For each phase, `VERIFICATION.md` §0 reproduces the findings on the current build. Anything that does not reproduce is reported and dropped or reshaped.
3. Then the usual loop of [the specs README](README.md): tests first, implement in small pieces, iterate, verify, document, ship.
4. This page's register is updated as findings are reproduced, fixed or declined, so that nothing is lost and nothing is claimed that was not checked.

## Final live run (2026-10-30)

`make test-live` (`mvn -q -Plive verify`) on the branch, with the keys this machine has (`OPENAI_API_KEY`, `JEV_API_KEY`, `XAI_API_KEY`, `SPIKE_ANTHROPIC_API_KEY`, `PERPLEXITY_API_KEY`; no Anthropic, Gemini, Sarvam or Ollama key for the main live suites). Spend was not measured; the tests send short prompts and the owner's cap was $3.

- **Passed, against real providers:** `ProviderSmokeIntegrationTest` (every vendor with a key answers and its wire formats still parse), `ClassifierJudgeLiveTest` (3), `JevVsLlmStudyIntegrationTest`, `LiveProviderContractTest.l9_aBadKeyIsAnAuthenticationFailure`, `ScrubTest` (2).
- **Skipped, with the reason "no provider credentials set" (the keys those suites read are not here):** `LiveProviderContractTest` l1 to l8 (8), `LiveNativeToolCallingTest` l12 and l13 (2), `LiveSwitchingTest` l10, `LoomLiveTest` l11, and the unicode-path and `LLM4J_LIVE` tests of the default build. The behaviour of the Phase 17 to 19 fixes against Anthropic, Gemini, Sarvam and Ollama is therefore **not** verified live.
- **The make target exited non-zero** at `loom-inspect-core` on a JaCoCo coverage rule: a live-only run cannot meet a coverage gate. No test failed. The limits were not touched; whether the `live` profile should skip the gate is a decision for the owner.
- Not run in the live suites: the manual checks M1 to M6 of Phases 17 to 22, the scripted dry run of the evaluation design skill, PIT mutation profiles, `make release-check`.

## Decisions recorded later

- **D11 (resolved by the owner, 2026-10-30):** the QA guide's structure and layout are the owner's own; its content ships with the repository under the project's licence, **Apache 2.0**. External works it cites are linked, not copied. The skill pages stay close to the guide's layout with credit.
