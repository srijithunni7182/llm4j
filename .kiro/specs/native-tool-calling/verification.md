# Native tool calling, tolerant parsing, outcome-based confidence, CI: verification

Companion to [design.md](design.md) and [test-strategy.md](test-strategy.md). Status in section 2 is **computed from the surefire reports of the final run**: *Verified* means every named test exists and passed.

## 1. Gates

| Gate | Check | Command | Result |
|---|---|---|---|
| G1 | Specs written, every ID has a row | design, test strategy, this plan | section 2 |
| G2 | Whole reactor compiles (examples included) | `mvn -q -o -DskipTests -Djacoco.skip=true compile` | exit 0 |
| G3 | New tests | `mvn -pl ai-agent4j test -Dtest='io.github.llm4j.toolcalling.*Test,CiCoversEveryModuleTest'` | 70 tests pass (model 12, provider wire 12, agent 19, text tolerance 17, confidence 6, end to end 2, CI guard 2) |
| G4 | Full regression, each touched module | `mvn -o -pl <module> verify` | ai-agent4j 853 (2 skipped, as before); addons 17; src/loom/ai-agent4j-loom 819 (1 skipped, as before) incl. jacoco rules; eval4j 466; eval4j-report 46; src/loom/ctk 30; ai-agent4j-tools 381 and engram 9 in the root-reactor run. 0 failures, 0 errors |
| G5 | No keys needed | every gate above ran with no provider key in the environment | same |
| G6 | Negative controls | section 3 | each of the 6 controls made its named tests fail |
| G7 | Real services | `LiveNativeToolCallingTest` (L12, L13) | **not run**: they need Gemini/Claude keys and are skipped without them; written and compiled, to be run by the maintainer with `-Plive` |

## 2. Requirement to test matrix

| ID | Requirement | Tests (all must pass) | Status |
|---|---|---|---|
| TCM-01 | `ToolSpec`: function-legal names, defaults | `ToolCallingModelTest#tcm01*` | Verified |
| TCM-02 | `ToolCall`: immutable arguments, ids can be added | `ToolCallingModelTest#tcm02*` | Verified |
| TCM-03 | `Message`: tool role, calls, results, provider data; plain messages unchanged on the wire | `ToolCallingModelTest#tcm03*` | Verified |
| TCM-04 | `LLMRequest`: tools, tool choice, complete `toBuilder()` | `ToolCallingModelTest#tcm04*` | Verified |
| TCM-05 | `LLMResponse`: tool calls and provider data | `ToolCallingModelTest#tcm05*` | Verified |
| TCM-06 | `supportsToolCalling()` and refusal of tools by clients that cannot honour them | `ToolCallingModelTest#tcm06*` | Verified |
| TCM-07 | `ToolSchema`, permissive default, tools declare real schemas | `ToolCallingModelTest#tcm07*`<br>`NativeAgentTest#tca02_toolSpecsComeFromTheToolsDeclaredSchemas` | Verified |
| TCP-01 | Gemini: function declarations, tool config, schema conversion, function calls read | `NativeProviderWireTest#tcp01*` | Verified |
| TCP-02 | Claude: tools, tool choice, tool_use blocks read | `NativeProviderWireTest#tcp02*` | Verified |
| TCP-03 | raw parts/blocks sent back verbatim; tool results merged as each API requires; end to end on both | `NativeProviderWireTest#tcp03*`<br>`NativeEndToEndTest#*` | Verified |
| TCP-04 | finish reason is TOOL_CALLS whenever there are calls | `NativeProviderWireTest#tcp01_googleReadsFunctionCallsAndKeepsTheRawParts`<br>`NativeProviderWireTest#tcp02_anthropicReadsToolUseBlocksAndKeepsTheRawContent` | Verified |
| TCP-05 | Sarvam and Ollama stay on the text protocol; tools to them are refused before sending | `NativeProviderWireTest#tcp05*` | Verified |
| TCP-06 | wrappers delegate support and pass tools, results and provider data through; masking covers tool text | `ToolCallingModelTest#tcp06*` | Verified |
| TCA-01 | `toolCalling(AUTO|NATIVE|TEXT)`; `toBuilder` keeps mode and prompt | `NativeAgentTest#tca01*` | Verified |
| TCA-02 | mode selection: capable client, no tools, illegal names, custom prompt, NATIVE refusals | `NativeAgentTest#tca02*` | Verified |
| TCA-03 | the loop: answer, tool call and result, several calls, iteration limit, listeners, usage, budgets | `NativeAgentTest#tca03*` | Verified |
| TCA-04 | every call gets a result; approvals, duplicates, unknown tools, errors | `NativeAgentTest#tca04*` | Verified |
| TCA-05 | arguments reach the tool unchanged; free-form input unwrapped only for undeclared tools | `NativeAgentTest#tca05*` | Verified |
| TCA-06 | an empty reply is nudged, not accepted | `NativeAgentTest#tca06*` | Verified |
| TCA-07 | native prompt: instructions, persona, skills, no JSON protocol | `NativeAgentTest#tca07*` | Verified |
| TCF-01 | fence variants and bare protocol JSON | `TextProtocolToleranceTest#tcf01*` | Verified |
| TCF-02 | multi-line and CRLF `Action Input:` | `TextProtocolToleranceTest#tcf02*` | Verified |
| TCF-03 | nothing that parsed before stops parsing | `TextProtocolToleranceTest#tcf03*` | Verified |
| TCC-01 | confidence by `StepOutcome`, not by text | `ConfidenceOutcomeTest#*` | Verified |
| CI-03 | every module with tests is in both pipelines (tantrik exempt) | `CiCoversEveryModuleTest#*` | Verified |

CI-01 and CI-02 (the pipelines themselves) cannot run here; CI-03 checks both files name every module that has tests, and the first real run of the workflow and the Jenkinsfile is the remaining evidence.

## 3. Negative controls

| # | Break | Failing tests |
|---|---|---|
| 1 | Old fence regex (`` ```json\n...\n``` ``) | `TextProtocolToleranceTest#tcf01_everyFenceVariantIsParsedAsTheProtocol`, `tcf01_anActionInAnyFenceVariantRunsTheTool` |
| 2 | Tool results not added to the conversation | `NativeAgentTest#tca03_aToolCallRunsTheToolAndItsResultGoesBackAsAToolMessage`, `tca03_severalCallsInOneTurn...`, `tca04_everyCallGetsAResultEvenWhenItDidNotRun` |
| 3 | Confidence counts failures by `startsWith("Error")` again | `ConfidenceOutcomeTest#aToolThatReturnsTextBeginningWithErrorIsNotPenalised`, `theNativeLoopScoresTheSameWay` |
| 4 | Claude's raw content blocks not sent back | `NativeProviderWireTest#tcp03_anthropicSendsBackTheRawContentAndGroupsToolResults`, `NativeEndToEndTest#anAgentOnClaudeCallsAToolAndAnswers` |
| 5 | Budget settlement rebuilds the response without its tool calls | `ToolCallingModelTest#tcp06_aBudgetedResponseKeepsItsToolCallsAndProviderData` |
| 6 | Loom removed from the GitHub workflow | `CiCoversEveryModuleTest#everyModuleWithTestsIsTestedByGitHubActionsAndJenkins` |

## 4. Iteration log

1. `Recorder` test helper was final, so a test could not subclass it; made non-final.
2. `TextProtocolToleranceTest#tcf02_crlfLineEndingsInTheLineFormat` failed: with CRLF, `Thought:` and `Action:` line patterns never matched (`.` does not match `\r`, and the lookahead wanted a bare `\n`). A real bug in the text protocol, fixed with `\r?\n`.
3. While reading the wrappers: `BudgetedLLMClient.settle` rebuilt every response from scratch and would have dropped tool calls whenever usage was estimated; fixed, with a test and control 5.
4. `MaskingLLMClient` rebuilt requests field by field; replaced with `LLMRequest.toBuilder()` (and `Message.toBuilder()`), so no field can be dropped, and tool-result text and tool-call arguments are masked.
5. Gemini refuses an object schema with no properties: tools with no declared parameters are offered one free-form `input` string, and the agent unwraps a JSON object written inside it (only for tools that declare nothing).
6. Wrapper tools (`NamedTool`, `ApprovalTool`, `RecordingTool`, `SimulatingTool`, `EvidenceTool`, `DescribedTool`, `EffectTool`, cached/fallback search) hid their delegate's schema; each now delegates `getParametersSchema()`.
7. `engram` and `tantrik` build only through the root reactor (tantrik needs the parent POM); tantrik is excluded from CI and from the guard test by decision (it is being removed).

## 5. Not covered, stated plainly

- Native calling has not been run against the real Gemini or Claude APIs (see G7). The wire formats follow the providers' documentation and are exercised against mock responses of that shape.
- Sarvam and Ollama stay on the text protocol; streaming does not carry tool calls; several calls in one turn run in order, not in parallel.
- `http` and `openapi` tools keep the permissive schema.
- The xAI banner wording is unchanged, as decided; the confidence score is still a heuristic baseline (now outcome-based).
