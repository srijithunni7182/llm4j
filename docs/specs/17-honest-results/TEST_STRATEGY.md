# Phase 17 test strategy: honest results

How each requirement of [DESIGN.md](DESIGN.md) is tested. The rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md) apply: the default build is hermetic (no network, no real model, no wall-clock waits);
anything that needs the outside world is tagged `integration`, `fragile` or `live`.

## 1. The principle: test what leaves the process

Every finding in this phase is a difference between *what the code intended* and *what was sent to, or reported from, a model*. A unit test of the code that composes text did pass for F01. So the main
tool of this phase is a **recording fake provider**: tests run the real `weave` pipeline (parser, validator, executor, provider adapter, HTTP client) against a loopback server that records every request
body and answers from a script. The assertion is on the **request that left**, or on the **summary that was printed**, never on an internal object.

## 2. Test levels

| Level | What it covers | Where |
|---|---|---|
| **Unit** | Pure logic: the output-limit decision (R2.1), the exit-code mapping (R4.3), the output contract (R5.2), time formatting (R3.1), usage labelling (R6.2) | next to the class, JUnit and AssertJ, table-driven (`@ParameterizedTest`) |
| **Adapter contract** | For each provider adapter: the request body for a fixed `LLMRequest`, and the reading of a recorded response (usage, finish reason, citations) | `ai-agent4j` provider tests, OkHttp `MockWebServer`, fixtures in `src/test/resources/<provider>/` with their date and model in the file name |
| **End to end with the fake provider** | A real `.loom` script through `WeaveCLI.execute(...)` or `Runs`, with a `RecordingModelServer` behind a `provider … { use: openai_compatible base_url: … }` (and the Gemini and Anthropic adapters pointed at the same server through their configurable base URL) | `ai-agent4j-loom` tests, new package `io.github.llm4j.loom.field` |
| **Field replay** | The whole `article-flow` shape, §4 | `src/test/resources/field/article-flow/` and `FieldReplayTest` |
| **Live** (opt-in, `live` tag, spend cap) | Recording the two new real fixtures (a Gemini recency request, a Gemini grounded answer without `recency:`) | `ProviderSmokeIntegrationTest`, never in the default build |

## 3. The recording fake provider (`RecordingModelServer`)

A small JUnit extension over `MockWebServer` (already a test dependency of `ai-agent4j`; if the Loom module's test scope lacks it, it is added there as `test` scope, and the choice is justified in the pull request
as `CLAUDE.md` requires). It:

- answers each request from a queue or a function, so a test can script "answer with text X, finish reason `length`, usage absent", an HTTP 400 with a body, or an answer with a grounding block;
- exposes `requests()`: the parsed JSON body of each request and its path, in order;
- offers readers for the system-level text of a request in each adapter's own field (`system`, `systemInstruction.parts[].text`, `messages[role=system|developer]`, `instructions`), so a test says
  `assertThat(server.systemText(0)).startsWith(SHARED)` without knowing the vendor;
- never reaches the network and binds loopback only.

The adapters accept a base URL (the OpenAI-compatible one already does; the Gemini and Anthropic adapters get a test-only override that is not a public setting).

## 4. Per requirement

| Requirement | Tests (to be written first; they must fail for the right reason on today's code where the finding reproduces) |
|---|---|
| **R1.1, R1.2** | `SharedPromptReachesTheModelTest` (parameterised over the agent forms: `prompt:` file, `system:`, both, persona, skills; and over the paths: `delegate`, `in parallel`, `broadcast`, `handoff`, `loop`, a retry, an `on_failure` fallback agent, a resumed run): the first request of each agent contains the canary exactly once, before the agent's own text. `EveryModelRequestComposesItsSystemTextInOnePlaceTest`: scans `HarnessExecutor` and the agent factories for a second composition (a structural test that fails when a new path bypasses `resolveSystemPrompt`). |
| **R1.3** | `<Vendor>SystemInstructionContractTest` per adapter: a fixed system text appears in the vendor's field of the recorded request body, in the right position. |
| **R1.4** | Dropped (D4). Replaced by `UnresolvedSharedPromptIsVisibleTest`: with a missing shared prompt file, `weave run` still starts, and the run summary and `weave explain` say the shared prompt is not applied and why. |
| **R1.5** | `SharedPromptIsNotReportedUnusedTest` (file form, `id@v2`, inline form, with and without a `prompts:` folder, a shared prompt that is also an agent's `prompt:`), and one case that must still warn (a prompt file no agent and no `shared_prompt` names). |
| **R1.6** | `ExplainWhatEachAgentIsToldTest`: the section lists the parts in order with sizes, and contains no text without `--show-text`; golden file per fixture. |
| **R2.1** | `OutputLimitDecisionTest` (table): per_call set; model maximum known and unknown; budget affords less than the maximum; budget affords more; cost and token limits together; no budget at all (unchanged: no cap). Plus `NoSilentClipTest`: the fake provider returns an answer longer than 1,024 tokens, and the run's answer is whole. |
| **R2.2** | `OutputLimitNoteTest` for `weave check` and `weave review` text and JSON. |
| **R2.3** | `CutAnswerIsReportedTest`: the fake provider answers with `finish_reason: length` (OpenAI), `MAX_TOKENS` (Gemini) and `max_tokens` (Anthropic); the event has `truncated: true`, the summary names the agent and count, `weave test` can assert it. |
| **R2.4** | the existing budget tests stay green; one new case: a budget that runs out mid-run is still exit 3. |
| **R3** | `GoogleRecencyWholeSecondsTest`: a `Clock` returning `…:24.123456789Z` yields `…:24Z` in both `startTime` and `endTime`; `EveryProviderTimestampIsWholeSecondsTest` iterates the adapters that send a time. A fixture-replay test from the recorded real request when it exists (R3.3). |
| **R4** | `DegradedRunTest`: scripts with one, some and all model steps on fallback; summary text, journal record, event, exit code 0 by default and 7 with `--exit-degraded`; "all steps failed" is exit 1; `weave test` assertions `degraded:` and `fallbacks:`. |
| **R5** | `WorkflowOutputContractTest` (return; no return but a last `note`; neither; `halt`), used by `weave run`, `weave eval`, `weave test`, `LoomHost` and the Java twin from the same function. `EvalNeverJudgesEmptyOutputTest`: a fake judge counts calls; zero for an empty output; the case is `ERROR` with the reason; exit non-zero. `RunPrintsTheResultTest`. |
| **R6** | `UsageIsReadTest` per adapter from a recorded response; `UsageLabelTest` for the summary and event text; `EstimatedUsageNeverClearsABudgetTest`. |
| **R7** | `CitationsReachTheWorkflowTest`: the recorded Gemini grounded response through a plain `delegate`, inside `in parallel`, and with `expecting`; asserts `{x_citations}` has the expected entries in each (the case that fails today, if H1 holds, is the finding). `GroundedWithoutSourcesIsReportedTest`: `searches: 0` or empty metadata yields the summary note. `CitationVariableCheckTest` for R7.3. |

## 5. Field replay

`FieldReplayTest` runs a fixture project shaped like `article-flow` (five agents, four tasks, grounding on two agents, a shared prompt, a budget without `per_call`, `on_failure` fallbacks, a dataset) against
the recording fake provider and asserts, **in one test class with one assertion per finding**:

- F01: the shared canary is in every agent's request;
- F03: no answer is cut at 1,024 when the fake answers with 2,000 tokens; and when the model's own limit is hit, the summary says so;
- F04: no request carries sub-second times;
- F05: with the fake failing four of five agents, the run is **not** "completed successfully" without a degraded line;
- F06/F07: `weave run` prints the result; `weave eval` on a workflow with no return value reports an error instead of judging;
- F09: the Sources variable has entries in the parallel branch.

The replay is the program's success measure 1 ([FIELD_FINDINGS §7](../FIELD_FINDINGS.md)). It is added **before** any fix, so each assertion fails first for the findings that reproduce, and the failing list is the
reproduction record in `VERIFICATION.md` §0.

## 6. Fault injection

Provider replies that are: an HTTP 400 with a body naming a field (R3.4); an empty body; a body with no `usage`; `usageMetadata` with only the prompt count; grounding metadata with offsets that do not fit
the answer (the documented drop); a stream that ends without a finish reason. Each must end in a message that names the agent and the cause, never in a silent success.

## 7. Mutation testing

The guards of this phase are decisions, and a test that does not notice a removed guard is worth little. `AgentPrompts` (system composition), `BudgetSet.reserve` (the output decision), the degraded-run classifier,
and the output-contract function join the `mutation` profile of `ai-agent4j-loom` (and `ai-agent4j`'s `BudgetSet` the budget profile). The score must not fall; surviving mutants are killed with better assertions, never excluded.
Each guard is also removed once by hand during review, and the test that fails is named in `VERIFICATION.md`.

## 8. Deliberately not tested

- That a particular model **obeys** a canary. Models are probabilistic; a hermetic test proves the text was sent. Whether a real model obeys is the **preflight** (Phase 20) and is a live, opt-in, capped check.
- Exact token counts a real vendor would report, beyond the recorded fixtures.
- The wording of a model's answer; only the structure of what was sent and what was reported.
