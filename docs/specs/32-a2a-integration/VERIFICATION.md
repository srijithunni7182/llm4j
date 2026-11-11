# Phase 32 verification plan: A2A integration

Status: **not started** (P2 and P3 are done: the specification was read, results in §7; all design decisions D1 to D31 are taken). Run §0 first, then top to bottom at the end of each sub-phase (**32a** the core, R1 to R12; **32b** the handler, R13, R14; **32c** Loom calling, R15 to R21, R26, R29; **32d** Loom being called, R22 to R25; **32e** audit and Fabric, R27, R28;
**32f** documentation, editor, compatibility kit and mutation tests). Results in §7. Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md). Principles: [PRINCIPLES.md](PRINCIPLES.md).

## 0. Before writing anything

| # | Task | Done when | Result |
|---|---|---|---|
| P1 | **The owner reviews this spec** and answers the proposed decisions D19 to D26 (or accepts the proposals) | the decisions table in DESIGN §6 says taken for each, or the item is removed | ⬜ |
| P2 | **Read the A2A 1.0.0 specification**: the exact definitions of Task, task states, Part (is there a separate structured-data part, and how is a file carried), the complete agent card fields, the JSON-RPC method names and result shapes (including the push-notification configuration methods), the error codes, and Appendix A (what changed from 0.3.0) | every item in the "unverified" list is marked confirmed with the section, or remains listed as unverified, and `SpecExamples` is filled with the specification's own examples | ✅ read; see §7 (a few items remain unverified and are listed there) |
| P3 | **Find out whether servers de-duplicate a message id**, and whether a client may supply the context id, so that the crash rule (R16) is sound. If neither holds, the rule rests on `find` by context id alone | recorded with the section, and DESIGN R16 matches | ✅ neither is guaranteed; R16 and D19 rewritten (§7) |
| P4 | **Survey Loom and ai-agent4j** for the places the new words touch (also: whether the `guardrail` block can wrap a remote step for masking; whether a Loom list-of-objects form exists for `skills`; how `expecting` is applied to a `delegate`; how a `LoomHost` is built with its own persistence and base folder for a per-scope host; whether a `ReActAgent` run handle can stop a run): every statement that can be followed by `ask`/`on_failure`, how `delegate` binds its result and a list variable (the grounding citations are the pattern), how `LoomHost` represents a pending `human_prompt`, what `runs()` and `run(id)` return, what a stopped or failed run looks like, and which parts of `ExecutorValidation` a new declaration must register with | the list is in §7 and DESIGN §4 matches it | ⬜ |
| P5 | **Find the next free audit rule numbers** and the review-lens rules to use, and read `docs/security/weave-audit.md` and `owasp-llm-top-10.md` for the wording to follow | recorded in §7 | ⬜ |
| P6 | **Ask the owner whether a real third-party A2A agent is available** for the optional live check (one that calls out or can be called; a sample from a vendor's SDK is enough). Keys stay in the owner's environment, never in the chat. **The plan does not depend on it**: the end-to-end checks use the mock A2A agent (TEST_STRATEGY §3) and the official definition, and the results state the evidence level in one line | recorded as run or not run | ⬜ |
| P7 | **Baseline**: `make test-fast` (the fast tier) once on `ai-agent4j`, `ai-agent4j-loom` and every module after it, the mutation profiles, and the VS Code extension suite; record the scores and any failure that exists before the change | recorded in §7 | ⬜ |
| P8 | **Build the compile-only starter** `src/examples/a2a-starter` (D31) against the real interfaces once 32a to 32d exist, with a marked `TODO` wherever authentication, TLS, address validation, signature checking or durable storage plug in, and with its Loom script excluded from `weave check` (D33: named in the exclusion list with its reason, parse-tested); add it to the extended build and to the CI extended job | the starter compiles in `make test-extended` and in CI, and `PageExcerptsMatchTheStarterTest` passes | ⬜ |
| P10 | **The official definition and an independent implementation.** Fetch the machine-readable definition of the protocol at the `1.0.0` tag (a JSON Schema if one is published, otherwise the protocol definition file) and decide how every wire message is validated against it in tests (a test-scope library only, never in a published module); build the `SpecRules` table from the specification's MUST and SHOULD statements, each with its section number; find out whether an official SDK sample agent or a conformance kit exists for 1.0 and can run locally inside the test environment (as test tooling, not a runtime dependency), which would be genuine third-party evidence | the definition is a test resource with its source, tag and hash; `SpecRules` exists; the answer about a local independent implementation is in §7 | ⬜ |
| P9 | **Survey the ecosystem** (DESIGN §4.6) before 32c: the policy for tutorial and starter scripts given that every `remote` warns (`loom-parse` blocks, or a marker for an expected warning, and what `StartersTest` allows); the format of `weave test` files and where a scripted remote and channel fit; what `ScriptDrift` checks; how `docs/serve/PROTOCOL.md` versions symbol kinds; whether any other Loom runtime exists in the repository (the compatibility kit speaks of Java and Python) and so needs the scenarios; which tutorial chapters and which guide chapters need which sections; whether `weave deploy` and `loom-maven-plugin` need any change | the answers are in §7 and DESIGN §4.6 matches them | ⬜ |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | A Java application can call a remote agent through a consumer's transport, with credentials passed as references, and `RemoteAgent` never waits or polls | R2 to R4 tests |
| A2 | A card cannot send a call to a host the script did not declare, and an unsupported version is refused before anything is sent | R5 tests |
| A3 | Unknown states are never completed; unknown fields never break a caller | R1, R7, R12 tests |
| A4 | A consumer's channel cannot exceed the question limit, the timeout or the size cap, and a channel that misbehaves fails the step | R10 tests, `MisbehavingChannel` |
| A5 | A structured reply is checked against its shape, and a bad one is re-asked and counted | R10, R21 tests |
| A6 | A file or link from outside never reaches the workflow without passing through the channel and the media checks; llm4j never follows a link | R11, R19 tests |
| A7 | A Loom script can call a remote agent with `delegate`, answer its questions through a channel, receive files and use them, with the journal recording each exchange | R15 to R21, R29 tests |
| A8 | A crash after a send never creates a second remote task: the task is found by its message id in the history, or the step stops and sends nothing | R16 tests |
| A9 | A Loom workflow can be exposed; a task is a durable run; callers only see their own tasks; no identity means refused | R13, R22 tests |
| A10 | An internal approval or `human_prompt` never reaches the caller | R24 tests |
| A11 | `weave check` reports each mistake with its line and fix, and warns for every remote agent that it is not validated | R26 tests |
| A12 | The audit flags an exposed workflow's outside input, data sent to a remote agent and an automatic channel answering outsiders | R27 tests |
| A13 | Nothing a credential, an untrusted string or a received file's bytes contains leaks into a log, trace, journal, report or exception | the shared assertions in every layer |
| A14 | A script without the new words, and every existing channel and statement, behave exactly as before | the existing suites, `LoomHostIsUnchangedTest` |
| A15 | Wire names exist in one class only | `OnlyThisClassKnowsAWireNameTest` |
| A16 | `weave card` prints the card an `expose` block produces, with no network | R25 tests |
| A17 | A send that returns a plain message is handled; a task in a terminal state takes no more messages | R4, R18 tests |
| A18 | The handler never blocks: a send that must wait returns a future the host completes | R13 tests |
| A19 | A card that requires an extension the transport lacks is refused before sending; a signed card is verified when a verifier is configured | R6b, R14b tests |
| A20 | The consumer's duties are documented, with a starter project that compiles and excerpts that match it | R30 tests |
| A21 | An incoming message binds only as R23 says: text to `text_input`, data by name, files refused, over-size refused before any work | R23 tests |
| A22 | A plain `ReActAgent` can be exposed through `AgentTaskRunner` with its stated limits, and refuses to start without the limits confirmation | §5 tests |
| A23 | Scopes never share a journal when given separate hosts, and filtering by scope key holds when they share one | R13 tests |
| A24 | `require_signed_card` and a failed signature are enforced; llm4j does no cryptography | R14b tests |
| A25 | A stopped run cancels its in-flight remote tasks, best effort | R15b tests |
| A26 | **No surface drifts.** Every surface in DESIGN §4.6 handles every construct of this phase, in the sub-phase that adds it, and the maintainer has reviewed it | R31 to R40 tests, and the maintainer's review of each sub-phase against the DESIGN §4.6 checklist (a general completeness test is deferred, D32) |
| A27 | A workflow that calls a remote agent can be tested offline with `weave test` (a scripted remote and channel) and its steps count in coverage | R33 tests |
| A28 | The editor offers go-to-definition, references, rename, completion and hover for `remote`, `agent_channel` and `expose`; the extension is released with a version bump | R32, R40 tests |
| A29 | A2A support has a structured documentation set in `docs/a2a/` (what A2A is, our philosophy, a Java guide, a Loom guide, what the consumer provides, security and review, a reference), each page landing with its code, linked together, with excerpts compiled or parsed and the reference matching the code | R41 tests |
| A30 | Every README of an affected project mentions A2A support and links the set, and the unaffected ones are recorded as reviewed | R42 tests and the census |
| A31 | The helper, a consumer-style transport and our handler interoperate over real HTTP on loopback against a mock A2A agent that implements the server side of A2A 1.0, obeys a rules table traced to the specification's text, is driven by the specification's own examples, and has every message validated against the official definition. §7 states the evidence level in one line: what ran (examples, the official definition, the mock, and a local independent implementation or a live agent if P10 or P6 found one) and what did not | R12, R13, R16 wire tests |

## 2. Quality gates

- **After every sub-phase:** `make test-core` green. **After the phase** (after 32f), and before the final push: `make test-fast` (`mvn -Pfast verify`) green on every module touched, with the coverage gates. **Once at the end of the session:** `make test-oracle` (the provenance oracle alone). The full tier (`mvn verify`) is left to CI and is run by hand only if the owner asks; **record in §7 which tiers ran, that the oracle ran, and that the other `slow` tests did not.** While iterating: `-Dtest=`. The Loom coverage rules are met in the fast tier; the Loom coverage rules met (a new class in a gated package is covered to the same bar); `make format-check` clean.
- The mutation profile for the decision-making classes at 85% or better, with survivors listed and explained (as for `mutation-media`).
- The VS Code extension suite green except for any failure recorded in P7 as already present.
- No new runtime dependency in `ai-agent4j` (`mvn dependency:tree` unchanged).
- `make release-check` (sources, javadoc, metadata) green: every new public type and method has Javadoc.
- GitGuardian-clean: no credential anywhere, including fixtures and docs.

## 3. Documentation and ecosystem checklist (principle 7 of `CLAUDE.md` and principle 11)

Each is updated in the same sub-phase as the behaviour it describes, not later. Check the box only when the page was read and corrected. The full list of surfaces, with what must change and what catches drift, is DESIGN §4.6 (R31 to R40); every box below that is a Loom surface follows it.

- [ ] `PRINCIPLES.md` (if anything here changes a principle) and this folder's specs
- [ ] `LOOM_GUIDE.md` (a section with parse-tested blocks), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md` and `07-validate-and-audit.md`
- [ ] The tutorial (a chapter or sections in 3, 6 and 9; the index; regenerate the page with `scripts/make-tutorial-html.sh`)
- [ ] `src/ai-agent4j/wiki/` (a new A2A page, linked from `Home.md`), the core README, the Loom README, the root README, `docs/ABOUT.md`, `llms.txt`
- [ ] `docs/security/weave-audit.md`, `owasp-llm-top-10.md`, the generated rule pages (`-Dregenerate.rule.docs=true`)
- [ ] `docs/API_COMPATIBILITY.md` and the current release notes
- [ ] The skills under `.claude/skills/` that teach Loom
- [ ] The VS Code grammar, hover text, README and `CHANGELOG.md` (with a version bump, because the release check requires one when the extension changes); update the frozen parser's parity baseline if the corpus changes
- [ ] The sample channel and transport, compiled by a test
- [ ] The documentation set `docs/a2a/` (DESIGN R41), page by page, each landing with its code: `README.md`, `WHAT_IS_A2A.md`, `PHILOSOPHY.md`, `JAVA_GUIDE.md`, `LOOM.md`, `INTEGRATING.md`, `SECURITY_AND_REVIEW.md`, `REFERENCE.md`, and `src/examples/a2a-starter/README.md`; plus `src/ai-agent4j/wiki/A2A.md`, the entries in `wiki/Home.md`, `docs/README.md` and `llms.txt`; `PRINCIPLES.md` reduced to a pointer once its content has moved
- [ ] The README census (DESIGN R42): `README.md`; `src/ai-agent4j/README.md`; `src/ai-agent4j-tools/README.md`; `src/loom/README.md`; `src/loom/ai-agent4j-loom/README.md`; `src/loom/vscode-loom/README.md`; `src/loom/ctk/README.md`; `src/loom/loom-inspect/README.md`; `src/loom/loom-fabric/README.md`; `src/loom/starters/README.md`; `src/eval4j/README.md`; `src/eval4j-report/README.md`; `src/spring/llm4j-spring-boot-starter/README.md`; `src/examples/README.md`; `docs/README.md`; `docs/guide/README.md`; `docs/tutorial/README.md`; `docs/deploy/README.md`; `docs/specs/README.md`. Reviewed with no change expected, each recorded as reviewed: `src/ai-agent4j-addons/README.md`, `src/engram/engram-core/README.md`, `install/README.md`, `probes/README.md`
- [ ] Every surface of DESIGN §4.6, each ticked in the sub-phase that adds the construct: language core visitors; symbols, `docs/serve/PROTOCOL.md` and the editor (grammar, hover, completion, snippets, parity baseline); `weave test`, `eval` and `capture` support; the debugger and demos; review, audit, cost and the generated rule pages; the graph and its copies; trace events, loom-inspect, OTLP and Fabric; the compatibility kit; the guide, tutorial, starters and skills; `COMMAND_REFERENCE.md` and `weave man`; the VS Code release
- [ ] The consumer's-duties page (DESIGN R30) and the compile-only starter `src/examples/a2a-starter`, covering: transport, channel, waiting (poll, stream, push, duplicates), serving (mounting, card address and caching, authentication, identity and tenant, the future for a waiting send), where tasks and the `received/` folder live with the journal, signing and verifying cards, and operations; examples compiled by a test

## 4. Manual checks

| # | Check | Expected |
|---|---|---|
| M1 | Run the Loom sample that calls a remote supplier against `ScriptedRemote` through the CLI with a host-supplied fake transport | the trace shows the exchange, the question handled by the channel, the files listed |
| M2 | Run `weave check` and `weave audit` on the sample | the "not validated" warning, and the expected audit findings with their fixes |
| M3 | Run `weave card` on the sample's `expose` block | the card JSON, with a placeholder address, matches the declaration |
| M4 | Kill the process after the send and resume | the run finds its task and does not send again |
| M5 | Against the **mock A2A agent** over loopback: call it, answer a question, receive a file, crash after the send and resume, call our own handler with `RemoteAgent` | it works, and the exchange is in the journal |
| M5b | With a real third-party A2A agent, only if available (P6): the same | it works; otherwise recorded as not verified against a third-party implementation |
| M6 | Read the sample channel as a reviewer would | the audience, limits and what leaves the process are clear from the script alone |

## 5. Exit checklist

- [ ] All of A1 to A31 pass with the evidence named, each marked with its level: fakes, loopback against the mock agent, or live. Anything claimed about interoperability with other implementations rests on the live level; without it, §7 says "not verified against a third-party implementation".
- [ ] Guard-removal checks (TEST_STRATEGY §5) done and recorded.
- [ ] Documentation checklist (§3) done; samples verified.
- [ ] Every claim about the specification is marked verified with its section (P2), or listed as unverified.
- [ ] Release notes and `API_COMPATIBILITY.md` updated.
- [ ] The roadmap row is current.
- [ ] Anything not done is listed in §7 under "Not verified", with the reason.

## 6. Risks

| Risk | Mitigation |
|---|---|
| The specification differs from what we read, or a detail listed as unverified in §7 turns out otherwise (the protocol definition was read in full and the specification text almost entirely, but a few sections were only seen as summaries) | P2 is done; the unverified items are listed in §7 and are checked when the helper is written; the design's wire knowledge sits in one class, so a correction is local |
| Servers do not de-duplicate and may refuse a proposed context id, so the crash rule cannot always find the task | the rule matches our message id in recent tasks' history, and a step that cannot tell stops with a message and sends nothing |
| Consumer channels and transports are hard to write correctly | a worked, compiled sample; defaults that fail closed; the bounds are the runtime's |
| An exposed workflow leaks data to a caller | the audit rule, caller isolation, the internal/caller split of pauses, guards on incoming text |
| A remote agent is not validated until run time | the warning on every `remote`, `on_failure` encouraged by a review rule, and the endpoint guard before any send |
| Received files fill the disk or hold hostile content | the cap while writing, the type check on close, the `received/` folder inside `media_root`, files only through the channel |
| The new words clash with existing scripts | contextual words, tested with agents and variables of those names |
| Scope is large | six sub-phases, each green and pushed alone; Part C (a plain agent) and the signed-card hook can be dropped without changing the rest |
| The protocol moves | principle 9; only `A2AJsonRpc10` changes |
| We test against our own reading of the specification (a mock we wrote cannot catch a shared misreading) | the mock's rules are a table traced to the specification's text; every message is validated against the official definition, which we did not write; a local independent implementation or a live agent (P10, P6) is the only evidence of interoperability, and §7 never claims more than the level that ran |
| The specification's prose disagrees with itself (and the GitHub copy of it is headed "Release Candidate") | the protocol definition (protobuf) at the `1.0.0` tag is followed; the helper accepts the variants; the disagreements are in §7 |
| Tenants share a host and one caller's data surfaces in another's (the journal, long-term memory, `received/` files) | principle 10: isolation is the consumer's duty, with one host per tenant as the supported way; the page says what a host shares; filtering by scope key is defence in depth and described as such |
| A waiting send makes a server hold a request for hours | the handler returns a future and the host decides; documented as the consumer's duty (R30) |

## 7. Results

### Specification reading (P2, P3), 2026-11-07

**Sources.** The protocol definition (`a2a.proto`) at the `v1.0.0` tag, read in full. The specification text at the same tag (`docs/specification.md`), read word for word, except where noted; it is headed "Release Candidate v1.0" and "Latest Released Version 0.3.0" and some of its examples still show 0.3, while the website calls 1.0.0 the latest. The "What's new in v1" page, partly summarised by the fetch tool. The website's own rendering of the specification, partly summarised. Where the definition and the prose disagree, the definition was followed.

**Confirmed from the definition.**
- A part is exactly one of `text`, `raw` (bytes, base64 in JSON), `url` or `data` (any JSON value), plus an optional `filename`, `mediaType` and `metadata`; there is no kind field and no separate file part.
- `TaskState` has `UNSPECIFIED`, `SUBMITTED`, `WORKING`, `COMPLETED`, `FAILED`, `CANCELED`, `INPUT_REQUIRED`, `REJECTED` and `AUTH_REQUIRED`. Terminal: completed, failed, canceled, rejected. Interrupted: input required, auth required.
- `AgentCard` requires `name`, `description`, `supportedInterfaces`, `version`, `capabilities`, `defaultInputModes`, `defaultOutputModes` and `skills`; it has no single address. `AgentInterface` has `url`, `protocolBinding` (`JSONRPC`, `GRPC`, `HTTP+JSON`), `protocolVersion` (such as `1.0`) and an optional `tenant`.
- Operations (JSON-RPC names): `SendMessage`, `SendStreamingMessage`, `GetTask`, `ListTasks`, `CancelTask`, `SubscribeToTask`, `CreateTaskPushNotificationConfig`, `GetTaskPushNotificationConfig`, `ListTaskPushNotificationConfigs`, `DeleteTaskPushNotificationConfig`, `GetExtendedAgentCard`. `SendMessageResponse` holds a task or a message. Error codes -32001 to -32009 with their names.
- `SendMessageConfiguration.returnImmediately` defaults to false, in which case the server must wait until a terminal or interrupted state.

**Confirmed from the specification text.**
- Task ids are server-made and a message that names a task must name an existing one. A server may accept a client-proposed context id, must reject it if it cannot and must not replace it; clients should not propose one unless they know the server's behaviour. A server may use the message id to detect duplicates ("MAY"). **(P3: neither is guaranteed, so R16 and D19 changed.)**
- Messages to a task in a terminal state are refused; a refinement is a new task in the same context.
- Listing and getting must be scoped to the caller; the existence of a resource the caller may not see must not be revealed. Servers must authenticate every request.
- Push notifications are delivered at least once; the receiver must answer with a success status; agents are advised to validate webhook addresses against SSRF. File references must be validated against SSRF.
- `AUTH_REQUIRED`: the agent must use a task, enter that state and explain the authorization; it must receive credentials out of band; the client is expected to arrange it (contact a person or another service, or do it out of band).
- Agent cards may be signed (JWS with canonicalisation); clients should verify at least one signature when present. A card may mark an extension as required; an agent errors when a client does not support it.
- The client sends the protocol version with each request and an unsupported version is an error (`VersionNotSupportedError`).

**Disagreements inside the specification** (handled by tolerance in the helper): state spellings in some examples are lowercase or hyphenated; stream events are `statusUpdate` and `artifactUpdate` in the body and `taskStatusUpdate` and `taskArtifactUpdate` on the migration page; the push setting is `taskPushNotificationConfig` in the definition and `pushNotificationConfig` in an example; HTTP errors are described as AIP-193 while two examples use `application/problem+json`; examples still show version `0.3`.

**Not verified.** The versioning rules of section 3.6 word for word (only a summary: the client must send the version with each request); how a tenant is carried on each binding; the push-notification configuration request shapes in the JSON-RPC binding; whether any real server behaves as the text says.

**Design changes made from this reading.** A send returns a task or a plain message (R1, R2, R4, R18); the translation always sets `returnImmediately` (R2, R12); the crash rule matches the message id in recent tasks' history and stops when it cannot tell (R16, D19); a required extension the transport lacks is refused (R6b, D29); a tenant on the endpoint and the caller identity (R1, R13, D30); `taskChanged` is idempotent (R17); a task in a terminal state takes no more messages (R4, R13); the handler's request rules and `ListTasks` paging follow the specification (R13); the card builder defaults to no streaming, no push and no extended card (R14); a card verifier hook is recommended (R14b, D25); the handler never blocks (R13, D27, taken); the consumer's duties are documented (R30, D31).

### 32f, the end of the phase: starter, mutation, docs set, tiers and the exit checklist, 2026-11-11

**Built.** `src/examples/a2a-starter` (compile-only, JDK only, in the `-Pextended` build, a test parses its script and checks the channel's refusal; every decision that is the consumer's is marked `YOUR DECISION`; its README lists what it does not do). The mutation profile `mutation-a2a` in `ai-agent4j` (`EndpointGuard`, `ExchangeGuard`, `ReceivedFileSink`, `RemoteAgent`, `A2AHandler`): first run 157 of 174 killed (90%); the 17 survivors were read, `GuardEdgesTest` was written for them, and the second and third runs killed 171 and then **174 of 174 (100%)** (a timed-out mutant counts as killed). The documentation set, the tutorial section in chapter 6 (with the regenerated page), the guide chapter 6 section, the four skills (their mapping pages say nothing about a repository path, which a test enforces), the READMEs of the census, `ABOUT.md`, `THE_STACK.md`, `EXAMPLES.md`, the deployment security chapter, the release notes and `API_COMPATIBILITY.md`.

**The D33 exception was not needed.** The starter's script loads cleanly with every other script (`RepositoryScriptsTest`), so no exclusion was added; the script is also parsed by the starter's own test.

**Tiers run.** `make docs-check` exit 0 and clean; `make test-core` exit 0 after each sub-phase; `make test-fast` on every module (core, tools, eval4j, Loom with its coverage gates, `loom-inspect*`, Fabric) exit 0 at the end, after fixing three things it found that the narrower runs had not: a wire-name literal in `RemoteAgent` (a parameter-name string, caught by the "only one class knows a wire name" test), a repository path in a skill page, and the `host` package falling to 0.79 branch coverage against a minimum of 0.80 (new `LoomTaskRunner` behaviour tests, no limit changed); `make test-oracle` exit 0; the release build (`mvn -Prelease -Dgpg.skip=true clean verify` with tests skipped because the fast tier had just run them) and `scripts/CheckJars.java` exit 0; the VS Code extension suite 783 of 783 on an otherwise idle machine after the parity baseline was regenerated (it lists the starter's script and two review examples as tracked differences, the same kind as the kit's). **Not run:** `make test-full` and the other `slow` tests (left to CI), `make test-extended` as a whole (only the starter's own module), `make test-live`, and the full-reactor release build with tests. One `loom-inspect` timing test (`ServerLinkTest.finishDeliversWhatIsLeftQuickly…`) failed once under load and passed alone three times and in the final run.

**Acceptance criteria, with the level of evidence.** "Loopback" means the real helper and handler over HTTP on loopback against the mock agent, every message validated against the official definition; "fakes" means unit tests with fakes. Nothing here is "live".

| Criterion | Evidence | Caveat |
|---|---|---|
| A1, A2, A3, A4, A5, A17, A18 | fakes and loopback | |
| A6 | fakes (sink and guards); links are never followed | |
| A7, A8 | loopback (a script calling the mock; the crash rule with a dropped reply and a listing the server refuses) | |
| A9 | fakes: `LoomTaskRunner` over a real `LoomHost`; the handler over HTTP with a fake runner | the runner was not mounted behind the handler over HTTP |
| A10 | fakes: an exposed workflow waiting on a `human_prompt` shows as working, is never input-required and never shows the prompt text; only an `ask` to a `caller` channel pauses a task for the caller | an `approve` inside an exposed workflow was not run separately (it suspends the run the same way) |
| A11, A12, A16, A21, A22, A24, A25, A27 | fakes, with guard-removal checks | |
| A13 | assertions in several layers (no remote text in spans, incidents, events or the failure sentence) | not an exhaustive sweep of every layer |
| A14 | the existing suites, green | |
| A15 | the wire-name test, which caught a real violation | |
| A19 | a required extension is refused before sending | the signature is whatever status the transport reports; llm4j does no cryptography |
| A20 | the starter compiles and its script parses | excerpts in the pages are not compared with the starter by a test |
| A23 | filtering by scope key is tested | isolation by separate hosts is the consumer's, and was not exercised |
| A26 | each construct handled in the sub-phase that added it, recorded in §7 | the open items below; the maintainer's review is still to come |
| A28 | grammar, hover, snippets and symbols; extension 1.4.0; suite green | |
| A29, A30 | pages written and linked; census checked | the unaffected READMEs are listed as reviewed with no change: `eval4j`, `eval4j-report`, `starters`, `addons`, `engram-core`, `install`, `probes`, the docs indexes for `docs/deploy` |
| A31 | loopback against the mock agent | **own mock only; nothing was checked against a third-party A2A agent (P6 not run; no local independent implementation was found or built)** |

**Not verified, and open.**
- Interoperability with any other A2A implementation (P6, P10 live level).
- The graph renderer does not draw `exposedAs`; served-task events are not turned into `loom-inspect` spans (the runner emits them for the consumer); no Fabric-specific test; `weave test` has no scripting for being called.
- `SpecExamples` fixtures and a full pass over every MUST and SHOULD of the specification (the mock enforces a rules table, not the whole text).
- No end-to-end test of `AUTH_REQUIRED` through Loom, no `PII` or `media: unscanned` guard on files sent to a remote (R20), a stop requested on a suspended run is seen at its next resume, and a deferred channel answer is completed only by the runner's own `answer` (there is no general API to complete a `Defer`).
- P7 baselines of the mutation profiles and the extension before the work began were not taken; the optional Masker overlap change was not done.
- Decision D32 (the completeness test) stays deferred as decided.

### 32e, audit, review and the served-run signal, 2026-11-11

**Built.** `weave audit` LA24 to LA27 (`A2AAudit`, called from `SecurityAudit`): LA24 an `expose` (low; every agent the exposed workflow reaches, through its steps and the workflows it calls, now counts as reading untrusted content, so the trifecta rule can fire), LA25 a `delegate` to a remote (low; medium when the workflow reaches private data and no agent of it has a `pii` guard that is on), LA26 an `audience: automatic` channel (medium; high when a `remote` uses it), LA27 an `expose` with neither its own budget nor a run budget (high). `AuditCatalog` entries, `docs/security/weave-audit.md`, `docs/security/owasp-llm-top-10.md` (LLM01, LLM02, LLM06, LLM10), the generated rule pages. Review lens: LR02 now fires on a `delegate` to a remote with no `on_failure`, with a firing and a quiet example. Observation: a run started by outsiders through an `expose` block says so (`HarnessExecutor.setServedFromOutside`, set by `LoomTaskRunner.wiring()`, `served` on the run-start trace, `loom.run.served` in the mapper); `loom-inspect-core` counts every agent of a served run as having read untrusted content, so private data plus a send raises the trifecta incident naming "exposed workflow input". New page `docs/a2a/SECURITY_AND_REVIEW.md` (linked from the README), the Security signals wiki page.

**Tests.** `ExposedAuditTest` (13), `ServedRunTrifectaTest` (3), `ServedRunMappingTest` (2), a `LoomTaskRunnerTest` case for the served marker, the review rule example tests, `A2ADocsTest`; `make docs-check` exit 0.

**Guard-removal.** Eight mutations of the audit rules (the expose budget test, recursion through `call`, the private-data and masking condition, high for a remote-used channel, the exposed leg itself, `pii: off` not counting as masking, the remote-only test of LA25, the automatic-only test of LA26): seven caught at once; the `pii: off` one survived and a test was added, then it was caught. The served-run signal has a test for each side (served and not served).

**Not done in 32e.** The Fabric server page shows the trifecta incident as it shows every incident, but no Fabric-specific test was written. "Accepting a finding in writing" for LA25 relies on the audit's existing mechanism and was not tried for the new rules. The checks of this phase were all run against scripts written for the tests; no real organisation's workflow was audited. The audit's reach is by statements in the exposed workflow and the workflows it calls: an agent reached only through a dynamic list that names none is not counted.

### 32d, being called (`expose`, `LoomTaskRunner`, `weave card`), 2026-11-11

**Built.** `LoomTaskRunner` (`io.github.llm4j.loom.host`): the handler's `TaskRunner` over a `LoomHost`, unchanged (D17), for one `expose` block; `wiring()` goes into `LoomHost.Builder.customizer`, then `attach(host)`. A message binds to the workflow's parameters as R23 says (text only to `text_input`, a JSON object by name with unknown keys ignored, files refused, `max_input` enforced before any work starts, a missing parameter refused with what the workflow takes). A task is a durable run owned by one scope. An `ask` to a channel whose audience is `caller` pauses it as `INPUT_REQUIRED` and `answer` carries it on; a `human_prompt` or `approve` stays internal (the task shows `WORKING`); `AUTH_REQUIRED` is never produced. The result is the workflow's `return` only, never its notes; a failure shows one fixed sentence; `cancel` stops a live run or marks a waiting one `CANCELED`. `ServedEvent`s report received, state, answered and cancelled with the task id and state only. `ExposedCard` and `weave card` (R25): the card from the script alone, https address only, a placeholder by default, text for `text_input` and JSON for the other parameters, nothing about the channel or the workflow inside. Graph: `exposedAs` on a workflow (schema and JSON). Editor: `expose`, `text_input`, `max_input` words, hover and an `expose` snippet (extension 1.4.0). Kit: `a2a_expose.loom`, `traces/a2a_expose.json`, `A2AExposeCtkConformanceTest`. Docs: `LOOM_GUIDE.md`, `LOOM_PROMPT.md`, `docs/a2a/LOOM.md`, `docs/a2a/README.md`, `llms.txt`, the command reference and the man page group. `weave check` for `expose` and symbols for `expose` were already done in 32c.

**Tests.** `LoomTaskRunnerTest` (18), `ExposedCardTest` (5), `CardCommandTest` (6), `ExposedGraphTest` (2), `A2AExposeCtkConformanceTest` (2); the extension snippet and grammar tests; `make docs-check` exit 0 and clean.

**Guard-removal.** Ten guards in `LoomTaskRunner` (files refused, `max_input`, `text_input` required, missing parameters, answer only while waiting, cancel of a finished task, the fixed failure sentence, only `return` as the result, scope on `get`, data part must be an object): the first round missed four (cancel, failure sentence, result source, data object) because no test would notice; tests were added and all ten are now caught.

**Found while testing.** A task shows `INPUT_REQUIRED` a moment before the host releases the run that paused it, so an answer that arrives at once got "already running". `answer` now retries the resume for up to about 2 s in 10 ms steps and fails the task if it cannot; this is the one wait in the class. A script that declares an `agent` made every run fail in the test host because its model factory returns nothing: the tests use workflows with no agent.

**Not done in 32d.** `loom-inspect` mapping of served-task events (the runner emits `ServedEvent`s for the consumer; they are not turned into spans); the graph renderer does not yet draw `exposedAs`; the card is not signed (the host's job) and no extended card, streaming or push is declared; after a restart a task's owner is recovered from the host's run record, but pending questions are lost until the run asks again; cancelling a run that is suspended leaves the host's registry saying `SUSPENDED`. No HTTP serving was tried (the handler and the loopback server were exercised in 32b). The audit rules (LA24 to LA27, LR02) are 32e, so the docs do not claim them yet.

### 32c, ecosystem surfaces (observation, editor, kit, debugger, guide), 2026-11-11

**Built.** Observation (R37): `loom-inspect-core` `Semconv` (`loom.remote` span kind, `loom.remote`, `loom.remote.task`), `TraceMapper` (one `loom.remote` span per remote call, with a span id from the run and step so it survives a resume; a failed call ends it in error; what the remote said is never in a record), a new `REMOTE` incident (a remote that failed or ran past its timeout, or a channel that spent its question limit: high, or warn when an `on_failure` took over; names the remote, step and attempts, never remote text), its label in the live page, the wiki pages (`Observability-Incidents-and-Rules`, `Observability-Reference`) and `docs/loom-inspect/DESIGN.md`. Editor (R40): grammar words (`remote`, `agent_channel`, `card`, `auth`, `require_signed_card`, `audience`, `max_questions`), hover for each (and for `ask`), three snippets (`remote`, `agent-channel`, `ask`) that the snippet test types and checks with `weave check`, README snippet list, CHANGELOG, version 1.4.0. Kit (R38): `ctk/scripts/a2a_call.loom`, `mocks/a2a_call.json`, `traces/a2a_call.json`, README section, and `A2ACtkConformanceTest` (a call that completes, a question answered by the channel, a card on another host). Debugger: `StepperRemoteTest` (stops before a remote call and names it; stepping over it without a transport sends nothing and says why). Guide: a section in `docs/guide/06-build-the-workflow.md` (parse-tested).

**Tests.** `RemoteIncidentTest` (5), `RemoteMappingTest` (4), `A2ACtkConformanceTest`, `StepperRemoteTest` (2); `GuideExamplesTest`, `GuideLinksTest`, `GuideTutorialPageTest`, `A2ADocsTest` green. The kit test was broken on purpose (an expected message count changed) and failed with the case named, then restored.

**Tiers run for this slice.** `make docs-check` exit 0, tree clean afterwards; `make test-core` exit 0; `loom-inspect-core` install with its gates; the extension suite (782 tests) once with 8 failures that were the three new snippets (a `remote` needs an `agent_channel`, and these three carry warnings by design, so the test now checks them for errors only) and the parity baseline (regenerated: the frozen TypeScript parser reads `Supplier` in the kit script as an agent, a tracked difference), plus five timing tests that failed only while other builds ran and passed alone (28 of 28). A full extension rerun after the fixes was not repeated on a quiet machine. `make test-fast`, `make test-oracle` and `make release-check` are for the end of the phase.

**P9 (other runtimes).** No other Loom runtime exists in this repository (the kit's README still says "Java and Python"; `scripts/loomgen.py` is a generator), so the Java conformance test is the only one to extend.

**Not done in this slice.** The security signal for an exposed workflow's input and the Fabric display of served tasks belong to 32d and 32e (nothing is served yet). Fabric shows the new records the way it shows every record, as names and counts; no Fabric-specific test was added. The tutorial chapter, starter, skills and the other guide chapters are 32f.

### 32c, runtime for calling (second slice), 2026-11-11

**Status: 32c is still NOT done.** The language foundation and the calling runtime are built and tested; the surfaces listed below are not.

**Built and green.** `RemoteRunner` (a `delegate` to a `remote`), `AskRunner` (`ask`), `Exchanges` (one `ExchangeGuard` per run and step, the runtime's own timeout through `callWithTimeout`, the file sink), `ExpectedShape` (the `expecting` check), `RunSuspended.Reason.REMOTE`, `TraceEvent.REMOTE` and `EXCHANGE`, `HarnessExecutor.setA2ATransport` and `setAgentChannel`, `RemoteTasks` (`wiring` for `LoomHost.Builder.customizer`, and an idempotent `taskChanged`). `RemoteAgent.findBySentMessage` was added to the core for the crash rule. **`LoomHost` itself is unchanged**: its existing `customizer` carries the wiring.

- Behaviour tested end to end against the mock A2A 1.0 agent over loopback: a call that completes at once and a replay that sends nothing; a plain-message reply; a still-working task suspends the run; a question answered through the channel; a failed task and `on_failure`; a card pointing elsewhere refused with nothing sent; a crash after the send (the dropped send is found again by its message id, no second task); a server that will not list, and one with no matching task (the step stops and sends nothing); a state that never becomes known fails at the deadline; a missing transport or channel fails closed; `ask` (typed answers re-asked and counted, the limit, a throwing channel, no second ask on resume); the result variables (`quote`, `quote_data`, `quote_files`, `expecting` from a data part or from JSON in text, several data parts, a mismatch); retry only after a definite failure; a link offered to the channel and never followed; an inline file accepted through the sink under `received/`; a stopped run asks the remote to cancel its task, and a cancel that fails is recorded and does not block the stop; `taskChanged` through a real `LoomHost` with file persistence (a repeat, a stale notice and an unknown run change nothing; the message was sent once). 59 tests in `loom.a2a`, plus 9 in `A2ADocsTest`.
- `make docs-check` clean; `make test-core` green (exit 0). The Loom fast tier was last run before the 32c runtime slice; it is run again at the end of the phase.

**Guard-removal checks for 32c (all restored afterwards).** CAUGHT: the send journaled before it happens; an earlier send is looked for and not repeated; an unknown state is never completed; the `expecting` shape is checked; a stopped run cancels remote tasks; a missing channel fails closed; the `ask` answer is checked; a stale `taskChanged` changes nothing. First run MISSED, then **tests added and re-run: CAUGHT**: a replay never resends (the plain-message case had no test), the stop when no recent task holds the message id, retry only after a definite failure, that a remote's reply is tagged unchecked, a missing transport fails closed (the assertion was too loose), and the sink guard on text going back to a remote. **Still MISSED, explained:** `taskChanged` ignoring a run that is not suspended: the run record of a completed run no longer carries the suspension reason and detail, so the status test is redundant with the other two (an equivalent mutant); it is kept as a defence.

**Deviations.**
1. A remote task entry in the journal records the remote's name, so a stop can cancel it (`<step>#r<n>/task`); journal keys for a remote step are `<step>#r<attempt>/send`, `/task`, `/ex<n>` and `/cancelled`, and a step's final entry is `remote` or `remote_failed`. Each `retry` attempt is a new message.
2. A reply to a question is journaled before it is sent (`/ex<n>` kind `reply`, then `sent`), so a crash between the two never asks the channel again. A `Defer` suspends the run with the token in the journal; **completing a deferred answer later (writing the reply into the journal) has no API yet**: this is not built.
3. An operator's stop or pause takes effect between a remote's states (the drive loop calls the run control); a stop asked for while the run is suspended at a remote wait is not seen until the run resumes (there is no operator stop on a suspended run in the host).
4. The `media: unscanned` / PII guard for files sent to a remote (R20) is **not built**; text sent to a remote passes the policy sink guard.
5. `AUTH_REQUIRED` goes to the channel as an authorization request and counts as a question, as designed; no test drives it end to end yet (the helper and the mock were tested for it in 32a).

**Not built in 32c, still to do (R31 to R38 for the calling constructs):** `SchemaInfer`, `StatementText`, `StaticSinkChecks`, `WorkflowLint`, `ScriptDrift`, import handling beyond `merge`; `weave test` scripting of a remote and a channel; the stepper, `why`, `whatif`, `Narrator`; the graph node kinds, chips and the renderer copies (`sync-graph-render.sh`); symbols (`FileIndexer`, `SymbolService`, `docs/serve/PROTOCOL.md`); the review rules, `AuditCatalog`, `CostEstimator` and `RunProfile` showing a remote step as outside the budgets; `Capture`; loom-inspect incidents and Fabric; OTLP spans; the VS Code grammar, hover, completion, snippets and parity baseline; the compatibility-kit scenarios; the tutorial, starters and skills; the `SECURITY_AND_REVIEW.md` page. The `ctk` check of whether another runtime needs the scenarios is not done.

### 32c, first slice (Loom syntax and `weave check` for calling), 2026-11-11

**Status: 32c is NOT done. Only the language foundation is built.** The runtime, the journal and `taskChanged`, the exchanges through the channel, files, `weave test` scripting, the stepper, the graph, the editor, the trace and inspect events, the compatibility kit and the `LOOM_GUIDE` / `docs/a2a/LOOM.md` sections are not started.

**Built and green.**
- AST: `RemoteDef`, `AgentChannelDef`, `ExposeDef` (with its skills and budget), all on a new `NamedSettings` base, and `AskStmt`; `LoomScript` holds and merges (imports) the three lists.
- Parser: `remote Name {}`, `agent_channel Name {}`, `expose Name {}` (with `skills: [ { id name description } ]` and a `budget { }` block) and `ask Channel "text" -> var [expecting {}] [retry] [backoff] [timeout] [on_failure {}]`. All four words are contextual: tests show an agent, a variable and a parameter named `remote`, `expose`, `ask` or `agent_channel` still parse, and that a script without the words is unchanged. `delegate ... to Remote` needed no parser change.
- `weave check` (`A2AChecks`, R26 for calling and for `expose`): unknown settings (with a tailored message for `version` and `pii`, which are deliberately not settings), missing `card`, `agent_channel`, `audience`, `workflow`, `description`; a card that is not https; a literal `auth`; an undeclared channel on a `remote`, an `expose` or an `ask`; `require_signed_card`, `timeout`, `max_input`, `max_questions` and `audience` values; an `expose` naming a missing workflow or a `text_input` that is not a parameter; a token budget on a `delegate` to a remote; a dynamic target list that mixes remote and local agents; `handoff`, `broadcast` and `extract` naming a remote. `delegate` to a declared remote no longer reports "no agent named". `attach` to a remote with no `media_root` is reported by the existing media check. A warning for every `remote` ("not validated") and for every channel (the host supplies it).
- R31 visitors handled so far: `StatementWalker` (an `ask`'s `on_failure`), `SubstitutedText` (the question is scanned; the channel and variable are not substituted: a structural test found this immediately), `ScriptValidator` (`knownAgent`, `walk`).
- Tests: `A2AParseTest` (5), `A2ACheckTest` (16). `make test-core` green (exit 0).

**R31 to R37 visitors still to do for these constructs (found by the survey of where `intentclassify` appears):** `SchemaInfer`, `StatementText`, `StaticSinkChecks`, `WorkflowLint`, `ScriptDrift`, the loader's import handling beyond `merge`, `Stepper`, `Kinds`, `GraphBuilder`, `NodeDescriber`, `SymbolService`, `FileIndexer`, `HandoffFacts`, `ConditionFacts`, `AuditCatalog`, `TestabilityRules`, `CostEstimator`, `RunProfile`, `Capture`, `Narrator`, the `wtest` classes (`Assertions`, `Labels`, `TestChecker`), `SecurityAudit`, `HarnessExecutor`, plus the editor grammar, hover, snippets and parity baseline.

**Guard-removal checks for 32c so far:** none run yet; the `weave check` tests assert each refusal, but the "break the guard and see a test fail" check has not been done for them.

**Deviations.** `skills` is parsed by a dedicated list-of-objects form because no such form existed (P4): `skills: [ { id: "x" name: "y" description: "z" } ]`; a skill needs an id, and its name defaults to the id. The check for a `text_input`-less exposed workflow is not an error (the card then advertises JSON input only), matching R23.

### 32b (the handler, the card builder, `AgentTaskRunner`), 2026-11-11

**Built and green (core).** `A2AHandler`, `CallerIdentity`, `A2ARequest`, `A2AResponse`, `TaskRunner` (with `OwnedTask`), `TaskRunners`, `TaskStore`, `AgentCardBuilder`, `AgentTaskRunner`; the server half of `A2AJsonRpc10` (`parseRequest`, `writeResponse`, `writeError`, `writeCard`). `mvn -pl src/a2a-testkit,src/ai-agent4j verify`: 3,958 core tests and 31 test-kit tests, 0 failures; `spotless:check` clean; the release-profile javadoc build of the core passes. `make docs-check` clean; `make test-core` was green after 32a (before 32b; it is run again with 32c).

**Interop (A18 and the second half of A31).** `HandlerInteropTest` mounts our handler on a loopback server (a test host that does the authentication, parses with the helper, waits for a blocking send and writes the answer) and calls it with `RemoteAgent` through our helper; everything the server writes is checked against the official definition. **It found a real bug:** a get or cancel answers with a bare `Task`, a send wraps it, and the first server-side writer used the wrapped form for all three. Fixed by making `writeResponse` take the request. Evidence level: loopback, our handler against our client, both from one reading of the specification; **not a third-party implementation**.

**Guard-removal checks (TEST_STRATEGY section 5), all caught by tests and restored:** 11 (no identity refused; scope filter on get, cancel and send; scope filter on list), 17 (a waiting send is not answered at once), 18 (list and get scoping), 19 (terminal task takes no more messages; mismatching context and task rejected), 25 (the plain-agent runner refuses without the limits confirmation), 26 (one runner per scope: this check first showed the test was weak, since it only called as one caller; the test was strengthened and then caught the mutation), plus: internal failures never leak their message, the card defaults to no streaming, the page-size upper bound.

**Survey answers (P4).** A `ReActAgent` run can be cancelled through `RunHandle.cancel(...)` (interrupts at the next step), so `AgentTaskRunner` cancel works for it. Not yet answered: how a `LoomHost` is built with its own persistence per scope (32d).

**Deviations.**
1. A2AHandler's response to a get or cancel is `TaskResponse`, and the wire shape is chosen by `A2AJsonRpc10.writeResponse(id, request, response)`; DESIGN R13 said `handle(...) -> A2AResponse`. The handler returns a `CompletionStage<A2AResponse>` as D27 says; an error is an exceptional completion with an `A2AException`.
2. `TaskRunner` takes the scope key on every method and returns `OwnedTask` (task, owner scope, last update), and has `settled(scope, taskId)` for a waiting send. The handler re-checks the owner of every task a runner returns. DESIGN described a runner per scope only; both are kept (principle 10, defence in depth).
3. `A2AException` gained `InvalidParams` and `MethodNotFound` (the protocol's invalid-parameters and method-not-found errors); a request naming a context the runner cannot honour is `InvalidParams` and the context is never replaced.
4. There is no in-memory `TaskStore` in the core: the consumer supplies one (D22); the tests have one.
5. The loopback test host waits for a blocking send with a bounded wait on the stage (the host's job, not the handler's); this is a test host, not shipped code.

**Not verified in 32b.** Behaviour under a real web stack; a `TaskRunner` over a Loom host (32d); the mutation profile (not yet written); `SpecExamples` fixtures.

### 32a, code half (core model, guards, `RemoteAgent`, helper, mock agent), 2026-11-07

**Status: the code and its tests are done; the 32a documentation half is NOT done** (the `docs/a2a/` pages, `API_COMPATIBILITY.md`, `llms.txt`, the core README and wiki page, the `PRINCIPLES.md` pointer, the Javadoc/`make release-check` pass, the mutation profile). 32a is therefore not closed and 32b must not start.

**Built.** `io.github.llm4j.a2a`: `TaskState`, `Part`, `A2AMessage`, `Artifact`, `RemoteTask`, `SendResult`, `SendOptions`, `AgentCardView`, `A2AException` and subclasses, `A2ATransport`, `EndpointGuard`, `AgentChannel`, `ChannelMessage`, `ChannelResponse`, `ChannelContext`, `ReceivedFile`, `ReceivedFileSink`, `ExchangeGuard`, `RemoteAgent`, `A2AJsonRpc10`. New unpublished module `src/a2a-testkit` (no llm4j dependency): the official `a2a.proto` at `v1.0.0` (hash-checked, compiled with protoc), `OfficialDefinition` (validator), `SpecRules` (23 rules, each with its section, checked against the text), `MockA2AAgent`. No runtime dependency was added to `ai-agent4j` (the test kit is test scope).

**Tests.** Core: 100+ new tests (model, endpoint guard, exchange guard, sink, `RemoteAgent`, helper, 15 loopback tests). Test kit: 31 (validator, one test per rule, a meta-test that every rule id is exercised). `mvn -pl src/a2a-testkit,src/ai-agent4j verify`: 3,902 core tests, 0 failures. `spotless:check` clean on the core. The mock validates every request it receives and every response it sends against the official definition, and the tests assert it recorded no invalid output.

**Evidence level (A31, partial).** Loopback against the mock agent, whose rules are traced to the specification and whose messages are validated against the official definition. **Not verified against any third-party implementation**: no SDK sample or conformance kit was found or run (P10 not finished), and P6 (a live agent) is unanswered. The mock and the helper were written by the same author from the same reading, which the definition validation only partly mitigates.

**Guard-removal checks (TEST_STRATEGY section 5), each caught by at least one test, then restored:** 1 endpoint guard, 2 unsupported-endpoint refusal, 3 question limit, 4 timeout owned by the runtime, 5 reply validation, 6 size cap while writing and claimed-kind check, 7 sender name never a path, 8 unhandled response fails, 10 credential never resolved by us, 14 UNKNOWN never completed, 15 wire names only in the helper, 16 required extension, 22 failed signature and `require_signed_card`; plus the terminal-task reply refusal. Not yet checked: 9 (link never followed: no link-fetching code exists in 32a, so nothing to break yet), 11 to 13, 17 to 21, 23 to 27 (later sub-phases).

**Deviations and corrections to DESIGN (all to be folded into DESIGN by the owner or me on request).**
1. **R8 exhaustive `switch`.** The code targets Java 17, where pattern-matching `switch` is only a preview feature. The sealed types are exhaustive for a consumer on JDK 21 and later; in this repository's own code and tests an `instanceof` chain is used. The compile-time guarantee that "a new kind cannot be added without every channel being updated" therefore holds only for JDK 21 consumers. The Javadoc says so. `SealedSwitchIsExhaustiveTest` as named in TEST_STRATEGY cannot be written on 17.
2. **A message to a terminal task** is answered by `UnsupportedOperationError` (-32004, section 3.1.1), not "task not found"; `TaskNotCancelableError` is for cancel. DESIGN R13 should say so for 32b.
3. **`TaskState.fromName` was removed** from the model: a state spelling is a wire name, so the tolerant mapping lives in `A2AJsonRpc10` (principle 9). 
4. **`RemoteAgent.send` returns a `SendResult`** (a task or a plain message), not a `RemoteTask` as R4's first sentence says; `reply`, `get` and `cancel` return `RemoteTask`. `reply` reads the task first (one extra call) so that it can refuse a task that is not waiting on the caller.
5. **Credential parameter** of the transport is a nullable `SecretRef` (null means none) rather than an `Optional`, per CLAUDE.md (no `Optional` parameters).
6. **Where the mock agent lives:** a new unpublished module `src/a2a-testkit` listed first in the parent's `<modules>`. This adds a module to the reactor and needs the owner's approval; it was proposed in section 7 above and not yet answered.
7. **The mock agent does not implement** streaming, push-notification delivery with a webhook receiver, or the extended card: its card declares them unsupported and it answers with the specification's errors (rules `streaming-unsupported`). TEST_STRATEGY section 3 listed them; they are not needed until a consumer uses them and llm4j itself offers none (R14).
8. **JSON Schema:** none is published at the tag, so the validator is built on the compiled definition and is stricter than protobuf's JSON parser (lowerCamel names, string enums, required fields, oneof presence). It is our own strictness, justified by the specification's text; it can reject something another implementation accepts.
9. **Test-first honesty.** For the model and the endpoint guard the first run was red (compile error, then a failing run). For the sink, the exchange guard, `RemoteAgent` (first run: 16 of 19 red, from a constructor bug), the helper and the mock agent the tests and the code were written in the same pass; the guard-removal checks above are the evidence that the tests do test the guards.
10. **`make setup`** does not install `eval4j`; see the baseline entry above.

**Open for 32a:** `SpecExamples` (the specification's own examples as fixtures) is not built; the SpecRules table is the MUST statements the mock enforces, not yet a full pass over every MUST and SHOULD in the specification; no local third-party implementation was looked for.

### Survey and baseline (P4 to P10 in progress), 2026-11-07

**P7 baseline** (`make test-fast` after `make setup`, untouched checkout at the merge of PR 107). `ai-agent4j`, `ai-agent4j-addons`, `ai-agent4j-tools` and `eval4j` passed. `ai-agent4j-loom` passed its tests but **failed the coverage gate: `execution.Decider` line coverage 0.77, minimum 0.78** (a failure that exists before this phase; the modules after Loom were skipped, so there is no baseline for them yet). Not fixed here and the limit is not lowered; it needs a test for the uncovered `Decider` lines, which the owner should decide to take separately or inside 32c. The mutation profiles and the extension suite are not yet baselined.

**`make setup` defect.** `scripts/setup-dev.sh` installs `ai-agent4j`, Loom and `loom-inspect-core` but not `eval4j`, so a stale `eval4j` jar in `~/.m2` (from the image) makes Loom fail to compile (`EvalDataset.load`). Worked around by `mvn -pl src/eval4j install`. Proposed fix: add `src/eval4j` to its `-pl` list.

**P10 (partly done).** The official definition is `specification/a2a.proto` at tag `v1.0.0` (sha256 `4b74c0baa923ae0acb55474e548f1d6e5d3f83b80d757b65f8bf3e99a3c2257f`); the specification text `docs/specification.md` has sha256 `087c6f6272ec08a7fcd90eb16843bf97e25e0dcd155afa5779033d23d5ac377d`. **No JSON Schema is committed at that tag** (the specification says `a2a.json` is generated at build time). Plan: validate in test scope by compiling the proto and parsing every message with protobuf `JsonFormat` (unknown fields, enum names and types are rejected). Not yet checked: that protoc resolves through Maven here; a local SDK sample or conformance kit; the `SpecRules` table.

**Unverified items now confirmed (spec text at v1.0.0).** Versioning (section 3.6): clients MUST send `A2A-Version` (`Major.Minor`) on every request, or as a request parameter; an empty value means 0.3; agents MUST return `VersionNotSupportedError` for an unsupported version. Tenant: a `tenant` field in each request message and in `AgentInterface` (REST: a `/{tenant}/` path prefix). Push configuration: `TaskPushNotificationConfig` is flat in the definition (`id`, `task_id`, `url`, `token`, `authentication`). No change to DESIGN is needed.

**P5.** The last audit rule documented is LA23, so LA24 to LA27 are free. The review-lens numbers (next free LR) are still to be read from the rule files.

**P4 (partial).** The surfaces that handle a statement such as `intentclassify` are the parser, `StatementWalker`, `SchemaInfer`, `StatementText`-style visitors, `Stepper`, `Kinds`, `GraphBuilder`, `NodeDescriber`, `SymbolService`, `FileIndexer`, `HandoffFacts`, `ConditionFacts`, `AuditCatalog`, `TestabilityRules`, `CostEstimator`, `RunProfile`, `ScriptDrift`, `Capture`, `Narrator`, the `wtest` classes, `SecurityAudit`, `Decisions`, `WorkflowLint`, `SubstitutedText` and `StaticSinkChecks`. This matches DESIGN R31 to R36. Proposed home for the mock agent: a new unpublished module `src/a2a-testkit` with no dependency on `ai-agent4j` (so it is an independent reading of the specification and both core and Loom tests can use it); awaiting the owner's view.

**P6.** Asked the owner whether a real third-party agent is available; no answer yet.

(Each sub-phase then records here what was built, the tests, the guard-removal results, deviations and what was not verified, newest first, as in [phase 31](../31-multimodal-input/VERIFICATION.md).)
