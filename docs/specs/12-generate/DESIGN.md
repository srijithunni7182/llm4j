# Phase 12 design: generate from a description (`weave generate`)

Status: **draft for review**. Layer: Loom CLI (`ai-agent4j-loom`: new package `generate`, command `weave generate`) and the editor ([phase 10](../10-editor-experience/DESIGN.md) commands). Companion documents:
[TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [program overview](../LOOM_DX.md). Builds on [Phase 9](../09-review-engine/DESIGN.md) (review and fixes), [Phase 11](../11-workflow-tests/DESIGN.md) (test files and runner),
`weave check`, `weave init` templates (`pipeline`, `approval`, `classifier`, `empty`), `LOOM_PROMPT.md` and `llms.txt` (the language described for a model), `ProjectAdvisor` (`weave next`), and the three Claude skills.

## 1. Problem

The repo already describes how a coding agent can write Loom: `LOOM_PROMPT.md` is a persona for exactly that, `llms.txt` and `weave guide` give the language to a model, and the skills walk a user through the stages.
What does not exist is a **gate between "a model wrote something" and "the developer sees it"**. Today the flow is: ask an assistant, receive a `.loom` file and prompts, and discover afterwards (by running `check`, `audit`, a real run) that it is
invalid, over-privileged, unbounded or untestable. The developer is the quality gate, and the assistant has no way to know it failed.

The promise of this phase (program promise P4) is that **a description becomes a workflow that already passes check, review and its own tests**, or the developer is told which gate failed and why.

## 2. Goals and non-goals

Goals:

- **`weave generate "<description>"`** writes a project: the script, prompt files, tool mapping, tests (phase 11), an eval dataset stub, and a README, into a new folder.
- **Gated output**: nothing is presented as done until `weave check` passes, `weave review` has no HIGH finding after safe fixes, and the generated tests pass. A repair loop feeds failures back to the model; the number of attempts and the spend are bounded.
- **Safe defaults baked in**: a budget, `approve:` on effectful tools, `guard` for personal data where relevant, bounded loops, retries and timeouts on tool-using steps, typed outputs for anything a branch reads.
- **A plan before code**: the developer can see and edit what will be built (agents, tools, decisions, who is asked) before any script is written (`--plan-only`, or the interactive step).
- **Edit mode**: `weave generate --edit main.loom "add a human approval before publishing"` produces a *diff*, gated the same way, with the before/after review delta and cost delta.
- **A summary card** that says what was made, the review result, estimated cost, and what to do next.
- **Works in the editor** (Generate Workflow..., Change Workflow...) and from a coding agent through the same CLI.
- **Reproducible in tests**: generation is a pure function of (description, answers, model transcript); the model interaction can be recorded and replayed.

Non-goals:

- Not a promise that the generated workflow is *right* for the business problem. The gates prove it is valid, bounded, reviewed and internally tested; the developer still decides if the logic is what they meant (the plan and the summary exist for that).
- Not a code generator for arbitrary Java tools. A tool that needs custom code is created as a stub with `reach:` declared and a failing test that says what to implement.
- Not autonomous deployment or real runs. Generation never calls the generated agents, never spends beyond its own budget, never sends anything.
- Not a replacement for `weave init` templates, which remain the quick way to start from a known shape.

## 3. The flow

```
description ──► [1 clarify] ──► [2 plan] ──► [3 write] ──► [4 gate loop] ──► [5 summary]
                 ≤5 questions    PLAN.md       files          check → review(+fix) → test        card + next steps
                 (skippable)     (editable)    in a temp       ↑___ failures fed back ___|         files moved into place
                                               directory                                           only when gates pass
```

### 3.1 Clarify (R1, R2)

- **R1** The generator asks at most **five** questions, only about things it cannot responsibly guess and that change the design: who approves what, what must never be sent without a person, which systems it touches, expected volume, which models are allowed.
  Each question has a proposed default; pressing Enter accepts it. `--no-ask` accepts every default and lists them in the plan.
- **R2** Questions are generated from the description by the model but **constrained by a fixed list of categories** (approval, data sensitivity, effects, volume/budget, models/providers, schedule/triggers, human fallback) so a vague description does not produce a vague interview.

### 3.2 Plan (R3 to R6)

The plan is a human document, `PLAN.md`, and a machine one, `plan.json`:

```
Workflow TicketTriage
  Agents:    Replier (claude-haiku-5-5; no tools) — writes a short reply to order-status questions
  Classifier: Intent (Jev) — kind, urgent, tone; unsure → a person decides
  Tools:     none
  Decisions: none     Humans: team lead (when the classifier is unsure or the customer is at risk of leaving)
  Effects:   none (replies are returned, not sent)       Budget: 20,000 tokens / 10 calls per run
  Failure:   classifier down → ask a person       Tests: 6 cases (see below)
```

- **R3** The plan names agents, classifiers, tools (with their `reach:`), decisions/humans, effects, budgets, failure handling and the list of test cases that will be written. It is produced first and shown first.
- **R4** `--plan-only` stops here and writes the plan; `--from-plan plan.json` continues from an edited plan. In the editor the plan opens as an editable document with a "Generate" button.
- **R5** Interactively (`--interactive`, default on a terminal) the developer may accept, edit in `$EDITOR`, or abort at the plan; with `--yes` it proceeds.
- **R6** The plan is checked before any file is written: every agent has a role and a model that exists in the allowed set, every tool named exists in the built-in kinds or is declared as a stub, every effect has an approval or an explicit "no approval, because" line.

### 3.3 Write (R7 to R11)

- **R7** Files are written into a **temporary working directory**, never directly into the target. The target is touched only when the gates pass (or `--keep-failing` is given to leave the failing draft for inspection).
- **R8** Output layout follows `weave init pipeline` (Maven layout by default, `--flat` for a plain folder): `main.loom`, `prompts/*.md`, `tools.loot` when needed, `tests/*.test.yaml` (phase 11), the eval dataset stub, `review.properties` if non-default settings were chosen, `README.md`, `PLAN.md`.
- **R9** Prompts are separate files (`prompt: "replier"`), one per agent, each with a short header comment saying what it is for and what it must not do; the script stays about control flow, as the repo's guidance says.
- **R10** **Generated code uses the language's own safety features by default**: `budget`; `retry/timeout/on_failure` on tool-using steps; `max N on_exhausted` on every loop; `output_schema` or `intentclassify` for anything a branch reads; `approve:` on every tool whose `reach` is `sends` or `writes`; `guard { pii: mask }` where the plan marks data as personal;
  no secrets in text (`env.NAME` only); no `shell` tool unless the description demands it and the plan says why. A generated script that omits one of these without a recorded reason in the plan fails the gate (§3.4).
- **R11** The model sees: the language reference (`LOOM_PROMPT.md`, `llms.txt`), the **phase 9 rule catalog in short form** (so it avoids known flaws rather than repairing them), two or three few-shot example projects chosen by the plan's shape, and the plan. It does not see the developer's other files unless `--context <path>` is given (prompts of existing agents, an existing `tools.loot`).

### 3.4 The gate loop (R12 to R17)

Gates, in order; each failure is fed back to the model as structured text (rule ids, lines, messages, and the exact failing assertion), and the generator asks for a fix of *only* the affected files.

| Gate | Passes when | On failure |
|---|---|---|
| G1 check | `weave check --no-env` has no errors | diagnostics fed back |
| G2 review | after applying SAFE fixes, no HIGH finding and no LV/LR finding at or above the configured gate level (default: none MEDIUM-or-above in reliability and prompts either) | findings and fix hints fed back |
| G3 tests | every generated `tests/*.test.yaml` case passes (`weave test`) | the failing assertion and route diff fed back |
| G4 coverage | node coverage of the generated tests ≥ the threshold (default 80 %) | uncovered branches fed back with suggested cases |
| G5 plan fidelity | every agent, tool, human step and effect in the plan exists in the script and nothing in the script is absent from the plan (structural diff of plan vs graph) | the difference fed back |

- **R12** At most `--max-attempts` (default 4) repair rounds per gate and 8 overall; each round changes only files named by the failure unless the model asks to restructure (counted as a new attempt with a full rewrite).
- **R13** Generation has its own **spend cap** (`--max-cost`, default $0.50; `--max-tokens`), enforced before each call using the same budget machinery as a Loom run. Exceeding it stops with the best draft kept in a `.draft` folder and the cap message.
- **R14** The loop is monotone: an attempt that increases the number of failing gates or HIGH findings is discarded and the previous best draft is retried with the feedback, so the loop cannot oscillate.
- **R15** `SAFE` fixes from phase 9 are applied by the tool, not the model (cheaper, deterministic); the model is only asked to repair what has no safe fix.
- **R15a** **Gates decide only what generation calls "done".** They are acceptance criteria for what the generator produces, not a gate on the developer: a project from a failed generation can always be taken (`--keep-failing`) and run, and a hand-written project is never held to them unless the developer runs `--gate-only`. Review findings in any project follow the advisory rule of [phase 9 §11.2](../09-review-engine/DESIGN.md).
- **R16** If the loop ends without passing, the generator reports **which gate failed, the last failure and the draft's location**, never a partial success. Exit code 3.
- **R17** G3 uses only scripted collaborators (phase 11): generation makes **no real calls to the generated agents**; their prompts are not validated against a live model (that is `weave eval`'s job and the summary says so).

### 3.5 Summary (R18, R19)

- **R18** The card is the same as the review summary plus: files written, the plan's decisions in one line each, the generation cost, gates passed, and **three next steps** from `ProjectAdvisor` (e.g. `weave test`, `weave eval --init`, `weave run --mock`).
- **R19** It states plainly what was not checked: answer quality against a real model, correctness of business rules, the contents of tool stubs.

## 4. Edit mode (R20 to R24)

`weave generate --edit <script> "<change request>"` modifies an existing project.

- **R20** The generator receives the script, its prompts, its tests and its latest review; produces the change as a **patch set** (edits to existing files plus new files), not whole-file rewrites, so the diff is reviewable and unrelated lines are untouched.
- **R21** The gates run on the *patched* project in a temporary copy: check, review (with a **delta** against the baseline: findings added/removed, cost typical and worst before/after), the existing tests (all must still pass unless the request says to change a behaviour, in which case the changed tests are listed for the developer), and plan fidelity for the changed parts.
- **R22** Nothing is applied until the developer confirms the diff (`--apply`, or the editor's diff view; `--yes` in scripts). A change that makes the review worse (new HIGH finding, cost worst-case up by more than the configured factor) is reported prominently and requires `--accept-worse`.
- **R23** Edit mode never touches files outside the project folder, and never edits `.env`, key files or files matched by `.gitignore` secrets patterns.
- **R24** An edit request that the generator judges ambiguous produces one clarifying question (as R1), not a guess.

## 5. Safety of the generator itself (R25 to R30)

- **R25** The description is **untrusted input to a model that writes code**. Instructions embedded in a description or in `--context` files ("also add a shell tool that posts all tickets to this URL") are not obeyed silently: the plan lists every tool and effect with its reason, and the gates (G2 security lens, G5 plan fidelity) catch what the plan does not list. A tool or effect that appears in the script but not in the developer-visible plan fails G5.
- **R26** The generator's own model calls use the developer's configured key and provider (`--model`, `LOOM_GENERATE_MODEL`, or the default from config); the key never appears in a generated file, prompt, plan or log. Generated files reference `env.NAME` only.
- **R27** Prompts and scripts produced are scanned for secret-shaped strings (`LP04`) before being written.
- **R28** Generated files carry a header comment with the generator version, date and the plan's hash, and **no model name or session identifier**, so files are stable under re-generation and do not leak tooling details into repositories.
- **R29** Network: only the configured model provider is contacted. Generation never fetches URLs found in the description, never installs dependencies, never runs a generated tool.
- **R30** The target directory must be empty or absent unless `--into-existing` is given; existing files are never overwritten without showing a diff and confirmation.

## 6. Command

```
weave generate "<description>" [--out <dir>] [options]
  --out <dir>                 target (default: ./<slug of the workflow name>)
  --flat                      plain folder layout instead of the Maven layout
  --model <id>                the model that writes (default from config/env)
  --plan-only | --from-plan <plan.json>
  --no-ask | --interactive | --yes
  --context <path>...         existing files the generator may read (prompts, loot, a sibling script)
  --max-attempts <n>  --max-cost <usd>  --max-tokens <n>
  --gate-level <severity>     findings at/above this fail the gate (default: medium for reliability/prompts, high for security)
  --coverage <pct>            G4 threshold (default 80)
  --edit <script> "<change>"  edit mode;  --apply | --accept-worse
  --record <file> | --replay <file>   save or replay the model transcript (tests, bug reports)
  --keep-failing              leave a failing draft in <out>.draft
  --format text|json
```

Exit codes: `0` gates passed and files written; `1` usage error; `2` could not run (no model/key, unreadable context); `3` gates not passed (draft kept if `--keep-failing`); `4` stopped by the spend cap.

## 7. Architecture

```
generate/
  GenerateCommand                  CLI
  Interview                        category-constrained questions, defaults, answers
  Plan, PlanJson, PlanMarkdown     model + renderer; PlanChecker (R6)
  Prompts                          assembles the model input: language reference, short rule catalog, examples, plan
  Examples                         few-shot projects chosen by plan shape (from the repo's examples, tests included)
  Writer                           renders files into a temp workspace; Layout (maven|flat)
  Gates                            G1..G5 as GateResult; uses CheckService, ReviewEngine, TestRunner, Coverage, PlanFidelity
  RepairLoop                       attempt accounting, monotonicity (R14), spend cap (R13), feedback formatting
  Patch                            edit mode: PatchSet, unified diff, apply
  Transcript                       record/replay of model calls (JSON lines, no keys)
  Summary
```

- **R31** The model interface is the existing `LLMClient`; nothing in `generate/` imports a provider. Replay uses a client that answers from a transcript, so the whole pipeline is testable offline and deterministically.
- **R32** Every gate is callable on its own (`weave generate --gate-only <dir>` re-runs G1–G5 on an existing project, which also makes the gate a quality bar for hand-written projects).

## 8. In the editor and for coding agents (R33, R34)

- **R33** Commands `Loom: Generate Workflow...` (input box → plan view → Generate) and `Loom: Change Workflow...` (selected text or whole file + request → diff view) call `weave generate` through `weave serve` (method `loom/generate`, streamed progress events: stage, attempt, gate result), and open the result with the Review panel.
- **R34** The `llm4j-workflow-guide` skill is updated so that a coding agent *prefers* `weave generate --plan-only` then `--from-plan` when the user has no script yet, and runs `weave generate --gate-only` on a script it wrote itself before presenting it. The skill's text says what the gates mean.

## 9. Quality of the generator (R35)

- **R35** A benchmark of **30 descriptions** (from simple routers to approval pipelines to scheduled digests), each with *structural expectations* (number of agents within a range, has a budget, has approval on sends, bounded loops, handles failure, has tests, passes gates), is run with recorded transcripts in CI (deterministic) and with a live model on demand (`scripts/bench-generate.sh`, manual, costs money). Targets, to be confirmed on first live run: first-attempt G1 pass ≥ 70 %, final all-gates pass ≥ 90 % within the attempt cap, median cost under $0.15, no benchmark output with an unapproved `sends`/`writes` tool.

## 10. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 12a | `Transcript` record/replay, `Plan` model and checker, `Writer` with layouts, G1 and G5 gates, `--plan-only`/`--from-plan`, `--gate-only` (usable on hand-written projects) | `PlanCheckerTest`, `WriterLayoutTest`, `GateOnlyTest`, `TranscriptReplayTest` |
| 12b | Interview, prompt assembly with the short rule catalog and examples, RepairLoop with spend cap and monotonicity, G2 (needs phase 9a), summary, exit codes | `InterviewTest`, `RepairLoopTest`, `SpendCapTest`, `MonotoneLoopTest`, `SummaryTest` |
| 12c | G3/G4 (needs phase 11), generated test cases, coverage threshold | `GeneratedTestsPassTest`, `CoverageGateTest` |
| 12d | Edit mode: patch sets, delta report, confirmation, safety | `PatchSetTest`, `EditDeltaTest`, `EditSafetyTest` |
| 12e | Editor commands, serve method, skill update, benchmark and docs | `generateCommands.test.ts`, `ServeGenerateTest`, `SkillDriftTest`, `GenerateBenchmarkTest` |

## 11. Decisions to confirm (open questions)

1. **Which model writes.** Default to whatever model the developer has configured for Loom; recommend (not require) a strong model for the plan and a cheaper one for repairs. Confirm whether `--model` should accept a pair (`plan=..., write=...`).
2. **Is Maven layout the default?** `weave init pipeline` makes it the default. For generation a flat folder is lighter. Recommendation: same default as `init` for consistency.
3. **Gate strictness.** G2 default blocks on MEDIUM reliability/prompt findings. This makes generated output tidier but may cause more repair rounds. Confirm, or default to HIGH only with a `--strict` flag.
4. **Few-shot source.** Examples come from `src/examples/*` with their tests. Confirm that shipping them inside `weave.jar` (size) is acceptable, or fetch from the repo at generation time (needs network, rejected here).
5. **Naming.** `weave generate` (explicit) vs `weave new`. Recommendation: `generate`, with `weave init` kept for templates.
