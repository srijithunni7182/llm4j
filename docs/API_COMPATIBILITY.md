# API Compatibility Policy

This document defines the compatibility guarantees for `ai-agent4j` and companion libraries.

## Versioning

- Public artifacts follow semantic intent using the canonical coordinates in `VERSION_MATRIX.md`.
- Major versions may contain breaking API changes.
- Minor versions are expected to remain source-compatible for public APIs.
- Patch versions are bug/security fixes with no intended API breaks.

## Public API Surface

The following packages are considered public and stable targets for consumers:

- `io.github.llm4j` (client entry points)
- `io.github.llm4j.model`
- `io.github.llm4j.config`
- `io.github.llm4j.agent` and `io.github.llm4j.agent.tools`
- `io.github.llm4j.provider`
- `io.github.llm4j.mcp`

eval4j's `io.github.llm4j.eval.dataset.EvalScenario` is a record that grows by adding components at the end. Each time it does, the previous constructor stays
(the 7-, 10-, 12-, 13- and 14-argument forms all still compile and behave as before), and a new accessor has a safe default (`namedInputs()` and `mustNotContain()` are never
null; `expectedMinWords()` and `expectedMaxWords()` are null when no limit is set). Code that builds a scenario with `new EvalScenario(...)` or reads its accessors is unaffected; code that destructures the record by its full component list
(pattern matching) is the one case that must change.

The agent library's run controls are additive. `ReActAgent.run(question)` behaves as before; `run(question, RunHandle)` adds steering, pausing, wrapping up and cancelling,
and `AgentResult` gains `getStop()` (a `StopReport`), `isWrappedUp()` and `isCancelled()`. Streaming, reasoning and tool hooks are builder options that are off by default.
Loom adds the agent settings `streaming`, `reasoning` and `steerable`, the `on_stopped` handler and the `intervention` block; a script that does not use them behaves exactly as before.
The graph JSON gains an `outcome` node kind, a `stopped` edge label and the optional agent flags `streaming`, `reasoning` and `steerable` (older readers ignore what they do not know).
The trace gains the events `intervention` and `step_stopped`, and the run journal the entry kinds `steer`, `stop`, `skipped` and `discarded`. The journal has no format version, so an older `weave` is not stopped from opening a journal with these entries (the design plans a stamp that would make it refuse; it is not built). Finish a run that has them with the version that wrote them.

`RunHook` (checks on the question, each model call and the final answer) and `AgentResult.isInputRejected()` are additive: an agent with no run hook behaves as before. Loom's top-level `shared_prompt` is additive too; a script without it is unchanged.

`io.github.llm4j.loom.host` is new and additive: `LoomHost` (with its `Builder`), `RunRequest`, `RunOutcome` (`Completed`, `Suspended`, `Stopped`), `RunRecord`, `AnswerResult`, `QuestionListener`,
`LoomRunFailedException`, `Persistence` and `DurabilityGuard`. It runs workflows from an application and resumes them from a store (`Persistence.file(dir)` uses the folder layout `weave` uses; `Persistence.jdbc(dataSource)` uses
the tables `LoomHost.createSchema` creates, including `llm4j_loom_runs` and `llm4j_loom_answers`). Beside it, `ScriptSource` and `LoomLoader.load(location, source)` read a script and its imports from anywhere (a jar, say),
`LootLoader.loadIntoRegistry(Reader, ToolRegistry)` and `LootLoader.reaches(Reader)` do the same for a loot file, and `LoomRun.start(executor, label, workflow, inputs, origin, invoker, runner, onDone)` runs on a thread the
application provides, for a named person, and reports when it ends. `LoomLoader.load(String)`, `LootLoader.loadIntoRegistry(String, ToolRegistry)`, `LootLoader.reaches(Path)` and the other `LoomRun.start` forms are unchanged.

Loom adds the statements `halt`, `set` and `return` and the condition `x is ok` / `x is not ok`; a script that does not use them is unchanged (the words are contextual, so a variable called `set` or `ok` keeps working). `io.github.llm4j.agent.task.Task` gains the default method `okOutcomes()` (`{ok}`), so every existing task keeps working. `HarnessExecutor.executeWorkflow` can now throw `io.github.llm4j.loom.runtime.RunHalted` (a `RuntimeException`) when a script halts, next to `BudgetExceeded`, `RunSuspended` and `RunStopped`; `weave run` exits with code 6. The trace gains the events `halt` and `schema_violation`, the run's final status `halted`, and the graph JSON the node kinds `halt`, `set` and `return`; loom-inspect shows a halted run as stopped.

**One behaviour change, deliberately.** An agent's answer is now checked against its `output_schema` / `expecting`. Before, an answer that was not JSON (or did not match) reached the script as raw text; now the step is retried once with a correction, and then fails into `on_failure`. A script that relied on the raw text must handle the failure (or drop the schema). Decision proposals (`decide`) keep reading answers leniently.

Internal implementation details may change without notice, especially:

- `io.github.llm4j.http`
- `io.github.llm4j.util`
- app-specific packages outside core libraries

## Deprecation Policy

- New replacements should be introduced before removing old APIs.
- Deprecated APIs should stay available for at least one minor release cycle when feasible.
- All deprecations must include migration notes in release documentation.

## Breaking Change Requirements

Before introducing a breaking change:

1. Document the impact in `MIGRATION_GUIDE_5_0.md` (or next migration guide).
2. Add compatibility notes in PR description.
3. Update examples and wiki pages in the same change set.

`io.github.llm4j.classify` (new in 6.1.0) and `io.github.llm4j.provider.ClassificationProvider` are additive: `Classifier`, `ClassifyRequest`, `Question`, `Option`, `Scale`, `ClassifyResult`, the answer records, `ClassifyUsage`, `ClassifyEvent`,
`ClassifyListener`, `ClassificationException`, `LlmClassifier`, `ClassifierResolver`, and `io.github.llm4j.provider.typesafe.JevProvider`. Nothing existing changes; an agent, judge or workflow that does not use a classifier behaves exactly as before. In Loom the additions are the words `intentclassifier` and `intentclassify` (contextual: a script that uses them as names keeps working), the trace event type `classify`, the graph node kind `classify` (the graph and trace schemas gain it; a consumer that ignores unknown kinds is unaffected), and audit rule LA19. The answer records grow by adding components at the end, keeping the previous constructor.

**Providers and grounding (6.1.0), all additive.** New: `io.github.llm4j.provider.openai` (`OpenAiCompatibleProvider`, `OpenAiResponsesProvider`, `Dialect`, `ResponsesDialect`, `ResponsesGrounding`, `ModelTraits`, `Vendor`), `io.github.llm4j.provider.perplexity.PerplexityDecisionsProvider`, `io.github.llm4j.provider.DecisionWire`, `io.github.llm4j.provider.GroundingCapabilities`, and `io.github.llm4j.grounding` (`Grounding`, `Citation`, `Span`, `SearchUsage`, `GroundingSupport`, `GroundingOption`, `Recency`). Existing types only gain members, every previous constructor and shape kept: `LLMRequest.grounding` / `LLMResponse.getCitations()` / `getSearches()` (and `toBuilder()`), the `LLMProvider` and `LLMClient` defaults `groundingSupport()` (`NONE`) and `groundingOptions()` (empty), `Limits`, `Spent`, `Remaining`, `Charge` and `PriceTable.Price` (a `searches` component and an optional third price number per thousand searches), `Dimension.SEARCHES` (a `switch` over `Dimension` that has no default must add the case), `ModelCallEvent` / `ModelStats` / `RunStats.totalSearches()`, `ReActAgent.Builder.grounding`, `AgentResult.getCitations()`, `GoogleProvider.GROUNDING_OPTIONS`, `AnthropicProvider.GROUNDING_OPTIONS`, and in eval4j `hasCitations`, `citesAtLeast`, `citesDomain`, `citesOnlyFrom`, `usedSearches` and `ScriptedClient.sources/searches`. A request without `grounding` produces the same provider requests as before (pinned by `RequestBytesRegressionTest` for Gemini, Claude, Sarvam and Ollama). In Loom the additions are the agent setting `grounding: web { ... }`, the budget field `searches`, the variable `<name>_citations`, the provider kinds `openai`, `xai`, `kimi`, `qwen`, `perplexity`, `perplexity_router`, `openai_compatible` and the options `api`, `header`, `query`, audit rule LA20, the `model_call` / `run_end` trace fields `searches` and `citations`, and the graph agent field `grounding` (the graph schema gains it). A script that does not use them behaves as before.
