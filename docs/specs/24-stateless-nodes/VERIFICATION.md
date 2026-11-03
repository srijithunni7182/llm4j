# Phase 24 verification plan: stateless nodes and least-privilege context

Status: **not started** (spec draft). Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **24a** the extractor and the internal-node contract (R1 to R7); **24b** transcript and shared-prompt opt-out with LP08 (R8, R9); **24c** LP09 with its fix, and LA21 (R10, R11); **24d** examples, starters and the tutorial (R12, and the tutorial rows of each earlier sub-phase that were not already shipped with it). Results in §7.
Evidence: E1 to E6 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E1 | Run a two-agent workflow under `weave test` with a capturing client; the first agent answers a canary. | the second agent's request contains `Conversation History` and the canary | ✅ 2026-11-01 at `28c74ca9`: `Phase24ReproductionTest.p1…` passes: second agent's request holds `Conversation History` and the canary. |
| P2 | E2 | Give the second agent `memory { max_tokens: 1 }`. | the canary is still sent (the latest two exchanges are kept); `max_tokens: 0` is refused | ◐ confirmed by reading `TranscriptAccumulationEngine.Limit` (rejects `maxTokens < 1`; latest two exchanges kept), not run. |
| P3 | E3 | A canary `shared_prompt` and two agents. | both agents' system prompts start with the canary; no setting removes it from one | ✅ 2026-11-01: `p3…` passes: both agents' requests carry the shared-prompt canary. |
| P4 | E4 | A `Task.withModel` run after the canary agent. | its request has no transcript and no shared prompt (the stateless path exists, in Java only) | ◐ confirmed by reading `TaskRunner.java:206` / `HarnessExecutor.clientForTask` (bare metered client), not run. |
| P5 | E5 | Read the guide's Intake example and starter 08; run starter 08 after a canary agent added in front. | the extractor agent receives the canary | ◐ confirmed by reading starter 08 (`agent Extractor` with `output_schema`, receives the transcript as any delegate does), not run with a canary agent. |
| P6 | E6 | `weave audit` on the TaintFixture (Researcher with a web tool, Sender with private data and an outward tool, both default transcript). | no LA01 for Sender; nothing mentions the transcript | ✅ 2026-11-01: `p6…` passes: no LA01 and no mention of the transcript for Researcher → Sender. |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | A script declares an `extractor` and runs it with `extract … from`, with no Java | `ExtractorParsesTest`, `ExtractStatementParsesTest`, CTK `extract_fields` |
| A2 | The extractor's request holds only the preamble, its instructions, the schema and its marked inputs: no transcript, no shared prompt, no persona; its answer is not stored in the transcript | `ExtractorSeesOnlyItsInputsTest`, `ExtractorAnswerIsNotWrittenToTranscriptTest`, `ExtractorInputsAreMarkedAsDataTest`, P1 to P5 compared |
| A3 | Optional fields read as `missing`; a required field is never invented; answered `null` it fails at once into `on_failure`; a malformed answer is corrected once, retried, then fails; `_error` names the fields | the R4 tests |
| A4 | An extract step is budgeted, journaled, scripted in tests, mocked in eval, traced with names only, drawn in the graph, and asserted in eval4j | the R5 tests |
| A5 | `weave check` refuses every misuse in DESIGN R6 with a message that says what to write | `ExtractorCheckErrorsTest`, `ExtractorForbiddenSettingsTest` |
| A6 | One contract test covers every internal node kind | `InternalNodeContractTest` |
| A7 | `transcript: none | own | all` and `shared_prompt: none` behave as specified; the default is unchanged | the R8 tests, `TranscriptAllIsTheDefaultTest` |
| A8 | LP08 fires only when a delegate to an opted-out agent passes no variables | LP08 rule fixtures |
| A9 | LP09 spots an agent used as an extractor and its fix produces a script that checks and no longer trips it | LP09 fixtures, `ToExtractorFixDiffTest` and its siblings |
| A10 | LA21 counts the transcript as a path for untrusted text, completes LA01 where the legs meet, names the path, and is broken by `transcript: none` or an extractor in between | the R11 tests, P6 re-run |
| A11 | The guide's Intake example and starter 08 use an extractor; their tests and diagram pass | `StartersTest`, `LoomGuideBlocksParseTest` |
| A12 | Every existing script parses and checks as before; every behaviour change is in the release notes and `API_COMPATIBILITY.md` | `RepositoryScriptsTest`, existing tests unmodified, DESIGN §5 |
| A13 | Every guard is noticed when removed | PIT score not lower; by-hand removals in §7 |
| A14 | The tutorial teaches extractors, the transcript modes and LA21, and its examples load | `TutorialExamplesTest`, `GuideTutorialPageTest`, the rows of DESIGN §9 ticked in §5 |
| A15 | The simple path stays simple and new rules pass the precision bar and the noise gate; agents that receive the full transcript are marked; extract steps are metered like agents | `SimplePathStaysSimpleTest`, `ReviewNoiseGateTest`, `FullTranscriptMarkedInGraphExplainEditorTest`, `ExtractMeteredLikeAnAgentTest` |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `ExtractorParsesTest`, `ExtractorForbiddenSettingsTest`, `LoomScriptMergeKeepsExtractorsTest`, `ExtractorKeywordIsContextualTest` | ⬜ |
| R2 | `ExtractStatementParsesTest`, `ExtractRequiresVariableTest` | ⬜ |
| R3 | `ExtractorSeesOnlyItsInputsTest`, `ExtractorAnswerIsNotWrittenToTranscriptTest`, `ExtractorInputsAreMarkedAsDataTest` | ⬜ |
| R4 | `MissingOptionalFieldReadsAsMissingTest`, `NullOptionalFieldReadsAsMissingTest`, `NullRequiredFieldFailsAtOnceTest`, `MalformedAnswerIsCorrectedOnceThenRetriedThenFailsTest`, `RequiredFieldIsNeverInventedTest`, `NativeJsonModeUsedWhenProviderHasItTest` | ⬜ |
| R5 | the A4 tests, `GraphRenderKindsInSyncTest` | ⬜ |
| R6 | `ExtractorCheckErrorsTest` | ⬜ |
| R7 | `InternalNodeContractTest` | ⬜ |
| R8 | the A7 tests, `OptedOutAgentIsStillSeenByOthersTest`, `PreflightC4SkipsExcludedAgentsTest`, `RewindRestoresPerModeTest`, `HandoffHonoursTranscriptModeTest`, `CustomMemoryEngineSeesModeTest` | ⬜ |
| R9 | LP08 fixtures | ⬜ |
| R10 | LP09 fixtures, `ToExtractorFixDiffTest`, `ToExtractorNotOfferedWhenTaskCarriesMeaningTest`, `ToExtractorRefusesBracesInSystemTest`, `ToExtractorNeedsYesTest` | ⬜ |
| R11 | the A10 tests, `SecurityDocTest` | ⬜ |
| R12 | `StartersTest`, `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest`, CTK `extract_fields`, `transcript_modes` | ⬜ |
| R13 | `SimplePathStaysSimpleTest`, `ReviewNoiseGateTest` | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `eval4j`, and every module touched; coverage gates held, none lowered, nothing excluded.
- [ ] After changing Loom classes that `loom-inspect*` and `loom-fabric` use: `mvn -pl src/loom/ai-agent4j-loom -DskipTests install`, then their `mvn verify` green.
- [ ] PIT: the Loom `mutation` profile with `ExtractRunner`, the transcript mode switch and the taint computation added; score not lower.
- [ ] `RepositoryScriptsTest` green; CTK scenarios `extract_fields` and `transcript_modes` added.
- [ ] Loom syntax changes everywhere they belong ([CLAUDE.md "things that change together"](../../../CLAUDE.md)): `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md`, the tutorial, the starters, the `llm4j-workflow-guide` skill, the VS Code grammar and snippets, a CTK scenario.
- [ ] `bash scripts/sync-graph-render.sh` then `bash scripts/check-graph-render-sync.sh` (new `extract` kind).
- [ ] `bash scripts/make-tutorial-html.sh` run; `GuideTutorialPageTest` green.
- [ ] `bash scripts/make-starter-diagrams.sh` run; `StartersTest` green.
- [ ] `weave rules docs core` regenerated `docs/review/rules/`; `weave rules test core` green.
- [ ] `SecurityDocTest` green (LA21 in `docs/security/weave-audit.md`).
- [ ] `make format-check`, `make release-check` green.
- [ ] No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | A capped real run (`--max-tokens 20000`) of starter 08 on a real invoice text with a cheap model: fields right, the missing total reads as `missing`, the trace shows field names only | ⬜ |
| M2 | Read the extractor's real request in `--trace` debug output and confirm nothing but the preamble, instructions, schema and inputs is there | ⬜ |
| M3 | Apply LP09's fix to the owner's own project (or an example) and read the diff aloud: would a reviewer understand it? | ⬜ |
| M4 | Read the new tutorial sections as a newcomer: does chapter 3's internal/external section make sense before chapter 5 shows the syntax? | ⬜ |

## 5. Documentation owed

The pages in DESIGN §8 and the tutorial rows in DESIGN §9, each ticked when updated: `LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/06` ⬜ · tutorial ch. 3 ⬜, 5 ⬜, 7 ⬜, 9 ⬜, 14 ⬜, README ⬜, HTML regenerated ⬜ · starter 08 and starters README ⬜ · `docs/review/rules/` ⬜ · `docs/security/*` ⬜ · skills (workflow guide ⬜, harness design ⬜, eval design ⬜) · VS Code grammar, snippets, CHANGELOG ⬜ · graph-render ⬜ · eval4j README ⬜ · `API_COMPATIBILITY.md` ⬜ · `llms.txt` ⬜ · release notes ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids; anything that did not reproduce reported
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] M1 to M4 done and recorded in §7
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated; roadmap status updated in [the spec README](../README.md)

## 7. Results

### 24a, in progress (2026-11-03)

Built, tests first (red for the right reason, then green): `ExtractorParsingTest` (19), `ExtractRunnerTest` (15), `ExtractorCheckErrorsTest`. Whole `ai-agent4j-loom` module: 4113 tests, 0 failures, 4 skipped (as before), `-Djacoco.skip=true`. `Phase24ReproductionTest` stays as the pin of the default (`transcript: all`, shared prompt to every agent).

**Done:** R1 (AST, parser, forbidden settings, duplicate field, optional names), R2 (statement), R3 (preamble, instructions, schema, marked inputs; no transcript, no shared prompt; answer not stored), R4 (missing/null optional, required null fails at once, malformed corrected once then retried), R5 in part (budget scopes, metering under `extractor <Name>`, journal and replay, trace `extract_start`/`extract_end`/`extract_replayed` with names only), R6 (validator checks), `missing` in conditions, `PiiGuard`/`NodeInputs` shared with the classifier.

**Not done yet in 24a:** native JSON mode (no provider in the core implements one; needs a provider-module change, to be decided with the owner), graph kind `extract` and `sync-graph-render`, `weave test` scripting and `--mock` for extractors, eval4j assertions, `weave explain`/narrator, cost estimate line, `InternalNodeContractTest`, the SecurityAudit/Capture/ScriptDrift/Decisions (whatif) wiring, PIT additions, coverage gates (`mvn verify`), hand-removed guards, and every document row of §5.

