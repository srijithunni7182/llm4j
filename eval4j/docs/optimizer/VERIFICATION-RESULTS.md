# eval4j Optimizer — Verification Results

Status of the [VERIFICATION-PLAN](VERIFICATION-PLAN.md). A first live **efficacy study** has been run
(below); blind human review, the fresh-adopter trial and an independent security review have not.
This file records exactly what has and hasn't been verified so nothing is over-claimed.

## Verified (automated, no live model)

| Plan item | Evidence |
|---|---|
| **C8 compatibility** | Existing suite passes unmodified (only additive changes: `AtomicFiles` and `PromptComparison.wilson` made public; new packages). Architecture tests enforce that nothing outside `optimize` references it and that `criteria` stands alone. |
| **C4 bounded (mechanics)** | Rollout caps never exceeded at 5 budget sizes and at parallelism 1/4/16 (counting system); every `StopReason` reachable and asserted (`TARGET_REACHED`, `MAX_ROLLOUTS`, `MAX_LLM_CALLS`, `MAX_ROUNDS`, `MAX_DURATION` via injected clock, `NO_PROGRESS`, `CANCELLED`, `FAILED` for an unscorable seed and for a judge outage via the failure breaker); `estimate()` bounds actual usage. |
| **C4 robustness (simulated faults)** | Flaky rewriter (garbage every 3rd call, exception every 5th) still converges; always-throwing rewriter ends `NO_PROGRESS` with the seed; kill-and-resume equals an uninterrupted run with no repeated rollouts; corrupt or mismatched checkpoint refused with a clear message; failed checkpoint write leaves the previous one intact. |
| **C5 safety (mechanics)** | `acknowledgeSideEffects()` required; only the checkpoint/report directories are written; parameter names cannot escape the patch directory; planted secrets are redacted from rewriter prompts, checkpoints, traces and reports; a client's `toString()` never appears in artifacts; forged `<<<END ...>>>` delimiters are neutralized in the rewriter prompt; the rewriter never sees validation/test data (marker strings). |
| **Anti-gaming design (simulated)** | Memorizing train answers doesn't win selection; validation gains that don't transfer to the sealed test split are flagged not generalized; a length constraint rejects a verbosity hack before any rollout; a guardrail zeroes a hacked candidate; a lucky selection-time score is corrected by the confirmation run; without any defense the gullible judge *is* exploited (documented by test). |
| **Determinism** | Same seed and responses give identical traces at parallelism 1, 4, 8 and 16. |
| **Scale** | 500 scenarios × 50 rounds completes well within 60 s. |
| **Locale / timezone / encoding / parallel JUnit** | Full suite (403 tests) green under `-Duser.language=tr -Duser.country=TR`, a non-UTC timezone and ISO-8859-1, and with concurrent JUnit execution. |
| **Coverage** | JaCoCo gate met; new packages: `optimize` 97% line / 93% branch, `criteria` 99% / 89%. |
| **Patch validity** | The unified diff applies cleanly with `git apply` (test). |
| **Docs as tests** | `OptimizerDocsExampleTest` runs the usage in [OPTIMIZER.md](../OPTIMIZER.md). |

## Live efficacy study (Claude models; single task, author-made data)

Raw outputs: [`docs/verification-results/2026-10-01/`](../verification-results/2026-10-01/). Task: routing
support tickets into five categories under a labelling policy the seed doesn't state (refunds are
billing even when about shipping, praise and feature requests are "other", ...). Roles: system model and
optimization judge J1 = Claude Haiku 4.5; rewriter = Claude Sonnet 5.5; independent final judge J2 =
Claude Opus 5.5 (took no part in the run); ground truth = exact-match label, computed without any judge.
Guardrail: the reply must be exactly one category word. Budget 300-400 rollouts, 3 seeds, 60 scenarios.

| Run | Seed prompt | Split | Outcome |
|---|---|---|---|
| 1 | Strong (lists the categories) | random | Seeds 1 and 2 already met the 0.95 validation target, so **zero rounds ran** (test accuracy 0.75 and 0.92 shows how noisy 22-scenario validation is). Seed 3: test 0.83 → 1.00 (J1, J2 and ground truth agree), `generalized = true`, 7 rounds, 154 rollouts. |
| 2 | Weak (names no categories) | random | Test accuracy 0.42-0.50 → **1.00 on all 3 seeds** (J1, J2, ground truth agree), `generalized = true` each, 1 round / 86 rollouts. **Leaky:** both wordings of a ticket could be in train and test, which can inflate this. |
| 3 | Weak | **by base ticket (leak-free)** | See below. |

**Run 3 (the one to trust most)**

| seed | GT test: seed → best | J2 (Opus): seed → best | `generalized` | stop | rounds / rollouts |
|---|---|---|---|---|---|
| 1 | 0.17 → **1.00** | 0.21 → 1.00 | true | NO_PROGRESS | 6 / 106 |
| 2 | 0.33 → **0.83** | 0.40 → 0.83 | **false** ("validation 1.000 exceeds test 0.833 by more than 0.10: a sign of overfitting") | TARGET_REACHED | 1 / 86 |
| 3 | 0.33 → **1.00** | 0.42 → 1.00 | true | NO_PROGRESS | 6 / 106 |

Control, random edits (unguided generic sentences appended): 0.17 → 0.17, `generalized = false`.
Control, no optimization: identical scores on re-run (temperature 0 is deterministic), so it measured no
noise. Cost per optimization run: 86-154 rollouts (172-311 LLM calls), well under the budgets.

### Reading the results against plan §4.3

| Threshold | Result |
|---|---|
| Ground-truth test gain > 0 on ≥ 2 tasks in ≥ 2 of 3 seeds | **Not testable:** one task only. On that task, gain > 0 in 3/3 seeds (runs 2 and 3) and 1/3 (run 1, ceiling). |
| No run where J1 gain ≥ 0.10 but ground truth ≤ 0 is marked generalized | **Met** (no such run). |
| Reflective loop beats random edits | **Met** on this task (+0.83 vs 0.00). |
| `estimate()` ≥ actual usage | **Met** (rollouts used ≤ 154 of 300-400). |
| Independent judge and ground truth confirm J1 gains | **Met**: J1, Opus and ground truth moved together in every optimized run. |

### Findings

1. **It works on this task, and the gains survive a leak-free split** (0.17-0.33 → 0.83-1.00 by ground
   truth). The prompts it wrote enumerate the categories and encode the policy; no sign of the judge being
   gamed (the guardrail and an independent judge both agree).
2. **The `generalized` verdict was too conservative once.** Seed 2 improved by +0.50 on ground truth and
   was still marked not generalized, because validation (1.000) exceeded a 12-scenario test score (0.833)
   by more than the fixed 0.10 gap. With so few test scenarios, a 0.17 difference is two scenarios, i.e.
   noise. Verdict recall on genuine improvements across runs 1-3 is 6 of 7; on neutral runs (two
   ceiling seeds, the random-edit control) specificity is 3 of 3. **Follow-up:** make the overfit-gap
   tolerance depend on the split sizes (for example, widen it by the binomial sampling error) and
   re-verify; it has *not* been changed, to avoid tuning to this data.
3. **A validation-only target can stop an already-good-looking prompt too early.** In run 1 the seed hit
   0.95 on validation yet scored 0.75 on test. The optimizer correctly claimed no improvement, but users
   should use larger validation sets (or a stricter target); see the guide.
4. **Study design lesson:** random splits leaked near-duplicate tickets (run 2). Group related scenarios
   into the same split.

### Limits of this evidence
One synthetic task written by the module's author, three seeds, small splits (12-14 test scenarios),
models from one provider, and temperature 0 (so no noise measurement). It shows the approach can work and
that the safeguards behaved as designed; it does not establish general effectiveness. The **verdict
validation (C3)** has only these few real runs behind it, and the **blind human review (C2/C6)** and
**fresh-adopter trial (C7)** are still outstanding.

## Not yet verified

| Plan item | Status |
|---|---|
| **C1/C2/C9 efficacy study** on more tasks (extraction, grounded QA), more seeds, larger splits | Run once on a single task (see above); broader coverage still needed. |
| **C3 verdict validation** at scale (recall/specificity of `generalized`) | Only 7 real optimized runs and 3 neutral runs so far; one false negative found (see finding 2). |
| **C4 live faults** (real 429s, timeouts, mid-run kill) | Not run; simulated equivalents pass. (One real run hit no faults.) |
| **C6/C2 blind human review**, **C7 fresh-adopter trial** | Need people. Not done. |
| Independent security review; API-diff tool; JDK/OS matrix | Not done. |
