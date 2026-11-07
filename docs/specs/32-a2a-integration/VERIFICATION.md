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
| P7 | **Baseline**: `mvn verify` on `ai-agent4j`, `ai-agent4j-loom` and every module after it, the mutation profiles, and the VS Code extension suite; record the scores and any failure that exists before the change | recorded in §7 | ⬜ |
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

- `mvn verify` green on `ai-agent4j`, `ai-agent4j-loom` and every module after it in the reactor; the Loom coverage rules met (a new class in a gated package is covered to the same bar); `make format-check` clean.
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

(Each sub-phase then records here what was built, the tests, the guard-removal results, deviations and what was not verified, newest first, as in [phase 31](../31-multimodal-input/VERIFICATION.md).)
