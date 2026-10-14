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
| 4 | Inspect and incidents | loom-inspect | Mapping to OpenTelemetry records; run state for context fill, compaction and tool statistics; incidents (context nearly full, truncated answer, compaction thrash, tool success rate, latency spike) | 3 |
| 5 | Fabric | loom-fabric | Context gauge and compaction timeline; tools table with success rate and latency percentiles; thresholds on the Configuration page; model-window catalog beside the price table; simulator scenarios | 4 |
| 6 | The profile | all | A written, versioned **Loom Agent Observability Profile**; a conformance suite other runtimes can run; a proposal for the missing pieces upstream | 1–5 |

Phases 2–6 get their own folders and specs when their turn comes. Each is specified in detail only when the one below it is
done, so that what we learn building a lower layer shapes the spec above it.

### Status

| # | Spec | Tests | Code | Verified |
|---|---|---|---|---|
| 1 | approved (decisions recorded) | all written | all done | verified (live and manual checks owed, see VERIFICATION §4); **awaiting review** |
| 2 | approved (decisions recorded) | all written | all done (2a–2e) | verified (live and manual checks owed, see VERIFICATION §4); **awaiting review** |
| 3 | approved, built | all written | all done (3a–3e) | verified (live and manual checks owed); **awaiting review** |
| 4–6 | not started | | | |
