# Phase 32 test strategy: A2A integration

How each requirement of [DESIGN.md](DESIGN.md) is tested. The rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md) apply: the default build is hermetic (no network, no real model, no wall-clock waits), tests are written first,
and a security guard is tested by breaking it on purpose. The principles in [PRINCIPLES.md](PRINCIPLES.md) are tested too: each safety rule in principle 2 and the version rule in principle 9 has at least one test that fails if the rule is removed.

## 1. Principles

- **No external network, ever, in the default build.** llm4j ships no transport, so most tests need no HTTP at all: a `FakeTransport` implements `A2ATransport` and replays recorded exchanges, and `A2AJsonRpc10` is tested on strings. A second level goes over **loopback only**, against a **mock A2A agent** (below), as the provider tests already do with OkHttp's `MockWebServer`.
- **The specification is the fixture.** Wire examples are taken from the published 1.0 text (VERIFICATION P2) and stored as test resources with the page and date they came from. A shape the specification does not show is not asserted as if it were real.
- **Hand-written fakes for types we own.** `FakeTransport`, `ScriptedRemote` (a remote agent that moves through a scripted list of states, questions and artifacts), `RecordingChannel` and `FakeClock` are written by hand; Mockito is not used for them.
- **Refusal is a feature.** Every "we refuse" requirement has a test that shows the refusal and a test that shows the transport was never called.
- **Never a secret, never an untrusted string unescaped.** One shared assertion, `assertNoCredentialLeak`, scans `toString`, Jackson output, log output, exception messages, journal lines and trace output for the fixture credential. Another, `assertUntrustedIsMarked`, checks that remote text appears in traces and Fabric only inside the escaped, shortened form.
- **Consumer code is treated as hostile in tests.** A `MisbehavingChannel` loops, throws, returns nothing, writes forever and returns the wrong shape, to prove the bounds are the runtime's.

## 2. Levels

| Level | What it covers |
|---|---|
| **Unit (model)** | The records and sealed types: validation, defensive copies, `TaskState` mapping, unknown state and unknown fields, `toString` hygiene |
| **Unit (guard)** | The endpoint guard (R5): same host, other host, userinfo and port tricks, a host that merely contains the declared one, no supported endpoint (the message names the offered versions), an `http` address |
| **Unit (exchange)** | `ExchangeGuard`: the question limit at, below and above; the limit across `retry`; timeout with a fake clock; invalid structured reply re-asked and counted; size cap; a channel that throws, hangs or returns nothing |
| **Unit (sink)** | The received-file stream: size cap reached mid-write, type from bytes, SVG and unknown refused, kind mismatch, atomic move, no leftover after a failure, label never used as a path, no `media_root` |
| **Helper** | `A2AJsonRpc10` round trips on the specification's examples, tolerant of both state spellings and of both names for stream events and the push setting, sets `returnImmediately` and the version header, writes only the 1.0 form, ignores unknown fields, malformed input |
| **RemoteAgent** | `send`, `reply`, `get`, `cancel` against a `ScriptedRemote`; a send that returns a plain message and no task; `reply` on a terminal task refused; it never waits; a card that points elsewhere; a required extension the transport lacks; a signed card with and without a verifier; credentials passed as references only |
| **Handler** | A send that must wait returns a pending future and the handler never blocks a thread (checked with a thread that fails the test if it is held); the specification's request rules (unknown task, mismatching context and task, a client-supplied task id, a terminal task, a context id that cannot be honoured); `ListTasks` paging, order and scope; `GetTask` history length; `CancelTask` twice; filtering by scope key and one runner resolved per scope; Caller identity required; one caller cannot see, cancel or list another's tasks; unknown task is "not found" for both a missing and a foreign task; malformed requests; nothing is started by the handler itself |
| **Plain agent runner** | `AgentTaskRunner`: the per-task agent function (two scopes never share an agent), refusal to start without the confirmation that the agent has limits, a task through a `TaskStore` and an `Executor` with a fake executor, no `INPUT_REQUIRED`, cancel with and without an interruptible run, a crash leaving a `working` task the consumer can clean up |
| **Loom parser** | The three declarations and `ask`: every setting (including `text_input`, `max_input` and `require_signed_card`), errors that say what to write, contextual words (an agent or variable named `remote`, `expose`, `ask` or `agent_channel` still parses), `ask` with and without `expecting` |
| **Loom check** | Each R26 error with line and fix (a token budget on a remote step, a mixed target list, a `text_input` that names no parameter), and the warning for every `remote` |
| **Loom runtime (calling)** | A duplicate or stale `taskChanged` changes nothing. End to end with `FakeTransport` and `ScriptedRemote`: a plain call, a question answered by the channel, a deferred answer completed later, an authorization request, a refused card, `UNKNOWN` until timeout, `on_failure`, `retry` |
| **Loom runtime (crash)** | A crash after the send (simulated by failing the journal after the message id and time are written): resume lists recent tasks, finds the one whose history holds the message id and does not send again; when it finds none, or the server will not list, the step stops with the unknown-outcome message and nothing is sent; a plain-message reply is not asked for again |
| **Loom runtime (results)** | A plain-message reply ends the step with no task; `quote`, `quote_files` and `quote_data`; `expecting` with a single data part, with JSON in a text part, with several data parts, with a mismatch (a definite failure, so `retry` applies); the size and depth caps on JSON from outside |
| **Loom runtime (stop)** | A stopped run asks the transport to cancel in-flight remote tasks; a cancel that fails is recorded and does not block the stop; `retry` after a definite failure but not after an unknown outcome |
| **Loom runtime (files)** | A link offered, the channel writes through the sink, `{name_files}` lists the files, the next step attaches one, a resumed run verifies the hash, a deleted file fails the step |
| **Loom runtime (serving)** | The mapping of an incoming message (R23): text only to `text_input`, data by name, unknown keys ignored, a missing parameter refused with the list, files refused, `max_input`, a message that names a task answers the waiting `ask`, a message that names only a context starts a run. `expose` over a real `LoomHost` with a fake model, and with one host per scope (journals never shared): a task is a run, state mapping, an internal `human_prompt` shows `WORKING`, an `ask` to a `caller` channel shows `INPUT_REQUIRED`, cancel stops the run, a crash resumes it, caller isolation |
| **Security** | See §5 |
| **Audit** | LA24 to LA27 and the LR02 extension each fire when they should and not otherwise (LA26 high when the channel handles authorization; LA25 accepted with a reason); it is listed in `docs/security/weave-audit.md` (`SecurityDocTest`); the trifecta rule fires for an exposed workflow with the other legs |
| **Graph / Fabric** | `AttachGraphTest`-style tests for the new node kinds and chips; the sync scripts; escaping of remote text |
| **Docs** | The new ` ```loom ` blocks in the guide and tutorial parse or check as their type says; the VS Code grammar finds the new words; `API_COMPATIBILITY.md` lists the new types |
| **CTK** | New scenarios: a call that completes; a call that asks a question; a refused card; an exposed workflow's card (`weave card`) |
| **Starter script** | The starter's `main.loom` is excluded from `weave check` and from `RepositoryScriptsTest`'s check (D33), and the exclusion names the file and the reason; `StarterScriptParsesTest` parses it so a grammar change cannot break it silently; `StarterExclusionIsTheOnlyOneForA2ATest` checks that no other A2A script is excluded |
| **Documentation set** | Every relative link and heading anchor in `docs/a2a/` resolves; every page names its reader and links to the README and the philosophy; the README lists every page (`A2ADocsIndexTest`); the ` ```loom ` blocks in `LOOM.md` parse or check as their marker says; the Java excerpts compile and match the starter's source; the `REFERENCE.md` tables match the code (every `TaskState`, every Loom setting and default, every trace event, every audit and review rule id, every limit default); the pointer left in `PRINCIPLES.md` resolves |
| **Ecosystem** | `weave test` with a scripted remote and a scripted channel (states, a question, an authorization request, a file, a refusal) and step coverage that counts remote steps; the symbols index for the new declarations and references (go-to-definition, references, rename); the stepper over a remote call; the cost estimate showing a remote step as outside the budgets; trace events and the loom-inspect rules and security signals for exchanges; OTLP spans; the graph kinds and chips with the sync check; the VS Code grammar, hover, completion, snippets (run through `weave check`) and the regenerated parity baseline; the compatibility-kit scenarios; the guide, tutorial and starter tests; the extension version check |
| **Mutation (PIT)** | A profile for the decision-making classes: the endpoint guard, `ExchangeGuard`, the sink, the caller filter, the state mapping and `A2AJsonRpc10`, at an 85% threshold |
| **Wire (loopback)** | Over real HTTP on loopback, with a test-only transport and the **mock A2A agent** (which validates every message against the official definition and obeys its rules table): the card is fetched and the endpoint guard runs; the helper's requests are accepted and its responses parsed; a task goes through working, a question, an answer and completion; a plain-message reply; `returnImmediately` honoured; a version the agent does not speak; a required extension; an authorization request; a file offered inline and by link; `ListTasks` for the crash rule; a connection dropped right after a send; hostile behaviour (a card pointing elsewhere, an oversized response, malformed JSON, an unknown state). A second test mounts **our own handler** on a loopback server and calls it with `RemoteAgent`, so the two halves are shown to interoperate |
| **Live (tagged `live`, never default)** | Optional, only if the owner has a real third-party A2A agent (VERIFICATION P6): one call each way with the owner's own keys. This is the only level that tests against an implementation we did not write. Run only when asked |

## 3. Fixtures

- **`SpecExamples`**: the JSON from the specification's examples, as test resources with a header comment saying where each came from, and which parts were checked against the page.
- **`FakeTransport`**: records every operation it receives, and answers from a script. It can fail at a named step, be slow (with the fake clock), or refuse.
- **`ScriptedRemote`**: a list of steps, each a state with an optional question, artifacts and link, used behind `FakeTransport`.
- **`MockA2AAgent`**: a **proper A2A 1.0 agent**, server side, for our tests alone (test scope, **never published**; where it lives so that core and Loom tests can share it is decided in the survey, P4). It runs on OkHttp's `MockWebServer` over loopback and never talks to a live service. It implements:
  - the **card** at `/.well-known/agent-card.json` with `Cache-Control` and `ETag`, several interfaces in preference order, an interface with a tenant, extensions (optional and required), and card fields for signatures (the verification itself is the transport's);
  - the **JSON-RPC binding** for every operation the helper speaks: `SendMessage` (task or plain message, `returnImmediately`), `SendStreamingMessage` and `SubscribeToTask` (server-sent events), `GetTask` (history length), `ListTasks` (paging, order, status and context filters, history), `CancelTask`, the four push-notification configuration operations with a **loopback webhook receiver** (delivery at least once, with duplicates and out-of-order delivery on request), and `GetExtendedAgentCard`, with the error codes and the version header;
  - the **task state machine** and the specification's **rules**, one row each in a rules table (`SpecRules`, a test resource with the section number of each rule): server-made task ids; a message that names a task names an existing one; context inferred from the task; a mismatching context and task rejected; a proposed context id accepted or rejected as scripted and never replaced; a finished task takes no more messages; scoping to the authenticated caller with "not found" for a foreign task; required extensions and version negotiation; interrupted states resumed by a message naming the same task and context; the `AUTH_REQUIRED` rules. A client that breaks a rule is refused as the specification says;
  - **scenarios**, named and scripted, moved by request count and never by the clock: completes at once; plain-message reply; working then completed; input required then answered; authorization required; rejected; failed; cancelled by the client; runs past a deadline; an artifact with text, with a data part, with JSON inside a text part, with several data parts, with a link, with an inline file; a required extension; an unsupported version; two interfaces; a tenant; a signed card; a context id refused; de-duplication on message id switched on and off; listing refused or with history cut; push duplicates and out-of-order;
  - **misbehaving modes**: a card pointing at another host, an oversized response, malformed JSON, an unknown state, a connection dropped right after a message is received (for the crash rule), a response that never ends.
  Every request and response, in both directions, is **validated against the official protocol definition**, a copy kept as a test resource with its source address, tag and hash (VERIFICATION P10). It records every message id it receives. Its first fixtures are the specification's own examples.
- **`LoopbackA2AServer`**: mounts our `A2AHandler` on a loopback server through the helper, to call it with `RemoteAgent`.
- **`HttpTestTransport`**: a small OkHttp transport for the tests (not the starter, which is separate and need not run).
- **`RecordingChannel`** and **`MisbehavingChannel`**: record every message and context; the second misbehaves on purpose.
- **`TempMediaRoot`** and **`TinyMedia`** from phase 31 for the file tests.
- **`PerScopeRunners`**: a resolver over two separate `LoomHost`s with separate persistence, to show scopes never share a journal.
- **`CrashingJournal`**: a journal wrapper that throws after the n-th write, for the crash tests.

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | `ModelRecordsRejectInvalidValuesTest`, `UnknownStateIsUnknownNeverCompletedTest`, `UnknownFieldsAreIgnoredTest` |
| **R2, R3** | `TransportIsTheOnlyWayOutTest`, `CredentialIsAReferenceNeverResolvedByUsTest`, `NoCredentialInAnyOutputTest` |
| **R4** | `RemoteAgentNeverWaitsTest`, `SendReplyGetCancelTest`, `SendMayReturnAPlainMessageTest`, `ReplyOnATerminalTaskIsRefusedTest`, `RemoteAgentIsNotAnAgentTest` (no shared interface) |
| **R5** | `CardPointingElsewhereIsRefusedBeforeAnySendTest`, `HostLookalikesAreRefusedTest`, `NoSupportedEndpointNamesTheOfferedVersionsTest`, `SelectsTheFirstSupportedEndpointTest` |
| **R6** | `FileOfAnUnacceptedTypeIsRefusedBeforeSendingTest`, `CardWithNoInputTypesSendsTest` |
| **R6b** | `RequiredExtensionTheTransportLacksIsRefusedBeforeSendingTest`, `OptionalExtensionIsNotAnObstacleTest` |
| **R7** | `UnknownStateUntilTimeoutFailsTheStepTest` |
| **R8, R9** | `SealedSwitchIsExhaustiveTest` (compile-time check kept as a test), `ContextShowsNoVariablesTest`, `UntrustedTextIsMarkedTest` |
| **R10** | `QuestionLimitHoldsAcrossRetriesTest`, `InvalidReplyIsReAskedAndCountedTest`, `ChannelThatThrowsFailsTheStepTest`, `ChannelThatNeverAnswersHitsTheTimeoutTest`, `UnhandledMessageFailsAndIsNeverDroppedTest`, `OversizeReplyIsRefusedTest` |
| **R11** | `SinkStopsAtTheSizeCapMidWriteTest`, `TypeIsDecidedFromTheBytesTest`, `SvgAndUnknownAreRefusedAndRemovedTest`, `FailedWriteLeavesNothingTest`, `SenderNameNeverBuildsAPathTest`, `NoMediaRootNoFilesTest`, `HashIsComputedOnTheWayTest` |
| **R12** | `EveryMessageValidatesAgainstTheOfficialDefinitionTest`, `MockAgentObeysEveryRuleInTheTableTest` (one case per row of `SpecRules`), `PushDeliveryDuplicatesAndReorderingAreHandledTest`, `StreamingAndSubscribeEventsAreParsedTest`, `HelperAndTransportRoundTripAgainstTheMockAgentTest`, `MockAgentRefusesWhatTheSpecificationForbidsTest`, `SetsReturnImmediatelyAndTheVersionHeaderTest`, `AcceptsBothNamesForStreamEventsTest`, `RoundTripsEverySpecExampleTest`, `AcceptsBothStateSpellingsWritesOneTest`, `OnlyThisClassKnowsAWireNameTest` (a test that greps the other classes for wire names), `MalformedInputIsAClearErrorTest` |
| **R13** | `HandlerAndRemoteAgentInteroperateOverLoopbackTest`, `WaitingSendReturnsAPendingFutureAndNeverBlocksTest`, `UnknownTaskIsNotFoundTest`, `MismatchingContextAndTaskIsRejectedTest`, `ClientSuppliedTaskIdIsNotAcceptedTest`, `TerminalTaskTakesNoMoreMessagesTest`, `ListTasksPagesOrdersAndScopesTest`, `ScopeKeyScopesTasksTest`, `RunnerIsResolvedPerScopeTest`, `SeparateHostsNeverShareAJournalTest`, `EndpointTenantIsPassedThroughUntouchedTest`, `NoIdentityIsRefusedTest`, `CallersNeverSeeEachOthersTasksTest`, `ForeignTaskLooksLikeMissingTaskTest`, `HandlerStartsNothingByItselfTest` |
| **R14** | `CardIsBuiltFromTheDeclarationAndHostDetailsTest`, `DefaultsToNoStreamingNoPushNoExtendedCardTest`, `ETagFollowsTheContentTest`, `BuilderRunsNothingTest` |
| **R14b** | `FailedSignatureIsAlwaysRefusedTest`, `RequireSignedCardRefusesAnythingNotVerifiedTest`, `UnsignedCardGoesAheadWhenNotRequiredAndTracesItTest`, `LlmFourJDoesNoCryptographyTest` (no signature library on the classpath of the core) |
| **R15** | `RemoteCallFetchesCheckGuardsThenSendsTest`, `FailureIsRetriedOnlyWhenItCanChangeTest` |
| **R15b** | `StoppedRunCancelsInFlightRemoteTasksTest`, `FailedCancelIsRecordedAndDoesNotBlockTheStopTest`, `RetryOnlyAfterADefiniteFailureTest` |
| **R16** | `CrashAfterSendAgainstTheMockAgentTest` (the mock drops the connection after receiving the message), `MessageIdAndTimeAreJournaledBeforeSendingTest`, `CrashAfterSendReattachesByMessageIdInHistoryTest`, `CrashAfterSendWithNoMatchStopsAndSendsNothingTest`, `ServerThatWillNotListStopsAndSendsNothingTest`, `ProposedContextIdIsNeverReliedOnTest` |
| **R17** | `DuplicateTaskChangedChangesNothingTest`, `StaleTaskChangedChangesNothingTest`, `RunPausesHoldingNoThreadTest`, `TaskChangedResumesTheRunTest`, `DeadlineFailsTheStepTest` |
| **R18** | `PlainMessageReplyEndsTheStepWithoutATaskTest`, `EachStateMapsToTheRightOutcomeTest`, `RemoteMessageIsShortenedAndMarkedUntrustedTest` |
| **R19** | `AuthorizationReplyIsOnlyAMessageNeverACredentialTest`, `QuestionGoesToTheChannelAndTheReplyIsSentTest`, `DeferredAnswerCompletesLaterTest`, `AuthorizationNeededFailsClosedUnlessHandledTest`, `LinkIsNeverFollowedByUsTest`, `IgnoreDropsOneFileExplicitlyTest`, `ExchangesAreJournaledWithKeysTest` |
| **R20** | `ReceivedFileCanBeAttachedWithLa23Test`, `SendingFilesToARemoteNeedsMediaUnscannedWithAPiiGuardTest` |
| **R21** | `AskGoesThroughTheExchangeGuardTest`, `AskWithExpectingBindsATypedReplyTest` |
| **R22** | `TaskIsADurableRunTest`, `StateFollowsTheRunTest`, `CancelIsStopTest`, `CrashResumesTheRunTest`, `LoomHostIsUnchangedTest` (its public surface is compared with a recorded list) |
| **R23** | `TextBindsOnlyToTextInputEvenWithOneParameterTest`, `SeveralTextPartsAreJoinedTest`, `DataPartBindsByNameTest`, `UnknownKeysAreIgnoredTest`, `MissingParameterIsRefusedWithTheListTest`, `FilePartsAreRefusedTest`, `MessageOverMaxInputIsRefusedBeforeAnyWorkTest`, `MessageNamingATaskAnswersTheWaitingAskTest`, `MessageNamingOnlyAContextStartsARunTest`, `IncomingTextPassesTheGuardsTest` |
| **R24** | `InternalHumanPromptShowsWorkingTest`, `AskToACallerChannelShowsInputRequiredTest`, `CallerNeverAnswersAnInternalApprovalTest` |
| **R25** | `WeaveCardPrintsTheCardOfferingNoNetworkTest`, `CardDiffShowsAChangeToExposeTest` |
| **R26** | One test per check, each asserting the line and the fix in the message |
| **R27** | One test per audit rule, positive and negative (LA24 to LA27 and LR02, including LA26 high with authorization); `SecurityDocTest` |
| **§4.1 results** | `PlainMessageReplyBindsQuoteFilesAndDataTest`, `ExpectingTakesTheSingleDataPartTest`, `ExpectingFallsBackToJsonInTextTest`, `SeveralDataPartsFailTheStepTest`, `JsonSizeAndDepthAreCappedTest` |
| **§5 (plain agent)** | `AgentFunctionIsCalledPerTaskTest`, `TwoScopesNeverShareAnAgentTest`, `RunnerRefusesWithoutTheLimitsConfirmationTest`, `NoInputRequiredFromAPlainAgentTest`, `CrashLeavesAWorkingTaskTheConsumerCanCleanUpTest`, `RefusalMessagePointsToLoomTest` |
| **R28** | `RemoteNodeAndChipTest`, `ExposeMarksTheEntryPointTest`, `GraphRenderSyncTest`, `FabricEscapesRemoteTextTest` |
| **R30** | `StarterCompilesInTheExtendedBuildTest` (`src/examples/a2a-starter` compiles against the real interfaces), `PageExcerptsMatchTheStarterTest`, `ConsumerDutiesPageListsEveryDutyTest` |
| **R29** | `JournalRecordsExchangesAndFilesTest`, `ResumeNeverResendsAnAcknowledgedMessageTest`, `RewindAndForkNeverResendTest`, `MissingReceivedFileFailsTheStepTest` |
| **R31** | `EveryStatementVisitorHandlesRemoteAskAndExposeTest` (a plain test per visitor; a general completeness test is deferred, D32), `ScriptDriftSeesTheNewConstructsTest`, `StaticSinkChecksReadRemoteAndAskTest` |
| **R32** | `SymbolsIndexRemoteChannelAndExposeTest`, `ReferencesFromToChannelAndAskResolveTest`, `RenameUpdatesEveryReferenceTest`, `ProtocolDocListsTheNewKindsTest`, the extension's `grammar`, `semantic`, `snippets` and `parserParity` tests |
| **R33** | `ScriptedRemoteInATestFileTest`, `ScriptedChannelInATestFileTest`, `AssertionOnARemoteStepTest`, `StepCoverageCountsRemoteStepsTest`, `EvalMockRunsARemoteStepTest`, `CaptureRecordsExchangesAsDigestsOnlyTest` |
| **R34** | `StepperStepsOverARemoteCallTest`, `WhyExplainsARemoteStepTest`, `NarratorReadsTheNewStatementsTest` |
| **R35** | `RuleDocsGeneratedTest` (regenerated pages), `SecurityDocTest`, `ReviewRuleTreatsARemoteAsAbleToActTest`, `CostEstimateShowsARemoteStepOutsideTheBudgetsTest`, `BudgetCoordinatorDoesNotCountARemoteStepTest` |
| **R36** | `RemoteAndExposeGraphKindsTest`, `GraphSchemaAcceptsTheNewKindsTest`, `GraphRenderSyncTest`, the JS chip tests |
| **R37** | `TraceEventsForRemoteExchangesTest`, `InspectRaisesAnIncidentForARemotePastItsDeadlineTest`, `InspectSignalsAnExposedWorkflowsUntrustedInputTest`, `OtlpSpansForRemoteCallsTest`, `FabricEscapesRemoteTextTest` |
| **R38** | `RemoteCallCtkConformanceTest`, `RemoteQuestionCtkConformanceTest`, `RefusedCardCtkConformanceTest`, `ExposedCardCtkConformanceTest` |
| **R39** | `TutorialExamplesTest` and `StartersTest` (with the policy for the not-validated warning), `GuideTutorialPageTest` (regenerated), the guide tests, `LoomGuideBlocksParseTest`, `RepositoryScriptsTest`, `SkillPackageTest`, `CommandReferenceListsWeaveCardTest` |
| **R41** | `A2ADocsIndexTest`, `A2ADocsLinksResolveTest`, `A2APagesNameTheirReaderTest`, `LoomPageBlocksParseTest`, `JavaPageExcerptsCompileTest`, `PageExcerptsMatchTheStarterTest`, `ReferenceListsEveryTaskStateTest`, `ReferenceListsEveryLoomSettingAndDefaultTest`, `ReferenceListsEveryTraceEventTest`, `ReferenceListsEveryAuditAndReviewRuleTest`, `PrinciplesPointerResolvesTest` |
| **R42** | `ReadmeRowsForAffectedProjectsMentionA2ATest` (each README in the R42 table links `docs/a2a/`), the existing link and docs tests, and the hand-checked census in VERIFICATION §3 |
| **R40** | The extension suite, `releaseCheck`, `manifest`, and the publish workflow's version check |

## 5. Test the guards themselves

Each of these is checked once by breaking the guard on purpose and confirming a test fails. The result is recorded in VERIFICATION §7.

1. The endpoint guard (the card points at another host).
2. The unsupported-endpoint refusal (no supported version).
3. The question limit and its counting across retries.
4. The timeout, and the runtime (not the channel) owning it.
5. Validation of a structured reply, and the re-ask counting.
6. The size cap while writing, and the type check on close.
7. The generated file name (the sender's name must not build a path).
8. "Unhandled message fails the step".
9. "A link is never followed" (a test with a transport that fails the test if asked to fetch anything).
10. The credential-by-reference rule (no resolved value reaches any output).
11. The caller identity requirement and the per-caller filter.
12. "An internal `human_prompt` or `approve` never reaches the caller".
13. The journal-before-send and the look-up before a resend.
14. `UNKNOWN` is never treated as completed.
15. The version rule: wire names appear only in `A2AJsonRpc10`.
16. The required-extension refusal.
17. "The handler never blocks" (a waiting send must return a pending future).
18. The tenant and caller scoping of `ListTasks` and `GetTask`.
19. The terminal-task rule and the context and task consistency rules.
20. The duplicate and stale `taskChanged` guard.
21. `text_input` being explicit, and `max_input` refusing before any work.
22. `require_signed_card`, and a failed signature always refused.
23. No mixed dynamic target lists, and no token budget on a remote step.
24. A stopped run cancelling its remote tasks.
25. The plain-agent runner refusing to start without the limits confirmation.
26. One runner per scope: a request for one scope never reaches another scope's runner.
27. The scripted remote in `weave test`: with scripting removed, a test of a remote step fails.

## 6. What is deliberately not tested

- A real third-party A2A server, and gRPC and REST bindings. Loopback tests prove that our pieces agree with our reading of the specification and with its examples; they do not prove that another implementation behaves as the specification says. The optional live test is the only evidence of that, and it is never part of the default build.
- Other protocol versions. Principle 9 puts them in a translation the consumer writes; llm4j tests only that nothing else knows a version.
- The consumer's transport and channel. The tests use fakes; the starter project is compiled in the extended build but never run against a network.
- Cryptography and signature verification: llm4j has none, so only the status handling is tested.
- Spring, Javalin or any web stack. The handler is tested as a function.
