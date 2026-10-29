# Phase 17 verification plan: honest results

Status: **not started** (specs in draft). Run §0 first, then top to bottom at the end of each sub-phase (17a to 17e, proposed in [DESIGN §3](DESIGN.md)); the relevant rows after each. Results in §7.
Findings: F01 to F09 of the [register](../FIELD_FINDINGS.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

Decisions D1 to D4 are answered (see the program page). Proposed sub-phases: **17a** reproduce and field replay (no product change); **17b** shared prompt (R1); **17c** output limit, usage and grounding times (R2, R3, R6); **17d** degraded runs, output contract and `weave eval` (R4, R5); **17e** citations and the record (R7, R1.7).

## 0. Reproduce first

Nothing in §3 is built until its row here has a result. Each procedure uses the recording fake provider ([TEST_STRATEGY §3](TEST_STRATEGY.md)) unless it says **live** (a real key from the environment, a spend cap, never pasted anywhere).
Record the date, the jar's build id (`weave --version`, and the git commit), and the outcome. A finding that does not reproduce is reported to the owner with what was tried, and its requirement is dropped or reshaped; it is not built on trust.

| # | Finding | Procedure | If the finding is real, you see | Result |
|---|---|---|---|---|
| P1 | F01 | Run `weave run` on a script with `shared_prompt: "CANARY-A …"` and an agent with `system:` against the fake server; then with `shared_prompt: prompt "rules"` and an agent with `prompt: "own"`; then each inside `in parallel`, with `grounding: web`, with `expecting`. Read the request bodies. | any request whose system-level text lacks the canary | ✅ 2026-10-29: **reproduced.** The request to the fake lacked the canary, text form and file form (`RealRunKeepsItsDeclarationsTest`). Cause found, see §7. |
| P2 | F01 | Repeat P1 with the Gemini adapter and the Anthropic adapter pointed at the fake server, reading each vendor's system field. | a request where the vendor's system field lacks the canary | ⏳ Gemini and Anthropic adapters not yet read; the cause (loading) is before any adapter, so H2 is not needed to explain F01. Contract tests still owed (R1.3). |
| P3 | F01 | **Live, capped:** the owner's `article-flow` project: one agent, the canary "begin every answer with CANARY-7QX" in `shared_prompt`, then in the agent's own prompt; run each once and read the answer. | the model ignores the canary only when it is shared | ⏳ owner, live, capped (M2) |
| P4 | F02 | `weave check` on the minimal script in [FIELD_FINDINGS §3 (F02)](../FIELD_FINDINGS.md): `prompts: "prompts"`, `shared_prompt: prompt "house-rules"`, one agent with `prompt: "helper"`. Reproduced once on 2026-10-29 on the extension's jar; repeat on current `main`, and trace `ScriptValidator.checkPromptFolder`'s `used` set to the cause. | the warning "not used by any agent" | ✅ 2026-10-29: **reproduced again and explained**: same cause as P1 |
| P5 | F03 | Script with `budget { tokens: 100000 calls: 20 }` and an agent with no `per_call`; the fake answers 2,000 tokens. Read the request's output-limit field and the answer the run keeps. | `max_tokens` (or the vendor's field) = 1024 and a cut answer with exit 0 | ✅ 2026-10-29: **reproduced.** `max_tokens` = 1024 on the request with `budget { tokens: 100000 calls: 20 }`; the answer is cut (test kept for 17c) |
| P6 | F03 | Same script without a budget. | no limit sent (control) | ✅ 2026-10-29: control passes: no limit sent without a budget |
| P7 | F04 | **Unit:** `GoogleGrounding` with a clock returning nanoseconds; read `timeRangeFilter`. **Live, capped:** one Gemini grounded request with `recency:`. | sub-second digits in the times; HTTP 400 "Granularity of nano is not supported" | ✅ 2026-10-29 unit: **reproduced** (`.123456789Z` sent). Live request ⏳ owner (M1) |
| P8 | F05 | The field-replay script with the fake failing four of five agents. | "Workflow completed successfully", exit 0, a stub saved, no degraded line | ✅ 2026-10-29: **reproduced.** A run where both model steps were rejected printed "Workflow completed successfully", exit 0 (test kept for 17d) |
| P9 | F06, F07 | A workflow that ends with `note` and no `return`; run it with `weave run`, then `weave eval` with a counting fake judge. | no `Result:` printed; the judge receives an empty output | ◐ 2026-10-29: `weave run` printed no `Result:` (F07 reproduced). `weave eval` side (F06) not yet tried |
| P10 | F08 | Fake provider with no `usage`; read the run summary and events. Fake that answers exactly at the limit; read the completion counts. | cost shown without a label; counts clustered at the limit (explaining the "1,020") | ◐ 2026-10-29: a response without usage printed no "estimated" label anywhere (F08 label part reproduced). The 1,020 clustering is explained by P5 |
| P11 | F09 | Recorded real Gemini grounded response through a plain `delegate`, inside `in parallel`, and with `expecting`; print `{x_citations}`. **Live, capped:** the same on the real API. | an empty or missing variable in at least one case | ⏳ needs a recorded Gemini grounded response (17e) |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | If a script declares `shared_prompt`, every request of every agent carries it once, before the agent's own text, on every path and in every provider adapter; or, where it cannot be resolved, the run summary and `weave explain` say it was not applied (D4: warn, do not refuse) | `SharedPromptReachesTheModelTest`, `<Vendor>SystemInstructionContractTest`, `UnresolvedSharedPromptIsVisibleTest`, P1 to P3 |
| A2 | `weave check` never says a referenced shared prompt is unused, and still says it of a prompt nothing names | `SharedPromptIsNotReportedUnusedTest`, P4 |
| A3 | A developer can see, without a model call, what each agent will be told | `ExplainWhatEachAgentIsToldTest`, manual M3 |
| A4 | No model call is shortened by a default nobody chose; the effective limit is stated; an answer cut at a limit is named in the run summary | `OutputLimitDecisionTest`, `NoSilentClipTest`, `OutputLimitNoteTest`, `CutAnswerIsReportedTest`, P5, P6 |
| A5 | A grounded Gemini call with `recency:` succeeds on the real API and sends whole seconds | `GoogleRecencyWholeSecondsTest`, `EveryProviderTimestampIsWholeSecondsTest`, manual M1 |
| A6 | A run that used fallbacks says which; a run with no real model contribution is a failure; exit codes are as decided (D2) | `DegradedRunTest`, P8 |
| A7 | A workflow has one defined output, `weave run` prints it, and `weave eval` never sends an empty output to a judge | `WorkflowOutputContractTest`, `RunPrintsTheResultTest`, `EvalNeverJudgesEmptyOutputTest`, P9 |
| A8 | Every cost and token figure says measured or estimated; an estimate never clears a budget | `UsageIsReadTest`, `UsageLabelTest`, `EstimatedUsageNeverClearsABudgetTest`, P10 |
| A9 | Citations reach the workflow in a plain `delegate`, in a parallel branch and with `expecting`; a grounded agent with none says so | `CitationsReachTheWorkflowTest`, `GroundedWithoutSourcesIsReportedTest`, `CitationVariableCheckTest`, P11 |
| A10 | The field replay reproduced each reproducing finding **before** the fix and passes after | `FieldReplayTest` (the failing list recorded in §7 before 17b) |
| A11 | Existing scripts behave as before except in the listed ways; every behaviour change is in the release notes and `API_COMPATIBILITY.md` | existing tests unmodified, the compatibility table in [DESIGN §4](DESIGN.md), manual M6 |
| A12 | The published record is corrected to what P1 to P3 found | docs tests, review of the release notes (R1.7) |
| A13 | Each guard is noticed when removed | PIT `mutation` profile score not lower; the by-hand removals named in §7 |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1.1 | P1 to P3 (§0) | ⬜ |
| R1.2 | `EveryModelRequestComposesItsSystemTextInOnePlaceTest`, `SharedPromptReachesTheModelTest` | ⬜ |
| R1.3 | `GeminiSystemInstructionContractTest`, `AnthropicSystemContractTest`, `OpenAiCompatibleSystemContractTest`, `OpenAiResponsesInstructionsContractTest`, `OllamaSystemContractTest`, `SarvamSystemContractTest` | ⬜ |
| R1.4 | dropped by D4; replaced by `UnresolvedSharedPromptIsVisibleTest` (visibility, R1.6) | ⬜ |
| R1.5 | `SharedPromptIsNotReportedUnusedTest` | ⬜ |
| R1.6 | `ExplainWhatEachAgentIsToldTest`, `reviewPanelAgentPrompts.test.ts` (if the editor shows it) | ⬜ |
| R1.7 | docs tests; review | ⬜ |
| R2.1 | `OutputLimitDecisionTest`, `NoSilentClipTest` | ⬜ |
| R2.2 | `OutputLimitNoteTest` | ⬜ |
| R2.3 | `CutAnswerIsReportedTest` | ⬜ |
| R2.4 | existing budget tests, `BudgetExhaustedStillExit3Test` | ⬜ |
| R2.5 | P10 | ⬜ |
| R3.1 to R3.4 | `GoogleRecencyWholeSecondsTest`, `EveryProviderTimestampIsWholeSecondsTest`, the live fixture, `ProviderBadRequestNamesTheFieldTest` | ⬜ |
| R4.1 to R4.4 | `DegradedRunTest`, `ScriptedTestAssertsDegradedTest` | ⬜ |
| R4.5 | `FallbackCountVariableTest` (only if accepted) | ⬜ |
| R5.1 | P9 | ⬜ |
| R5.2 to R5.5 | `WorkflowOutputContractTest`, `RunPrintsTheResultTest`, `EvalNeverJudgesEmptyOutputTest`, `EvalReportKeepsOutputsTest` (with Phase 19) | ⬜ |
| R6.1 to R6.3 | `UsageIsReadTest`, `UsageLabelTest`, `EstimatedUsageNeverClearsABudgetTest` | ⬜ |
| R7.1 to R7.3 | `CitationsReachTheWorkflowTest`, `GroundedWithoutSourcesIsReportedTest`, `CitationVariableCheckTest` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on every module touched (`ai-agent4j`, `ai-agent4j-loom`, `eval4j`), including the JaCoCo gates; no limit lowered, no exclusion added.
- [ ] PIT: the `mutation` profile of `ai-agent4j-loom` and the budget classes of `ai-agent4j`; the score does not fall; `AgentPrompts`, `BudgetSet.reserve`, the degraded classifier and the output-contract function are in the profile.
- [ ] `make format-check` and `make release-check` green.
- [ ] `FieldReplayTest` runs in the default build in under 30 s and is hermetic (loopback only).
- [ ] Every guard of R2.1, R4.2 and R5.4 removed once by hand; the failing test is named in §7.
- [ ] `SecurityDocTest` and the docs tests green; `docs/security/secure-by-construction.md` records the D4 decision (a shared prompt that cannot apply only warns; revisit at 7.0).
- [ ] No new dependency, or its justification (size, licence, CVEs, `mvn dependency:tree`) is in the pull request.

## 4. Manual checks (need the owner, a real key from the environment, and a spend cap)

| # | Check | Pass |
|---|---|---|
| M1 | A real Gemini grounded call with `recency:` succeeds and returns sources (live, capped at 0.05) | ⬜ |
| M2 | The `article-flow` project, rebuilt on the fixed jar, with the canary in the shared prompt: the real model obeys it; one article is whole (no mid-sentence end) and above the length the old run stopped at | ⬜ |
| M3 | `weave explain` and the Review panel show what each agent is told, and the owner can say what it means without reading the code | ⬜ |
| M4 | Read the summary of a degraded run as a person: it says what happened and what to do next, with no jargon | ⬜ |
| M5 | `weave eval` on a real workflow with a real judge: outputs are present, none is empty, the cost line says what it includes | ⬜ |
| M6 | Run five existing sample and example scripts on the old and the new jar and compare: only the listed differences | ⬜ |

## 5. Documentation owed

Release notes (6.2.0), `LOOM_GUIDE.md`, `docs/guide` chapters 6 and 9, the generated command pages, `docs/API_COMPATIBILITY.md`, `docs/security/secure-by-construction.md`, the workflow skill, `llms.txt`, the Loom wiki, the
VS Code README if the editor shows R1.6. Stack-level pages once at the end of the program ([specs README](../README.md)).

## 6. Exit checklist

- [ ] §0 recorded for every row, with the date and build id; findings that did not reproduce reported to the owner
- [ ] Every row in §2 ✅ or recorded as partial with a reason; every A-criterion has evidence
- [ ] M1 to M6 done and recorded in §7
- [ ] DESIGN §6 questions answered and recorded; decisions D1 to D4 recorded in the program page
- [ ] Docs in §5 updated; the roadmap status in the specs README updated
- [ ] The release notes correction (R1.7) merged

## 7. Results

### 17a/17b: reproduction and the first fix (2026-10-29)

Build under test: `weave 6.1.0` as built from commit `9e1afdce` (manifest `Build-Time: 2026-10-29T18:21:21Z`); `weave --version` carries no commit, which is Phase 18's F11. Tests ran against a recording fake provider on loopback (`RecordingModelServer`, `FieldCli` in `src/test`), through the real `weave` command line.

**The contradiction in F01 is settled.** `AgentPrompts.resolveSystemPrompt` is correct, and `SharedPromptTest` is right that it works when a script is parsed directly. But every `weave` command loads a script through `LoomLoader`, which builds the result by `LoomScript.merge`, and `merge` did not copy `shared_prompt`. So in a real run no agent was told the shared prompt: the 6.1.0 release-note sentence "The prompt is applied at run time" was **false**, and the field report (F01) was right. Hypotheses H1 (a path that composes its own text), H2 (an adapter dropping a block) and H4 (a model ignoring first text) were not needed. F02 has the same cause: the validator read the merged script, which no longer named the prompt, so the file looked unused.

**A related finding, F66** (not in the register): `merge` also dropped `supervise` and `intervention` blocks, so a supervised script ran unsupervised and `weave explain` omitted them.

Fixes: `LoomLoader` keeps the importing file's own `shared_prompt` (as it does for `prompts:`); `LoomScript.merge` carries `supervise` blocks and `intervention`; Gemini recency times are truncated to whole seconds (F04, R3.1).

Tests written first (red on the unmodified code, then green): `RealRunKeepsItsDeclarationsTest` (4), `LoaderKeepsEveryDeclarationTest` (4), `GoogleRecencyWholeSecondsTest` (2), `EveryProviderTimestampIsWholeSecondsTest` (R3.2 audit: only the Google adapter reads the clock).
Guards removed by hand once: removing the loader and merge change fails 7 of the 8 loader and real-run tests (the eighth checks that an imported file's shared prompt is not imposed, which holds either way); removing the truncation fails both Google tests.

Still red on purpose, to be committed with their fixes: output limit 1,024 (F03), a degraded run reported as success (F05), `weave run` prints no result (F07), no estimated label (F08).

