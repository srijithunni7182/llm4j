# Phase 26 test strategy: the policy boundary

How each requirement of [DESIGN.md](DESIGN.md) is tested: hermetic, deterministic, no model, no network. Tests are written first.

## 1. Principle: try to get text out, and fail to

The boundary is a security control, so it is tested the way an attacker would test it: by trying every route a model-written value could take to a sink without a check, and asserting each one is stopped. The routes are enumerated from the runtime's own value-building code (the same "runtime vs list" device phase 22 used for substitution), so a new route cannot be added without a test. Every guard is also removed once by hand to show a test notices.

## 2. Reusable devices

| Device | Used for |
|---|---|
| **`CapturingClient`** (phase 24): records each request; scripted replies per call | R2, R3, R5 |
| **`ScriptedEvaluator`**: answers per rule (`violated`, `clean`, `unsure`, error, timeout, wrong shape) | R3 to R5, R8 |
| **`RecordingSink`**: a fake outward tool, a `CHANGES` task, an entry `return` reader and a fake channel, each recording what reached it | R6 to R8 |
| **Laundering matrix**: a generated set of scripts, one per route a value can take (`set`, interpolation, field path, list element, object field, `call` argument and result, `parallel`, `for each`, `rewind`, resume from journal, `replace with` built from a model value, classifier `reason`, verdict `reason`, `Task.withModel` result), each ending at each sink kind | R7, R8 |
| **Route coverage test**: finds every place the runtime builds or moves a value (`resolve`, `resolvePayload`, `VariableContext.set`, call binding, journal replay) and asserts the laundering matrix has a row for it | R7 |
| **Canary policy**: a policy whose rule text is a unique canary; any agent request containing it shows policy text leaked into a prompt | R1, R9 |
| **`InternalNodeContractTest`** (phase 24), extended with the evaluator | R2, R3 |
| **Repository-scripts corpus** | R4 (contextual keywords) |
| **Rule fixtures** | R10 |

## 3. Fixtures

- **`PolicyFilesFixture`**: valid policy files (one rule, many rules, versions `v1`/`v2`), and invalid ones (no rule, duplicate id, bad id, empty rule, a rule over the length limit, a missing file).
- **`SupportFixture`**: Assistant → `check reply against HouseRules using Compliance` with each action, and a sink after it (entry `return`, `use`, a `CHANGES` task).
- **`TwoPoliciesFixture`**: two policies sharing a rule id.
- **`LeakFixture`**: answers that repeat 11 and 12 words of the system prompt, of the shared prompt, of a policy rule, with punctuation and case changed; answers that match and nearly match `internal_refs`; a catastrophic pattern against a long input.
- **`AgentToolFixture`**: an agent with an outward tool, with and without `approve:`, under a policy (for D25's proposed rule).

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | `PolicyFileParserTest` (public `PolicyFile`), `PolicyCheckErrorsTest` (table), `PolicyVersionResolutionTest` (latest and `@v2`, like prompts), `PolicyHashRecordedInJournalAndTraceTest`, `PolicyTextNeverInAgentPromptTest` (canary policy), `LoomScriptMergeKeepsPoliciesAndEvaluatorsTest` |
| **R2** | `EvaluatorParsesTest`, `EvaluatorForbiddenSettingsTest`, `EvaluatorUsesClassifierEngineForCalibratedBackendTest`, `EvaluatorMarkedCalibratedInGraphAndAuditTest`, `InternalNodeContractTest` (evaluator row) |
| **R3** | `EvaluatorSeesOnlySubjectAndRulesTest` (captured request), `EvaluatorAnswersEveryRuleTest`, `EvaluatorFailsClosedTest` (table: error, timeout, out of budget, wrong shape twice, rule left out, confidence below threshold: each → `unsure` → violated), `SamplesMajorityAndAgreementTest` |
| **R4** | `CheckStatementParsesTest` (every form), `CheckKeywordIsContextualTest` (inside `trust` still works), `VerdictFieldsTest`, `SharedRuleIdsArePrefixedTest`, `CheckRetryTimeoutOnFailureTest` |
| **R5** | `RewriteIsStatelessTest` (the rewriter's request has no transcript or shared prompt, whatever its settings), `RewriteIsCheckedAgainOnceThenFailsClosedTest`, `AskPersonThreeAnswersTest` (send as is → checked and audited; send this instead → person-typed, not model-written; stop → failed), `AskPersonSuspendsAndResumesTest`, `ReplaceWithTest`, `ReplaceWithModelValueRefusedByCheckTest`, `NoOnViolationMeansFailTest`, `FailErrorNamesRulesNeverTextTest` |
| **R6, R7** | The laundering matrix (every route × every sink: blocked when unchecked, allowed when checked), `RouteCoverageTest`, `NonModelWrittenTypesPassTest` (labels, enums, numbers, booleans, inputs, constants, plain task results), `EditingACheckedValueUnchecksItTest`, `ProvenanceSurvivesRewindAndResumeTest`, `ProvenanceNeverAppearsAsTextTest` |
| **R8** | `RuntimeBlocksUncheckedValueAtEachSinkTest`, `BlockedStepRunsOnFailureTest`, `MustPassEveryDeclaredPolicyTest`, `NoPolicyMeansNoEnforcementTest` (pins today's behaviour), `StaticSinkPathErrorsTest` (line of sink and of agent), `ExplainSinksListsUnfollowedPathsTest`; for D25 once decided: `AgentOutwardToolUnderPolicyTest` |
| **R9** | `LeakGuardRepeatedWordsTest` (11 passes, 12 warns; folding of case and punctuation; each source text), `LeakGuardInternalRefsTest`, `InternalRefsPatternLimitsTest`, `InternalRefsTimeoutFailsClosedTest`, `LeakGuardDefaultWarnUnderPolicyTest`, `LeakGuardBlockFailsStepTest`, `LeakGuardOffIsAFindingTest`, `LeakGuardEventHasNoTextTest` |
| **R10** | LY07 fixtures (fires once per script listing every path; quiet with a policy; quiet when no model text reaches a sink) |
| **R11** | `LoomGuideBlocksParseTest`, `PreflightC4AdviceTextTest` |
| **R12** | `SkillsMentionPolicyAndEvaluatorTest` (the skills' stage text holds the required sentences; the skills' existing checkable-rule tests from phase 20 extended), `verify-skill-package.sh` |
| **R13** | `ScriptedEvaluatorInWeaveTestTest`, `ExpectChecksAndActionsTest`, eval4j `ChecksPolicyAssertionTest`, `EvaluatorGoldenCaseFormatTest` (parsing only; the gate is phase 28), `MockEvaluatorSaysNotJudgedTest` |
| **R14** | `PolicyCheckTraceEventTest` (no text, no reason), `SinkBlockedTraceEventTest`, `PolicyAuditEventsTest`, `CheckGraphNodeTest`, `GraphRenderKindsInSyncTest`, `ExplainSaysEachCheckTest` |

## 5. Fault injection

The evaluator's provider returns HTTP 500, 429 with retry, a cut-off answer, an answer for a rule that does not exist, two answers for one rule; the rewriter fails; the person never answers (`ask person` with a timeout in a scheduled workflow: the step fails closed after the timeout and LR04's rule applies); the policy file changes between a pause and a resume (the resumed check uses the version recorded at the pause, and says so); the journal is replayed with a different policy version (the replayed verdict is reused and the trace shows the recorded version); a value of 10 MB reaches a check (bounded by the evaluator's budget, refused before the call when it cannot fit, fail closed).

## 6. Mutation testing

`Provenance`, `CheckRunner`'s action logic and re-check rule, the evaluator's fail-closed mapping, the sink enforcement and the leak guard's word-run matcher join the Loom `mutation` profile at the same threshold as the ladder engine. Guards removed once by hand and the failing tests recorded in `VERIFICATION.md` §7: the sink block (R8), the re-check after a rewrite (R5), `unsure` counting as violated (R3), provenance through interpolation (R7), and the leak guard (R9).

## 7. Coverage

Every new class meets the module's per-class gates. No limit is lowered and nothing is excluded.

## 8. Deliberately not tested

- How well a real model judges a real policy: that is the evaluator's own golden cases (phase 28) and a capped live check (`VERIFICATION.md` M1).
- The wording of a person's override screen in a host application (the host owns it); only the `HumanInterface` contract.
