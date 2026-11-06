# Phase 32 verification plan: A2A integration

Status: **not started**. Run §0 first, then top to bottom at the end of each sub-phase (**32a** the core, R1 to R12; **32b** the handler, R13, R14; **32c** Loom calling, R15 to R21, R26, R29; **32d** Loom being called, R22 to R25; **32e** audit and Fabric, R27, R28;
**32f** documentation, editor, compatibility kit and mutation tests). Results in §7. Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md). Principles: [PRINCIPLES.md](PRINCIPLES.md).

## 0. Before writing anything

| # | Task | Done when | Result |
|---|---|---|---|
| P1 | **The owner reviews this spec** and answers the proposed decisions D19 to D26 (or accepts the proposals) | the decisions table in DESIGN §6 says taken for each, or the item is removed | ⬜ |
| P2 | **Read the whole A2A 1.0.0 specification** (the page was only partly read verbatim): the exact definitions of Task, task states, Part (is there a separate structured-data part, and how is a file carried), the complete agent card fields, the JSON-RPC method names and result shapes (including the push-notification configuration methods), the error codes, and Appendix A (what changed from 0.3.0) | every item in the "unverified" list of the working notes is marked confirmed with the section number, and `SpecExamples` is filled with the specification's own examples | ⬜ |
| P3 | **Find out whether servers de-duplicate a message id**, and whether a client may supply the context id, so that the crash rule (R16) is sound. If neither holds, the rule rests on `find` by context id alone | recorded with the section, and DESIGN R16 matches | ⬜ |
| P4 | **Survey Loom** for the places the new words touch: every statement that can be followed by `ask`/`on_failure`, how `delegate` binds its result and a list variable (the grounding citations are the pattern), how `LoomHost` represents a pending `human_prompt`, what `runs()` and `run(id)` return, what a stopped or failed run looks like, and which parts of `ExecutorValidation` a new declaration must register with | the list is in §7 and DESIGN §4 matches it | ⬜ |
| P5 | **Find the next free audit rule numbers** and the review-lens rules to use, and read `docs/security/weave-audit.md` and `owasp-llm-top-10.md` for the wording to follow | recorded in §7 | ⬜ |
| P6 | **Ask the owner whether a real A2A agent is available** for the live check (one that calls out or can be called; a sample from a vendor's SDK is enough). Keys stay in the owner's environment, never in the chat | recorded as run or not run | ⬜ |
| P7 | **Baseline**: `mvn verify` on `ai-agent4j`, `ai-agent4j-loom` and every module after it, the mutation profiles, and the VS Code extension suite; record the scores and any failure that exists before the change | recorded in §7 | ⬜ |
| P8 | **Check the sample channel and transport compile** against the real interfaces once 32a is built, and keep them as a docs test | the sample is in `src/examples` or a docs test | ⬜ |

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
| A8 | A crash after a send never creates a second remote task | R16 tests |
| A9 | A Loom workflow can be exposed; a task is a durable run; callers only see their own tasks; no identity means refused | R13, R22 tests |
| A10 | An internal approval or `human_prompt` never reaches the caller | R24 tests |
| A11 | `weave check` reports each mistake with its line and fix, and warns for every remote agent that it is not validated | R26 tests |
| A12 | The audit flags an exposed workflow's outside input, data sent to a remote agent and an automatic channel answering outsiders | R27 tests |
| A13 | Nothing a credential, an untrusted string or a received file's bytes contains leaks into a log, trace, journal, report or exception | the shared assertions in every layer |
| A14 | A script without the new words, and every existing channel and statement, behave exactly as before | the existing suites, `LoomHostIsUnchangedTest` |
| A15 | Wire names exist in one class only | `OnlyThisClassKnowsAWireNameTest` |
| A16 | `weave card` prints the card an `expose` block produces, with no network | R25 tests |

## 2. Quality gates

- `mvn verify` green on `ai-agent4j`, `ai-agent4j-loom` and every module after it in the reactor; the Loom coverage rules met (a new class in a gated package is covered to the same bar); `make format-check` clean.
- The mutation profile for the decision-making classes at 85% or better, with survivors listed and explained (as for `mutation-media`).
- The VS Code extension suite green except for any failure recorded in P7 as already present.
- No new runtime dependency in `ai-agent4j` (`mvn dependency:tree` unchanged).
- `make release-check` (sources, javadoc, metadata) green: every new public type and method has Javadoc.
- GitGuardian-clean: no credential anywhere, including fixtures and docs.

## 3. Documentation checklist (principle 7)

Each is updated in the same sub-phase as the behaviour it describes, not later. Check the box only when the page was read and corrected.

- [ ] `PRINCIPLES.md` (if anything here changes a principle) and this folder's specs
- [ ] `LOOM_GUIDE.md` (a section with parse-tested blocks), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md` and `07-validate-and-audit.md`
- [ ] The tutorial (a chapter or sections in 3, 6 and 9; the index; regenerate the page with `scripts/make-tutorial-html.sh`)
- [ ] `src/ai-agent4j/wiki/` (a new A2A page, linked from `Home.md`), the core README, the Loom README, the root README, `docs/ABOUT.md`, `llms.txt`
- [ ] `docs/security/weave-audit.md`, `owasp-llm-top-10.md`, the generated rule pages (`-Dregenerate.rule.docs=true`)
- [ ] `docs/API_COMPATIBILITY.md` and the current release notes
- [ ] The skills under `.claude/skills/` that teach Loom
- [ ] The VS Code grammar, hover text, README and `CHANGELOG.md` (with a version bump, because the release check requires one when the extension changes); update the frozen parser's parity baseline if the corpus changes
- [ ] The sample channel and transport, compiled by a test
- [ ] Documentation that tells the consumer what the framework leaves to them: transport, polling, hosting, login, where the `received/` folder must live with the journal

## 4. Manual checks

| # | Check | Expected |
|---|---|---|
| M1 | Run the Loom sample that calls a remote supplier against `ScriptedRemote` through the CLI with a host-supplied fake transport | the trace shows the exchange, the question handled by the channel, the files listed |
| M2 | Run `weave check` and `weave audit` on the sample | the "not validated" warning, and the expected audit findings with their fixes |
| M3 | Run `weave card` on the sample's `expose` block | the card JSON, with a placeholder address, matches the declaration |
| M4 | Kill the process after the send and resume | the run finds its task and does not send again |
| M5 | With a real A2A agent, if available (P6): call it, answer a question, receive a file | it works, and the exchange is in the journal |
| M6 | Read the sample channel as a reviewer would | the audience, limits and what leaves the process are clear from the script alone |

## 5. Exit checklist

- [ ] All of A1 to A16 pass with the evidence named.
- [ ] Guard-removal checks (TEST_STRATEGY §5) done and recorded.
- [ ] Documentation checklist (§3) done; samples verified.
- [ ] Every claim about the specification is marked verified with its section (P2).
- [ ] Release notes and `API_COMPATIBILITY.md` updated.
- [ ] The roadmap row is current.
- [ ] Anything not done is listed in §7 under "Not verified", with the reason.

## 6. Risks

| Risk | Mitigation |
|---|---|
| The specification is not what we assumed (the page was read in part) | P2 before any code; the design's wire knowledge sits in one class, so a correction is local |
| Servers do not de-duplicate, so the crash rule cannot be proved | P3; the rule relies on `find` by context id, and a step that cannot tell stops with a message |
| Consumer channels and transports are hard to write correctly | a worked, compiled sample; defaults that fail closed; the bounds are the runtime's |
| An exposed workflow leaks data to a caller | the audit rule, caller isolation, the internal/caller split of pauses, guards on incoming text |
| A remote agent is not validated until run time | the warning on every `remote`, `on_failure` encouraged by a review rule, and the endpoint guard before any send |
| Received files fill the disk or hold hostile content | the cap while writing, the type check on close, the `received/` folder inside `media_root`, files only through the channel |
| The new words clash with existing scripts | contextual words, tested with agents and variables of those names |
| Scope is large | six sub-phases, each green and pushed alone; Part C (a plain agent) and the signed-card hook can be dropped without changing the rest |
| The protocol moves | principle 9; only `A2AJsonRpc10` changes |

## 7. Results

(Empty. Each sub-phase records here what was built, the tests, the guard-removal results, deviations and what was not verified, newest first, as in [phase 31](../31-multimodal-input/VERIFICATION.md).)
