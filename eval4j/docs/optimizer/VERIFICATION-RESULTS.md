# eval4j Optimizer — Verification Results

Status of the [VERIFICATION-PLAN](VERIFICATION-PLAN.md). **Live verification (efficacy study, verdict
validation with real models, blind review, adopter trial) has not been run yet**; this file records
exactly what has and hasn't been verified so nothing is over-claimed.

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

## Not yet verified

| Plan item | Status |
|---|---|
| **C1/C2/C9 efficacy study** (real gain on held-out data, confirmed by an independent judge and ground truth; cost) | Runner written (`PromptOptimizerEfficacyIntegrationTest`), **not run**: needs a live key. |
| **C3 verdict validation** on real runs (recall/specificity of `generalized`) | Not run; simulated cases only. |
| **C4 live faults** (real 429s, timeouts, mid-run kill) | Not run; simulated equivalents pass. |
| **C6/C2 blind human review**, **C7 fresh-adopter trial** | Need people. Not done. |
| Independent security review; API-diff tool; JDK/OS matrix | Not done. |
