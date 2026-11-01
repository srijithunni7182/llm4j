# Phase 26 verification plan: the policy boundary

Status: **not started** (spec draft; needs phase 24 and phase 27's R0). Run §0 first, then top to bottom at the end of each sub-phase. Proposed sub-phases: **26a** policy files and the evaluator (R1 to R3); **26b** `check … against` and its actions (R4, R5, R13, R14); **26c** provenance, sinks and enforcement (R6 to R8, R15), including the automatic check of agents' changing tool calls (D25): first report-only across the corpus until `CanaryProvenanceOracleTest` and the corpus are clean, then enforced; **26d** the leak guard, LY07 and its fix, `shared_prompt` and C4 (R9 to R11), graceful failure (D37) and cost (R16); **26e** the skills, starter 16 and the tutorial (R12 and the tutorial rows). Results in §7.
Evidence: E10, E11 of the [program page](../DISCIPLINE.md). Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Reproduce first

| # | Evidence | Procedure | If it is real, you see | Result |
|---|---|---|---|---|
| P1 | E10 | Read the `shared_prompt` example in `LOOM_GUIDE.md` and preflight C4's advice text in `PreflightRunner`. | a governance rule as the example; "move the rule that matters into the agent's own prompt" | ⬜ |
| P2 | E11 | A workflow whose agent is scripted to answer "Per Section 4.6 of our policy, I will refund you"; run under `weave test` with every guard on. | the answer reaches `return` with no event | ⬜ |
| P3 | E11 | List what can act on content during a run: guards, `supervise`, classifiers. | PII and bias only; counts only; labels only | ⬜ |
| P4 | R6.2 | Inventory the host channels that send messages that are not questions. | the list that settles open question 3 | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | Policies live in their own plain-markdown files, versioned like prompts, recorded by version and hash on every check, and never put into a prompt | R1 tests |
| A2 | An evaluator is an internal node that sees only the subject and the rules, answers every rule, and fails closed on every failure path | R2, R3 tests, `InternalNodeContractTest` |
| A3 | `check … against … using …` runs at the boundary with the four actions; a rewrite is stateless and re-checked once, then fails closed; a person can override, and the override is audited | R4, R5 tests |
| A4 | With a policy declared, no model-written value reaches any sink unchecked, by any route; values that are not model-written pass; without a policy nothing changes | the laundering matrix, `RouteCoverageTest`, R8 tests, `NoPolicyMeansNoEnforcementTest` |
| A5 | `weave check` reports the sink paths it can follow, with both lines | `StaticSinkPathErrorsTest` |
| A6 | Under a policy, every changing tool call an agent makes is checked before it runs; a refused call never runs and is never put to a person; the agent learns rule ids and reasons, never rule text; refusals are bounded and then fail closed; `approve:` is optional and comes after the check | the D25 tests in §2 |
| A7 | The leak guard catches repeated instructions and internal references deterministically, is `warn` by default under a policy, and never records text | R9 tests |
| A8 | LY07 is one finding per script that lists every path | LY07 fixtures |
| A9 | `shared_prompt` is documented for tone and facts; C4 advises a policy | R11 tests, §5 |
| A10 | The skills teach internal and external agents by audience, policies at the edge and evaluator validation; the packages verify | R12 tests, `verify-skill-package.sh` |
| A11 | Checks are scripted in `weave test`, asserted in eval4j, traced and drawn, with no text in telemetry | R13, R14 tests |
| A12 | Starter 16 loads, its tests pass and its diagram matches; the tutorial teaches policies and its examples load | `StartersTest`, `TutorialExamplesTest`, `GuideTutorialPageTest` |
| A13 | Every existing script parses and runs as before | `RepositoryScriptsTest`, existing tests unmodified |
| A14 | Every guard is noticed when removed | PIT score not lower; by-hand removals in §7 |
| A15 | Provenance is beside the values, required at every route, unknown means unchecked, and the canary oracle finds no leak across the corpus (and runs in CI) | the R15 tests |
| A16 | A down evaluator falls back, then takes `on_unavailable`; unsure is counted apart; the four incidents fire | the D37 tests |
| A17 | Checks are shown in the cost estimate, cheap by default, after free checks, reused within a run, reserved in the budget, timed, and metered like agents | the R16 tests |
| A18 | LY07's severity follows exposure and its `add-policy` fix writes a working boundary | the R10 tests |

## 2. Traceability: requirement → tests

| Req | Tests | Status |
|---|---|---|
| R1 | `PolicyFileParserTest`, `PolicyCheckErrorsTest`, `PolicyVersionResolutionTest`, `PolicyHashRecordedInJournalAndTraceTest`, `PolicyTextNeverInAgentPromptTest`, `LoomScriptMergeKeepsPoliciesAndEvaluatorsTest` | ⬜ |
| R2 | `EvaluatorParsesTest`, `EvaluatorForbiddenSettingsTest`, `EvaluatorUsesClassifierEngineForCalibratedBackendTest`, `EvaluatorMarkedCalibratedInGraphAndAuditTest`, `InternalNodeContractTest` | ⬜ |
| R3 | `EvaluatorSeesOnlySubjectAndRulesTest`, `EvaluatorAnswersEveryRuleTest`, `EvaluatorFailsClosedTest`, `SamplesMajorityAndAgreementTest` | ⬜ |
| R4 | `CheckStatementParsesTest`, `CheckKeywordIsContextualTest`, `VerdictFieldsTest`, `SharedRuleIdsArePrefixedTest`, `CheckRetryTimeoutOnFailureTest` | ⬜ |
| R5 | `RewriteIsStatelessTest`, `RewriteIsCheckedAgainOnceThenFailsClosedTest`, `AskPersonThreeAnswersTest`, `AskPersonSuspendsAndResumesTest`, `ReplaceWithTest`, `ReplaceWithModelValueRefusedByCheckTest`, `NoOnViolationMeansFailTest`, `FailErrorNamesRulesNeverTextTest` | ⬜ |
| R6, R7 | laundering matrix, `RouteCoverageTest`, `NonModelWrittenTypesPassTest`, `EditingACheckedValueUnchecksItTest`, `ProvenanceSurvivesRewindAndResumeTest`, `ProvenanceNeverAppearsAsTextTest` | ⬜ |
| R8 | `RuntimeBlocksUncheckedValueAtEachSinkTest`, `BlockedStepRunsOnFailureTest`, `MustPassEveryDeclaredPolicyTest`, `NoPolicyMeansNoEnforcementTest`, `StaticSinkPathErrorsTest`, `ExplainSinksListsUnfollowedPathsTest`, the D25 tests of the strategy (`AgentChangingToolCallIsCheckedBeforeItRunsTest` and its siblings) | ⬜ |
| R9 | the R9 tests | ⬜ |
| R10 | LY07 fixtures | ⬜ |
| R11 | `LoomGuideBlocksParseTest`, `PreflightC4AdviceTextTest` | ⬜ |
| R12 | `SkillsMentionPolicyAndEvaluatorTest`, `verify-skill-package.sh` | ⬜ |
| R13 | `ScriptedEvaluatorInWeaveTestTest`, `ExpectChecksAndActionsTest`, `ChecksPolicyAssertionTest`, `EvaluatorGoldenCaseFormatTest`, `MockEvaluatorSaysNotJudgedTest` | ⬜ |
| R14 | `PolicyIncidentRulesTest`, `PolicyCheckTraceEventTest`, `SinkBlockedTraceEventTest`, `PolicyAuditEventsTest`, `CheckGraphNodeTest`, `GraphRenderKindsInSyncTest`, `ExplainSaysEachCheckTest` | ⬜ |
| R15 | the R15 tests of the strategy | ⬜ |
| R16 | the R16 tests of the strategy | ⬜ |

## 3. Quality gates

- [ ] `mvn verify` green on `ai-agent4j-loom`, `eval4j`, `loom-inspect*` and `loom-fabric` (after `mvn -pl src/loom/ai-agent4j-loom -DskipTests install`), coverage gates held, none lowered.
- [ ] PIT: the Loom `mutation` profile with `Provenance`, `CheckRunner`, the evaluator's fail-closed mapping, the sink enforcement and the leak guard; score not lower.
- [ ] LY07 measures ≥ 0.9 precision (Phase 9 R18) and passes the noise gate; `SimplePathStaysSimpleTest` green.
- [ ] `CanaryProvenanceOracleTest` added to the CI job that runs on runtime changes.
- [ ] `RepositoryScriptsTest` green; CTK scenarios `policy_check`, `policy_sinks` and `prompt_leak` added with their `*CtkTest`.
- [ ] Loom syntax everywhere it belongs: `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, `docs/guide/06`, the tutorial, starter 16, the workflow-guide skill, the VS Code grammar and snippets, the CTK.
- [ ] `bash scripts/sync-graph-render.sh` and `bash scripts/check-graph-render-sync.sh` (`check` kind).
- [ ] `bash scripts/make-starter-diagrams.sh`; `StartersTest` green (starter 16).
- [ ] `bash scripts/make-tutorial-html.sh`; `GuideTutorialPageTest` green.
- [ ] `weave rules docs core`; `weave rules test core` green.
- [ ] `SecurityDocTest` green; `secure-by-construction.md`, `owasp-llm-top-10.md` and `SECURITY.md` updated.
- [ ] `bash scripts/verify-skill-package.sh` green for the workflow guide, harness design, eval design and deployer.
- [ ] `make format-check`, `make release-check` green. No new dependency.

## 4. Manual checks

| # | Check | Pass |
|---|---|---|
| M1 | Starter 16 with a real cheap evaluator under a cap: five replies the owner writes by hand (two that break a rule, two that nearly do, one clean); record each verdict and confidence | ⬜ |
| M2 | Have someone who is not a developer read `policies/house-rules.md` in starter 16 and say whether they could own it | ⬜ |
| M6 | Give starter 16's assistant a changing tool (a fake email tool), script it to write a reply that breaks a rule, and watch the call be refused, the agent rewrite it, and the second call pass; then add `approve:` and confirm the person sees only the passing call with its verdict | ⬜ |
| M3 | Try by hand to get an unchecked reply out of starter 16 by editing the script (a `set`, a `call`, a `replace with "{reply}"`); each attempt is refused, at check time or at run time | ⬜ |
| M4 | `ask person` answered from Telegram (or the console channel) with each of the three answers; the audit shows the override | ⬜ |
| M5 | Read tutorial chapter 9's new section as a newcomer | ⬜ |

## 5. Documentation owed

`LOOM_GUIDE.md` ⬜ · `LOOM_PROMPT.md` ⬜ · `docs/guide/06` ⬜, `07` ⬜ · tutorial ch. 3 ⬜, 9 ⬜, 10 ⬜, 14 ⬜, README ⬜, HTML regenerated ⬜ · starter 16 and starters README ⬜ · `docs/review/rules/` ⬜ · `docs/security/*` ⬜ · `SECURITY.md` ⬜ · skills (workflow guide ⬜, harness design ⬜, eval design ⬜, deployer ⬜) · VS Code grammar, snippets, CHANGELOG ⬜ · graph-render ⬜ · eval4j README ⬜ · observability wiki pages ⬜ · `API_COMPATIBILITY.md` ⬜ · `llms.txt` ⬜ · release notes ⬜.

## 6. Exit checklist

- [ ] §0 recorded with dates and build ids
- [ ] The provenance and sink code reviewed by someone other than its author against DESIGN §6, and the review recorded in §7 (D36)
- [ ] Every row in §2 ✅, or partial with a reason; every A-criterion has evidence
- [ ] M1 to M6 done and recorded in §7
- [ ] DESIGN §7 questions answered
- [ ] Docs in §5 updated; roadmap status updated

## 7. Results

*(empty until 26a)*
