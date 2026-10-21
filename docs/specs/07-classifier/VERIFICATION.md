# Phase 7 verification: classification

Companion to [DESIGN.md](DESIGN.md) and [TEST_STRATEGY.md](TEST_STRATEGY.md). The phase is done when every box below is ticked and the result
is recorded in §6.

## 1. Sub-phases

| # | Sub-phase | Delivers |
|---|---|---|
| 7.0 | **Study first (gate)** | `scripts/jev-spike/`: the head-to-head study of TEST_STRATEGY §5, run with the Python harness on the labelled sets **before any production code**. Its results decide what 7a–7e build (see §1a) |
| 7a | Contract and chat backend | `io.github.llm4j.classify` types, `LlmClassifier`, `ScriptedClassifier`, `ClassifierContract` |
| 7b | eval4j | `llmJudged(...).classifier(...)`, cache/telemetry integration, `CalibrationRunner` mode |
| 7c | Jev provider | `JevProvider`, `JevWire`, `jev` as a provider name, resolver; begins by reading Jev's reference and recording fixtures (DESIGN Q1) |
| 7c-live | Study in Java | `JevVsLlmStudyIntegrationTest` ports the 7.0 study onto `Classifier`, so the team can rerun it on their own data with the same runner. It must reproduce the 7.0 results within noise |
| 7d | Loom | `intentclassifier` block, `intentclassify` statement, checks, execution, trace event, budget, audit, graph |
| 7e | Tooling and docs | VS Code grammar/completion/diagnostics, wiki page, Loom guide chapter, `llms.txt`, examples |

### 1a. The 7.0 gate

7.0 is done when the study has been run with real keys, the results are in §7, and the verdict rule of TEST_STRATEGY §5.4 has been applied as
written in advance. Then, before 7a starts, the spec is revised from the evidence:

| Outcome | Effect on the plan |
|---|---|
| Jev meets the rule | Proceed as written; the guide and wiki recommend Jev for routing and judging |
| Jev meets it for some tasks only | Proceed; the docs name the tasks; Loom's `confidence` guidance is written from the calibration results |
| Jev does not meet it | The `Classifier` contract, `LlmClassifier`, the Loom primitive and the eval4j judge are still built (they stand on their own); `JevProvider` is built as an option and the docs say Jev is not recommended on our data. Or it is deferred, if the results are poor enough |

## 2. Acceptance criteria

| # | Criterion | Requirement | Test | Status |
|---|---|---|---|---|
| A1 | Both backends pass the same `ClassifierContract` suite | R1, R2 | `ClassifierContract` subclasses for `LlmClassifier`, `JevProvider` | 7a ✅ `LlmClassifierContractTest` extends `ClassifierContract` (10 cases); JevProvider: 7c |
| A2 | Invalid replies (unknown label, missing/extra question, bad probability, truncated) never become values | R1, R2, R4 | unit + property tests | 7a ✅ `ClassifierContract`, `ClassifyTypesTest`, `LlmClassifierTest`, `LlmClassifierPropertyTest` (1,500 random replies) |
| A3 | Injection corpus does not change answers; the state is delimited | R3 | security tests | 7a ✅ for the structure (`LlmClassifierTest`: delimited data, forged markers neutralised, state never in the system prompt); the behavioural check on a real model is the opt-in live injection test (owed, §5) and the study's injection cases (STUDY_REPORT §3) |
| A4 | `LlmClassifier` answers are never `calibrated`; `JevProvider` answers are | R5 | contract tests | 7a ✅ LlmClassifier never calibrated (`ClassifierContract.calibratedOnlyWhenTheBackendIs`, `LlmClassifierTest.theSelfReportedConfidence…`); JevProvider: 7c |
| A5 | The Jev key never appears in exceptions, logs, events or traces | R6 | canary-key test | 7c |
| A6 | A judge backed by a classifier passes/fails at the threshold and keeps `samples`, cache, dimensions and telemetry | R10 | eval4j tests | 7b ✅ `LlmJudgeClassifierTest` (threshold boundary, samples averaging, cache replay and no mixing with chat verdicts, telemetry tokens, dimensions from one call, failure text, failing classifier → `JudgeEvaluationException`) |
| A7 | `.judge(LLMClient)` and every existing judge test behave exactly as before | R11 | full existing eval4j suite | 7b ✅ the existing `LlmJudgeConditionTest`/`LlmJudgePresetsTest` and all 539 eval4j tests unchanged and green; `LlmJudgeClassifierTest.theChatJudgesFailureMessageIsExactlyWhatItWas` pins the message |
| A8 | `intentclassify` routes an `alt` on label and confidence; the three confidence actions work at the boundaries | §5.2, §5.3 | Loom execution tests | 7d |
| A9 | `unsure` appears only from a confidence rule | R7 | Loom tests | 7d |
| A10 | `weave check` rejects: unknown classifier, unbound `on`, duplicate or fewer than two labels, a declared `unsure`, an `alt` on a label never produced, a `confidence` line with no available confidence | R8 | validator tests | 7d |
| A11 | A classify step is charged to the budget and a breach halts | R9 | budget tests | 7d |
| A12 | The trace and call telemetry carry backend, model, tokens, latency, outcome; no state text at the default detail level | R12 | trace tests | 7a ✅ `ClassifyTypesTest.anObserverSeesNumbers…` (classifier events); Loom trace: 7d |
| A13 | A script or test with no classifier gives byte-identical output to the previous release | R11 | regression test | 7b, 7d |
| A14 | `weave audit` lists a hosted classifier backend as a data flow; the graph marks `uncalibrated` | §5.5 | audit and graph tests | 7d |
| A15 | `Classifier.using(chat)` and `Classifier.using(jev)` can be compared on a dataset with `CalibrationRunner`, giving agreement, calibration curve, cost and latency | §7.3 | eval4j calibration tests | 7b ✅ `ClassifierComparisonTest` (accuracy, ECE 0.25 and Brier 0.1475 computed by hand, selective accuracy, agreement, per-category, latency/tokens/cost, reliability bins, invalid calls, flip rate); public class `ClassifierComparison`, not inside the test-scope `CalibrationRunner` |
| A16 | All documentation samples execute in tests | docs | docs examples test | 7a, 7b ✅ `ClassificationDocsExamplesTest` (7 samples), `ClassifierJudgeDocsTest` (3 samples); later pages: with their sub-phases |
| A17 ✅ (7.0 harness; Java port 7c-live owed) | The 7.0 study runs end to end with a real Jev key and at least one chat model, writes its report and CSV, stays inside its spend cap, and its results are recorded in §7 with the verdict rule applied as written in advance | §5 of the test strategy | `JevVsLlmStudyIntegrationTest` (live, opt-in) |

## 3. Traceability

One row per requirement. A requirement with no row blocks the phase. "pending" names the sub-phase that fills it in.

| Req | Tests | Status |
|---|---|---|
| R1 no label/probability/position outside the request | `ClassifierContract` (unknown label, unknown scale point, probability 1.2 / -0.1, confidence 1.7, wrong kind), `ClassifyTypesTest.answersRefuseValuesOutsideTheirRange`, `.aProviderResultIsCheckedAgainstTheRequest`, `LlmClassifierPropertyTest` | 7a ✅ (LlmClassifier, `Classifier.using(provider)` validation); JevProvider via the same contract in 7c |
| R2 exactly the requested questions | `ClassifierContract` (missing, extra), `ClassifyTypesTest`, `LlmClassifierPropertyTest`; request built once, one backend call: `ClassifierContract.answersExactly…` asserts `callsSent() == 1` | 7a ✅ |
| R3 state is untrusted data | `LlmClassifierTest.theStateIsDelimitedData…`, `.forgedMarkersInsideTheStateCannotCloseTheBlock`, `.anUnnamedTextAndSeveralFieldsAreAllDelimited` | 7a ✅ structure; live behaviour owed (§5) |
| R4 output capped; truncation is an error naming the cap | `LlmClassifierTest.aReplyCutOffByTheCapIsAnErrorNamingTheCap`, `.theOutputIsCappedAndTheCapGrowsWithTheQuestions`, `.anExplicitCapWinsAndRationaleRaisesTheDefault`, `.proseAndTruncatedJsonAreErrors` | 7a ✅ |
| R5 calibrated only when the backend says so | `ClassifierContract.calibratedOnlyWhenTheBackendIs`, `LlmClassifierTest.theSelfReportedConfidenceIsCarriedButNeverCalibrated` | 7a ✅ LlmClassifier; Jev: 7c |
| R6 no request when invalid; key never leaves | `ClassifyTypesTest` (an invalid request cannot be built, so no backend is called); canary-key test | 7a ✅ first half; 7c second half |
| R7 `unsure` only from a confidence rule | pending | 7d |
| R8 `alt` on a label never produced is a check error | pending | 7d |
| R9 classify step charged to the budget | pending | 7d |
| R10 classifier-backed judge yields `JudgeVerdict`, honours threshold/samples/cache/telemetry | `LlmJudgeClassifierTest` | 7b ✅ |
| R11 nothing existing changes | full existing suites green: ai-agent4j 2,826 tests, eval4j 539 tests; existing source touched only in `LlmJudgeCondition` (an added builder option and branch; every pre-existing judge test unchanged) and the ai-agent4j pom (a coverage rule) | 7a ✅, 7b ✅ (eval4j 539 tests green, chat-judge message pinned); Loom in 7d |
| R12 telemetry carries backend, model, tokens, latency, outcome; never state or rationale | `ClassifyTypesTest.anObserverSeesNumbersAndLabelsButNeverTheStateOrTheReason`, `.anObserverSeesAFailure…` | 7a ✅ classifier events; trace: 7d |

## 4. Quality gates

- The repo's own build, formatting, and static checks pass on every changed module.
- Line and branch coverage on the new packages meets the repository's existing thresholds; mutation testing (PIT) is run on `intentclassify` and its
  validators like the other core packages.
- `weave check` and `weave audit` run clean on all new example scripts.
- No new runtime dependency in ai-agent4j (Jev goes through `HttpClientWrapper`).
- Public types have Javadoc; the wiki code samples are executed (A16).

## 5. Manual and live checks (owed, opt-in)

- [ ] A recorded session against the real Jev API replaces the fixtures (Q1), and the contract suite still passes.
- [ ] The live study (TEST_STRATEGY §5): `Classifier.using(jev)` against a strong and a cheap chat model on the judging, intent and safe/unsafe tasks. Results recorded in §7 and the Jev recommendations in the guide and wiki written from them.
- [ ] An end-to-end triage workflow in Loom with Jev, run once with `--observe` to see the call events in Fabric.
- [ ] The VS Code extension on the new syntax: highlighting, completion, diagnostics.

## 6. Documentation owed

Per the spec method, at the end of each sub-phase: the wiki page (`src/ai-agent4j/wiki/Classification.md`), its executed samples, Javadoc, module
READMEs and hub links, `llms.txt`, and the Loom guide chapter. Once, at the end of the whole phase: the stack-level pages (root `README.md`,
`wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`) and the `llm4j-workflow-guide` skill (so it can advise on `intentclassify` and on a
classifier-backed judge).

## 6a. Release 6.1.0 gate

For the classifier work to be part of 6.1.0: every **Must** and **Should** item of DESIGN §10 that is included has its acceptance criteria ticked in §2;
the release notes (`RELEASE_NOTES_6_1_0.md`) describe it, including what Jev is and the 7.0 verdict in plain words; `docs/VERSION_MATRIX.md` and
`docs/API_COMPATIBILITY.md` are updated (the additions are new types and default-less additive methods only); the publish steps in `docs/PUBLISHING.md`
are followed. Items not ready are listed in the release notes as "coming", never described as working.

## 7. Result

### 7.0 The study (2026-10-21), done

Full report: [STUDY_REPORT.md](STUDY_REPORT.md); raw data in [study/](study/). Contenders: Jev (`jev-latest` = jev-1.13.0), `claude-opus-5-5` (strong), `claude-haiku-5-5` (cheap). Three full runs (standard set with 1 and 3 repeats, a hard set written and labelled beforehand) plus a smoke run. Estimated spend: Jev $0.01 of $5, Anthropic $0.95 of about $6.

| Task | Set | Jev acc / ECE | Cheap (Haiku) acc / ECE | Strong (Opus) acc / ECE | p95 ms (Jev vs Haiku) | $/1,000 (Jev vs Haiku) | Rule §5.4 |
|---|---|---|---|---|---|---|---|
| Intent | standard, 52 | 1.000 / 0.020 | 1.000 / 0.083 | 1.000 / 0.079 | 321 vs 1,558 | 0.017 vs 0.037 | recommended |
| Intent | hard, 65 | 0.969 / 0.030 | 0.969 / 0.096 | 1.000 / 0.102 | 476 vs 1,576 | 0.018 vs 0.038 | recommended |
| Judging | standard, 30 | 0.967 / 0.071 | 0.900 / 0.136 | 0.967 / 0.078 | 253 vs 2,254 | 0.013 vs 0.056 | recommended |
| Safe/unsafe | standard, 40 | 0.875 / 0.077 | 0.900 / 0.058 | 0.900 / 0.071 | 242 vs 1,935 | 0.012 vs 0.040 | **not** recommended |
| Safe/unsafe | hard, 40 | 0.950 / 0.062 | 1.000 / 0.048 | 0.875 / 0.065 | 228 vs 1,535 | 0.012 vs 0.042 | **not** recommended |

**Verdict (the outcome row "meets it for some tasks only" of §1a):** Jev is recommended for intent routing and for judging, and for `confidence … else escalate` routing on intent and judging (accuracy at 80% coverage equals the strong model's). It is not recommended as the only safe/unsafe-command guardrail (below the cheap model on both sets; one confident miss, `> /etc/passwd` at 0.95). Caveats recorded with the verdict: small hand-written sets, differences of one or two cases are noise, intent saturated on the standard set, no Gemini contender, no 5-sample chat pass.

A17 is satisfied by the harness run for 7.0; the Java port (7c-live) is owed.

