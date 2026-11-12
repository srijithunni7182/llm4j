# Phase 28 design: evaluation first

Status: **draft for review** (decisions D17 to D19 recorded 2026-11-01). Layer: the review engine (the Testability lens, LT06), `weave check` (a gate on internal nodes), `weave eval` (`--requirements`, `--changed`), `weave run`, `weave deploy` and the live run page (a notice), `weave guide`, the skills and the tutorial. Part of the [engineering discipline program](../DISCIPLINE.md).
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Depends on [phase 24](../24-stateless-nodes/DESIGN.md) (extractors) and [phase 26](../26-policy-boundary/DESIGN.md) (evaluators, policies, the evaluator case format). Builds on [Phase 11](../11-workflow-tests/DESIGN.md) (`weave test`), [Phase 14](../14-production-loop/DESIGN.md) (baselines, `weave gate`) and [Phase 19](../19-evaluation-that-works/DESIGN.md) (datasets, fingerprints, judge validation).
Evidence owned: **E12, E13, E26, E27**. Mistakes: **M3, evaluation as an afterthought**, **M12, the doer grades itself** (D45), and **M13, gating on self-reported confidence** (D46). The program's end-of-program documentation step is [phase 29](../29-reviewable-architecture/DESIGN.md)'s.

## 1. Problem

The owner's rule: the golden dataset **is** the requirements document, written first and run continuously. The workflow-guide skill already teaches this well, but the tooling around it says the opposite. The Testability lens is off by default for a reason that no longer applies (E12); `weave guide` calls the dataset "optional" (E13); skipping evaluation leaves no trace outside the README line the skill writes. And the new internal nodes raise the stakes: an `evaluator` is an LLM judge enforcing policy in production, and an extractor is the easiest node to test on its own. If nobody has measured them, the boundary of phase 26 rests on trust.

## 2. Goals and non-goals

Goals:

- Evaluation stays skippable, but **skipping is loud** (R1 to R3, D17).
- An evaluator, an extractor or a policy rule **cannot be used untested**: `weave check` fails (R4, D18).
- A non-technical reviewer can **read and sign off** the requirements as written in the dataset (R5, D19).
- Real evaluation runs **continuously and cheaply**, on what changed (R6, D19).
- The skills, the guide and the tutorial present the dataset as the requirements (R7).
- Nothing grades its own work by default: a different judge model, and a warning when an agent decides its own loop is done (R12, R13, D45); self-reported confidence is taught for what it is (R14, D46).

Non-goals:

- Making evaluation compulsory for ordinary agents or for going live (D17: skippable).
- New judge machinery: judges, validation and fingerprints are [phase 19](../19-evaluation-that-works/DESIGN.md)'s.
- Using a policy as a judge in workflow cases: an open question of phase 26 (§7, question 1), built here only if the owner says yes (§7).

## 3. Requirements

### Louder (D17)

**R1 The Testability lens is on by default, and LT06 is exact and medium.**
- `Lens.TESTABILITY` moves to the default set, as the review design intended ([Phase 9](../09-review-engine/DESIGN.md) R33: off only "until the workflow-test phase ships `weave test`"). `lenses = -testability` restores the old default. The rule index and the summary card stop marking it *(off)*.
- LT06 ("an agent has no golden dataset") stops being heuristic: it uses **the same dataset lookup `weave eval` uses** (one shared `GoldenDatasets.find(baseDir, name)`, any capitalisation, `.yaml` or `.yml`, the workflow file `workflow.yaml` or `<Workflow>.yaml`), so it can never disagree with `weave eval`. Its severity follows LT01's rule (D35): **medium when the script can write, send or ask a person**, low otherwise. It is reported as **one grouped finding per script** naming every agent and internal node without cases of its own ("LT06: 4 agents have no golden cases: Researcher, Writer, …"), excluding agents covered by a workflow dataset that names them in an `expect` line. Before the lens is switched on, LT01 and LT06 each meet the precision bar and the noise gate (program page §4.6).
- LT01 ("the script has no test file") is unchanged in condition and severity (medium when the script can write, send or ask a person).

**R2 A notice when nothing measures the agents.** When the script has **no golden cases at all** (no agent, node or workflow file with at least one case):
- `weave run` prints one line after the result: "No evaluation: nothing has measured whether these agents are right. `weave eval --init` starts one." `--quiet` leaves it out.
- `weave deploy` prints the same line, and the generated deployment README (`deploy/Readme.java`) carries it under "Before you rely on this".
- The live run page (`--observe`) and Fabric show a small, dismissible banner with the same words for runs of that script (the run's start event carries `evaluated: false`; the page never reads the dataset itself).
- `weave next` lists "write the golden dataset (the requirements)" as the next step while it is missing.
- When the skill recorded `Evaluation: skipped` in the project README, the notice says "Evaluation was skipped on purpose (see README)", once per run, instead.

**R3 `weave guide` and the skill stop calling the dataset optional.** `weave guide`'s stage 2 becomes "Write the requirements as a golden dataset"; its page (`guide/02-golden-dataset.md`) opens with "The golden dataset is the requirements: what the workflow must do and must never do, as examples" and says in one sentence that a prototype may skip it and what that costs. The workflow-guide skill keeps asking once ("Do you want tests first?", default yes) and keeps recording a "no" in the README (D17); its wording changes to match.

### Binding for internal nodes (D18)

**R4 `weave check` fails on an untested internal node.** For each node the script uses:

| Node | Required in `eval/golden/` | Error when missing |
|---|---|---|
| `extractor X` | `x.yaml` with **at least one case** whose expectation names fields (`expected_fields: { total: 240 }` and/or `expected_missing: [total]`) | "Extractor `X` has no golden cases. Write at least one in `eval/golden/x.yaml` (`weave eval X --init`)." |
| `evaluator E` with each policy `P` it is used with | `e.yaml` with, **for every rule of `P`**, at least one case `expected: { <rule>: violated }` **and** at least one case where the rule is `clean` (a near miss counts as clean, and is recommended) | "Evaluator `E` has no case that breaks `P.no_refund_promise` (and no clean case). A policy rule nobody has tested is not binding: write one of each in `eval/golden/e.yaml`." |

- The case format for evaluators is phase 26's (R13). Extractor cases are exact field checks: no judge, cheap and deterministic in a real run.
- `weave check` validates the cases' **structure** (ids unique, fields and rules exist, each expectation well formed); whether they are good cases is the reviewer's job (R5) and `weave eval`'s.
- **There is no flag that skips the gate.** It applies wherever the project's dataset is: `weave check` in the CLI and the editor, `weave serve`, and `weave deploy`'s own check. The repository's documentation tests give their examples real cases (§9).
- **Not at run time in a packaged host.** An application that loads a script with `LoomLoader` need not ship `eval/golden/`, so loading never reads it and is never refused by the gate; it logs once, at WARNING, that the gate is enforced at build time. A host project enforces it in its own build by running `weave check` over the project directory, as the CI template does (R8).
- Interaction with the fixes that create an extractor (phase 24 LP09's `to-extractor`, phase 25 LP10's `split`): the fix converts any existing golden cases of the old agent whose expectations name JSON fields into extractor cases; when there are none, the fix's result says which file to write and gives the `--init` command. The fix is otherwise unchanged (it still needs a yes); the script then fails `weave check` only on this gate, which is the intent.
- `weave eval X --init` scaffolds the file with commented examples and **no** cases, so a scaffold never satisfies the gate by itself.

### Requirements a person can sign off (D19)

**R5 `weave eval --requirements`.** Renders the dataset as a plain page, Markdown by default (`--format html` for a page to share):
- Per workflow, agent and internal node, in plain sentences: "When a customer writes *I was charged twice, 240 dollars*, the answer must mention *approval*, must never contain *4111*, must be at least 50 words; a judge must confirm that it *asks a person before refunding*."
- Per policy rule: its text from the policy file, then the examples that break it and the ones that must not count as breaking it (near misses marked).
- Per extractor: the input excerpts and the fields expected, with the missing ones named.
- The page shows the dataset's identity (its `version:` and the hash of every case, as Phase 19 computes it), the policies' versions and hashes, and what has **no** requirement yet (agents with no cases).
- `weave eval --requirements --approve --approver <name> --why "<text>"` records an approval of exactly that identity in `eval/requirements-approvals.yaml` (the same approval mechanics as baselines: git `user.email` by default; a review aid, not a security control). A later change to any case makes the page say "changed since approved by <name> on <date>" until approved again.
- No model, no key, no cost.

### Continuous, and cheap (D19)

**R6 Evaluate what changed.**
- **Fingerprints per node.** Every real `weave eval` run records, per agent and internal node, a fingerprint of what determines its answers: the resolved system prompt (including the shared prompt it receives), model, temperature, schema or fields, instructions, tools and their declared reach, and for an evaluator the versions and hashes of its policies; plus the hashes of the node's own cases. The prompt part reuses the `promptHash` the runtime already traces.
- **`weave eval --changed [--since baseline|last|<file>]`** runs, for real, only the cases of nodes whose fingerprint differs from the reference (default: the current baseline, else the last recorded run), plus every workflow case whose route reaches a changed node. With no reference, everything counts as changed, and it says so. It needs a cap like any real run (`--max-tokens`, `--max-calls` or `--max-cost`) and asks first, as `weave eval` does today.
- The report has a section "What changed" naming each node and what changed in its fingerprint (prompt, model, policy version, cases).
- **Three costs, documented together**: `weave test` on every change (free, scripted); `weave eval --changed` with a cap before a merge (the CI template and `weave gate` gain this step); a full `weave eval` on a schedule (the CI template gains a scheduled job, off until the user enables it, because it spends money).

### Teaching

**R7 The dataset is the requirements, everywhere.**
- `llm4j-workflow-guide`: stage 2 is "write the requirements as a golden dataset"; step 2b's list of requirements becomes the first cases; at go-live, the skill runs `weave eval --requirements` and shows the page to the user before asking them to sign off; internal nodes' cases are part of stage 2, and the skill explains the `weave check` gate before it fires.
- `llm4j-eval-design`: `EVAL_DESIGN.md` gains a section per internal node (an extractor's exact checks; an evaluator's per-rule cases with near misses, its agreement with a person and its unsure rate), and the three costs of R6 become the default run protocol.
- `llm4j-harness-design`: the harness test stage names the evaluator's cases as part of testing the harness.
- `LOOM_GUIDE.md` "Evaluating a Workflow" opens with "the golden dataset is the requirements" and documents R4 to R6.

### End of the program

**R8 The CI template.** The project templates (`weave init` and the starters' shared CI file, where present) run `weave check`, `weave test` and `weave review --fail-on high` on every push, `weave eval --changed --max-cost <set by the user>` on pull requests when a key is configured (skipped with a notice otherwise, never failing for the missing key), and a scheduled full run that is commented out.

**R9 Stack-level documentation: moved to phase 29.** The program's one update of the stack-level pages is made by [phase 29](../29-reviewable-architecture/DESIGN.md), which documents the program's differentiator, so those pages are touched once, with the claim and the page together.

**R10 Evaluators are measured for over-caution** (D37). `weave eval` reports, for each evaluator, its **false-alarm rate**: the share of its clean and near-miss cases (phase 26 R13) it flagged as violated or unsure, per rule and overall, with a 95% interval. A run **fails** when an evaluator's rate is above its limit: `false_alarm_limit: 10%` by default, settable on the evaluator. A timid evaluator is caught on the bench, not by customers. `--requirements` shows the limit beside each policy rule.

**R11 Internal nodes in the report** (D38). The report's **By agent** section becomes **"By agent and node"**: extractors and evaluators have their own rows with their pass rate on their own cases, calls, tokens, cost and latency, exactly as agents do.


**R12 The default judge is a different model** (M12, D45).
- `weave eval` chooses the judge in this order: `--judge <model>`; `judge:` in `dataset.yaml`; `eval.judge` in the project's settings; otherwise **the cheapest model the project can reach that is not the model being judged**, preferring another provider when one is configured. The choice and why appear under "Read this first" in the report.
- `--judge same` judges a model with itself, deliberately; the report's *Cautions* section says so ("self-judging: scores may be biased toward the judged model's own style").
- When the project can reach only one model, `weave eval` judges with it and records the same caution: it never refuses for want of a second key.
- A workflow's `expect` lines follow the same rule. eval4j's Java API keeps its explicit judge parameter; its default judge factory follows the same order.

**R13 LR12: the agent decides its own work is done** (M12, D45; Reliability).
- **Medium**: a `loop until` (or a loop's `when` rewind condition) reads a field of a variable bound, inside the loop body, by a delegate to an agent that also produces the loop's work (the same agent binds another variable the loop iterates on, or the condition reads the very answer that carries the work). What to do: let a different agent review it (starter 03's shape), check the criteria in code with a task (best when they can be checked, such as tests passing), or `check` it.
- **Low**: an `evaluator` (phase 26) whose model is the same as the model of the agent whose output it checks.
- Structural facts only; precision bar and noise gate (D35); fix option `add-reviewer` adds a reviewer agent on the cheapest model and rewires the condition to its verdict, shown as a diff.

**R14 Self-reported confidence is taught, not ruled** (M13, D46). The `llm4j-eval-design` skill (quality dimensions: calibration) and the `llm4j-workflow-guide` skill (stage 6) say plainly that a confidence number a chat model writes about itself is not a calibrated probability; gating on it (`alt (answer.confidence > 0.8)`) needs either a calibrated source (an `intentclassifier` on a calibrated backend, phase 7) or calibration measured on the golden dataset before the threshold is trusted. `LOOM_GUIDE.md` "Output Schema Enforcement" gains the same note in two sentences. No review rule: detection would rest on field names.

## 4. Java API

| Type | Change |
|---|---|
| `eval.GoldenDatasets` (or the existing dataset loader made public for this purpose) | `find(Path baseDir, String name)` shared by `weave eval`, LT06 and the gate |
| `review.model.Lens` | `TESTABILITY` default on |
| Eval run record | per-node fingerprints (a new field in the JSON; older records without it are read as "no reference") |

`docs/API_COMPATIBILITY.md` records the eval record's new field and the lens default.

## 5. Compatibility

| Change | Existing users |
|---|---|
| Testability lens on; LT06 exact and medium | More findings by default; `lenses = -testability` restores. A CI job with `--fail-on medium` may start failing. Listed in the release notes. |
| The notice | One line on `weave run` and `weave deploy` for scripts with no cases; `--quiet` removes it. |
| The gate | Applies only to scripts that use an extractor or an evaluator, which do not exist before phases 24 and 26. |
| `--requirements`, `--changed` | New options. |
| CI template | New projects only; existing projects are unchanged. |

## 6. Security notes

- The requirements page and the approvals file contain the dataset's inputs, which may hold personal data; the page masks PII with the same masker as the eval report, and the guide says to read cases before committing them (as Phase 14 already says for captured cases).
- `--changed` never decides that something did not change when it cannot compute a fingerprint: an unreadable prompt file or a missing record counts as changed (fail closed: run more, not less).
- A scheduled full run in the CI template is commented out, so a template never spends money without the user's act.

## 7. Open questions

1. Phase 26's open question 1: should a policy also act as a judge in workflow cases (`expect_policy: HouseRules`)? If yes, it is built here, reusing the evaluator, and `--requirements` lists it.
2. R4: is one case enough for an extractor, or should every required field be asserted in at least one case? Proposed: one case for the gate; review suggests more (a low finding, LT08, "fields never asserted"), not a check error.
3. R8: which CI system does the template target? Proposed: GitHub Actions, the one the repository itself uses, with the commands written so another system can copy them.

## 8. Documentation affected

`LOOM_GUIDE.md` ("Evaluating a Workflow", with the judge order and `--judge same`; "Output Schema Enforcement" with the confidence note), `docs/review/rules/` (LR12), `LOOM_PROMPT.md`, `docs/guide/02-golden-dataset.md` (and `weave guide`'s stage title), `docs/guide/05-test-agents-with-caps.md` and `docs/guide/09-go-live.md` (the notice, the sign-off), the tutorial (§9), `docs/review/rules/` (LT06, the lens index), `docs/specs/09-review-engine/DESIGN.md` R33 (a note that the lens is now default, linking here), `docs/TESTING_STRATEGY.md` (the three costs), eval4j's README, the command reference pages for `weave eval`, `weave check`, `weave run`, `weave deploy`, the three skills in R7, the starters (each starter that uses an extractor or evaluator has its cases; the starters README says the dataset is the requirements), `docs/API_COMPATIBILITY.md`, `llms.txt`, the next release's notes. The stack-level pages are phase 29's.

## 9. Tutorial

| Chapter | Change |
|---|---|
| [10. Testing](../../tutorial/10-testing.md) | **Who judges**: the default judge is a different model, `--judge same`, and what the report says (R12). "`weave eval`: the quality, with a golden dataset" opens with **"The dataset is the requirements"** and shows `weave eval --requirements` and a sign-off. New sections **"Cases for an extractor"** (exact field checks) and **"Cases for an evaluator"** (per rule: breaks it, nearly breaks it, clean) with the `weave check` error a reader will meet first. "The order that saves money" becomes the three costs of R6 with `weave eval --changed`. "From CI" uses the new template. |
| [1. Your first workflow](../../tutorial/01-first-workflow.md) | The check, test, run loop mentions the notice the reader will see on their first `weave run`, in one sentence, and where to start a dataset. |
| [5. Structured answers and failure](../../tutorial/05-structured-answers-and-failure.md) and [9. Safety and cost](../../tutorial/09-safety-and-cost.md) | The extractor and policy examples point to their cases in chapter 10. |
| [14. Cookbook](../../tutorial/15-cookbook.md) | "Mistakes and their fixes" gains the gate's two errors, LT06 and the notice; the cheat sheet gains `--requirements` and `--changed`. |
| [README](../../tutorial/README.md) | "How a Loom project fits together" shows `eval/golden` as the requirements, beside the script. |

Tutorial blocks that declare an extractor or an evaluator are complete examples with their cases (as files beside the block, marked `loom-parse` where the block alone cannot load, with the complete version in a starter). Every block is checked by `TutorialExamplesTest`; the page is regenerated with `bash scripts/make-tutorial-html.sh`.
