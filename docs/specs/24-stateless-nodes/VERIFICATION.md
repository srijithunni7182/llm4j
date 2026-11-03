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
| R4 | `MissingOptionalFieldReadsAsMissingTest`, `NullOptionalFieldReadsAsMissingTest`, `NullRequiredFieldFailsAtOnceTest`, `MalformedAnswerIsCorrectedOnceThenRetriedThenFailsTest`, `RequiredFieldIsNeverInventedTest` | ◐ native JSON mode deferred by the owner (2026-11-03): no provider has one; the single entry point is `ExtractRunner.request()` |
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

**Added since (2026-11-03):** graph kind `extract` (`ExtractGraphTest`, `sync-graph-render.sh` run, `check-graph-render-sync.sh` in sync); `weave test` scripting of extractors (`ExtractScriptedTest`; a misspelt name in `models:` is refused with a hint); `--mock` answers an extractor with a well-formed value (`ExtractMockedTest`, via `ExtractorPrompt`); eval4j `runsExtractTimes`, `extractedField`, `leftMissing` (`WorkflowExtractAssertTest`; the trace holds field names, so `extracted(name, field, value)` of the DESIGN became "was the field found", a deliberate deviation since values never enter a trace); cost and time estimates list `extract Name` as its own line (`ExtractCostTest`); `InternalNodeContractTest` over the classifier and the extractor.

**Guards removed by hand, then restored:** a required field answered `null` accepted → `aRequiredFieldAnsweredNullFailsAtOnceWithoutAskingAgain` failed; a required field left out accepted → `aRequiredFieldIsNeverInventedByTheRuntime` and `aWrongKindAnUnknownEnumAndALeftOutRequiredFieldAreEachMalformed` failed.

**Gates run (2026-11-03):** `mvn verify` on `eval4j` (571 tests) and `ai-agent4j-loom` (4131 tests, 4 skipped as before): green, "All coverage checks have been met", no limit lowered, nothing excluded. After `mvn -pl src/loom/ai-agent4j-loom -DskipTests install`: `eval4j-report`, `loom-inspect-core`, `loom-fabric`, `loom-inspect` `verify` green. `make format-check` covers core and addons only, which this phase has not touched.

**Mutation (2026-11-03):** new profile `mutation-extract` (`ExtractorChecks`, `ExtractRunner`, `ExtractAnswer`, tests `extract.*Test`, threshold 75). First run 76% (45 of 59); after `ExtractRunnerEdgesTest` (backoff doubling, a recorded failure replayed without asking again, `pii: mask`, an input with no value, timeouts, multi-line replies) **82% (93 of 114)**. Surviving mutants, not excluded: trace and audit calls (`audit`, `traceFailure`), a replay's `setVariable` (the value is also reached through the journal), a `Thread.interrupt` call, a condition boundary on the retry counters, and the first-line trimming of an error message. Each is either equivalent or observable only through a log line.

**Docs and tooling run for 24a:** `LOOM_GUIDE.md` (section "Extracting fields" and "Internal nodes"; its blocks are parse-tested), `LOOM_PROMPT.md`, `docs/guide/06`, tutorial chapter 5 (new section with a "you don't need this until" box) and the tutorial README row, `bash scripts/make-tutorial-html.sh`, the `llm4j-workflow-guide` skill, the CTK scenario `extract_fields` with `ExtractCtkConformanceTest`, the VS Code grammar, snippets (`extractor`, `extract`), hover text, README and CHANGELOG (`npm test` 773 of 773), `graph-render` (`npm test` 91 of 91), `API_COMPATIBILITY.md`, `llms.txt`, release notes 6.2.0. `TutorialExamplesTest`, `GuideTutorialPageTest`, `StartersTest`, `RepositoryScriptsTest` green.

**Still open in 24a:** native JSON mode (deferred by the owner), the whatif/`Decisions`, `Capture`, `ScriptDrift`, `Narrator` and `weave explain` wiring for `extract`, the harness-design and eval-design skills, `docs/guide/05`, and the wiki/stack-level pages (the latter once, in phase 29).

### 24b: transcript and shared-prompt opt-out, LP08 (2026-11-03)

Tests first (`TranscriptModesTest`, 13; `ExtractCtkConformanceTest` earlier; `TranscriptVisibleTest`, 2; the preflight C4 case; `SimplePathStaysSimpleTest`, 2; `ReviewNoiseGateTest`, 1; corpus scripts `lp08-*`), red for the right reason, then green.

**Done:** `memory { transcript: none | own | all }` (engine tags each exchange with its agent; `all` is the default and unchanged, pinned by `allIsTheDefaultAndSeesEverythingSaidBefore`); `shared_prompt: none` on an agent (parser, `AgentDef`, `AgentPrompts`); `handoff` and `delegate` honour the mode; a custom `MemoryEngine` is told the mode through `AgentDef.getTranscriptMode()`; a rewind restores per mode; validator: unknown mode refused with the choices, `none` with `max_tokens` warns "no effect", a memory block with only `transcript:` is valid; preflight C4 skips an excluded agent and says why; D39: `weave explain` ("sees everything said before it" and its siblings), graph JSON agents carry `transcript` and `sharedPromptExcluded` (schema, goldens regenerated and reviewed: the only change is the new key), the editor's agent card shows what the agent sees; LP08 (rule data in `prompts.yaml`, four example scripts, two corpus scripts, `docs/review/rules/lp08.md` regenerated with `weave rules docs core`); `SimplePathStaysSimpleTest` and `ReviewNoiseGateTest` (reusable; later phases add their rule ids and constructs to the lists in them).

**Changed existing test, on purpose:** `ExplainCommandTest` pinned the exact agent line; it now ends with "; sees everything said before it." (the visible-default decision D39). Nothing else was edited.

**Gates (2026-11-03):** `mvn verify` `eval4j` 571 and `ai-agent4j-loom` 4157 tests (3 skipped), coverage checks met, none lowered. Downstream `eval4j-report`, `loom-inspect-core`, `loom-fabric`, `loom-inspect` green after `install` and a **clean** build (a first run failed `DeployerScriptsTest` with "loom-inspect 6.2.0 (development, 603a71ab) cannot run with ai-agent4j-loom ... (development, 9bbe7f49): they must be the same build": modules stamped at different commits; not a code fault; remember `clean` when the commit changes). VS Code `npm test` 773 of 773; `graph-render` 91 of 91; `check-graph-render-sync.sh` in sync; tutorial page regenerated, `TutorialExamplesTest`, `GuideTutorialPageTest`, `StartersTest` green.

**Docs (24b):** `LOOM_GUIDE.md` ("Who sees what was said before", shared prompt: leaving out and tone-not-rules), `LOOM_PROMPT.md`, tutorial chapters 3 (new "Agents that talk to people, and nodes that work for the workflow" with a "you don't need this until" box), 7 ("Who sees what was said before") and the README rows, `API_COMPATIBILITY.md`, release notes, `llms.txt` unchanged, VS Code hover and CHANGELOG, the harness-design and eval-design skills, `docs/review/rules/`.

**Not done in 24b:** a mutation-profile entry for the mode switch in `TranscriptAccumulationEngine` (the `mutation-extract` profile covers the extractor only), and the by-hand removal of `transcript: none` (see below, done).

**Guard removed by hand, then restored (24b):** `case NONE -> List.of()` changed to `entries` in `TranscriptAccumulationEngine.visibleTo` → `noneReceivesNothingBeyondItsTask` and `handoffHonoursTheMode` failed.

### 24c: LP09 with its fix, and LA21 (2026-11-03)

Tests first: `TranscriptTaintTest` (11), `ToExtractorFixTest` (8), LP09 rule fixtures (seven examples, two corpus scripts, ten existing corpus scripts marked `// ignore: LP09` where they test other rules), then green.

**Done:** LA21 (`TranscriptTaint`, wired into `SecurityAudit`; listed in `AuditCatalog`, `docs/security/weave-audit.md`, `owasp-llm-top-10.md`, `secure-by-construction.md`; `SecurityDocTest` green): an agent that receives the whole transcript after an untrusted reader has run, by statement order, through `call` and `handoff`, in a later loop round, with `alt` branches and parallel rounds treated as possibly sequential; it emits LA21 and, where the trifecta completes, LA01 naming the path (HIGH with an open way out, MEDIUM when every way out is approved, INFO for a receiver that cannot act). LP09 (rule data plus new agent facts `grounded`, `choosesOneLabel`, `delegatesPassVariables`; setting `prompts.short-prompt-tokens`, default 300) and its `to-extractor` option (`JavaFixOption`, `ToExtractorFix`, registered in `JavaFixOptions`; review safety so `--yes` is required, `--dry-run` shows the diff; the finding says why when it is not offered). Tutorial chapters 9 (LA21 with a diagram) and 14 (recipe, cheat sheet, mistakes table), guide, release notes, API compatibility, rule pages regenerated.

**Deviations from the DESIGN, to be confirmed by the owner:**
1. **LP09 is narrower than R10.** `SimplePathStaysSimpleTest` (D34 A) failed on starters 03 and 04 with the rule as written. It now requires the agent's **own** `output_schema` (a per-step `expecting` is how an agent is used in several ways, as starter 03's reviewer is) and excludes a schema that is a single enum field (that picks a label: a classifier's job, as starter 04's router is). Starter 08 still fires. Revert in `prompts.yaml` if the owner wants R10 literally; D34 A would then fail on 03 and 04.
2. **An extractor between two agents does not break LA21 by itself** (the DESIGN's `ExtractorBetweenBreaksTaintTest` implies it does). The reader's answer is stored in the transcript whatever a later step extracts, so the only things that break the path are `transcript: none | own` on the receiver or splitting the work; the finding and the tutorial say so, and `anExtractorBetweenThemDoesNotByItselfStopTheTranscript` pins it.
3. **`weave generate`'s review gate ignores LP09** (`ReviewGate.ADVICE_ONLY`). Without that, LP09 (Prompts lens, medium) failed the generator's G2 gate and added a repair call to 11 of the 28 recorded transcripts in `GenerateBenchmarkTest`; the generator's own LA01 recipe also tells it to give an agent a typed answer. LP09 still shows in `weave review` for generated projects.
4. **`to-extractor` drops the lead-in text** before the variable ("Extract the invoice from: {text}" becomes `from text`) and is offered only for a single labelled variable or one labelled variable per line. The DESIGN's "text around the variables that carries meaning" cannot be decided without reading meaning (D11), so the mechanical test is: nothing but a `label:` before each variable.

**Guards removed by hand, then restored:** the reader being recorded (`readers.add`) → five `TranscriptTaintTest` cases failed; the `transcript: all` check on the receiver → `transcriptNoneOnTheReceiverBreaksTheTaint` and `transcriptOwnAlsoBreaksIt…` failed.

**Gates (2026-11-03):** `mvn verify` `eval4j` 571 and `ai-agent4j-loom` 4177 (3 skipped), coverage met, nothing lowered; downstream modules green after `install` and a clean build (`ServerLinkTest.finishDeliversWhatIsLeftQuickly…` failed once on timing and passed on rerun without a change); VS Code 773 of 773; `mutation-extract` 82% (unchanged); `ReviewCorpusTest` LP08/LP09/LA21 precision and recall 1.00 on the labelled corpus; `ReviewNoiseGateTest` and `SimplePathStaysSimpleTest` extended with LP09 and LA21 and green.

**Still open:** mutation profile entries for `TranscriptTaint` and the mode switch; the Fabric/live-page views of `extract` (the new trace events and the graph kind are in, but the live page was not extended); `Decisions`/`Capture`/`ScriptDrift`/`Narrator` for `extract`.

