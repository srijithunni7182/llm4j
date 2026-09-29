# eval4j — Verification Results (deepeval-parity features)

Status of [VERIFICATION-PLAN-deepeval-parity.md](VERIFICATION-PLAN-deepeval-parity.md) after
implementation. **Not everything in the plan could be executed; the gaps are listed explicitly.**

## Executed and passing

| Plan item | Result |
|---|---|
| Automated suite (E1) | 249 tests, 0 failures (149 pre-existing + 100 new) |
| Coverage gate | JaCoCo per-package ≥ 80% line coverage: met |
| Pre-existing tests unmodified | Yes, except one added annotation (`@ResourceLock`) on `EvalReportExtensionTest` for parallel-run safety |
| Locale/encoding/timezone matrix (E2) | Suite green under `-Duser.language=tr -Duser.country=TR -Duser.timezone=Pacific/Auckland -Dfile.encoding=ISO-8859-1` (after fixing two locale bugs it exposed) |
| JUnit parallel execution | Suite green with `junit.jupiter.execution.parallel.enabled=true`, concurrent classes and methods |
| Cross-cutting: cache | Zero judge calls on identical rerun for RAG, conversation, synthesis, pairwise; changed input/criteria/identifier misses |
| Cross-cutting: prompt injection | Forged `<<<END ...>>>` markers neutralized in RAG chunks, conversation turns, source documents, pairwise outputs, seeds |
| Cross-cutting: failures | Malformed/garbage judge output names the failing item; generator exceptions counted, not propagated; atomic file writes |
| Cost guards | Call counts asserted (n chunks → n calls, recall 1+m, pairwise 2 per scenario, identical outputs 0) |
| Scale smoke | 200 chunks and 500-turn transcript finish well under 10 s; per-call context stays windowed |
| Formula correctness | Precision known-answer + property tests; Wilson interval against reference values; gate boundary table incl. floating-point edge (0.85→0.80) |
| Report safety | HTML has no external references; hostile strings escaped |

## Live run (Claude judge)

`Eval4jParityIntegrationTest` ran against `claude-haiku-4-5-20251001` (via `EVAL4J_ANTHROPIC_API_KEY`):
5/5 passed in ~36 s — real judge output parsed for RAG relevancy/precision/recall, knowledge
retention, pairwise comparison (with position swap) and dataset synthesis, and scores moved in the
expected direction (good context > noisy context, attentive > forgetful assistant, better answer wins,
generated scenarios well-formed). This is one small run with lenient direction-only assertions, not
the calibration study.

## Calibration study (first run)

Full output: [docs/verification-results/2026-09-29/calibration.md](docs/verification-results/2026-09-29/calibration.md)
(judges: Claude Haiku 4.5 and Sonnet 5.5; runner: `CalibrationStudyIntegrationTest`). **Caveat: the
datasets are small, synthetic and labelled by the module author, not independent humans, so these
figures are optimistic.**

| Check (plan §4.2) | Haiku 4.5 | Sonnet 5.5 |
|---|---|---|
| RAG per-chunk agreement ≥ 80% / κ ≥ 0.5 | 100% / 1.00 | 100% / 1.00 |
| Recall ordering full > half > none | yes (1.00/0.50/0.00) | yes (1.00/0.50/0.00) |
| Conversation gap clean − defective ≥ 0.25 (4 metrics) | all pass (0.33–0.58) | all pass (0.33–0.50) |
| Clean outscores matched defective in ≥ 80% of pairs | 3 of 4 metrics; **completeness 4/6 fails** | all 4 pass |
| Pairwise agreement with label on decisive results ≥ 75% | 100% (12/12) | 100% (12/12) |
| Cross-judge consistency (same clean/defective ordering) | yes | yes |

What this does and doesn't show:
- **The RAG and pairwise datasets were too easy.** Perfect scores, zero order-flips without
  mitigation, and zero score variance (temperature 0) mean they cannot demonstrate discrimination on
  hard cases, position-bias mitigation, or `samples(3)` benefit. Those claims remain **unproven**.
- **Conversation metrics discriminate but are miscalibrated in places.** Haiku scored *clean*
  knowledge-retention conversations at 0.75 (it penalizes trivial replies such as "You're welcome!"
  for not using known facts) and clean completeness at 0.67; it also scored the one-of-two-goals
  completeness defect at 0.25 rather than ~0.5. Sonnet was closer (0.92 clean). Treat the
  knowledge-retention and completeness metrics as **experimental** with small judge models until the
  rubric is tightened and re-measured on fresh cases (tuning on these same cases would overfit).
- Dataset-synthesis quality (human rating) was not part of this run.

## NOT executed (blocked or out of scope for this session)

| Plan item | Why |
|---|---|
| Live-model checks against Gemini/Ollama | No Gemini key was available and Ollama could not be installed, so those paths are untested. (The live suite did run against Claude — see the next section.) It can also target a local Ollama judge (`EVAL4J_JUDGE=ollama`, optional `OLLAMA_MODEL`/`OLLAMA_BASE_URL`); Ollama could not be installed in this sandbox (ollama.com, its model registry and GitHub releases are unreachable), so that path is untested too. With no judge configured the suite skips cleanly. Score-direction claims are verified against stubs only. |
| Calibration study with independent human labels, hard cases, and synthesis-quality rating | Only the author-labeled, easy-case first run above was done. |
| Fresh-adopter trial (§10) | Needs a person unfamiliar with the code. |
| Sample-app and CI-simulation scenarios (E4/E5, S1.x–S5.x walk-throughs) | Only their automated equivalents (component/TestKit tests) were run. |
| Headless-browser XSS check | Escaping is unit-tested; no browser run. |
| API-diff tool (japicmp) and JDK/OS matrix | Changes reviewed by hand as additive; only the local JDK/OS was run. |
| Memory bound under `-Xmx256m` | Not measured. |
| Parity matrix sign-off (§7) | Not completed; depends on the calibration and adopter results. |

## Known limitations

- The embedding adapter for the ONNX/DJL providers in `ai-agent4j-addons` is not separately tested;
  eval4j accepts any `EmbeddingProvider`, tested with fakes.
- Reported scores from a stochastic judge are noisy; the suggested defaults (suite-level baseline
  averages, `samples(3)` on gated metrics) have not been calibrated against measured noise.
