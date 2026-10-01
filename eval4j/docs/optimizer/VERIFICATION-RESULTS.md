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
   re-verify; it has *not* been changed, to avoid tuning to this data. *(Done afterwards: see "Second study" below.)*
3. **A validation-only target can stop an already-good-looking prompt too early.** In run 1 the seed hit
   0.95 on validation yet scored 0.75 on test. The optimizer correctly claimed no improvement, but users
   should use larger validation sets (or a stricter target); see the guide. *(Addressed afterwards: a
   run that stops on the target is no longer `generalized` unless the sealed test agrees.)*
4. **Study design lesson:** random splits leaked near-duplicate tickets (run 2). Group related scenarios
   into the same split.

### Limits of this evidence
One synthetic task written by the module's author, three seeds, small splits (12-14 test scenarios),
models from one provider, and temperature 0 (so no noise measurement). It shows the approach can work and
that the safeguards behaved as designed; it does not establish general effectiveness. The **verdict
validation (C3)** has only these few real runs behind it, and the **blind human review (C2/C6)** and
**fresh-adopter trial (C7)** are still outstanding.

## Second study: verdict changes and two more tasks (2026-10-01)

After the first study, two changes were made to the verdict (both unit-tested, `PromptOptimizerVerdictTest`):
the validation-to-test overfit tolerance now widens with small splits (`max(maxOverfitGap,
0.5·sqrt(1/nVal + 1/nTest))`), and a run that stops because validation reached the target is not
`generalized` unless the sealed test agrees. These were motivated by the ticket-routing runs, so the two
new tasks below are the first data the changes were *not* tuned on. Models as before (system and J1 Haiku
4.5, rewriter Sonnet 5.5, J2 Opus 5.5). Results:
[extract](../verification-results/2026-10-01/optimizer-multitask-extract.md),
[qa](../verification-results/2026-10-01/optimizer-multitask-qa.md). Budget was limited by remaining API
credit (extract: 250 rollouts, qa: 200, qa with 2 seeds), so these are small.

| Task (scenarios) | Criteria the optimizer saw | seed | GT test: seed → best | J1 → | J2 (Opus) → | `generalized` | stop; rounds / rollouts |
|---|---|---|---|---|---|---|---|
| **extract** (40; ISO date, integer cents, vendor w/o legal suffix) | deterministic field scorer, no judge | 1 | 0.00 → **1.00** | 0.29 → 1.00 | n/a | true | TARGET_REACHED; 1 / 60 |
| | | 2 | 0.00 → **1.00** | 0.25 → 1.00 | n/a | true | TARGET_REACHED; 1 / 60 |
| | | 3 | 0.00 → **1.00** | 0.25 → 1.00 | n/a | true | TARGET_REACHED; 1 / 60 |
| **qa** (40; short answer or `NOT_IN_CONTEXT`) | Haiku correctness judge + ≤ 8-word guardrail | 1 | 0.00 → **0.38** | 0.13 → 0.97 | 1.00 → 1.00 | true | NO_PROGRESS; 6 / 80 |
| | | 2 | 0.00 → **0.50** | 0.00 → 0.75 | 1.00 → 1.00 | **false** (2 test scenarios violate the guardrail) | NO_PROGRESS; 6 / 92 |

### What this adds

1. **Extraction: clean success, with a caveat.** All three seeds reached ground-truth 1.00 in a single
   round, and the prompts the rewriter wrote state every normalization rule. The caveat is that the
   deterministic scorer's feedback quotes the expected value ("date: expected 2024-03-03 but got March 3"),
   which makes rules easy to infer. Judge feedback in real tasks is vaguer, so do not expect one round.
2. **Judged QA: the judge was satisfied while the policy was not.** J1 rose from 0.0-0.13 to 0.75-0.97,
   and Opus (J2) scored *both* the seed and the best prompt at 1.00, yet the exact-policy ground truth
   (a bare short phrase; the literal token `NOT_IN_CONTEXT` when the fact is missing) only reached
   0.38-0.50. The judges accept "Not stated in the passage" as correct, which is semantically true but
   not the policy. The optimizer did exactly what it was asked: it improved what the criteria measured.
   **Lesson: a prompt can only be optimized toward what the criteria and guardrails actually check.** If
   a rule is a hard format rule, encode it as a deterministic guardrail or assertion, not a rubric; a
   judge cannot tell you about a rule it was never told. Opus agreeing with Haiku here is *not*
   independent confirmation, because both read the same under-specified criterion.
3. **The tolerance fix held out of sample.** `generalized` was true on 4 of 5 runs, and the one false
   result (qa seed 2) is a real guardrail violation on the test split, not noise.
4. **Both QA runs ended `NO_PROGRESS`** after 6 rounds with a lower-than-target score: expected when the
   criteria cannot distinguish the remaining failures.

### Reading plan §4.3 again

| Threshold | Result |
|---|---|
| Ground-truth test gain > 0 on ≥ 2 tasks in ≥ 2 of 3 seeds | **Met on 3 tasks** (tickets 3/3, extract 3/3, qa 2/2 with 2 seeds). QA's gain is partial (0.38-0.50). |
| No run where J1 gain ≥ 0.10 but ground truth ≤ 0 is marked generalized | **Met** (no such run); but see finding 2: J1 gain was large and ground-truth gain small, which is the near-miss this threshold guards against. |
| Independent judge and ground truth confirm J1 gains | **Met for tickets; not met for qa**, where the judges agreed with each other and not with the policy. |

### Limits
Three synthetic, author-made tasks; 2-3 seeds each; 8-12 scenarios in test splits; one provider; temperature 0.
Datasets and ground-truth scorers were written by the optimizer's author. The judged-task findings
describe a criteria-design failure mode, not a bug in the loop, but they show the loop cannot protect you
from it. The third task (`reply`, a rubric-only policy task) is implemented but was **not run** for lack of
credit.

## Worked example: Hexamind Hub personas (2026-10-01)

Two agents from `examples/hexamind-hub` (Rahul, the skeptic; Casey, the advocate) were optimized against
golden sets derived from the personas documented in `MEET_THE_TEAM.md` and `prompts.yaml`
(`src/test/resources/hexamind/*.yaml`, 36 and 30 scenarios; harness
`HexamindPersonaOptimizerIntegrationTest`). Full output with sample replies and prompt diffs:
[hexamind-persona-optimization.md](../verification-results/2026-10-01/hexamind-persona-optimization.md).

| Agent | seed | GT test (all rules hold): seed → best | Opus persona fidelity: seed → best | `generalized` | stop; rounds / rollouts |
|---|---|---|---|---|---|
| Rahul | 1 | 0.00 → **0.83** | 0.67 → 0.77 | true | TARGET_REACHED; 5 / 96 |
| Rahul | 2 | 0.00 → **0.75** | 0.65 → 0.77 | true | TARGET_REACHED; 1 / 62 |
| Casey | 1 | 0.00 → **0.78** | 0.44 → 0.75 | true | TARGET_REACHED; 1 / 53 |

Casey seed 2 did not finish: the independent Opus check hit a transient 529 "Overloaded" and the harness
stopped (not a credit problem); it was not re-run.

What it shows, and what to be careful about:

- **It found a real gap.** The shipped Casey persona is the library's generic `customerSupport()` rep,
  which does not match the documented accessibility advocate. The optimizer rewrote it into the
  documented persona (people-first, accessibility, explicit approve/veto), and an independent Opus rubric
  agreed (0.44 → 0.75).
- **Most of the seed's score of 0.00 is format.** The seeds answer in long markdown; the `agent_analyze`
  prompt asks for 2-4 sentences. The real agents' output is chunked, so this rule is partly an artifact of
  how the harness measures a single reply.
- **The rewriter learns the rules it is shown.** Rahul's best prompt says every reply to a real topic must
  contain a failure mode *and* an explicit probability, because that is what the checks reward. It also
  produces confident-looking numbers with no source ("one in four", "$5-9 billion per gigawatt"). With
  the real web-search tool this is the thing to review; here there is none.
- **Tool-less harness.** Web search was removed, so tool use was not tested. The optimized text is a
  persona prompt rendered by `AgentPersona.toSystemPromptAddition()`; the diff is against that rendering,
  so porting it into `AgentConfiguration` is a manual edit followed by a real-app check.
- **Small and synthetic.** 9-12 test scenarios per run, one provider, one Casey seed, rules and datasets
  written by the optimizer's author. The remaining 17-25% of failures were not analyzed.

## Not yet verified

| Plan item | Status |
|---|---|
| **C1/C2/C9 efficacy study** on more tasks, more seeds, larger splits | Three tasks now (tickets, extract, qa), 2-3 seeds each, small splits. The rubric-only `reply` task is implemented but unrun. Larger splits and other providers still needed. |
| **C3 verdict validation** at scale (recall/specificity of `generalized`) | 12 real optimized runs and 3 neutral runs; one false negative found and fixed; the fix held on 5 later runs, but those are few. |
| **C4 live faults** (real 429s, timeouts, mid-run kill) | Not run; simulated equivalents pass. (One real run hit no faults.) |
| **C6/C2 blind human review**, **C7 fresh-adopter trial** | Need people. Not done. |
| Independent security review; API-diff tool; JDK/OS matrix | Not done. |
