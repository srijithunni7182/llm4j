# Phase 27 test strategy: approval at the boundary

How each requirement of [DESIGN.md](DESIGN.md) is tested: hermetic, deterministic, no model, no network. Tests are written first.

## 1. Principle: `use` is the same tool call, or it is a hole

`use` is a new way into every tool. The strongest test is **parity**: for every control that applies to an agent's tool call (reach, `NetPolicy`, secrets per host, the file root, the shell allow-list, the effect protocol, idempotency, budgets, trace), the same input through `use` gives the same outcome. The parity test is generated from the list of controls, so a new control cannot be added for agents without being checked for `use`.

## 2. Reusable devices

| Device | Used for |
|---|---|
| **Tool-call parity harness**: runs each fixture tool call once through a scripted agent and once through `use`, and compares outcomes, journal entries and trace events | R1 |
| **`MockWebServer`** behind the `http`/`webhook` tools, with `NetPolicy` cases (loopback, private address, metadata address, a host not allowed, plain HTTP) | R1 |
| **`ScriptedHuman`**: a `HumanInterface` fake answering yes, no, never (timeout), or suspending | R2 |
| **Crash points**: kill the run between `effect_pending` and `effect_done`, and between the approval answer and the step | R1, R2 |
| **Rule fixture tables** for LA02, LH02, LH03, LH05 | R3 to R6 |
| **Repository-scripts corpus** | R1, R2 (contextual words) |

## 3. Fixtures

- **`EffectsFixture`**: one tool of every kind undeclared, and the same tools with each declared effect; an OpenAPI spec with GET and POST operations and an `effects:` map covering some; a fake MCP server listing tools with each annotation combination.
- **`UseFixture`**: `use` of a built-in tool, a declared `webhook`, an `http` tool with each `NetPolicy` case, a `file` tool with a `..` path, a `shell` tool with a program not allowed, an OpenAPI operation, a Java tool, and a non-idempotent changing tool with `retry`.
- **`ApproveStepFixture`**: `use … approve`, `run … approve`, with a heading, inside `parallel` and `for each`, with a secret in the arguments, with PII in the arguments and a guard.
- **`UnattendedFixture`**: the same agent with `approve: [Send]` in a workflow with a `schedule`, one named by a trigger, one run by the daemon, and one started by hand.
- **`QuestionCountFixture`**: agents with `approve:` and `max_iterations: 20` inside a loop of 2; a step `approve` inside `for each` over 5; a `check … ask person` (when phase 26 exists).

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R0** | `ToolEffectParsesTest` (`effect:` on every kind; `effects:` for openapi and mcp), `DeclaredEffectWinsTest` (a POST tool declared `reads` is treated as reading by the runtime, `weave check`, the audit and review), `AssumedEffectTableTest` (one row per kind in DESIGN R0, pinning today's behaviour for undeclared tools), `OpenApiOperationEffectsTest`, `McpHintsTest` (`readOnlyHint`, `destructiveHint`, none, server unreachable with and without a cached list), `ReachMapsToEffectTest`, `EffectAndReachTogetherIsAnErrorTest`, `EffectSourceShownTest` (explain, graph, review JSON), `OneResolverTest` (the audit, the runtime and review ask `Capabilities.resolve` and agree on every fixture tool), `ChangesMeansChangesEverywhereTest` (sinks, LA02, LH02, LH05, retry refusal and mock recording all follow a declared effect) |
| **R1** | `UseParsesTest`, `UseKeywordIsContextualTest` (`use partial` and `use:` unchanged), `UseArgumentsCheckedAgainstToolParametersTest`, `UseToolCallParityTest` (the parity harness over every control), `UseNetPolicyRefusalsTest`, `UseEffectProtocolNoRepeatAfterCrashTest`, `UseRetryRefusedOnNonIdempotentChangingToolTest`, `UseScriptedInWeaveTestTest`, `UseMockedInEvalMockTest`, `UseGraphNodeTest`, `GraphRenderKindsInSyncTest`, eval4j `UsesToolAssertionTest`; with phase 26: `UseIsASinkTest` |
| **R2** | `ApproveOnUseAsksOnceWithExactArgumentsTest`, `ApproveOnRunAsksOnceTest`, `ApproveAndRequiresApprovalAskOnceTest`, `ApproveNoFailsStepWithWhoTest` (no `on_declined`), `ApproveNoRunsOnDeclinedTest` (binds nothing; `on_failure` not run), `OnDeclinedWithoutApproveIsAnErrorTest`, `ApproveTimeoutIsNoTest`, `ApproveJournaledNeverAskedTwiceAfterResumeTest`, `ApproveNoNeverRunsAfterResumeTest`, `ApproveShowsSecretsByNameOnlyTest`, `ApproveMasksPiiUnderGuardTest`, `ApproveHeadingTest`, `ApproveInParallelTest` |
| **R3** | LA02 rule-page golden text; LA02 fixture: condition unchanged |
| **R4** | LH03 fixtures from `QuestionCountFixture` (worst case computed and the message names each source) |
| **R5** | LH02 fixtures for steps (fires on read-only `use`, on `NONE`/`READS` tasks; not on changing ones) |
| **R6** | LH05 fixtures from `UnattendedFixture` (fires for schedule, trigger, daemon; not for a hand-started run; not for a read-only tool; not for a `use … approve`) |
| **R9 (D35)** | `GroupedFindingsTest` (text, JSON, panel), `NewSinceLastReviewFirstTest`, `BaselineSuggestedOnceAfterUpgradeTest`, `BaselineSuggestionNotRepeatedTest`, precision on the benchmark corpus for LH01 to LH05, `ReviewNoiseGateTest` |
| **R6, R0 (D34)** | `ToBoundaryFixDiffTest`, `ToBoundaryNotOfferedWhenNotMechanicalTest`, `DeclareEffectQuickFixTest` (editor and review option) |
| **R10 (D41)** | LR11 fixture table: fires for prose into a changing agent, a changing `use`, a `CHANGES` task, directly and through `set`; quiet when an `extract`, a reading task, a classifier, a `check`, a `decide` or `approve` stands between, and quiet for a tool whose effect is `reads`; `InsertExtractFixDiffTest`, `ApproveStepFixDiffTest`; precision on the benchmark corpus; `ReviewNoiseGateTest` |
| **R7** | `OversightLensOnByDefaultTest`, `LensesMinusOversightRestoresTest`, rule index regenerated without *(off)* |
| **R8** | `StartersTest` (starter 07 loads and passes `weave check`, tests pass for a yes, a no and a failed send, diagram matches, LH04 no longer fires on it), `LoomGuideBlocksParseTest`, `TutorialExamplesTest`, `GuideTutorialPageTest`, `verify-skill-package.sh`, CTK scenarios `use_tool` and `approve_step` |

## 5. Fault injection

The tool's server returns 500, 429 with `Retry-After`, a 10 MB body (bounded by the tool's response limit), a redirect to a private address (refused by `NetPolicy`, as for agents); the approval channel is down (the step suspends, as `human_prompt` does, and resumes when an answer arrives); an approval answered after the run was cancelled (ignored, recorded); a crash between the yes and the tool call (on resume the step runs once, with the same idempotency key).

## 6. Mutation testing

`UseRunner`, the approval-on-step logic and LH03's counting join the Loom `mutation` profile. Guards removed once by hand and the failing tests recorded in `VERIFICATION.md` §7: the `NetPolicy` check through `use` (parity fails), the "no means no after resume" rule, and the exact-arguments display.

## 7. Deliberately not tested

- The look of the approval message on each channel beyond the arguments and heading it carries (the channels' own tests own their formatting).
