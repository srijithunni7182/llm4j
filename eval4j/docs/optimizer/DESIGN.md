# eval4j Optimizer — Design

Companions: [SPEC](SPEC.md) · [TEST-STRATEGY](TEST-STRATEGY.md) · [VERIFICATION-PLAN](VERIFICATION-PLAN.md)

This document explains **how** the optimizer specified in [SPEC](SPEC.md) is built, and **why** it is
shaped this way.

## 1. Summary

A small, single-threaded-decision / parallel-evaluation search loop that repeatedly (1) picks a
promising prompt from a pool, (2) runs it on a few training scenarios, (3) asks an LLM to rewrite it
based on *why* it failed, and (4) keeps the rewrite only if it earns its place — using eval4j's
existing scoring machinery as the objective. Overfitting and judge-gaming are handled structurally
(three data splits, deterministic guardrails, confirmation run), not by hoping the judge is honest.

## 2. Background: what GEPA does

*(From the GEPA paper and repository as summarised in research; the paper itself was not directly
readable from the authoring environment, so treat details as approximate.)*

GEPA — **G**enetic-**Pa**reto reflective prompt evolution — optimizes text parameters with an LLM
reading execution traces rather than a scalar reward:

1. **Reflective mutation.** Run a candidate, gather outputs, traces and textual feedback, and have an
   LLM diagnose the failures and propose an improved prompt. Feedback text is the analogue of a
   gradient.
2. **Pareto selection.** Keep a pool; choose parents from the candidates that are best on at least one
   validation example, weighted by how many they win. This preserves specialists and avoids collapsing
   on one average.
3. **Cheap gating.** Try a child on a small minibatch first; pay for full validation only if it wins.
4. **Budgeted.** Stop by rollout budget; reported to need orders of magnitude fewer rollouts than RL.
5. **Merge** (optional): combine the strengths of two candidates in multi-module systems.

### What this design keeps, changes and adds

| | GEPA | This design |
|---|---|---|
| Reflection on traces + feedback | ✔ | ✔ — feedback = eval4j judge reasons and assertion messages |
| Pareto frontier selection | ✔ | ✔ |
| Minibatch gate then validation | ✔ | ✔ |
| Budget by metric calls | ✔ | ✔ plus wall-clock, LLM-call and round caps |
| Merge/crossover | ✔ | Deferred (extension point) |
| Sealed **test** split | Recommended practice | **Mandatory** by default; `generalized` verdict |
| Deterministic **guardrails** | — | ✔ hard gate independent of judge scores |
| **Confirmation run** with fresh judge draws | — | ✔ |
| Restricted feedback (rewriter sees train only) | — | ✔ |
| Output is a reviewable patch, never auto-applied | — | ✔ |

## 3. Placement and dependencies

**Decision: a package inside `eval4j`**, `io.github.llm4j.eval.optimize`, with a one-way dependency:

```
ai-agent4j  <-- eval4j (assertions, judge, compare, dataset, report)
                   ^
                   |  (uses)
             eval4j.optimize
```

- `optimize` may use everything in the rest of eval4j and `ai-agent4j`. **Nothing outside `optimize`
  may reference it** (enforced by a dependency test, see test strategy).
- The independently useful part — `Criterion`, `CriterionResult`, `Scorecard` — lives in its own
  package (`io.github.llm4j.eval.criteria`) so it is usable without the optimizer.

**Why in eval4j and not a new module.** deepeval, the closest peer, ships its optimizer in the same
library as its metrics; one artifact is easier to adopt; and the optimizer's whole value is reusing
eval4j's scoring, datasets, caching and comparison. **Why the boundary still matters.** The rest of
eval4j only *measures*; this package *changes things* (it calls an LLM to rewrite text and runs your
system many times). Keeping it in its own package, one-way, with an explicit acknowledgement
(`acknowledgeSideEffects`) keeps that distinction visible.

**Fallback.** If the package grows heavy dependencies or its release cadence diverges, extracting it
into a module is mechanical because of the one-way rule. This is [SPEC open question 1](SPEC.md#9-open-questions).

## 4. Architecture

```
                                 ┌───────────────────────────────┐
  seed, scenarios, criteria ───▶ │        PromptOptimizer        │
  budget, target, constraints    │  (builder, validation, run)   │
                                 └──────────────┬────────────────┘
                                                │ owns
        ┌───────────────┬───────────────┬───────┴───────┬────────────────┐
        ▼               ▼               ▼               ▼                ▼
   DataSplit        CandidatePool    Evaluator        Rewriter       BudgetTracker
 (train/val/test)  (+Frontier,       (rollouts →     (meta-prompt,   (rollouts, LLM
                    Selector)         Scorecards)     parse, retry)   calls, time)
                                          │                │
                                          ▼                ▼
                                    Criterion[]        LLMClient
                                (judged / assertion /  (rewriter)
                                     guardrail)
        ┌──────────────────────────────────────────────────────────────┐
        │ Checkpointer   Trace/Reporter   ConstraintChecker   Listener │
        └──────────────────────────────────────────────────────────────┘
```

### 4.1 Key types

| Type | Responsibility |
|---|---|
| `Candidate` | Immutable `Map<String,String>` of parameters + `id`, `parentId`, `origin` (SEED / REWRITE), `round`. Equality by parameter content (dedupe identical rewrites). |
| `Criterion` / `Criteria` | Adapters turning eval4j conditions/assertions into `CriterionResult`. `judged(...)` calls each condition's `evaluate(...)` (already returns `JudgeVerdict`), so no test-time assertion failure is needed to obtain reasons. |
| `Scorecard` | Score, per-criterion results, guardrail flag, output, feedback. |
| `Evaluator` | `evaluate(candidate, scenarios) → List<Scorecard>`. Runs rollouts in a bounded thread pool, charges the `BudgetTracker` **before** each rollout, converts exceptions to score-0 cards. |
| `DataSplit` | Deterministic partition; validates sizes. |
| `CandidatePool` | Stores candidates and their per-validation-scenario scores; computes the frontier and selection weights (`ParetoSelector`). |
| `Rewriter` | Builds the meta-prompt from failing train scorecards, calls the LLM, parses/repairs, returns a proposed parameter text. |
| `ConstraintChecker` | Cheap pre-checks (length, required/forbidden substrings, custom predicate). |
| `BudgetTracker` | Thread-safe counters and deadline; `tryAcquire(kind)` semantics so caps are hard. |
| `Checkpointer` | Atomic JSON write per round (reuses `AtomicFiles`); fingerprint check on resume. |
| `OptimizationTrace` / `Reporter` | Round records, JSON + Markdown output, optional `EvalRecorder` metrics. |

### 4.2 Run sequence

```
build() ─ validate config, splits, acknowledgements, estimate()
run():
  resume? ── load checkpoint ── else init
  score seed on validation                      ← failure ⇒ FAILED
  loop:
    stop? ── BudgetTracker / target / patience / interrupt
    parent  = Selector.pick(pool, rng)
    batch   = train.sample(k, rng)
    P = Evaluator(parent, batch)                ← charges rollouts
    if all P ≥ perfect: record SKIPPED; continue
    child   = Rewriter(parent, param, failures(P))
    ConstraintChecker(child) ? else REJECTED
    C = Evaluator(child, batch)
    if mean(C) > mean(P)+margin:
        V = Evaluator(child, validation); pool.add(child, V)
    Checkpointer.save(round)
  confirm(best) ; test(seed, best) ; verdict ; report
```

### 4.3 Frontier and selection (precise)

Let `S[c][v]` be candidate `c`'s validation score on scenario `v`. `max[v] = max_c S[c][v]`.
`bestOn(c) = { v : S[c][v] ≥ max[v] − ε }`. Discard candidates whose `bestOn` set is empty, or is a
strict subset of another candidate's set with no better score anywhere (dominated). Sample a parent
with weight `|bestOn(c)|` (weighted reservoir with the seeded RNG). Scores are stable inputs (recorded
once per candidate), so selection is a pure function of pool state + RNG — trivially unit-testable.

### 4.4 Scoring pipeline

```
output = system.run(candidate, scenario)                 // may throw → score 0, feedback = message
for criterion in criteria:  result = criterion.score(scenario, output)
guardrail failed?  → score 0, guardrailViolated
else               → weighted mean of non-guardrail scores
feedback = failed assertion messages + judge reasons (truncated, deduplicated)
```

Judged criteria reuse `JudgeCalls` (caching, sampling, delimiting). Rollout outputs may also be
cached by `(candidate parameter hash, scenario, systemIdentifier)` via an optional `OutputCache`, so a
resumed or repeated evaluation does not re-run the agent.

### 4.5 The rewriter prompt

System message: role (prompt engineer), the rule that everything between `<<<BEGIN ...>>>` markers is
data, output format (`{"new_text": "..."}` in a fenced json block), and preservation rules (keep
required placeholders and the prompt's purpose; make the smallest change that addresses the
failures; generalize, do not memorize example answers). User message sections: PARAMETER
DESCRIPTION, CURRENT TEXT, FAILURES (up to k examples: input, output, feedback), and optionally
LESSONS (a short rolling list of prior accepted edits' one-line summaries, to avoid re-proposing them).

The "generalize, do not memorize" instruction is a courtesy, not a defense: the validation and test
splits are the defense.

### 4.6 Confirmation and the generalization verdict

Judge noise is the main source of false wins. After the loop the best candidate's validation
scorecards are recomputed with judge caching disabled (fresh draws; optionally `samples(n)`), and that
number replaces the (possibly lucky) selection-time score. The verdict then compares test-split
seed-vs-best using the existing `PairwiseJudge`/`PromptComparison` machinery for a win-rate and
Wilson interval, plus the score gap and guardrail checks in SPEC §4.6.

### 4.7 Persistence and resume

Checkpoint = JSON of: config fingerprint (hash of seed text, scenario ids/inputs, criterion names,
split seed), pool (candidates + per-scenario validation scores), round trace, RNG state, budget
counters. Written to a temp file and moved atomically after each round, so an interrupted process
loses at most the in-flight round. Resume validates the fingerprint before touching anything.

### 4.8 Concurrency

`Evaluator` uses a fixed pool sized by `parallelism`; rollouts within one evaluation are independent.
Decisions (selection, gating, pool mutation) happen on the calling thread in order, so the trace is
deterministic given deterministic rollouts regardless of `parallelism` (results are collected by
index, not completion order).

### 4.9 Failure handling

| Failure | Handling |
|---|---|
| Rollout throws | Score 0, feedback = exception message; counts toward budget |
| Judge call fails / unparseable | `JudgeEvaluationException` from the criterion → scenario scored 0 with feedback, logged; run continues (consecutive-failure breaker: 5 in a row ⇒ `FAILED` to avoid burning budget on an outage) |
| Rewriter output unparseable | One repair retry, then `REWRITE_FAILED` round |
| Candidate violates constraints | `REJECTED_CONSTRAINT` round, no rollouts |
| Duplicate candidate | `REJECTED_DUPLICATE`, no rollouts |
| Process killed | Resume from checkpoint |
| Budget hit mid-evaluation | Stop before the next rollout; the partially evaluated child is discarded |

## 5. Key decisions and alternatives

| Decision | Chosen | Alternatives considered | Why |
|---|---|---|---|
| Algorithm | GEPA-style reflective + Pareto | MIPROv2 (Bayesian, few-shot bootstrapping); TextGrad (per-step gradient calls); plain hill-climbing | Uses eval4j's *feedback text*, sample-efficient, keeps diversity. MIPRO/TextGrad are extensions, not v1. |
| Objective | Scorecards from existing criteria | New metric layer | Reuse; eval4j's conditions already return score + reason |
| Anti-gaming | Splits + guardrails + confirmation + restricted feedback | Trust the judge; judge panel | Structural defenses are cheap and testable. A judge panel is an extension. |
| Data splits | Train / Validation / Test, test sealed | Train/validation only | Selection on validation is itself a form of overfitting; a sealed test is the only honest generalization check |
| Frontier weighting | Proportional to scenarios won | Uniform over frontier; top-k by mean | Matches GEPA; favors broadly strong specialists |
| Output | Patch + report | Auto-apply / write registry | A human should review generated prompts; auto-apply hides reward hacking |
| Placement | Package in eval4j, one-way dep | New module | See §3 |
| Randomness | Seeded `SplittableRandom` per concern (split, batch, select) | Global `Random` | Reproducibility; independent streams so adding a draw in one place doesn't shift another |
| Guardrail semantics | Score 0 on violation | Subtract penalty | A penalty can be outweighed by gains elsewhere; a violation should never be "worth it" |
| Cache | Reuse `JudgeCache`; optional output cache | None | Cost control and resumability |

## 6. Risks

| Risk | Likelihood / impact | Mitigation |
|---|---|---|
| Reward hacking (prompt games the judge) | High / High | Sealed test split, deterministic guardrails, confirmation run, second-judge check in the efficacy study, human diff review |
| Overfitting to a small dataset | High / Med | Minimum split sizes, `maxOverfitGap`, generalization verdict, docs |
| Judge noise causes false acceptance | Med / Med | Strict gate margin option, `samples(n)`, confirmation run |
| Runaway cost | Med / High | Hard budgets, `estimate()`, consecutive-failure breaker |
| System under test performs real side effects | Med / High | `acknowledgeSideEffects()` required; docs demand test doubles |
| Rewriter prompt injection via outputs | Low–Med / Med | Delimiting + sanitizing (existing), constraints, generalization checks |
| Rewriter degenerates prompt (huge, vague) | Med / Med | Max length, required substrings, tie-break toward shorter |
| Complexity creep | Med / Med | Strict non-goals; extensions behind interfaces |

## 7. Mapping to existing code

| Need | Existing piece |
|---|---|
| Judge score + reason | `LlmJudgeCondition.evaluate`, `RagContextCondition.evaluate`, `ConversationJudgeCondition.evaluate` → `JudgeVerdict` |
| Caching, sampling, delimiting | `JudgeCalls`, `JudgeCache`, `FileSystemJudgeCache`, `JudgePrompt.sanitize` |
| Scenarios and YAML | `EvalScenario`, `EvalScenarios`; `DatasetSynthesizer` to grow the dataset |
| Head-to-head verdict | `PairwiseJudge`, `PromptComparison` (+ Wilson interval) |
| Atomic files | `AtomicFiles` (report package; may need visibility widening or a small copy) |
| Metrics into reports/baselines | `EvalRecorder`, `EvalReportExtension`, `@EvalBaseline` |
| Rewriter/agent models | `ai-agent4j` `LLMClient`, `ReActAgent`, providers |

New code: `Criterion`/`Scorecard` (criteria package), and the `optimize` package.

## 8. Milestones

| M | Deliverable | Exit |
|---|---|---|
| M1 | `criteria` package: `Criterion`, `Criteria` adapters, `Scorecard`, scoring rule | R1, R2 |
| M2 | Splits, budget tracker, evaluator, pool + Pareto selector (no LLM) | R3, R5, R7 |
| M3 | Rewriter + constraints + the loop with stub LLMs; stopping reasons | R4, R6, R8, R12, R13, R15 |
| M4 | Confirmation, generalization verdict, comparison on test | R9 |
| M5 | Trace, report, patch, checkpoint/resume, determinism | R10, R11, R14 |
| M6 | Live integration and the efficacy study; docs | Acceptance §7 |

## 9. Extension points

- **Merge / crossover** between frontier candidates (GEPA's system-aware merge) via a `CandidateCombiner`.
- **Other parameter kinds**: tool descriptions and skill files are just more named text parameters;
  Loom scripts would need a structured mutator and a syntax check (`weave check`) as a constraint.
- **MIPRO-style demonstrations** as a second parameter type.
- **Judge panels / second judge for confirmation** via a `ConfirmationStrategy` interface.
- **Loom as governor**: run the optimizer as a workflow step with budgets across days and a
  `human_prompt` before applying the patch — no change to the optimizer needed.
- **Alternative rewriters** (e.g., one that proposes several candidates per round) behind `Rewriter`.

## 10. Documentation plan

A new guide `docs/OPTIMIZER.md` (usage, the three splits and why, the honest limits, cost
estimation), an entry in the docs index, a README feature blurb that repeats the caveat that judged
optimization can be gamed, and the calibration-style efficacy results published alongside
`VERIFICATION-RESULTS.md`.
