# eval4j Optimizer — Specification

Status: Draft · Scope: new package `io.github.llm4j.eval.optimize` in `eval4j`
Companions: [DESIGN](DESIGN.md) · [TEST-STRATEGY](TEST-STRATEGY.md) · [VERIFICATION-PLAN](VERIFICATION-PLAN.md)

An autonomous, budget-bounded loop that improves the **text parameters of an AI system** (first: an
agent's system prompt) until eval4j's own metrics say it is good enough — and that hands the result
to a human as a reviewable patch.

It is modelled on **GEPA** (reflective prompt evolution with Pareto candidate selection); see
[DESIGN §2](DESIGN.md#2-background-what-gepa-does) for the background and how this differs.

## 1. Goals and non-goals

### Goals
1. Given a seed prompt, a dataset of scenarios and eval4j criteria, search for a prompt that scores
   higher, **without a human in the inner loop**.
2. Use eval4j's existing signals as the objective: deterministic assertions, judge conditions, RAG and
   conversation metrics, and their **reasons** as the feedback that drives rewriting.
3. **Resist gaming.** Optimize on one data split, select on another, and verify on a third that the
   optimizer never sees. Deterministic checks act as hard guardrails.
4. **Bounded and observable.** Hard budgets, deterministic stopping rules, a full trace, and
   checkpoint/resume.
5. **Reviewable output.** Emit a patch and a report; never change the user's files unless asked.
6. Feel like the rest of eval4j: plain Java, builders and records, JUnit-friendly, no runner or CLI
   required.

### Non-goals (v1)
- Weight tuning, RL, or fine-tuning.
- Automatic deployment or auto-merge of the result.
- Optimizing Loom workflows, few-shot demonstration bootstrapping (MIPRO-style), or Bayesian search.
  Listed as [extensions](DESIGN.md#9-extension-points).
- A UI. Output is JSON, Markdown and a diff; the existing HTML report may embed a summary.

## 2. Concepts

| Term | Meaning |
|---|---|
| **Parameter** | A named piece of text the optimizer may change, e.g. `system-prompt`. A system has one or more. |
| **Candidate** | An immutable assignment of text to every parameter, plus its id, parent id and origin. |
| **Scenario** | An `EvalScenario` (existing record): input plus optional expectations. |
| **Criterion** | One way of scoring an output for a scenario: a deterministic check or a judged metric. |
| **Scorecard** | The result of scoring one candidate on one scenario: score, per-criterion results, guardrail status and feedback text. |
| **Evaluation** | Running a candidate on a set of scenarios and producing one Scorecard each. |
| **Rollout** | One scenario run for one candidate. The unit of budget. |
| **Guardrail** | A criterion that must pass regardless of score. A candidate that violates one scores 0 on that scenario. |
| **Frontier** | The set of pool candidates that are best on at least one validation scenario. |
| **Train / Validation / Test** | Scenario splits used for reflection, selection, and final verification respectively. |

## 3. Public API

### 3.1 Criteria and scoring (new, reusable outside the optimizer)

```java
public interface Criterion {
    String name();
    CriterionResult score(EvalScenario scenario, Object output);   // AgentResult, LLMResponse or String
}

public record CriterionResult(double score, boolean passed, String feedback) {}   // score in [0,1]

public final class Criteria {
    static Criterion judged(LlmJudgeCondition condition);          // uses evaluate(): score + reason
    static Criterion judged(RagContextCondition condition);
    static Criterion judged(ConversationJudgeCondition condition);
    static Criterion assertion(String name, Consumer<Object> check);   // AssertionError -> failed, message = feedback
    static Criterion guardrail(Criterion inner);                       // failure forces scenario score 0
    static Criterion guardrail(String name, Consumer<Object> check);
    static Criterion perScenario(Function<EvalScenario, Criterion> factory);  // e.g. correctness(scenario.expectedOutput())
}

public record Scorecard(String scenario, double score, boolean guardrailViolated,
                        List<CriterionResult> results, String output, String feedback) {}
```

Scoring rule for one scenario: if any guardrail failed → `score = 0`, `guardrailViolated = true`;
otherwise `score` = mean of the non-guardrail criteria scores (weights optional via
`Criteria.weighted(criterion, weight)`). `feedback` concatenates failed-assertion messages and judge
reasons, each truncated (default 400 chars).

`Criterion` and `Scorecard` are useful on their own: they are the "structured scoring result" that
today's conditions and assertions do not expose as data.

### 3.2 System under optimization

```java
@FunctionalInterface
public interface SystemUnderTest {
    Object run(Candidate candidate, EvalScenario scenario);  // typically builds an agent from candidate text
}
```

Example: `(c, s) -> ReActAgent.builder().llmClient(client).systemPrompt(c.get("system-prompt"))...build().run(s.input())`.

### 3.3 Optimizer

```java
OptimizationResult result = PromptOptimizer.builder()
    .seed(Candidate.of("system-prompt", currentPrompt))
    .system(system)                                          // a SystemUnderTest
    .criteria(List.of(correctness, relevancy, Criteria.guardrail("no-pii", noPii)))
    .scenarios(EvalScenarios.fromYamlResource("scenarios.yaml"))
    .split(Split.ratios(0.5, 0.3, 0.2).seed(42))             // train / validation / test
    .rewriter(rewriterClient)                                // an LLMClient; should differ from the judge
    .constraints(PromptConstraints.builder().maxChars(4000).mustContain("{{input}}").build())
    .budget(Budget.builder().maxRollouts(600).maxRounds(30).maxDuration(Duration.ofMinutes(30))
            .maxLlmCalls(3000).build())
    .target(Target.validationMean(0.9).andTestNotBelowSeed())
    .patience(6)
    .parallelism(4)
    .cache(FileSystemJudgeCache.at(dir))
    .checkpointDir(Path.of("target/optimizer"))
    .acknowledgeSideEffects()                                 // required: the system is executed many times
    .listener(OptimizerListener.console())
    .build()
    .run();
```

`OptimizationResult`:
```java
record OptimizationResult(Candidate seed, Candidate best, StopReason stopReason,
        ScoreSummary seedScores, ScoreSummary bestScores,    // train / validation / test means + per-scenario
        boolean generalized,                                  // see §4.6
        Comparison seedVsBestOnTest,                          // PromptComparison-style win rates + CI
        Cost cost, List<Round> trace) {
    PromptPatch toPatch();                                    // unified diff + files; writes nothing by itself
    void writeReport(Path dir);                               // optimizer-trace.json + optimizer-report.md
}
```

`PromptPatch.applyTo(Path)` is an explicit call by the user; the optimizer itself never writes to
source files.

### 3.4 Cost estimation

`optimizer.estimate()` returns the **upper bound** of rollouts, judge calls and rewriter calls
implied by the configured budget, before anything runs.

## 4. Behaviour

### 4.1 Splits
- `Split.ratios(train, validation, test)` shuffles scenarios with the seed and partitions them.
  Ratios must sum to 1; each split must have at least `minSplitSize` scenarios (default 5) or
  `build()` throws with a message stating the counts and how to fix them.
- `Split.explicit(train, validation, test)` for datasets that are already separated.
- Test may be omitted only with `allowNoTest()`; the result is then never `generalized` and the report
  says so.

### 4.2 The loop

```
pool ← [seed]; score seed on validation (and record per-scenario scores)
round ← 0
while not stopped:
    parent ← select(pool)                                   # §4.3
    param  ← next parameter (round-robin)
    batch  ← sample(train, batchSize, rng)                  # default 4
    cardsP ← evaluate(parent, batch)                        # rollouts on train batch
    child  ← rewrite(parent, param, failing(cardsP))        # §4.4
    if invalid(child): record REJECTED_CONSTRAINT; continue
    cardsC ← evaluate(child, batch)
    if mean(cardsC) > mean(cardsP) + gateMargin:            # default margin 0.0 (strictly better)
        evaluate child on validation; add to pool
    record round
    check stop conditions                                    # §4.5
confirm best (§4.6); evaluate seed and best on test; build result
```

If every scorecard in `cardsP` already scores ≥ `perfectThreshold` (default 0.99) the batch is skipped
(nothing to learn from) and another is sampled; this counts as a round but consumes no rewriter call.

### 4.3 Candidate selection (Pareto)
1. For each validation scenario find the maximum score across the pool; a candidate is *best on* a
   scenario if it scores within `1e-9` of that maximum.
2. Remove candidates that are dominated (never best where another candidate is also best, and not
   better anywhere).
3. Select a parent with probability proportional to the number of scenarios it is best on.
4. With an empty or singleton frontier, select the seed / the only member.

### 4.4 Rewriting
The rewriter is an `LLMClient` given a meta-prompt containing: the parameter's purpose (optional
description), its current text, and up to `maxFailuresInPrompt` (default 4) train examples with the
input, the output and the scorecard feedback. It must return the complete new text in a fenced JSON
block. All example content is passed as **delimited untrusted data** (existing `JudgeCalls.delimited`
machinery). The rewriter **never sees validation or test scenarios**, their outputs, or their
feedback (restricted feedback). Unparseable output is retried once with a repair message, then the
round is recorded as `REWRITE_FAILED`.

Candidate constraints (`PromptConstraints`): max length, required substrings (e.g. template
placeholders), forbidden substrings, and an optional custom predicate. A violating candidate is
rejected without spending rollouts.

### 4.5 Stopping
Checked before each rollout and after each round. The first condition met wins and is reported as the
`StopReason`:

| StopReason | Condition |
|---|---|
| `TARGET_REACHED` | Best validation mean ≥ target **and** no guardrail violations on validation |
| `MAX_ROLLOUTS` / `MAX_LLM_CALLS` / `MAX_ROUNDS` / `MAX_DURATION` | Budget exhausted. Budgets are hard caps: no rollout starts once one would exceed a cap. |
| `NO_PROGRESS` | `patience` consecutive rounds with no new frontier member |
| `CANCELLED` | `Thread.interrupt()` or a cancel token |
| `FAILED` | Unrecoverable error (e.g. the seed cannot be scored) |

`NO_PROGRESS` and budget stops still return the best candidate found; `generalized` will say whether
it is trustworthy.

### 4.6 Confirmation and generalization
After the loop:
1. **Confirmation run.** The best candidate is re-scored on validation with judge caching disabled
   (fresh draws) to discount lucky noise. The score used from here on is the confirmation score.
2. **Test.** Seed and best are scored on the sealed test split.
3. `generalized = true` only if all hold: best test mean ≥ seed test mean + `minTestGain` (default
   0.02); no guardrail violations on test; best validation confirmation is within `maxOverfitGap`
   (default 0.10) of its test mean; and the head-to-head seed-vs-best comparison on test does not favor
   the seed.
4. The result always includes the seed-vs-best comparison and both scores, so a reader can see
   overfitting directly.

### 4.7 Determinism
A `seed` fixes splitting, batch sampling and parent selection. LLM outputs are not deterministic;
the trace records every rewriter and judge input/output so a run can be inspected and replayed from
the cache. Given identical LLM responses (e.g. stubs), two runs with the same seed produce identical
traces.

### 4.8 Checkpoint and resume
With `checkpointDir`, state (pool, scorecards, RNG state, budget counters, round trace) is written
atomically after every round. `run()` on a directory containing a compatible checkpoint resumes
from it; an incompatible one (different seed candidate, scenarios or criteria fingerprint) fails
fast with an explanation rather than silently starting over.

### 4.9 Concurrency
Rollouts within an evaluation run in parallel up to `parallelism`. Everything the optimizer owns is
thread-safe; user-supplied `SystemUnderTest`, `Criterion` and clients must be, and the docs say so.

### 4.10 Safety
- **Side effects.** The system is executed hundreds of times. `build()` throws unless
  `acknowledgeSideEffects()` is called, and the docs require test doubles for tools that write,
  send or charge.
- **Judge/rewriter independence.** If the rewriter and judge share a client instance or the same
  configured identifier, `build()` logs a warning (and the report repeats it).
- **Untrusted text.** All scenario, output and feedback text reaching the rewriter is delimited and
  sanitized; the rewriter system prompt states it is data, never instructions.
- **No secret leakage.** Traces store prompts, outputs and feedback, never client configuration or
  API keys. `Redactor` hook lets callers scrub outputs before they are persisted.
- **Least surprise.** No file outside `checkpointDir` / the chosen report directory is written.

### 4.11 Reporting
`optimizer-trace.json` (full machine-readable trace) and `optimizer-report.md`: seed vs best text
(diff), per-round table (parent, param, batch scores before/after, gate result, validation mean if
evaluated), frontier evolution, budget used, stop reason, the generalization verdict with its
reasons, and warnings. If `EvalRecorder` is active, each round's best validation mean is recorded as
metric `Optimizer: best validation` so the existing HTML report and baselines can show it.

## 5. Errors
Each condition below throws `OptimizerConfigurationException` from `build()` (fail fast, with counts
and the fix) unless noted: missing required setting; splits too small; ratios not summing to 1;
`acknowledgeSideEffects()` absent; zero criteria; a seed that violates its own constraints; an
unsupported checkpoint. Runtime problems (a rewrite that fails to parse, a rollout that throws) never
abort the run: they are recorded on the round; a rollout exception counts as score 0 with the
exception message as feedback, and if the *seed* cannot be scored at all the run ends with `FAILED`.

## 6. Requirements summary (testable)

| ID | Requirement |
|---|---|
| R1 | `Criterion`/`Scorecard` expose score, reasons and guardrail status for any eval4j condition or assertion |
| R2 | Guardrail failure forces the scenario score to 0 regardless of other criteria |
| R3 | Splits are seed-deterministic, disjoint, and validated for size |
| R4 | The rewriter prompt contains only train-split data; validation and test data never appear |
| R5 | Pareto selection follows §4.3 exactly (best-on counts, dominated removal, weighted choice) |
| R6 | A child is evaluated on validation only if it beats its parent on the train batch |
| R7 | Budgets are hard: no rollout, rewriter call or judge call starts past a cap |
| R8 | Each StopReason in §4.5 is reachable and reported correctly |
| R9 | The confirmation run uses fresh judge draws; `generalized` follows §4.6 |
| R10 | Same seed + same LLM responses ⇒ identical trace |
| R11 | Kill and resume from a checkpoint reproduces the uninterrupted result; incompatible checkpoints fail fast |
| R12 | Constraint violations are rejected before any rollout |
| R13 | Untrusted content cannot forge delimiters in the rewriter prompt |
| R14 | Nothing is written outside the checkpoint/report directories; no secrets in traces |
| R15 | On a simulated system with a known optimum, the optimizer reaches the target within budget (see test strategy) |

## 7. Acceptance criteria (release)
1. R1–R15 covered by named automated tests; coverage gate met for the new package.
2. The simulated-system suite (deterministic) shows: convergence, budget adherence, and detection of
   overfitting and reward hacking by the split/guardrail/confirmation design.
3. A live run with a real judge and rewriter on a small dataset improves the **held-out test** mean
   over the seed with `generalized = true`, and a second judge model that never took part in the run
   agrees the best prompt is not worse (see the efficacy study in the test strategy).
4. README/docs section added, with the honest limits (below) stated.
5. No public-API change to existing eval4j classes other than additions.

## 8. Known limits (to be stated in the docs)
- Optimizing against an LLM judge can raise the judge's score without improving real quality;
  splits, guardrails and confirmation reduce but cannot eliminate this. Review the diff.
- Small datasets give noisy selection; the minimum split sizes are a floor, not a recommendation.
- Text-only: no few-shot selection, no structural changes to the agent.
- Cost scales with `rollouts × (agent + judge calls)`; use `estimate()` first.

## 9. Open questions
1. Package inside `eval4j` (proposed) versus a separate module. Decision criteria and the fallback are
   in [DESIGN §3](DESIGN.md#3-placement-and-dependencies).
2. Should `Criteria` live outside the `optimize` package (e.g. `io.github.llm4j.eval.criteria`) since it
   is independently useful? Proposed: yes.
3. Can `PromptRegistry` (ai-agent4j) write new prompt versions? If so, `PromptPatch` could target it;
   otherwise files/diff only.
4. Merge/crossover between candidates (GEPA's system-aware merge) — v1.1?
