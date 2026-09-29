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

## NOT executed (blocked or out of scope for this session)

| Plan item | Why |
|---|---|
| Live-model checks (E3), `Eval4jParityIntegrationTest` | No Gemini/Google API key was available in the session environment, so the live suite is written and compiles but has **never been run**. Score-direction claims are verified against stubs only. |
| Calibration study (§4): human-labeled agreement, discrimination margins, cross-judge consistency, noise measurement | Needs labeled datasets, human labelers and a live judge. Until it is run, the judged metrics should be treated as **unvalidated/experimental** per plan §4.2. |
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
