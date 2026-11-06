# Phase 32 test strategy: A2A integration

How each requirement of [DESIGN.md](DESIGN.md) is tested. The rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md) apply: the default build is hermetic (no network, no real model, no wall-clock waits), tests are written first,
and a security guard is tested by breaking it on purpose. The principles in [PRINCIPLES.md](PRINCIPLES.md) are tested too: each safety rule in principle 2 and the version rule in principle 9 has at least one test that fails if the rule is removed.

## 1. Principles

- **No network, ever, in the default build.** llm4j ships no transport, so the tests do not need an HTTP server for A2A either: a `FakeTransport` implements `A2ATransport` and replays recorded exchanges. The only code that builds wire JSON (`A2AJsonRpc10`) is tested on strings.
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
| **Helper** | `A2AJsonRpc10` round trips on the specification's examples, tolerant of both state spellings, writes only the 1.0 form, ignores unknown fields, malformed input |
| **RemoteAgent** | `send`, `reply`, `get`, `cancel` against a `ScriptedRemote`; it never waits; a card that points elsewhere; credentials passed as references only |
| **Handler** | Caller identity required; one caller cannot see, cancel or list another's tasks; unknown task is "not found" for both a missing and a foreign task; malformed requests; nothing is started by the handler itself |
| **Loom parser** | The three declarations and `ask`: every setting, errors that say what to write, contextual words (an agent or variable named `remote`, `expose`, `ask` or `agent_channel` still parses), `ask` with and without `expecting` |
| **Loom check** | Each R26 error with line and fix, and the warning for every `remote` |
| **Loom runtime (calling)** | End to end with `FakeTransport` and `ScriptedRemote`: a plain call, a question answered by the channel, a deferred answer completed later, an authorization request, a refused card, `UNKNOWN` until timeout, `on_failure`, `retry` |
| **Loom runtime (crash)** | A crash after the send (simulated by failing the journal after the message id is written): resume finds the task by context id and does not send again; when it finds nothing it sends once; when the transport cannot tell, the step stops with the message |
| **Loom runtime (files)** | A link offered, the channel writes through the sink, `{name_files}` lists the files, the next step attaches one, a resumed run verifies the hash, a deleted file fails the step |
| **Loom runtime (serving)** | `expose` over a real `LoomHost` with a fake model: a task is a run, state mapping, an internal `human_prompt` shows `WORKING`, an `ask` to a `caller` channel shows `INPUT_REQUIRED`, cancel stops the run, a crash resumes it, caller isolation |
| **Security** | See §5 |
| **Audit** | Each R27 rule fires when it should and not otherwise; it is listed in `docs/security/weave-audit.md` (`SecurityDocTest`); the trifecta rule fires for an exposed workflow with the other legs |
| **Graph / Fabric** | `AttachGraphTest`-style tests for the new node kinds and chips; the sync scripts; escaping of remote text |
| **Docs** | The new ` ```loom ` blocks in the guide and tutorial parse or check as their type says; the VS Code grammar finds the new words; `API_COMPATIBILITY.md` lists the new types |
| **CTK** | New scenarios: a call that completes; a call that asks a question; a refused card; an exposed workflow's card (`weave card`) |
| **Mutation (PIT)** | A profile for the decision-making classes: the endpoint guard, `ExchangeGuard`, the sink, the caller filter, the state mapping and `A2AJsonRpc10`, at an 85% threshold |
| **Live (tagged `live`, never default)** | If a real A2A agent is available (VERIFICATION P6), one call each way with the owner's own agent and keys; confirms the helper against a real server. Run only when asked |

## 3. Fixtures

- **`SpecExamples`**: the JSON from the specification's examples, as test resources with a header comment saying where each came from, and which parts were checked against the page.
- **`FakeTransport`**: records every operation it receives, and answers from a script. It can fail at a named step, be slow (with the fake clock), or refuse.
- **`ScriptedRemote`**: a list of steps, each a state with an optional question, artifacts and link, used behind `FakeTransport`.
- **`RecordingChannel`** and **`MisbehavingChannel`**: record every message and context; the second misbehaves on purpose.
- **`TempMediaRoot`** and **`TinyMedia`** from phase 31 for the file tests.
- **`CrashingJournal`**: a journal wrapper that throws after the n-th write, for the crash tests.

## 4. Per requirement

| Requirement | Tests (written first) |
|---|---|
| **R1** | `ModelRecordsRejectInvalidValuesTest`, `UnknownStateIsUnknownNeverCompletedTest`, `UnknownFieldsAreIgnoredTest` |
| **R2, R3** | `TransportIsTheOnlyWayOutTest`, `CredentialIsAReferenceNeverResolvedByUsTest`, `NoCredentialInAnyOutputTest` |
| **R4** | `RemoteAgentNeverWaitsTest`, `SendReplyGetCancelTest`, `RemoteAgentIsNotAnAgentTest` (no shared interface) |
| **R5** | `CardPointingElsewhereIsRefusedBeforeAnySendTest`, `HostLookalikesAreRefusedTest`, `NoSupportedEndpointNamesTheOfferedVersionsTest`, `SelectsTheFirstSupportedEndpointTest` |
| **R6** | `FileOfAnUnacceptedTypeIsRefusedBeforeSendingTest`, `CardWithNoInputTypesSendsTest` |
| **R7** | `UnknownStateUntilTimeoutFailsTheStepTest` |
| **R8, R9** | `SealedSwitchIsExhaustiveTest` (compile-time check kept as a test), `ContextShowsNoVariablesTest`, `UntrustedTextIsMarkedTest` |
| **R10** | `QuestionLimitHoldsAcrossRetriesTest`, `InvalidReplyIsReAskedAndCountedTest`, `ChannelThatThrowsFailsTheStepTest`, `ChannelThatNeverAnswersHitsTheTimeoutTest`, `UnhandledMessageFailsAndIsNeverDroppedTest`, `OversizeReplyIsRefusedTest` |
| **R11** | `SinkStopsAtTheSizeCapMidWriteTest`, `TypeIsDecidedFromTheBytesTest`, `SvgAndUnknownAreRefusedAndRemovedTest`, `FailedWriteLeavesNothingTest`, `SenderNameNeverBuildsAPathTest`, `NoMediaRootNoFilesTest`, `HashIsComputedOnTheWayTest` |
| **R12** | `RoundTripsEverySpecExampleTest`, `AcceptsBothStateSpellingsWritesOneTest`, `OnlyThisClassKnowsAWireNameTest` (a test that greps the other classes for wire names), `MalformedInputIsAClearErrorTest` |
| **R13** | `NoIdentityIsRefusedTest`, `CallersNeverSeeEachOthersTasksTest`, `ForeignTaskLooksLikeMissingTaskTest`, `HandlerStartsNothingByItselfTest` |
| **R14** | `CardIsBuiltFromTheDeclarationAndHostDetailsTest`, `BuilderRunsNothingTest` |
| **R15** | `RemoteCallFetchesCheckGuardsThenSendsTest`, `FailureIsRetriedOnlyWhenItCanChangeTest` |
| **R16** | `MessageAndContextIdsAreJournaledBeforeSendingTest`, `CrashAfterSendDoesNotSendAgainTest`, `CrashBeforeSendSendsOnceTest`, `UnknownOutcomeStopsWithTheMessageTest` |
| **R17** | `RunPausesHoldingNoThreadTest`, `TaskChangedResumesTheRunTest`, `DeadlineFailsTheStepTest` |
| **R18** | `EachStateMapsToTheRightOutcomeTest`, `RemoteMessageIsShortenedAndMarkedUntrustedTest` |
| **R19** | `QuestionGoesToTheChannelAndTheReplyIsSentTest`, `DeferredAnswerCompletesLaterTest`, `AuthorizationNeededFailsClosedUnlessHandledTest`, `LinkIsNeverFollowedByUsTest`, `IgnoreDropsOneFileExplicitlyTest`, `ExchangesAreJournaledWithKeysTest` |
| **R20** | `ReceivedFileCanBeAttachedWithLa23Test`, `SendingFilesToARemoteNeedsMediaUnscannedWithAPiiGuardTest` |
| **R21** | `AskGoesThroughTheExchangeGuardTest`, `AskWithExpectingBindsATypedReplyTest` |
| **R22** | `TaskIsADurableRunTest`, `StateFollowsTheRunTest`, `CancelIsStopTest`, `CrashResumesTheRunTest`, `LoomHostIsUnchangedTest` (its public surface is compared with a recorded list) |
| **R23** | `OneTextPartBindsOneParameterTest`, `DataPartBindsByNameTest`, `AnythingElseIsRefusedWithTheParameterListTest`, `IncomingTextPassesTheGuardsTest` |
| **R24** | `InternalHumanPromptShowsWorkingTest`, `AskToACallerChannelShowsInputRequiredTest`, `CallerNeverAnswersAnInternalApprovalTest` |
| **R25** | `WeaveCardPrintsTheCardOfferingNoNetworkTest`, `CardDiffShowsAChangeToExposeTest` |
| **R26** | One test per check, each asserting the line and the fix in the message |
| **R27** | One test per audit rule, positive and negative; `SecurityDocTest` |
| **R28** | `RemoteNodeAndChipTest`, `ExposeMarksTheEntryPointTest`, `GraphRenderSyncTest`, `FabricEscapesRemoteTextTest` |
| **R29** | `JournalRecordsExchangesAndFilesTest`, `ResumeNeverResendsAnAcknowledgedMessageTest`, `RewindAndForkNeverResendTest`, `MissingReceivedFileFailsTheStepTest` |

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

## 6. What is deliberately not tested

- A real network, a real A2A server or gRPC and REST bindings. The optional live test uses the owner's own agent and is never part of the default build.
- Other protocol versions. Principle 9 puts them in a translation the consumer writes; llm4j tests only that nothing else knows a version.
- The consumer's transport and channel. The tests use fakes; the sample in the documentation is compiled in a docs test but not run against a network.
- Spring, Javalin or any web stack. The handler is tested as a function.
