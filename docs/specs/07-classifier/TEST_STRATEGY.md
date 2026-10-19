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
| Parser/validator | Loom | grammar of `classifier` and `classify`; every `weave check` rule in DESIGN §5.5; round-trip through the explain and graph code; error messages carry line numbers |
| Execution | Loom | `classify` binds fields; `alt` routes on them; `confidence … else escalate | ask person | fail` each tested at, just under and just over the threshold; `unsure` only from the rule (R7); budget charge and breach (R9); trace carries `ClassifyEvent` and no state text (R12) |
| Regression | Loom, eval4j | the whole existing suite is the R11 test; plus one test that a script and a test with no classifier produce byte-identical traces and reports to the previous release |
| eval4j | eval4j | `llmJudged(...).classifier(c)` passes/fails at the threshold; `dimensions`, `samples`, cache hit/miss, telemetry; `.judge(LLMClient)` unchanged; failure text with and without a reason |
| Calibration | eval4j | `CalibrationRunner` with a `Classifier` on the existing calibration datasets, using scripted backends, checks the report maths (agreement, calibration curve, cost) — not the vendor's quality |
| Tooling | VS Code | grammar highlights the new keywords; completion offers the declared classifiers and their labels; diagnostics show the check errors |
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

- Jev's accuracy and calibration quality (vendor claims; measured by the team's own datasets via §7.3 of the design, as a live opt-in run).
- The real wire format beyond the recorded fixtures: fixtures are replaced by recordings from the official API when Q1 is resolved.
- Chat-model accuracy on classification, which varies by model; only the harness around it is tested.
