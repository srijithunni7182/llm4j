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

`RunHook` (checks on the question, each model call and the final answer) and `AgentResult.isInputRejected()` are additive: an agent with no run hook behaves as before. Loom's top-level `shared_prompt` is additive too; a script without it is unchanged. **New public contract in 6.2.0 (build identity):** every module jar's manifest carries `Implementation-Version`, `Build-Kind` (`release` or `development`), `Build-Commit`, `Build-Dirty` and `Build-Time`; `io.github.llm4j.BuildIdentity` reads them; `weave --version` prints `weave <version> (<kind>, <commit>, built <time>), serve protocol <n>` (the `serve protocol` suffix is kept). Main carries `-SNAPSHOT` versions between releases; `llm4j.released` names the last release. **Behaviour change in 6.2.0 (degraded runs):** `weave run` exits 1 when no model step succeeded and every step fell back (it exited 0); exit code 7 exists only with `--exit-degraded`; `HarnessExecutor` gains `output()`, `fallbacksTaken()`, `modelSteps()` and `successfulModelCalls()`, and `Runs.Result` (internal) gains two components. **Behaviour change in 6.2.0 (output limit):** `BudgetSet.DEFAULT_OUTPUT` is now 8192 (was 1024) and `BudgetedLLMClient` sends a model's known maximum output when a budget limits output and no limit was chosen; `BudgetSet.reserve` has an overload taking the model maximum, and the old forms behave as before with the new default. **Behaviour change in 6.2.0:** loading a script (`LoomLoader`, so every `weave` command) now keeps the script's own `shared_prompt`, `supervise` and `intervention` declarations, which 6.1.0 and earlier dropped. A script that declares them is now supervised and has its shared prompt applied; `LoomScript.merge` also carries `supervise` blocks and the `intervention` defaults from imports.

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

**Model listing, Decisions wiring and paused turns (6.1.0), all additive.** `OpenAiCompatibleProvider`, `OpenAiResponsesProvider` and `AnthropicProvider` now implement the existing `DescribableProvider` (`listModels()`, `getFirstAvailableModel()`), with the new `io.github.llm4j.provider.ModelListing` helper and `Dialect.modelsPath()` / `ResponsesDialect.modelsPath()` (plus their builder methods; the default is `/models`, null for Perplexity's Orchestrator and Agent API, whose `listModels()` throws `InvalidRequestException`). `AnthropicProvider.chat` continues a `pause_turn` reply; a reply that never pauses is sent and read exactly as before. Loom gains the provider kind `perplexity_decisions`; the Spring starter gains `llm4j.classifiers.<name>` (the `Classifiers` bean), `llm4j.budget.searches` and `llm4j.pricing.models.<m>.search-per-thousand`. No existing property, type or signature changes.

**The graph of a `parallel` block (6.2.0), a change of shape.** The graph JSON gains two optional node attributes: `lanes` on a `parallel` node (a list of `{label, nodes}`, one per branch) and `value` on a `set` node (the value as written, masked and cut at 200 characters). The branches of a `parallel` block are now nodes of their own (with their lines and their `on_failure` handlers), laid out after the main path so that **no existing step is renumbered**; the block leads to each branch and every branch leads on to the step after the block, where before the block led straight to the next step. A consumer that reads only `agents` and `branches` of the block, or that follows edges from it to the next step, now sees the branch nodes in between. `graph-result.schema.json` allows both attributes. `weave graph --format mermaid` draws each lane as a `subgraph`. An older engine sends no `lanes`, and the editor and the shared renderer still draw its graph as before.

**Strings, parse hints and CLI options (6.2.0).** The lexer reads `\"` and `\\` inside a string as a quote and a backslash (any other backslash is kept as written), so a string can no longer end with a single backslash before its closing quote. **This is an incompatible change for scripts that wrote a doubled backslash**: `"C:\\dir"` and `"\\d+"` now hold one backslash where they held two, and `"C:\"` (which used to lex) is an unterminated string. Write `\\` for each backslash you want, and `\\\\` where you meant two. Parse errors may end with ` Hint: …` (the message text before it is unchanged). `weave test` gains `--classes`, `--no-auto-classes`, `--loot`, `--env-file` and `--no-env-file`, every `weave` command `-v` / `-vv`, and the default log level of `weave run`, `resume`, `tick` and `daemon` is now WARNING. `ProjectClasses.use` gains an overload with an `auto` flag; the old signature is unchanged.

**Review setup findings and cost fields (6.2.0), additive.** `ReviewReport` gains a `setup` component (findings about the machine, such as a key that is not set); its previous constructors are kept and give it no setup findings, and `withSetupAsFindings()` merges them back. The JSON report gains `setup` and `summary.setup`, and each cost workflow `tokenCap` and `capStopsBeforeWorstCase`. `weave review` gains `--include-setup`; the default now excludes setup findings from `--fail-on`. `weave observe rules` gains `--init`.

## Additions in 6.2.0 (Loom language and test files)

- **`substitution: legacy|braces`**, a new top-level script setting. Absent means `legacy`, today's behaviour; `braces` becomes the default only in the next major version.
- **`real:`**, a new key of a workflow test case. Test files without it behave as before.
- **`weave check --substitution`, `--fix-substitution`, `--yes`**, new options.
- **`weave fabric up | down | status`**, a new command. The deployer scripts keep working without it; with it, `fabric-up` generates a first-start admin password instead of refusing, and `run-observed` gains `--secrets-key-file`.
- **eval4j `EvalScenario`** gains six components (`correctConclusion`, `acceptedEquivalents`, `rejected`, `acceptableActions`, `unacceptableActions`, `requiredCitations`); the sixteen-component constructor and every older one are kept, and datasets without them read as before.
- **`weave judge validate`**, a new command; `eval/judge-validation.json`, a new file.
- **`WeaveCLI.run(String...)`**, a new public method: runs `weave` in the process and returns the exit code (`main` now calls it).
- **`weave security generate`**, `--route-danger`, `--emit-test`, `weave gate --protocol`, `weave judge validate --system-model`, and the `expect.told` key of test cases: new.
