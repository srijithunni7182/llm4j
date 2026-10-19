# Phase 7 test strategy: classification

Companion to [DESIGN.md](DESIGN.md). Requirement ids (`R1`…) are the ones defined there. Tests are written before the code they cover.

## 1. Principles

- **No test needs Jev, a network or a key.** Every Jev behaviour is tested against a local HTTP server that replays recorded responses; every chat
  behaviour against `ScriptedClient`.
- **One scripted backend for everything above the contract.** A `ScriptedClassifier` (in `io.github.llm4j.eval.testing`, next to `FakeJudge`)
  returns canned answers per question name, so Loom and eval4j tests do not depend on either real backend.
- **Live tests are separate and opt-in** (an env var, like the existing integration tests), never part of the default build.
- **A requirement without a failing-first test is not done.**

## 2. Levels

| Level | Where | What |
|---|---|---|
| Unit | ai-agent4j | request validation, answer validation (R1, R2), the prompt and JSON schema `LlmClassifier` builds, parsing of malformed replies, vote-share arithmetic for `samples > 1`, `JevWire` mapping both ways |
| Contract | ai-agent4j | `JevProvider` against a local server replaying recorded wire fixtures (success, each question kind, HTTP 401/429/5xx, timeout, malformed body, empty answers, an answer naming an unknown label). The same suite runs against `LlmClassifier` over `ScriptedClient` so both backends meet one behavioural contract (`ClassifierContract`, an abstract test class) |
| Security | ai-agent4j | R3 injection corpus; R6 key never appears in exception text, logs or events (a canary key is searched for in every captured output) |
| Property | ai-agent4j | for random requests and random (valid and invalid) backend replies: the result either satisfies R1/R2 or is a `ClassificationException`; never both, never neither |
| Parser/validator | Loom | grammar of `intentclassifier` and `intentclassify`; every `weave check` rule in DESIGN §5.5; round-trip through the explain and graph code; error messages carry line numbers |
| Execution | Loom | `intentclassify` binds fields; `alt` routes on them; `confidence … else escalate | ask person | fail` each tested at, just under and just over the threshold; `unsure` only from the rule (R7); budget charge and breach (R9); trace carries `ClassifyEvent` and no state text (R12) |
| Regression | Loom, eval4j | the whole existing suite is the R11 test; plus one test that a script and a test with no classifier produce byte-identical traces and reports to the previous release |
| eval4j | eval4j | `llmJudged(...).classifier(c)` passes/fails at the threshold; `dimensions`, `samples`, cache hit/miss, telemetry; `.judge(LLMClient)` unchanged; failure text with and without a reason |
| Calibration | eval4j | `CalibrationRunner` with a `Classifier` on the existing calibration datasets, using scripted backends, checks the report maths (agreement, calibration curve, cost) — not the vendor's quality |
| Tooling | VS Code | grammar highlights the new keywords; completion offers the declared classifiers and their labels; diagnostics show the check errors |
| Live study | eval4j | opt-in head-to-head of Jev against real chat models on labelled data (§5) |
| Docs | all | code samples in the new wiki page and guide chapter are executed in a test (the `ContextDocsExamplesTest` pattern), so they cannot drift |

## 3. Fault injection

| Fault | Expected |
|---|---|
| Backend returns a label not in the request | `ClassificationException`, nothing returned (R1) |
| Backend omits a question / adds one | `ClassificationException` (R2) |
| Probability 1.2, -0.1, NaN | `ClassificationException` (R1) |
| Chat reply is prose, fenced JSON, JSON with trailing text, truncated JSON | fenced and trailing-text JSON are accepted; prose and truncated are `ClassificationException` (R4) |
| Timeout / 429 / 5xx / 401 | provider layer retries as for other providers; then step fails (Loom) or the assertion errors with the cause (eval4j), not "failed to match" |
| State contains instructions, delimiter strings, very long text, unicode | untrusted-data handling holds (R3); over-long state is rejected with the limit named, not silently cut |
| `confidence` line with a backend that supplies none | `weave check` error naming `samples` |

## 4. Deliberately not tested

- Jev's accuracy and calibration quality in the **default** build (they are measured by the live study in §5, which is opt-in and never part of the default build).
- The real wire format beyond the recorded fixtures: fixtures are replaced by recordings from the official API when Q1 is resolved.
- Chat-model accuracy on classification, which varies by model; only the harness around it is tested.

## 5. Live head-to-head study: Jev against a real LLM

The goal is first-hand evidence of how good Jev is for the two jobs this phase serves, measured against real chat models on data with
**human labels**. The labels, not the LLM, are the ground truth; the LLM is a competitor, not a referee.

### 5.1 How it runs

- Class `JevVsLlmStudyIntegrationTest` (eval4j integration tests, next to `CalibrationStudy*IntegrationTest`), skipped unless `JEV_API_KEY` and
  at least one chat key are set (`EVAL4J_ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, or an Ollama model, as the existing studies read them). It writes a
  Markdown report and a CSV of every call to `target/`, like `CalibrationRunner`.
- Every case goes to **every contender with identical inputs**: the same state, the same labels and instructions (through the same
  `ClassifyRequest`), temperature 0, one call per case. A second pass runs the chat contenders with `samples(5)` to see how much of Jev's edge
  is simply "more samples".
- Contenders: **Jev**; a **strong** chat model (the reference, e.g. Claude); a **cheap** chat model of the class a team would actually use for
  routing (e.g. Gemini Flash, or a small Ollama model). Jev vs the strong model shows the quality ceiling; Jev vs the cheap model is the
  like-for-like cost comparison.
- A `SpendGuard` cap bounds the run, and the report prints the total spend per contender.
- All datasets are synthetic or public. **No customer data** goes to a hosted API in this study.

### 5.2 Tasks and data

| Task | Data | Why |
|---|---|---|
| **Judging** | The existing labelled calibration datasets (`CalibrationDatasetV2`: RAG relevancy, recall, conversation retention/role/completeness/relevancy, pairwise) turned into classifier questions by the §7.2 mapping | The judging use case, on cases whose right answer is already known |
| **Intent routing** | A new labelled set (target 300 utterances, 6–8 intents including `other`): clear cases, ambiguous cases, two-intent messages, typos and slang, a block in Indian languages and Hinglish (Sarvam is a first-class provider here), out-of-scope, and prompt-injection attempts | The routing use case, including the failure modes that matter |
| **Safe/unsafe command** | A new labelled set (target 150 shell commands and tool calls, safe, risky, destructive, obfuscated) as yes/no and a 3-level score | The "guardrail for an agent" use Jev's makers describe; a high-stakes check where calibration matters |

### 5.3 What is measured

| Metric | Why |
|---|---|
| Accuracy and macro-F1 against the human labels (per task, per category of case) | Basic quality, and where each contender fails |
| Agreement with the strong LLM, and with the cheap LLM | How interchangeable they are |
| **Calibration**: reliability curve, Brier score and expected calibration error, for Jev against the chat models' self-reports and vote shares | Whether `confidence at least 0.8` can be trusted: the main claim of Jev |
| **Selective accuracy**: accuracy and coverage as the confidence threshold rises | What a Loom author actually gets from `confidence … else escalate` |
| Latency p50/p95 and cost per 1,000 decisions (price table) | The speed and price claims |
| Run-to-run stability: the same input repeated 5 times, flip rate | Whether a judge or router is repeatable |
| Invalid-output rate | How often a backend gives an unusable reply (our guards in R1, R4 count these) |
| Injection resistance: attack set, rate of answers changed by the attack | R3 for each contender |
| Mixed-language accuracy | Whether the intent task holds outside English |

### 5.4 Deciding in advance

To keep the conclusions honest, the verdict rule is written **before** the first run and recorded in the report header:

- Jev is *recommended* for a use if its accuracy is within 2 points of the cheap chat model **or better**, its ECE is at most 0.10, and its p95
  latency is at least 3× lower or its cost per 1,000 decisions at least 3× lower than the cheap chat model's.
- Jev is *recommended for high-stakes routing* (use with `confidence … else escalate`) only if, at a confidence threshold giving 80% coverage,
  its accuracy is at least that of the strong model at the same coverage.
- Otherwise the report says where it falls short, and the guide and wiki are written to match: Jev as an option, not a default.

These thresholds are proposals for review (DESIGN Q9). The numbers that come out are recorded, whatever they are, in VERIFICATION §7.

### 5.5 Limits of the study

It measures quality on our datasets, not on a team's. The guide tells a team to rerun it on their own cases with the same runner (the
`CalibrationRunner` mode from DESIGN §7.3). The data sets are small enough for a team to read through; they are checked in under
`src/eval4j/src/test/resources/`, with their provenance.
