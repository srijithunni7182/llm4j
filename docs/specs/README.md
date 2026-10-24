# Specs

Work that changes how agents run or what they report is **specified before it is built**. A spec is a set of short
documents in this directory, reviewed and agreed first; the code, tests and docs then follow it, and a phase is finished
only when its verification plan passes.

## The method

Every phase has a folder `NN-name/` with three documents:

| Document | Answers |
|---|---|
| `DESIGN.md` | What are we building and why; the public API (Java types and how a developer uses them); behaviour and invariants; what is out of scope; open questions |
| `TEST_STRATEGY.md` | How each requirement will be tested: levels, fixtures, fault injection, contract tests, what is deliberately not tested |
| `VERIFICATION.md` | How we decide it is done: numbered acceptance criteria, a traceability matrix (requirement → test), quality gates, manual checks, exit checklist |

Requirements in `DESIGN.md` carry stable ids (`R1`, `R2`, …) so a test and an acceptance criterion can point at them.

### The loop for a phase

1. **Spec**: write the three documents. Review them. Open questions are answered or moved out of scope; nothing starts
   with a question open that changes the API.
2. **Tests first**: write the contract and unit tests the spec calls for. They fail for the right reason.
3. **Implement** in small, decoupled pieces until the tests pass. Each piece is usable and testable on its own.
4. **Iterate on quality**: review the code against the invariants, add the fault-injection and soak tests, fix what they find.
5. **Verify**: run `VERIFICATION.md` top to bottom and tick it off. Record the result at the end of that file.
6. **Document**: update every relevant document **at the end of every sub-phase**, not just at the end of the phase (see below). A sub-phase is not done while a document still describes the old behaviour, or describes the new one as if it were finished.
7. **Ship**: a pull request. Specs stay in the repository; when the design changes, the spec changes in the same pull request.

### Documents to update at the end of every sub-phase

| Document | Update when |
|---|---|
| The feature's **wiki page** (for ai-agent4j: `src/ai-agent4j/wiki/`) | Always. Say what works now and what does not, with a status box while a feature is in progress |
| **Code samples** in that page | Always. Samples are written in a test and the page is checked against the test (see `ContextDocsExamplesTest`), so they cannot drift |
| **Javadoc** of every new public type and method | Always |
| The module's **README** and **wiki Home** (hub links and feature list) | When a new page or a user-visible capability appears |
| **`llms.txt`** (root and module) | When a new page or public API appears |
| **Stack-level pages** (root `README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`) | **Once, at the end of the whole work** (decision of 2026-10-14), not per sub-phase. Each phase's `VERIFICATION.md` lists what is owed there |
| **Dependent layers' docs** (Loom guide, observability pages, Fabric) | In the phase that connects them |
| **This directory**: `DESIGN.md` if the design changed, the traceability table and results in `VERIFICATION.md`, the status table below | Always |

An in-progress feature is documented as in progress, in the same words everywhere (the wiki page's status box is the source), and the
words are removed in the sub-phase that finishes it.

### Principles

- **Java first.** Interfaces for extension points, immutable value types (records), builders for configuration, static
  factories for the built-ins, `ServiceLoader` for discovery, `@FunctionalInterface` where one method is enough, no
  reflection tricks, no framework needed. A Java developer should use a built-in with one line and plug in their own with one class.
- **Opt-in, never surprising.** Existing behaviour does not change unless the developer asks for the new behaviour. A test pins this.
- **Decoupled.** A lower layer never knows about a higher one: ai-agent4j knows nothing of Loom or Fabric; Loom knows nothing of Fabric.
- **Observable by design.** Each new behaviour reports what it did through a listener or an event, with numbers a person can act on.
- **Secrets and text stay out of telemetry** unless the detail level allows them, as in [the observability design](../loom-inspect/DESIGN.md).

## Roadmap: from the core upward

Order matters: each phase builds on the one below and is shippable on its own.

| # | Phase | Layer | What it delivers | Depends on |
|---|---|---|---|---|
| **1** | [Context management and compaction](01-context-management/DESIGN.md) | ai-agent4j | Know how full the context is; a `ContextCompactor` extension point with built-in strategies; compaction in both agent loops; overflow handling; token-aware conversation history; a model-window catalog | none |
| 2 | [Call-level telemetry](02-call-telemetry/DESIGN.md) | ai-agent4j | One event per model call (tokens, latency, finish reason, retries, fallback model); a real tool outcome and error category instead of text guessing; per-tool and per-agent statistics | 1 |
| 3 | [Loom integration](03-loom-integration/DESIGN.md) | Loom | A `context` block per agent in the script (strategy and knobs); each model's window configured outside the script; `MemoryEngine` compaction for Loom's transcript; new `TraceEvent`s for context use and compaction; budgets that know about context | 1, 2 |
| 4 | [Inspect and incidents](04-inspect-and-incidents/DESIGN.md) | loom-inspect | Mapping to OpenTelemetry records; run state for context fill, compaction and tool statistics; incidents (broken and diverted workflows, tool success rate, security: trifecta, injection, access; context nearly full, truncated answer, compaction thrash, latency spike) | 3 |
| 5 | [The Fabric page](05-fabric-ui/DESIGN.md) | loom-inspect-core, loom-fabric | Context gauge and compaction timeline; tools table with success rate and latency percentiles; thresholds on the Configuration page; model-window catalog beside the price table; simulator scenarios | 4 |
| 6 | The profile | all | A written, versioned **Loom Agent Observability Profile**; a conformance suite other runtimes can run; a proposal for the missing pieces upstream | 1–5 |
| 7 | [Classification](07-classifier/DESIGN.md) | ai-agent4j, Loom, eval4j | A `Classifier` contract with a chat-model backend and a Jev provider; a Loom `classifier`/`classify` primitive for intent routing; classifier-backed `llmJudged` in eval4j | provider SPI, 2 (telemetry) |
| 8 | [Providers and grounding](08-providers-and-grounding/DESIGN.md) | ai-agent4j, Loom, eval4j | OpenAI, xAI, Kimi, Qwen, Perplexity and a generic OpenAI-compatible provider; then provider-neutral native grounding (web search with typed citations, search budgets, audit) across all providers that offer it | provider SPI, 1, 2, 3 |
| 9 | [Review engine](09-review-engine/DESIGN.md) | Loom | `weave review`: nine default lenses (security, validity, cost and performance, reliability, prompts, observability, durability, testability, privacy) plus four optional (governance, maintainability, oversight, blast radius) in one ranked list with machine-applicable fixes; static cost, time and effects estimates; rules as data in rule packs over a small fact model (`weave rules`); `--fix`; SARIF | existing `check`, `audit`, `graph`, prices |
| 10 | [Editor experience](10-editor-experience/DESIGN.md) | VS Code, Loom | A review panel beside the graph (linked both ways), quick fixes, cost CodeLens, a semantic layer (references, rename, typed completion) from the Java parser, and `weave serve` for instant feedback | 9 |
| 11 | [Workflow tests](11-workflow-tests/DESIGN.md) | Loom | `weave test`: scripted models, tools, tasks and humans; assert the route, calls and effects; failure injection with a virtual clock; branch coverage; a Java twin (`LoomTestKit`) | graph, eval mock seams |
| 12 | [Generate from a description](12-generate/DESIGN.md) | Loom, VS Code | `weave generate`: description to plan to project, gated by check, review and generated tests before it is shown; edit mode with a review and cost delta | 9, 11 |
| 13 | [Time-travel debugging](13-time-travel/DESIGN.md) | Loom, VS Code | `weave why` (exact decisions re-derived from the journal), a stepper with step-back, `weave whatif` (replay a past run with a change), a run overlay on the graph, a debug adapter | journal, `fork`, `OverlayJournal` |
| 14 | [The production loop](14-production-loop/DESIGN.md) | Loom, Fabric, CI | `weave capture` (a real run becomes a test), `weave regress` (a change against real history), `weave gate` (CI), Fabric incident actions; masking, consent and approval built in | 11, 13 |
| 15 | [Embed and deploy](15-embed-and-deploy/DESIGN.md) | Loom, Spring | Typed Java bindings and `@LoomWorkflows`; optional typed workflow parameters; `weave deploy` to Docker, compose and Kubernetes with Fabric wired and secrets kept out | 9, Spring starter, `package` |
| 16 | [First run](16-first-run/DESIGN.md) | Loom, VS Code | `weave try` (zero keys, narrated), `weave doctor`, JBang and a launcher, an interactive `init`, `weave next` as the spine, an editor welcome view | 11, 12 |

Phases 9 to 16 are the **Loom developer experience** program: [the overview](LOOM_DX.md) has the thesis, the shared contracts, the promises and the build order.

Phases 2–6 get their own folders and specs when their turn comes. Each is specified in detail only when the one below it is
done, so that what we learn building a lower layer shapes the spec above it.

### Status

| # | Spec | Tests | Code | Verified |
|---|---|---|---|---|
| 1 | approved (decisions recorded) | all written | all done | verified (live and manual checks owed, see VERIFICATION §4); **awaiting review** |
| 2 | approved (decisions recorded) | all written | all done (2a–2e) | verified (live and manual checks owed, see VERIFICATION §4); **awaiting review** |
| 3 | approved, built | all written | all done (3a–3e) | verified (live and manual checks owed); **awaiting review** |
| 4 | approved, built | all written | all done (4a–4e) | verified (live and manual checks owed); **awaiting review** |
| 5 | approved, built | all written | all done (5a–5e) | verified (live and manual checks owed); **awaiting review** |
| 6 | not started | | | |
| 7 | approved (decisions recorded; scope for 6.1.0 set; §4.3 corrected from the real API) | 7.0 study run ([report](07-classifier/STUDY_REPORT.md)) | 7a, 7b, 7c, 7d, 7e done (contract, LlmClassifier, judge via `.classifier`, ClassifierComparison, JevProvider + recorded fixtures, resolver, Loom `intentclassify` with checks, trace, budget, audit, graph, CTK, docs, skills, example) and VS Code support; the Java port of the study (7c-live), PIT and a live Loom run are done (VERIFICATION §7.2) | verified (the only owed item is `make format-check`, whose baseline already fails); **awaiting review** |
| 8 | approved (decisions recorded; scope for 6.1.0 set; §3.2 corrected from the vendors' references) | Part A: 8a, 8b, 8b2, 8c written first and green | Part A done (8a to 8c-live): OpenAI, xAI, Kimi, Qwen, Perplexity Agent API and Orchestrator, generic kind, Loom/Spring/CTK wiring; Part B done (8d to 8h): the grounding contract, native grounding for Perplexity, OpenAI, xAI, Google and Anthropic (supported, recorded; the few Claude settings not yet recorded are listed in VERIFICATION §7.13), `ReActAgent.grounding`, Loom `grounding:`, `searches` budgets, audit LA20, eval4j citation assertions, tooling; 8k Perplexity Decisions (supported, recorded). Follow-ups done: the Phase 7 study with Perplexity Decisions, Loom `use: perplexity_decisions` and Spring wiring, `listModels` for the OpenAI family and Anthropic, Anthropic `pause_turn` (VERIFICATION §7.12). Left out: Kimi and Qwen grounding and fixtures (no key) | Verified (VERIFICATION §7); **done, not released** (no version bump, no PR) |
| 9 | draft for review | not started | not started | not started |
| 10 | draft for review (built to its recommendations, OPEN_ITEMS D10) | 10a to 10e written first and green | all done (10a to 10e): `weave serve`, the review panel in three layouts with triage, the semantic layer, degraded modes, accessibility and latency gates | verified (VERIFICATION §7); the manual checks in VS Code are owed; **awaiting review** |
| 11 | draft for review | not started | not started | not started |
| 12 | draft for review | not started | not started | not started |
| 13 | draft for review | not started | not started | not started |
| 14 | draft for review | not started | not started | not started |
| 15 | draft for review | not started | not started | not started |
| 16 | draft for review | not started | not started | not started |
