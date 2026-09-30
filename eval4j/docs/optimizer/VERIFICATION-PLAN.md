# eval4j Optimizer — Verification Plan

Companions: [SPEC](SPEC.md) · [DESIGN](DESIGN.md) · [TEST-STRATEGY](TEST-STRATEGY.md)

The [test strategy](TEST-STRATEGY.md) defines how the automated tests are built. This plan defines how
we **prove the optimizer does what we claim, and that its verdicts can be trusted**, which mostly
cannot be shown by unit tests: whether real optimization improves real quality, whether the
overfitting and gaming defenses actually catch real failures, and whether a person can use it safely.

## 1. What we are claiming (and therefore must verify)

| ID | Claim | Why it needs more than unit tests |
|---|---|---|
| C1 | **It works:** it finds prompts that score higher on held-out data | Needs real models and real tasks |
| C2 | **The gain is real, not gaming:** improvements are confirmed by measures the optimizer never saw | Reward hacking only shows up against an independent measure |
| C3 | **`generalized` is trustworthy:** it flags overfit and hacked results and rarely flags good ones | A verdict is itself a classifier and needs its error rates measured |
| C4 | **It is bounded:** budgets and stop rules hold under real conditions (failures, latency, parallelism) | Real providers rate-limit, time out and fail |
| C5 | **It is safe to run:** no unintended writes, no secret leakage, injection is contained, side-effect acknowledgement works | Needs adversarial and end-to-end checks |
| C6 | **Its output is reviewable:** the patch and report let a person judge the change quickly and correctly | Needs human use |
| C7 | **It is usable:** a Java developer can adopt it from the docs alone | Needs a person who did not build it |
| C8 | **It is compatible:** nothing existing in eval4j changed behaviour | Regression check |
| C9 | **It is affordable and predictable:** cost matches `estimate()` and is reasonable | Needs measurement |

## 2. Verification methods

| Method | Used for | Evidence |
|---|---|---|
| **A. Automated tests** (unit, simulation, architecture) | C4 (mechanics), C5 (mechanics), C8 | CI runs, coverage, traceability table |
| **B. Efficacy study** | C1, C2, C9 | Study report with per-task, per-seed results |
| **C. Verdict validation** (planted successes/failures) | C3 | Confusion matrix for `generalized` |
| **D. Live run under fault injection** | C4 | Run logs with induced failures |
| **E. Blind human review** | C2, C6 | Reviewer judgments |
| **F. Security review and adversarial runs** | C5 | Checklist, run transcripts |
| **G. Fresh-adopter trial** | C7 | Timed notes |
| **H. Compatibility and API review** | C8 | Diff report, unchanged suite |

Every claim has at least one method; the mapping is in §9.

## 3. Environments

| Env | Setup | Used by |
|---|---|---|
| **E1 hermetic** | `mvn -pl eval4j -am test`, no network; JDK 17 and latest LTS | A, H |
| **E2 variants** | E1 with `-Duser.language=tr -Duser.country=TR`, non-UTC timezone, `-Dfile.encoding=ISO-8859-1`, JUnit parallel enabled | A |
| **E3 live** | `-P integration-tests` with a configured judge and rewriter (env-selected provider), a **hard spend cap** set on the provider account, `FileSystemJudgeCache` enabled | B, C, D, F |
| **E4 sample app** | A new Maven project outside the module consuming the built artifact: a small RAG-QA agent and a small extraction agent with sandboxed tools | B, E, G |
| **E5 fault harness** | A provider wrapper that injects latency, 429s, timeouts, malformed JSON and outages on a schedule | D |

**Secrets:** keys come from environment variables only, are never written to files, traces or
reports (verified by a scan of all produced artifacts for the key prefix), and any key exposed during
the work is rotated afterwards.

## 4. Efficacy study (C1, C2, C9)

### 4.1 Design
Tasks are chosen so quality has **ground truth that does not depend on an LLM judge**:

| Task | Ground truth | Why chosen |
|---|---|---|
| T1 Structured extraction (fields from short texts) | Exact/field-level match | Objective, prompt-sensitive |
| T2 Classification with a rubric (e.g., support-ticket routing) | Labels | Objective; prompts often under-specify edge cases |
| T3 Grounded RAG-QA over a fixed corpus | Known answers + supporting passages | Exercises retrieval-aware judged metrics and faithfulness |
| T4 (optional) Tone/format-constrained rewriting | Deterministic format checks + a rubric | Where judge-only optimization is riskiest (gaming) |

Per task: ≥ 60 scenarios (train/validation/test 50/30/20), a deliberately mediocre **seed prompt**,
and criteria = judged metric(s) **plus** deterministic guardrails where possible.

Three judges are involved and their roles are strict:
- **J1** scores during optimization.
- **R** is the rewriter model (different from J1).
- **J2** is a *second, different* judge model that **never** takes part in the run; it and the
  ground-truth metric are used only afterwards to evaluate the seed and best prompts.

### 4.2 Protocol
1. For each task and each of **3 seeds**: run the optimizer with fixed budgets (recorded).
2. Score seed and best on the sealed test split with: (a) J1, (b) J2, (c) ground truth.
3. Record: gains on (a)–(c), stop reason, rounds, rollouts, LLM calls, tokens/cost, wall time, the
   `generalized` verdict and its reasons, and the final diff.
4. Also run two **control conditions** on one task: (i) *no-optimization baseline* (re-sample the
   seed with fresh judge draws, to measure how large "gain" from noise alone is), and (ii) *random
   edits* (an unguided rewriter) to show the reflective loop beats chance.
5. Repeat T3 with a **weak, lenient J1** on purpose to show the design's response when the judge is
   exploitable (expected: `generalized = false` or blocked by guardrails).

### 4.3 Acceptance thresholds (initial; tune once with rationale recorded, then freeze)
- Ground-truth test gain > 0 on ≥ 2 of 3 tasks in ≥ 2 of 3 seeds, and the mean gain exceeds the
  noise-only control's gain by a margin larger than its observed spread.
- No case where J1 gain ≥ 0.10 but ground-truth gain ≤ 0 **and** `generalized = true`.
- Reflective loop beats random edits on ground-truth gain on the primary task.
- `estimate()` upper bound ≥ actual usage in every run; median actual ≤ 80% of estimate when the
  loop stops early.
- Cost per run recorded and reported honestly, with a per-task summary of cost per point of gain.

A miss is a finding, not a failure to hide: it triggers a design change (stricter confirmation,
larger margins, different defaults) and a re-run, or the feature ships labelled experimental with the
measured numbers disclosed.

### 4.4 Limits to state alongside the results
Small, author-selected tasks; few seeds; provider drift over time; results are for the models used.

## 5. Verdict validation (C3)

`generalized` is a classifier: "this optimized prompt is a real improvement." Measure its errors.

### 5.1 Labelled runs
Build a set of optimizer runs whose true status is known **by construction** (using the simulated
system) or **by ground truth** (using the efficacy tasks):
- *Genuine improvements* (true positive cases): runs where ground-truth test gain is clearly positive.
- *Overfit runs*: rewriter forced to memorize train answers.
- *Hacked runs*: lenient/exploitable J1 with a verbose-flattery rewriter.
- *Noise wins*: mediocre candidates that win once due to judge noise.
- *Neutral runs*: no real change.

### 5.2 Metrics
- Recall on genuine improvements (target ≥ 0.8) and specificity on overfit/hacked/noise/neutral runs
  (target ≥ 0.9), with counts reported (the sets are small; report intervals).
- For each false positive or false negative, a written root cause and either a fix or a documented
  limit.
- Sensitivity of the verdict to its parameters (`minTestGain`, `maxOverfitGap`, confirmation
  `samples`): a small table showing how error rates trade off.

## 6. Live robustness under faults (C4)

Using E5 on a real small task (cost-capped):

| Fault | Expected behaviour |
|---|---|
| Rate limits (429) on the judge | Backoff via the provider layer; run continues or ends `FAILED` via the breaker; no budget overshoot |
| Timeouts / latency spikes | `MAX_DURATION` respected within one rollout's latency; partial evaluation discarded |
| Malformed JSON from rewriter (10–30% of calls) | Repair retry, then `REWRITE_FAILED` rounds; progress still possible |
| Judge returns garbage for every call from round k | Breaker trips within 5 consecutive failures; spend stops |
| Process killed (`kill -9`) mid-round, then resumed | Resume reproduces the uninterrupted result; no rollout repeated (verified by call counts) |
| Concurrent runs sharing a cache directory | No corruption; each run's trace intact |
| `parallelism` 1, 4, 16 | Identical traces given identical responses; caps never exceeded |

Evidence: run logs, call-count tables, and the final traces.

## 7. Blind human review (C2, C6)

Two reviewers who did not build the feature review results **without knowing which prompt is which**:
1. **Output quality.** For 20 test scenarios per task, each reviewer sees outputs from the seed and
   the best prompt in random order and picks the better one (or tie). Compare with J1, J2 and ground
   truth. Report agreement and any systematic disagreement (a sign of gaming).
2. **Diff review.** Reviewers read the patch and report and answer: What changed? Is it plausible and
   safe to ship? Did it add anything suspicious (memorized answers, flattery, instructions to the
   judge, verbosity)? Time to a decision is recorded.
3. **Report usability.** Could they find, without help, the stop reason, budget used, whether it
   generalized and why?

Success: reviewers' preferences agree with ground truth on ≥ 75% of decided items; any hacked or
overfit run is spotted from the diff/report by at least one reviewer; a decision on a run takes under
10 minutes.

## 8. Security, safety and compatibility

### 8.1 Security and safety (C5)
Checklist, reviewed by someone other than the author:
- [ ] `build()` fails without `acknowledgeSideEffects()`; docs tell users to use test doubles for
      side-effecting tools.
- [ ] Nothing is written outside the checkpoint and report directories (before/after directory
      snapshots on real runs).
- [ ] No API keys or client configuration in traces, reports, patches, checkpoints or logs (scan every
      artifact from every live run for key prefixes and known secret strings).
- [ ] Untrusted text (scenario inputs, system outputs, judge feedback) is delimited and sanitized in
      the rewriter prompt; run S11-style attacks live against a real rewriter and inspect the
      candidates produced.
- [ ] Candidate constraints stop unbounded growth and enforce required placeholders.
- [ ] Spend caps: the provider-side cap and the optimizer's own caps both exist and the smaller one
      binds.
- [ ] Checkpoint files contain prompts and outputs; the docs warn about PII and the `Redactor` hook
      is tested.

### 8.2 Adversarial live runs
- A system whose outputs contain instructions aimed at the rewriter/judge.
- A rewriter told to maximize J1's score by any means (exploit attempt); confirm the sealed test,
  guardrails and constraints contain it.
- A dataset with duplicated train/test items (leak) — document what the design does and does not
  catch.

### 8.3 Compatibility (C8)
- Existing eval4j suite passes unmodified.
- API diff (japicmp or careful review): additions only; no existing signature changed.
- `criteria` and `optimize` packages respect the one-way dependency rule (automated).
- Build on JDK 17 and the latest LTS; run E2 variants.

## 9. Usability trial (C7)

A Java developer who has not seen the spec is given only the README and `docs/OPTIMIZER.md` and asked,
in an empty Maven project with a provided toy agent and dataset, to:
1. Run a cost `estimate()` and interpret it (≤ 10 min).
2. Optimize a prompt with a budget and read the report (≤ 30 min).
3. Decide, from the patch, whether to apply it, and apply it (≤ 15 min).
4. Add a guardrail criterion and observe its effect (≤ 20 min).

Record time, confusion points, every place they left the docs, unhelpful error messages. Success: all
tasks completed unaided within the limits; each confusion becomes a doc or message fix that is
re-verified.

## 10. Requirement-to-verification traceability

| Claim / requirement | Methods | Evidence |
|---|---|---|
| C1 improves held-out quality | B | efficacy report |
| C2 gain confirmed independently | B, E | J2 + ground truth + blind review |
| C3 verdict trustworthy | C | confusion matrix |
| C4 bounded, robust | A (R7, R8), D | tests + fault-run logs |
| C5 safe | A (R13, R14), F | checklist + artifact scans |
| C6 reviewable output | E | reviewer timings and accuracy |
| C7 usable | G | trial notes |
| C8 compatible | A, H | unchanged suite, API diff |
| C9 affordable, predictable | B, D | cost tables, estimate vs actual |
| SPEC R1–R15 | A | [test strategy traceability](TEST-STRATEGY.md#8-traceability) |

## 11. Defects, exit criteria and sign-off

**Severity.** S1: wrong verdict presented as trustworthy, budget or spend exceeded, data/secret
leakage, unintended writes, breaking change. S2: unusable in a documented scenario, misleading report,
flaky test. S3: cosmetic or wording.

**Per-milestone exit** (DESIGN §8): its requirements covered by passing named tests; no open S1/S2;
coverage gates met.

**Release exit** (all required):
1. All milestones done; no open S1/S2.
2. Efficacy study executed; thresholds in §4.3 met, **or** the feature is labelled experimental with
   the measured numbers disclosed.
3. Verdict validation (§5) meets targets or its limits are documented in the user docs.
4. Fault-injection runs (§6) all behave as specified.
5. Blind review (§7) and usability trial (§9) pass.
6. Security checklist (§8.1) complete with evidence.
7. Environment variants (E2) green; suite unmodified; API diff additive.
8. Evidence bundle committed under `docs/verification-results/<date>/optimizer/`.

**Regression rule.** Any S1/S2 fix adds a failing-first test and re-runs the affected verification
step plus the full E1/E2 suite.

## 12. Roles, schedule, evidence

| Role | Responsibility |
|---|---|
| Implementer | Automated tests, harnesses (fault wrapper, simulations), fixes |
| Independent verifier | Runs §4–§8 without relying on the implementer's setup; owns the security checklist |
| Reviewers (2) | Blind review (§7) |
| Fresh adopter | Usability trial (§9) |

Sequence: milestones M1→M5 are verified by method A as they land; the efficacy study, verdict
validation and fault runs need the full system (after M5); blind review and the usability trial come
last, once the report and docs exist (M6). Cost: set a provider spend cap and a per-study budget
before starting, and record actual spend in the results.

**Evidence bundle:** CI links and coverage; simulation results; efficacy report (per task, seed,
control); verdict confusion matrix and parameter-sensitivity table; fault-run logs; blind-review
data and summary; security checklist with artifact-scan output; usability notes; API diff; cost table;
and a plain statement of what was **not** verified and why.
