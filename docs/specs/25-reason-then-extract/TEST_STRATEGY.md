# Phase 25 test strategy: reason, then extract

How each requirement of [DESIGN.md](DESIGN.md) is tested: hermetic, deterministic, no model. Tests are written first.

## 1. Principle: a rule is tested by what it fires on and what it leaves alone

LP10 is only useful if it is quiet on the right cases: a small verdict schema on a tool-using agent, a large schema on a short-prompt agent with no tools. So every rule has a **table of firing and non-firing scripts**, and the non-firing rows are as many as the firing ones. The rewording of LR05 and LP02 is tested by **running their recommendation through LP10**, so the two can never contradict each other again.

## 2. Reusable devices

| Device | Used for |
|---|---|
| **Rule fixture tables** under `review-rules/core/examples/`, run by `weave rules test core` and `PackTester` | R1, R2 |
| **Golden diffs** for the split fix | R3 |
| **Whole-repository rule run**: LP10 over every `.loom` in the repository (the `RepositoryScriptsTest` corpus) | R4 |
| **Recommendation round-trip**: apply each rule's documented fix to its own fixture, then run LP10 on the result | R2 |
| **Example test suites**: each migrated example's own `weave test` cases and JUnit tests | R4 |

## 3. Fixtures

- **`ReasoningAgentFixture`**: one agent per reason (tools; grounding; works over rounds; long prompt) crossed with one schema per size (flat 2 fields; flat 6 fields; nested; list of objects). 16 cells; the expected firing set is written in the test as a table.
- **`SplitFixture`**: an agent used as fields only (fix offered); used as both prose and fields (not offered); used in a `broadcast` (not offered); with `expecting` on one delegate only.
- **`RulesSettingsFixture`**: `review.properties` with `prompts.short-prompt-tokens` and `prompts.large-schema-fields` changed.

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | LP10 fixture table (16 cells, firing set asserted); `Lp10MessageNamesTheReasonAndTheSchemaTest`; `Lp10SettingsTest`; `Lp10UsesExpectingOfAnyDelegateTest`; `ReasonsFactsTest` and `SchemaFactsTest` (unit tests of the new facts); `RoundsDetectionTest` (agent's answer fed back in a loop; a loop that does not feed back is not "rounds") |
| **R2** | `Lr05AndLp02RecommendationsPassLp10Test` (round-trip); rule-page golden text for both (`weave rules docs core` output compared) |
| **R3** | `SplitFixDiffTest` (golden), `SplitKeepsLaterUsesOfTheVariableTest` (every `v.field` still resolves, checked by `weave check`), `SplitNotOfferedForProseAndFieldsTest`, `SplitNotOfferedForBroadcastTest`, `SplitListsTestCasesToUpdateTest`, `SplitNeedsYesTest`, `SplitAddsNoHighFindingTest`, `SplitPicksCheapestModelOfSameProviderTest` |
| **R4** | `ExamplesAreCleanOfLp10Test` (whole repository, with the recorded acceptance for the LC01 fixture as the only exception and the test listing it by name); each example's own tests green; `InspectSimExpectedTracesTest` updated for the extra step |
| **R5** | `LoomGuideBlocksParseTest` (new section), `TutorialExamplesTest`, `GuideTutorialPageTest` |

## 5. Fault injection

A schema with a field typed as a bare list (counts as a list, not a list of objects); an agent whose prompt file cannot be read (prompt size unknown: LP10 does not guess, it does not fire on size and says so in `--explain`); a script with `expecting` that differs per step; a split applied to a script under `substitution: legacy` (the new extract line uses braces and the diff says so).

## 6. Mutation testing

The `reasons` and `large` fact computations join the Loom `mutation` profile. The size thresholds are removed once by hand (fact always true) and the fixture table goes red; recorded in `VERIFICATION.md` §7.

## 7. Deliberately not tested

- Whether a real model reasons better in prose than in JSON. The owner's experience and the literature motivate the phase; a capped live comparison on one example is a manual check (`VERIFICATION.md` M2), not a gate.
