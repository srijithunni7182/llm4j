# API Compatibility Policy

This document defines the compatibility guarantees for `ai-agent4j` and companion libraries, and records every change since 6.1.0 so you can see what a release can break.

## Contents

- [The policy](#the-policy): [versioning](#versioning) · [public API](#public-api-surface) · [deprecation](#deprecation-policy) · [breaking changes](#breaking-change-requirements)
- [Behaviour changes at a glance](#behaviour-changes-at-a-glance): what might break you
- [How to read the ledger](#how-to-read-the-ledger)
- [The ledger](#the-ledger): [language and runtime](#loom-language-and-runtime) · [agents, models and providers](#agents-models-and-providers) · [tools](#tools-phase-35) · [A2A](#a2a) · [supervision and children](#supervision-and-child-workflows-phases-33-and-34) · [evaluation and review](#evaluation-and-review) · [CLI](#cli-and-files)

---

## The policy

### Versioning

| Release | Promise |
|---|---|
| Major | May contain breaking API changes |
| Minor | Expected to remain source-compatible for public APIs |
| Patch | Bug and security fixes, with no intended API breaks |

Public artifacts follow semantic intent using the canonical coordinates in `VERSION_MATRIX.md`.

### Public API surface

These packages are public and stable targets for consumers:

| Package | What it is |
|---|---|
| `io.github.llm4j` | Client entry points |
| `io.github.llm4j.model` | |
| `io.github.llm4j.config` | |
| `io.github.llm4j.agent` and `io.github.llm4j.agent.tools` | |
| `io.github.llm4j.provider` | |
| `io.github.llm4j.mcp` | |

Internal implementation details may change without notice, especially:

- `io.github.llm4j.http`
- `io.github.llm4j.util`
- app-specific packages outside the core libraries

### Deprecation policy

- New replacements are introduced before old APIs are removed.
- A deprecated API stays available for at least one minor release cycle when feasible.
- Every deprecation includes migration notes in the release documentation.

### Breaking change requirements

Before introducing a breaking change:

1. Document the impact in `MIGRATION_GUIDE_5_0.md` (or the next migration guide).
2. Add compatibility notes in the PR description.
3. Update examples and wiki pages in the same change set.

### Records that grow

Several public records grow by adding components **at the end**. Each time, the previous constructors stay, and a new accessor has a safe default, so code that builds a record or reads its accessors is unaffected. The one case that must change is code that destructures a record by its full component list (pattern matching).

For example, eval4j's `io.github.llm4j.eval.dataset.EvalScenario` keeps its 7-, 10-, 12-, 13- and 14-argument forms. `namedInputs()` and `mustNotContain()` are never null; `expectedMinWords()` and `expectedMaxWords()` are null when no limit is set.

---

## Behaviour changes at a glance

Most changes below are **additive**: a script or program that does not use the new thing behaves as before. These are the ones that are **not**. Each links to its entry.

### Incompatible

| Release | Change | What you do | Entry |
|---|---|---|---|
| 6.2.0 | A backslash in a string now escapes: `"C:\\dir"` and `"\\d+"` hold one backslash where they held two, and `"C:\"` is an unterminated string | Write `\\` for each backslash you want, and `\\\\` where you meant two | [Strings](#judge-validation-strings-and-cli-options) |
| 6.2.0 | `run(question, null)` on `ReActAgent` no longer compiles (ambiguous) | Write `run(question)` or `run(question, (RunHandle) null)` | [Media with agents](#media-with-agents-masking-budgets-and-compaction-phase-31d) |
| 6.2.0 | An agent or extractor with `pii: mask\|block` that is sent media now fails | Add `media: unscanned` if that is intended | [Media in Loom](#media-in-loom-phase-31e) |
| 6.2.0 | A `MaskingLLMClient` refuses a request that carries media | Call `allowUnscannedMedia()` | [Media with agents](#media-with-agents-masking-budgets-and-compaction-phase-31d) |
| 6.2.0 | A request with media to a model nobody recorded is refused | Record the model's media support | [Media in a message](#media-in-a-message-phases-31b-and-31c) |

### Behaviour that changes

| Release | Change | Entry |
|---|---|---|
| 6.2.0 | An agent's answer is checked against its `output_schema` / `expecting`; one retry with a correction, then `on_failure` | [Run controls](#output-checked-against-a-schema) |
| 6.2.0 | `weave run` exits 1 when no model step succeeded and every step fell back (it exited 0) | [Build identity and degraded runs](#build-identity-degraded-runs-and-output-limit) |
| 6.2.0 | `BudgetSet.DEFAULT_OUTPUT` is 8192 (was 1024) | [Build identity and degraded runs](#build-identity-degraded-runs-and-output-limit) |
| 6.2.0 | Loading a script keeps its own `shared_prompt`, `supervise` and `intervention` declarations, which 6.1.0 and earlier dropped | [Build identity and degraded runs](#build-identity-degraded-runs-and-output-limit) |
| 6.2.0 | A script that declares a policy refuses model-written text at its exits, and fails `weave check` for a path with no check | [Policies and evaluators](#policies-and-evaluators-phases-26a-to-26d) |
| 6.2.0 | A looping agent sends cache markers to Anthropic (changes the bill, not the answers) | [Prompt caching](#prompt-caching-phase-31a) |
| 6.2.0 | `Vendor.ofModel("sarvam/…")` resolves; `sarvam/<model>` builds an `OpenAiCompatibleProvider` on `/v2/chat/completions`; its provider name is `sarvam` | [Sarvam V2](#sarvam-v2) |
| 6.2.0 | `weave audit --fail-on` can now fail a script that passed before (LA21, LA24 to LA27, LR02, LA30) | [A2A in Loom](#a2a-in-loom-phases-32c-to-32e), [Extractors](#extractors-phase-24), [Signals](#signals-phase-34c) |
| 6.2.0 | `weave check` no longer counts as risky a tool the audit says changes nothing; a host tool named `duckduckgo`, `serpapi` or `google_search` is no longer assumed a web search | [Tool kinds say what they reach](#tool-kinds-say-what-they-reach-phase-35a) |
| 6.2.0 | A `use` step binds some tool results as unchecked model text (stricter) | [New tool kinds](#new-tool-kinds-and-the-origin-of-a-tools-result-phase-35b) |
| 6.2.0 | `ToolFactory.register` throws for a name already registered (it replaced the kind) | [Kinds from jars](#kinds-from-jars-phase-35a-d3) |
| 6.2.0 | Loom closes every `AutoCloseable` tool it built at the end of a top-level run, and builds it again for the next | [The extras modules](#the-extras-modules-phase-35e) |
| 6.2.0 | `http`'s `base_url` is not required when `reaches:` names a sandbox | [`sandbox`](#sandbox-and-kinds-that-name-each-other-phase-35d) |
| 6.2.0 | The default judge of `weave eval` is a different model than the one judged | [Judge choice](#judges-datasets-and-the-evaluation-lens-phases-28a-to-28e) |
| 6.2.0 | Only `{name}` is replaced in a text | [CLI and files](#cli-and-files) |
| 6.2.0 | A delegate whose target comes from data and is unbounded warns, or is an error | [Delegate to a list](#delegate-to-a-list-phase-27e) |
| 6.2.0 | `wait h -> r` on an unfinished child binds a value that raises on read, and more | [Closing the phase 33 gaps](#closing-the-phase-33-gaps-phase-34a) |
| 6.2.0 | The graph of a `parallel` block has the branches as nodes of their own | [Graph of a `parallel` block](#the-graph-of-a-parallel-block) |
| 6.2.0 | The default log level of `weave run`, `resume`, `tick` and `daemon` is WARNING | [Judge validation, strings and CLI options](#judge-validation-strings-and-cli-options) |
| 6.2.0 | `use: openapi` is the kind of a new module on the shared HTTP layer; the core OpenAPI classes are deprecated for removal | [Integrations (phase 36)](#integrations-phase-36) |
| 6.2.0 | An `mcp` block gains `args`, `env`, `isolation` and more; its tools get approval; a server's annotations are not an effect | [Integrations (phase 36)](#integrations-phase-36) |
| 6.2.0 | `credential_rejected`: a new `ErrorCategory`, `Outcome.Status` and failure category | [Integrations (phase 36)](#integrations-phase-36) |
| 6.2.0 | `weave review` excludes setup findings from `--fail-on` by default | [Review setup findings](#review-setup-findings-and-cost-fields) |
| 6.2.0 | A2A: `parseRequest` throws `UnsupportedOperation` and `PushNotificationNotSupported` where it threw `MethodNotFound` | [A2A server half](#a2a-server-half-phase-32-follow-up) |

---

## How to read the ledger

Each entry says what changed, in the same few parts:

| Part | Meaning |
|---|---|
| **New** | What was added. Nothing existing changes unless another part says so |
| **Behaviour change** | Something that works differently for code that did not opt in |
| **Incompatible** | Code that compiled or ran before may not now, and what to do |
| **Deprecated** | Still works; on its way out |
| **Unchanged** | What you can rely on staying the same |

The **contextual words** in Loom (such as `halt`, `use`, `attach`) are verbs only where a statement starts and its shape follows, so a variable, agent or parameter with that name keeps working. An entry that says "contextual" means this.

---

## The ledger

Entries are grouped by area, newest areas first within each group where a date applies.

## Loom language and runtime

### Loom statements `halt`, `set`, `return`

**New**

- The statements `halt`, `set` and `return`, and the condition `x is ok` / `x is not ok`. A script that does not use them is unchanged (the words are contextual, so a variable called `set` or `ok` keeps working).
- `io.github.llm4j.agent.task.Task` gains the default method `okOutcomes()` (`{ok}`), so every existing task keeps working.
- `HarnessExecutor.executeWorkflow` can throw `io.github.llm4j.loom.runtime.RunHalted` (a `RuntimeException`) when a script halts, next to `BudgetExceeded`, `RunSuspended` and `RunStopped`. `weave run` exits with code 6.
- The trace gains the events `halt` and `schema_violation` and the run's final status `halted`.
- The graph JSON gains the node kinds `halt`, `set` and `return`.
- loom-inspect shows a halted run as stopped.

### Output checked against a schema

**Behaviour change, deliberately.** An agent's answer is now checked against its `output_schema` / `expecting`.

- Before, an answer that was not JSON (or did not match) reached the script as raw text.
- Now the step is retried once with a correction, and then fails into `on_failure`.
- A script that relied on the raw text must handle the failure (or drop the schema).
- Decision proposals (`decide`) keep reading answers leniently.

### Build identity, degraded runs and output limit

**New: build identity (a public contract in 6.2.0).**

- Every module jar's manifest carries `Implementation-Version`, `Build-Kind` (`release` or `development`), `Build-Commit`, `Build-Dirty` and `Build-Time`.
- `io.github.llm4j.BuildIdentity` reads them.
- `weave --version` prints `weave <version> (<kind>, <commit>, built <time>), serve protocol <n>` (the `serve protocol` suffix is kept).
- Main carries `-SNAPSHOT` versions between releases; `llm4j.released` names the last release.

**Also additive:** `RunHook` (checks on the question, each model call and the final answer) and `AgentResult.isInputRejected()`; an agent with no run hook behaves as before. Loom's top-level `shared_prompt` is additive too; a script without it is unchanged.

**Behaviour change: degraded runs.**

- `weave run` exits 1 when no model step succeeded and every step fell back (it exited 0).
- Exit code 7 exists only with `--exit-degraded`.
- `HarnessExecutor` gains `output()`, `fallbacksTaken()`, `modelSteps()` and `successfulModelCalls()`.
- `Runs.Result` (internal) gains two components.

**Behaviour change: output limit.**

- `BudgetSet.DEFAULT_OUTPUT` is now 8192 (was 1024).
- `BudgetedLLMClient` sends a model's known maximum output when a budget limits output and no limit was chosen.
- `BudgetSet.reserve` has an overload taking the model maximum; the old forms behave as before with the new default.

**Behaviour change: loading a script.** Loading a script (`LoomLoader`, so every `weave` command) now keeps the script's own `shared_prompt`, `supervise` and `intervention` declarations, which 6.1.0 and earlier dropped. A script that declares them is now supervised and has its shared prompt applied. `LoomScript.merge` also carries `supervise` blocks and the `intervention` defaults from imports.

### Embedding Loom: `io.github.llm4j.loom.host`

New and additive. It runs workflows from an application and resumes them from a store.

| Type | What it is |
|---|---|
| `LoomHost` (with its `Builder`) | Runs and resumes workflows |
| `RunRequest`, `RunOutcome` (`Completed`, `Suspended`, `Stopped`), `RunRecord`, `AnswerResult` | Request and result types |
| `QuestionListener`, `LoomRunFailedException` | Questions to a person; failure |
| `Persistence`, `DurabilityGuard` | Where runs are kept |

- `Persistence.file(dir)` uses the folder layout `weave` uses.
- `Persistence.jdbc(dataSource)` uses the tables `LoomHost.createSchema` creates, including `llm4j_loom_runs` and `llm4j_loom_answers`.

Beside it:

- `ScriptSource` and `LoomLoader.load(location, source)` read a script and its imports from anywhere (a jar, say).
- `LootLoader.loadIntoRegistry(Reader, ToolRegistry)` and `LootLoader.reaches(Reader)` do the same for a loot file.
- `LoomRun.start(executor, label, workflow, inputs, origin, invoker, runner, onDone)` runs on a thread the application provides, for a named person, and reports when it ends.

**Unchanged:** `LoomLoader.load(String)`, `LootLoader.loadIntoRegistry(String, ToolRegistry)`, `LootLoader.reaches(Path)` and the other `LoomRun.start` forms.

### Policies and evaluators (phases 26a to 26d)

All additive, except the one behaviour change below.

**Phases 26a and 26b**

- New in `io.github.llm4j.loom.ast`: `PolicyDef`, `EvaluatorDef` and `CheckStmt` (with the sealed `CheckStmt.Action`: `Rewrite`, `AskPerson`, `Replace`, `Fail`).
- `LoomScript.getPolicies()`, `addPolicy`, `findPolicy`, `getEvaluators()`, `addEvaluator`, `findEvaluator` (`merge` carries them).
- New `io.github.llm4j.loom.policy.PolicyFile` (a public parser of a policy file) and `PolicyCatalog`.
- The words `policy`, `evaluator`, `check … against … using`, `on_unavailable`, `rewrite`, `replace`, `ask person` and `checked by` are contextual.
- New trace event `policy_check`, the graph node kind `check` (the graph schema gains it), and audit events `policy_violation`, `policy_override` and `policy_check`.
- `HumanInterface` is unchanged: `ask person` uses `promptHuman(stepId, message, Hints)` with kind `DECIDE`.

**Phases 26c and 26d**

- `VariableContext.originOf`, `setOrigin` and `clearOrigin`: default methods that record where a value came from. A custom context that ignores them makes every value count as unchecked, which only matters in a script that declares a policy.
- `ScopedContext.putLocalOrigin`.
- `HarnessExecutor.setSinkOracle` and `setStaticSinkChecks` (test seams), and `ScriptValidator.Context.staticSinkChecks`.
- `AgentDef.GuardConfig.getPromptLeak`, `getPromptLeakWords` and `getInternalRefs`, and the guard words `prompt_leak`, `prompt_leak_words` and `internal_refs`.
- `StaticSinkChecks.modelTextExits`, the review rule LY07 and its script facts.
- The trace events `sink_blocked` and `tool_refused_by_policy`.
- In eval4j: `WorkflowTrace.checks`, `ruleViolated` and `checkActions`, with `WorkflowTraceAssert.checksPolicy`, `violated` and `actionTaken`.

**Behaviour change.** A script that declares a policy now refuses model-written text at its exits and fails `weave check` for a path with no check. Scripts that declare no policy are unchanged.

### `use` and `approve` on a step (phase 27b)

All additive. Nothing existing changes. `use partial` in a stop handler and `use:` in a tool block are unchanged.

- New `io.github.llm4j.loom.ast.UseStmt`.
- `RunStmt.isApprove()`, `getApproveHeading()` and `getOnDeclined()`; `StatementWalker` visits `on_declined` blocks.
- The graph node kind `use` (the graph schema gains it).
- The contextual words `use` (at statement level before `Name(`), `approve` (after the arguments) and `on_declined`.
- Trace field `by: use` on `tool_call`; `ApprovalGate.approveStep`.
- eval4j `WorkflowTrace.usesOf` and `WorkflowTraceAssert.usesTool`.

### Delegate to a list (phase 27e)

**New:** `delegate … to {x} in [..]`, `-> results[key]`, and the `route_refused` trace event.

**Behaviour that is stricter.**

- A delegate whose target comes from data and is not bounded by an allow-list or an `enum` warns, or is an error when any agent holds a tool that changes things or reaches private data.
- A bare dynamic result name must be plain, new, and not a name the script uses elsewhere.
- `DelegateStmt` gains `getAllowedTargets()` (old constructors kept).
- The old unbounded form keeps working for scripts whose agents hold no changing tool; the map form is the one that remains at the next major version.

### Extractors (phase 24)

All additive. Nothing existing changes. The default transcript behaviour is unchanged (`all`).

**Extractors**

- New in `io.github.llm4j.loom.ast`: `ExtractorDef` and `ExtractStmt`, with `LoomScript.getExtractors()`, `addExtractor` and `findExtractor` (`merge` carries extractors).
- The words `extractor`, `extract`, `optional` and `missing` are contextual.
- New trace events `extract_start`, `extract_end` and `extract_replayed`, and the graph node kind `extract` (the graph schema gains it).
- `io.github.llm4j.loom.execution.ExtractorPrompt` is public so mock models can recognise an extraction.
- eval4j: `WorkflowTrace.extractRuns`, `extractorsInOrder`, `fieldsFound`, `fieldsMissing`, and `WorkflowTraceAssert.runsExtractTimes`, `extractedField`, `leftMissing`.

**Transcript and shared prompt**

- `TranscriptMode` (`ALL`, `OWN`, `NONE`), `AgentDef.getTranscriptMode()` / `setTranscriptMode()` and `isSharedPromptExcluded()` / `setSharedPromptExcluded()`.
- The agent words `memory { transcript: … }` and `shared_prompt: none`.
- The graph JSON gains `transcript` (always) and `sharedPromptExcluded` on each agent. `AgentInfo` grows two components at the end (its previous 18-component constructor is kept).
- A custom `MemoryEngine` that ignores `getTranscriptMode()` keeps today's behaviour.

**Rules**

- Review rules LP08 and LP09; audit rule LA21.
- LA21: an agent that receives the transcript after an untrusted-content reader has run counts as reading untrusted content. It can make LA01 fire for an agent that did not have it before, so a CI job with `weave audit --fail-on high` may start failing for a real trifecta it was missing.
- `io.github.llm4j.loom.review.fix.JavaFixOption` and `ToExtractorFix` (a Java-coded fix option, offered as `to-extractor`).

**Bounded fan-out**

- `ForEachStmt.getMax()`, `setMax`, `getOnTooMany()` and the new `io.github.llm4j.loom.ast.ListProducers`.
- The contextual words `max` and `on_too_many` on a `for each`.
- The trace event `fanout_exceeded` and the Fabric incident kind `FANOUT`; review rule LR13.

**Rule-pack changes.** Review rule LP10 (and the reworded advice of LR05 and LP02) and the settings `prompts.large-schema-fields` and `prompts.short-prompt-tokens` are additive.

### Intent classification

`io.github.llm4j.classify` (new in 6.1.0) and `io.github.llm4j.provider.ClassificationProvider` are additive. Nothing existing changes; an agent, judge or workflow that does not use a classifier behaves exactly as before.

- **Types:** `Classifier`, `ClassifyRequest`, `Question`, `Option`, `Scale`, `ClassifyResult`, the answer records, `ClassifyUsage`, `ClassifyEvent`, `ClassifyListener`, `ClassificationException`, `LlmClassifier`, `ClassifierResolver`, and `io.github.llm4j.provider.typesafe.JevProvider`.
- **In Loom:** the words `intentclassifier` and `intentclassify` (contextual), the trace event type `classify`, the graph node kind `classify` (the graph and trace schemas gain it; a consumer that ignores unknown kinds is unaffected), and audit rule LA19.
- The answer records grow by adding components at the end, keeping the previous constructor.

### The graph of a `parallel` block

**A change of shape (6.2.0).**

- The graph JSON gains two optional node attributes: `lanes` on a `parallel` node (a list of `{label, nodes}`, one per branch) and `value` on a `set` node (the value as written, masked and cut at 200 characters).
- The branches of a `parallel` block are now nodes of their own (with their lines and their `on_failure` handlers), laid out after the main path so that **no existing step is renumbered**.
- The block leads to each branch, and every branch leads on to the step after the block. Before, the block led straight to the next step.
- A consumer that reads only `agents` and `branches` of the block, or that follows edges from it to the next step, now sees the branch nodes in between.
- `graph-result.schema.json` allows both attributes. `weave graph --format mermaid` draws each lane as a `subgraph`.
- An older engine sends no `lanes`, and the editor and the shared renderer still draw its graph as before.

### Judge validation, strings and CLI options

**Judge validation and probes (6.2.0).**

- `JudgeValidation.Result` gains `selfPreferenceChecked` and `positionBiased` (the old constructors are kept; a result that did not check self-preference is not usable for gating).
- `Metamorphic.Relation` gains `PARAPHRASE`, `MONOTONICITY` and `NEGATION`.
- `weave metamorphic`, `weave judge validate` and the new `weave probe eviction` gain options as listed in the command reference.
- `Splits.of` reads a held-out tag first.

**Strings (6.2.0). Incompatible for scripts that wrote a doubled backslash.**

- The lexer reads `\"` and `\\` inside a string as a quote and a backslash (any other backslash is kept as written), so a string can no longer end with a single backslash before its closing quote.
- `"C:\\dir"` and `"\\d+"` now hold one backslash where they held two, and `"C:\"` (which used to lex) is an unterminated string.
- **What to do:** write `\\` for each backslash you want, and `\\\\` where you meant two.
- Parse errors may end with ` Hint: …` (the message text before it is unchanged).

**CLI options (6.2.0).**

- `weave test` gains `--classes`, `--no-auto-classes`, `--loot`, `--env-file` and `--no-env-file`.
- Every `weave` command gains `-v` / `-vv`.
- **Behaviour change:** the default log level of `weave run`, `resume`, `tick` and `daemon` is now WARNING.
- `ProjectClasses.use` gains an overload with an `auto` flag; the old signature is unchanged.

---

## Agents, models and providers

### Run controls, streaming and interventions

The agent library's run controls are additive.

- `ReActAgent.run(question)` behaves as before. `run(question, RunHandle)` adds steering, pausing, wrapping up and cancelling.
- `AgentResult` gains `getStop()` (a `StopReport`), `isWrappedUp()` and `isCancelled()`.
- Streaming, reasoning and tool hooks are builder options that are off by default.
- Loom adds the agent settings `streaming`, `reasoning` and `steerable`, the `on_stopped` handler and the `intervention` block. A script that does not use them behaves exactly as before.
- The graph JSON gains an `outcome` node kind, a `stopped` edge label and the optional agent flags `streaming`, `reasoning` and `steerable` (older readers ignore what they do not know).
- The trace gains the events `intervention` and `step_stopped`, and the run journal the entry kinds `steer`, `stop`, `skipped` and `discarded`.
- **Journal versioning:** the journal has no format version, so an older `weave` is not stopped from opening a journal with these entries (the design plans a stamp that would make it refuse; it is not built). Finish a run that has them with the version that wrote them.

### Media in a message (phases 31b and 31c)

Additive, except where marked.

**New in `io.github.llm4j.model` (31b)**

| Type | What it is |
|---|---|
| `MediaKind` | The enum `IMAGE`, `AUDIO`, `VIDEO`, `DOCUMENT` |
| `Media` | Made with `Media.of(kind, bytes, name, policy)` or `Media.image/audio/video/document(path, policy)`. Its type is decided from its bytes; it prints `media:image/png:182340B:ab12cd34`, serialises as a `MediaDescriptor` and is not `Serializable` |
| `MediaDescriptor` | A record |
| `MediaPolicy` | A record: per-item, per-message and per-request limits and a closed allow-list per kind |
| `MediaCapabilities` | What a model takes |

- `Message` gains `getMedia()`, `Message.user(text, media)` and `Builder.media(...)`. Only a user message can carry media. `toBuilder`, `withContent`, `equals` and `hashCode` keep it; the old constructors mean "no media"; JSON writes descriptors, and reading a message back leaves the media empty.
- `LLMRequest` gains `getMediaPolicy()`, `mediaKinds()` and `Builder.mediaPolicy(...)`, and now refuses media over the policy.
- `LLMClient.mediaCapabilities(String)` and `LLMProvider.mediaCapabilities(String)` are default methods returning `NONE`.
- `DefaultLLMClient` refuses a request whose media the model cannot take before any call. The budgeted, masking and routing clients pass the answer on (the router takes the intersection).

**Phase 31c makes it work**

- `Dialect.mediaCapabilities(String)` and `ResponsesDialect.mediaCapabilities(String)`, and the builder methods `Dialect.Builder.mediaModels(MediaKind, Pattern)` and `ResponsesDialect.Builder.mediaModels(...)`, record what a model takes by name.
- `mediaCapabilities(String)` is overridden by the OpenAI-family, Anthropic, Google and Ollama providers.
- `Providers.requireMedia(LLMProvider, LLMRequest)` is the refusal every provider calls first, so media sent straight to a provider that cannot take it is refused and never dropped.

**Incompatible.** A model nobody recorded takes nothing, so **a request that carries media to an unrecorded model is refused**. Text is unchanged.

Wire shapes and the table of what each provider takes: [wiki/Media-Input.md](../src/ai-agent4j/wiki/Media-Input.md).

### Media with agents, masking, budgets and compaction (phase 31d)

Additive, except where marked.

- `ReActAgent.run(String, List<Media>)` and `run(String, List<Media>, RunHandle)` send media with the first user message of a run (native loop) or with the single user message of each call (text loop). `ReActAgent.Builder.mediaPolicy(MediaPolicy)`.
- **Incompatible:** the existing `run(String)` and `run(String, RunHandle)` are unchanged, but **a call that passes a bare `null` as the second argument, `run(question, null)`, is now ambiguous and does not compile**. Write `run(question)` or `run(question, (RunHandle) null)`.
- **Incompatible:** `MaskingLLMClient.allowUnscannedMedia()`. **A `MaskingLLMClient` now refuses a request that carries media** (it cannot mask a picture or a recording) until this is called. Text is unchanged.
- `io.github.llm4j.budget.MediaTokens.estimate(Media)`. `CharsPerTokenEstimator` now adds it to the prompt estimate, so a budget or a context window that was blind to media now counts it. Text-only estimates are unchanged.
- `Messages.DROPPED_MEDIA`, `Messages.droppedMedia(Message)` and `Messages.describeMedia(...)`. The `drop_oldest` marker and the `summarize` summary say which media they removed (text-only output is unchanged).

### Media in Loom (phase 31e)

Additive. A script that does not use the new words is unchanged.

**New syntax**

- A trailing `attach image|audio|video|document "<file>", …` on `delegate`, `broadcast`, `handoff` and `human_prompt`.
- Media inputs in `extract … from`.
- Top-level `media_root: "<folder>"` and `media { max_bytes: … max_items: … }`.
- `media: unscanned` in an agent's `guard { }` and in an `extractor`.
- The words are contextual, so an existing agent, variable or parameter named `attach` or `media` still parses.

**New types and members**

- In `io.github.llm4j.loom.ast`: the records `AttachItem` and `ExtractMedia`; `getAttachments()` on `DelegateStmt`, `BroadcastStmt`, `HandoffStmt` and `HumanPromptStmt`; `ExtractStmt.getMediaInputs()`.
- `LoomScript.getMediaRoot()`, `getMediaMaxBytes()` and `getMediaMaxItems()`; `GuardConfig.isUnscannedMedia()`; `ExtractorDef.isUnscannedMedia()`.
- `AgentDef` carries the `cache` setting. Added in 31a: `cache: true | false` on an agent.
- New audit rule LA23. The journal gains `#media` sibling keys that older readers ignore.

**Incompatible only for:** an agent or extractor with `pii: mask|block` that is sent media now fails unless it says `media: unscanned`.

Guide: [LOOM_GUIDE.md](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md).

### Prompt caching (phase 31a)

**New in `io.github.llm4j.model`**

- The class `PromptCache` (with its builder; at most four breakpoints), and `LLMRequest.getPromptCache()` / `LLMRequest.Builder.promptCache(...)` (kept by `toBuilder`, in `equals`).
- New constants `Providers.CACHED_TOKENS` and `Providers.CACHE_WRITE_TOKENS` (the metadata keys `usage.cached_tokens`, which OpenAI already reported, and `usage.cache_write_tokens`). Anthropic and Google now report them.
- `PriceTable.Price` gains two components, `cacheReadPerMillion` and `cacheWritePerMillion` (null means "priced as input"). Its two- and three-argument constructors are kept, and `PriceTable.load` accepts two more optional fields.
- `BudgetSet.Pricing` gains the default method `cost(prompt, completion, cachedRead, cacheWrite)`.
- `ReActAgent.Builder.promptCaching(boolean)`, on by default.
- Loom: the agent setting `cache: true | false`, `AgentDef.isPromptCaching()`.

**Behaviour change.** A looping agent now sends cache markers to Anthropic. This changes its bill, not its answers.

### Native JSON output (phase 30a)

All additive. Existing clients and providers answer `NONE`, so nothing changes for them.

**New in `io.github.llm4j.model`**

- The record `ResponseFormat` (name, JSON Schema, strict) and the enum `StructuredOutput` (`NONE`, `JSON_SCHEMA`).
- `LLMRequest.getResponseFormat()` and `LLMRequest.Builder.responseFormat(...)` (kept by `toBuilder`, in `equals`).
- `LLMClient.structuredOutput(String model)` and `LLMProvider.structuredOutput(String model)`, both default methods returning `NONE`; a null model means the one the client is configured with.

**Providers and dialects**

- `Dialect.structuredOutput(String)` and `Dialect.Builder.structuredOutputModels(Pattern)`.
- `ResponsesDialect.structuredOutput(String)`, `structuredStyle()`, the enum `ResponsesDialect.StructuredStyle` and `Builder.structuredOutput(Pattern, StructuredStyle)`.
- `AnthropicModels.supportsStructuredOutput(String)`. The Anthropic, Google, Ollama and Responses providers override `structuredOutput`.
- `DefaultLLMClient`, `MaskingLLMClient`, `BudgetedLLMClient` and `RoutingLLMClient` pass the answer on (routing says yes only when every client does).

**In Loom:** the trace event `structured_fallback`, and the keys `structured` and `structured_reason` in the data of `extract_end`.

### Providers and grounding (6.1.0)

All additive. A request without `grounding` produces the same provider requests as before (pinned by `RequestBytesRegressionTest` for Gemini, Claude, Sarvam and Ollama). A script that does not use the new words behaves as before.

**New packages and types**

- `io.github.llm4j.provider.openai`: `OpenAiCompatibleProvider`, `OpenAiResponsesProvider`, `Dialect`, `ResponsesDialect`, `ResponsesGrounding`, `ModelTraits`, `Vendor`.
- `io.github.llm4j.provider.perplexity.PerplexityDecisionsProvider`, `io.github.llm4j.provider.DecisionWire`, `io.github.llm4j.provider.GroundingCapabilities`.
- `io.github.llm4j.grounding`: `Grounding`, `Citation`, `Span`, `SearchUsage`, `GroundingSupport`, `GroundingOption`, `Recency`.

**Existing types only gain members (every previous constructor and shape kept)**

- `LLMRequest.grounding`, `LLMResponse.getCitations()` / `getSearches()` (and `toBuilder()`).
- The `LLMProvider` and `LLMClient` defaults `groundingSupport()` (`NONE`) and `groundingOptions()` (empty).
- `Limits`, `Spent`, `Remaining`, `Charge` and `PriceTable.Price` (a `searches` component and an optional third price number per thousand searches).
- `Dimension.SEARCHES`: a `switch` over `Dimension` that has no default must add the case.
- `ModelCallEvent` / `ModelStats` / `RunStats.totalSearches()`, `ReActAgent.Builder.grounding`, `AgentResult.getCitations()`.
- `GoogleProvider.GROUNDING_OPTIONS` and `AnthropicProvider.GROUNDING_OPTIONS`.
- In eval4j: `hasCitations`, `citesAtLeast`, `citesDomain`, `citesOnlyFrom`, `usedSearches` and `ScriptedClient.sources/searches`.

**In Loom**

- The agent setting `grounding: web { ... }`, the budget field `searches`, the variable `<name>_citations`.
- The provider kinds `openai`, `xai`, `kimi`, `qwen`, `perplexity`, `perplexity_router`, `openai_compatible`, and the options `api`, `header`, `query`.
- Audit rule LA20, the `model_call` / `run_end` trace fields `searches` and `citations`, and the graph agent field `grounding` (the graph schema gains it).

### Model listing, Decisions wiring and paused turns (6.1.0)

All additive. No existing property, type or signature changes.

- `OpenAiCompatibleProvider`, `OpenAiResponsesProvider` and `AnthropicProvider` now implement the existing `DescribableProvider` (`listModels()`, `getFirstAvailableModel()`), with the new `io.github.llm4j.provider.ModelListing` helper and `Dialect.modelsPath()` / `ResponsesDialect.modelsPath()` (plus their builder methods). The default is `/models`; it is null for Perplexity's Orchestrator and Agent API, whose `listModels()` throws `InvalidRequestException`.
- `AnthropicProvider.chat` continues a `pause_turn` reply; a reply that never pauses is sent and read exactly as before.
- Loom gains the provider kind `perplexity_decisions`.
- The Spring starter gains `llm4j.classifiers.<name>` (the `Classifiers` bean), `llm4j.budget.searches` and `llm4j.pricing.models.<m>.search-per-thousand`.

### Sarvam V2

**Additive:** `Dialect.SARVAM`, `Vendor.SARVAM` (kind `sarvam`, prefix `sarvam/`), `OpenAiCompatibleProvider.sarvam(LLMConfig)` and `ModelTraits.sarvam(String)`.

**Behaviour change**

- `Vendor.ofModel("sarvam/…")` now resolves (it returned empty), and `Vendor.kinds()` includes `sarvam`.
- A Loom `sarvam/<model>` or `use: sarvam` and a Spring `provider: sarvam` build an `OpenAiCompatibleProvider` on `/v2/chat/completions` instead of a `SarvamChatProvider` on `/v1/chat/completions`.
- Its provider name is `sarvam` (was `sarvam-chat`) and it calls tools natively.

**Deprecated:** `SarvamChatProvider` (since 6.2.0, kept for at least one minor release per the policy above). Its default model is now `sarvam-105b`, because Sarvam no longer serves `sarvam-m`.

---

## Tools (phase 35)

Newest first. The tools module moved, tool kinds say what they reach, and new kinds and modules were added.

### Integrations (phase 36)

**New**

- **`ai-agent4j-tools-openapi`**, a module whose `OpenApiKind` is the `openapi` tool kind: one declared tool per operation (`operation:`), `base_url`, `server`, `pin`, `max_bytes`, `save_dir`, `save_max_bytes` and the shared network options. `weave` bundles it (Loom depends on it), so `use: openapi` works out of the box.
- **An `mcp` block** takes `args` (a list; each entry one argument), `env`, `env_inherit`, `isolation` (`none` or `process`), `timeout`, `reach` and `pin`. **`weave mcp list`** (with `--server`, `--write`, `--yes`) starts each server under its declared isolation and lists its tools. `weave check` warns when an `openapi` tool's `pin:` no longer matches the local description.
- **Audit rules** LA31 (extended to an `mcp` server with no isolation or at `none` or `process`), LA34 (an `openapi` tool with no `operation:`) and LA35 (a pin that no longer matches; raised by `weave check`).
- **`credential_rejected`:** `ErrorCategory.CREDENTIAL_REJECTED` and `Outcome.Status.CREDENTIAL_REJECTED` are added at the **end** of their enums, so no existing value moves, and `credential_rejected` joins the categories a workflow test can inject with `fail:`. `Outcome.credentialRejected(String)`, `CredentialRejected` (a `ToolRefusal`) and a tool's outcome `credential_rejected` in the audit trail.
- **Additive members:** `ToolKind.lateBound()` and `ToolKind.create(name, options, lateBound, baseDir, context)` (default methods: a kind that does not list an option as late-bound is unaffected), `McpTransport.onClose` (a default method), `StdioMcpTransport.scrubbed`, `McpClient.restarting`, the exceptions `McpException`, `McpToolException`, `McpTimeoutException` and `McpServerExitedException`, `McpPin`, `NetOptions` (now public), and an `HttpSupport` constructor that takes a trust manager and socket factory (certificate and host-name checks stay on).

**Behaviour change**

- **The address a description names is not trusted.** The old tool called whatever `servers` entry the description gave, over any scheme and to any address. The new kind sends every request through `NetPolicy`: unless the declaration names `base_url:`, a loopback, private or link-local address is refused, and plain http needs `allow_http: true`. *Migration:* for an internal API write `base_url:` (the host you name is your own choice), or `allow_private: true` (and `allow_http: true` when it is not https).
- **Requests are guarded and bounded.** A timeout (default 15 s), retries of a read on a 429, a 5xx or a dropped connection (default 2, honouring `Retry-After`; other methods only with `idempotency: true` or `on_unknown: retry`), and a size limit that says it cut. *Migration:* `retries: 0` restores a single attempt.
- **A longer answer is no longer cut at 2,000 characters.** It is shown up to `max_bytes` (default 64 KiB) and says when it was cut; `save_dir:` keeps a long or non-text answer whole. A binary answer with no `save_dir` is refused with a hint, where the old tool returned its bytes as text. An answer that names no content type is returned when its bytes are text.
- **PATCH works** (the old tool listed it and then refused it). Path values are percent-encoded and a value that would climb out of the address (`..`) is refused; the old tool substituted them raw. A header parameter that looks like a credential is not offered to a model.
- **`auth_value` must be at least four characters**, as for every other kind.
- **The description is read by llm4j, not the parser.** A remote description and each external reference are fetched through the guarded layer and pasted in; a reference outside the description's folder, or nested more than twelve deep, is refused.
- **A tool of an `mcp` server is wrapped like any other tool.** `approve: all` and `approve: [tool]` now reach it (they did not), and decisions see its evidence. A tool whose result says `isError` is a failure (it was returned as an answer). A server that cannot list its tools is a load error (it was logged and the agent ran without them); a request that is not answered in 30 seconds is a timeout, a server that exits is restarted twice and then reported, and a call in flight when it exits is not repeated.
- **A server's annotations are no longer used as an effect.** `McpEffects.resolve` used to take `readOnlyHint: true` as `reads` when the annotations had been read; nothing in production ever called it. An undeclared MCP tool changes; `weave mcp list` shows what the annotations suggest. *Migration:* `weave mcp list --write`, or an `effects:` entry per tool.
- **`approve:` may name a tool of an MCP server** that the agent does not list under `tools:`; a name that is neither is a load error when the servers start.
- **The error stream of an `mcp` server** with an `isolation:` line is captured, redacted and bounded instead of inherited; the environment is only what `env:` and `env_inherit:` give. A block with no `isolation:` line behaves as before, with a warning (`weave check`) and LA31.
- **The older all-operations form keeps working** (an `openapi` tool with no `operation:`): the arguments `endpoint`, `method`, `parameters` and `body`, the tool's description, and a per-operation `effects:` map that now reaches the journal. `weave check`'s description of it is shorter: the specification's own text is cut to a short line each. It ends at the next major version, and `weave audit` reports it (LA34).
- **`StdioMcpTransport` no longer logs message bodies** and a server's output that fails to parse is logged by length only.

**Incompatible**

- `Capabilities.operationEffect(ToolDef, String, Capabilities)` (public, Loom) is removed: nothing in production called it. The per-operation effects reach the run through the tool kind.
- A `switch` over `Outcome.Status` or `ErrorCategory` without a default needs a case for the new value.
- The Loom-side kind class `io.github.llm4j.loom.tools.OpenApiKind` is gone (it was package-private).

**Deprecated:** `OpenAPITool`, `OpenAPIParser`, `OpenAPISpec`, `OpenAPIEndpoint` and `OpenAPIParameter` in `io.github.llm4j.agent.tools.openapi` (since 6.2.0, `forRemoval = true`; removed with `swagger-parser` from the core at the next major version). *Migration for Java users:* add `ai-agent4j-tools-openapi` and build the tool with `new OpenApiKind().create(name, options, baseDir)` (see that module's README); the options are the script's (`spec`, `operation`, `base_url`, `auth_header` and `auth_value`, ...). The old classes keep working until then and are unguarded: they use a bare `HttpClient`, and the parser fetches remote references on its own.

**Unchanged:** every script that loads today still loads. `use: openapi spec: "x.json"` with no other option is the older all-operations tool; an `mcp` block with only `transport`, `cmd` and `effects` runs as before.

---

### The extras modules (phase 35e)

Additive, with one behaviour change in Loom.

- **New artifacts:** `ai-agent4j-tools-search` (`SearchKind`, the `SearchProvider` SPI, `BraveSearch`, `FakeSearch`, `SearchHit`), `ai-agent4j-tools-documents` (`DocumentsKind`, `Extracted`), `ai-agent4j-tools-git` (`GitKind`) and `ai-agent4j-tools-browser` (`BrowserKind`, the `BrowserBackend` contract, `CdpBrowser`, `FakeBrowser`, `RequestGate`).
- In the light module, `SandboxKind.servedPorts(...)` is new.
- **Behaviour change:** at the end of a top-level run Loom closes and drops every tool it built that is `AutoCloseable` under its wrappers. A host kind whose tool implements `AutoCloseable` is now closed after each run and built again for the next.
- The message for a script using `search`, `documents`, `git` or `browser` without its jar names the module.

### `sandbox` and kinds that name each other (phase 35d)

Additive, with one looser option.

- **In `ai-agent4j-tools`:** `SandboxKind`, the `SandboxBackend` SPI with `ProcessSandbox`, `ContainerSandbox` and `FakeSandbox`, the records `SandboxRequest` and `SandboxResult`, and `EgressProxy`; `NetPolicy.servedPort(...)`.
- **In the core**, default methods so existing kinds and contexts compile and behave as before: `EffectContext.lookup(String)` (null), `ToolKind.resolvedPerCall()` (none), `ToolKind.references()` (none) and `ToolKind.buildsAtRunStart()` (false).
- **In Loom:** the same three on Loom's `ToolKind`, and `ToolFactory.declarations(...)` and `buildsAtRunStart(String)`.
- **Behaviour change:** `http`'s `base_url` is no longer required when `reaches:` names a sandbox (it is still required otherwise, with the same message).

### `workspace` and a per-call effect policy (phase 35c)

Additive.

- `WorkspaceKind` in `ai-agent4j-tools` (built in to Loom).
- In the core, `Effectful.policy(Map)` is a default method returning `policy()`, and `EffectTool` asks it for each call. A tool can say that one action (a restore) is safe to repeat after an unknown outcome and another (an append) is not. An existing tool behaves as before.

### New tool kinds and the origin of a tool's result (phase 35b)

Additive, with one stricter behaviour.

- **In `ai-agent4j-tools`:** `ComputeKind`, `VerifyKind` and `ScanKind` (`compute`, `verify`, `scan`, built in to Loom).
- **In the core:** `ToolProfile.pure(String)`, and `ToolKind.uncheckedResult(Map)` (a default method returning none) with the constant `ToolKind.WHOLE_RESULT`.
- **In Loom:** the same default method on Loom's `ToolKind`, `ToolFactory.uncheckedResultOf`, and `Capabilities.written(ToolDef)` is public.
- **Stricter:** a `use` step binds the fields a kind names as unchecked model text, and the result of a kind that reaches nothing and names no fields with the origin of its arguments (it was always "not model-written"). This affects `calculator`, `datetime`, `current_time` and the language kinds given model text, under a declared policy.

### Tool kinds say what they reach (phase 35a)

Additive in the core, two behaviour changes in Loom.

**New**

- Core (`io.github.llm4j.agent.tool`): `ToolProfile` (a record: `untrusted`, `privateData`, `outward`, `effect`, `isolation`, `journaled`, `known`, `note`) and `Isolation` (`NONE`, `PROCESS`, `CONTAINER`, `VM`).
- A default method `ToolKind.profile(Map)` that returns `ToolProfile.unknown(...)`, so an existing kind compiles unchanged and is audited as before an audit knew it: as reaching everything. The six kinds of `ai-agent4j-tools` override it.
- Loom: the same default method on `io.github.llm4j.loom.tools.ToolKind`; `ToolFactory.standardKind(String)` and `standardKindNames()`; `io.github.llm4j.loom.tools.ReachDeclaration`; `Capabilities.profile(ToolDef)` is public.
- `PlanChecker.KINDS` is now the factory's built-in kinds (the same set).

**Behaviour that changes**

1. The rewind checks ask the audit what a tool can change. So `weave check` no longer counts as risky a tool the audit says changes nothing: the language kinds, a `read_only` knowledge graph, a skill registry, a class tool declaring `reach: none`, `reads` or `fetches`, and a tool declaring `effect: none` or `effect: reads`. `side effects: repeat` no longer requires their approval.
2. A tool a host registers under the name `duckduckgo`, `serpapi` or `google_search`, with no declaration, is no longer assumed to be a web search. Like any host tool, the audit assumes it reaches everything unless the `.loot` file declares its reach.

The output of `weave audit` and `weave review` for every script in the repository is otherwise unchanged (`ToolKindCharacterizationTest`).

### Kinds from jars (phase 35a, D3)

- `ToolFactory` finds the kinds a jar lists in `META-INF/services/io.github.llm4j.agent.tool.ToolKind` in the project's class loader (`new ToolFactory()`) or a given one (`new ToolFactory(ClassLoader)`, new).
- New: `ToolFactory.BUILT_IN`, `sources()`, `jarKinds()`, `loadProblems()`, `profileOf(String, Map)`, `knownKind(String)`; `ExecutorValidation.Host.toolKindProblems()` (a default method).
- **Behaviour change:** `ToolFactory.register` (and so `HarnessExecutor.addToolKind`) throws `IllegalArgumentException` for a name already registered, where it replaced the kind. A host that replaced a built-in kind this way must use another name.
- The message for an unknown kind gains how to add an optional module's jar.

### The tools module moved (phase 35a)

The tools module moved to `src/tools/ai-agent4j-tools`. The artifact coordinates are unchanged; its parent is now `ai-agent4j-tools-parent`, which inherits from `llm4j-parent`.

### Tool effects (phase 27a)

All additive. A script that declares no effect behaves as before.

- `ToolDef.getEffect()` (`Optional<TaskEffect>`), `setEffect`, `getOperationEffects()` and `putOperationEffect`.
- `McpServerDef.getToolEffects()` and `putToolEffect`.
- The tool words `effect:` and `effects:`, and the same `effects:` inside an `mcp` block.
- `security.Capabilities` gains `level`, `effectSource` (`Capabilities.EffectSource`: `DECLARED`, `ASSUMED`) and `effectReason` at the end. Its previous six-component constructor is kept and assumes the effect. Also `Capabilities.operationEffect`.
- `security.McpEffects` (new). The review fact model gains `effect`, `effectSource` and `effectReason` on a `tool`.
- **Incompatible (a new load error):** `effect:` together with `reach:` on one tool.

---

## A2A

All additive. Documentation: [docs/a2a](a2a/README.md). The test kit `llm4j-a2a-testkit` is not published.

### A2A, calling (phase 32a)

A new package `io.github.llm4j.a2a` in `ai-agent4j`. Nothing existing changes and no runtime dependency is added.

**Public types**

- The enum `TaskState`; the sealed interface `Part` (`Text`, `Data`, `FileOffered`).
- The records `A2AMessage` (with `Role`), `Artifact`, `RemoteTask`, `SendOptions`, `AgentCardView` (with `Skill`, `Endpoint`, `Extension`, `SignatureStatus`), `ReceivedFile`.
- The sealed interfaces `SendResult`, `ChannelMessage` and `ChannelResponse`.
- The interfaces `A2ATransport` and `AgentChannel`, implemented by the consumer (there is no default transport).
- The final classes `RemoteAgent`, `EndpointGuard`, `ExchangeGuard`, `ChannelContext`, `ReceivedFileSink`, `A2AJsonRpc10` (the only class that knows a wire name) and `A2AException` with its nested subclasses.

### A2A, serving (phase 32b)

Additive. Nothing existing changes. In `io.github.llm4j.a2a`:

- The final class `A2AHandler`; the record `CallerIdentity`.
- The sealed interfaces `A2ARequest` (`Send`, `GetTask`, `ListTasks`, `CancelTask`) and `A2AResponse` (`TaskResponse`, `MessageResponse`, `TaskPage`).
- The interfaces `TaskRunner` (with the record `OwnedTask`), `TaskRunners` and `TaskStore`.
- The final classes `AgentCardBuilder` and `AgentTaskRunner`.
- `A2AJsonRpc10` gained `parseRequest`, `writeResponse`, `writeError`, `writeCard` and the records `Call` and `CardSpec`.
- `A2AException` gained `InvalidParams` and `MethodNotFound`.

### A2A in Loom (phases 32c to 32e)

Additive, with one change to what `weave audit` can report.

**New syntax and commands**

- `remote`, `agent_channel` and `expose` declarations, the `ask` statement, and `delegate` to a remote. The words are contextual: a script that already uses them as names still loads.
- New CLI command `weave card`.

**New public types in `ai-agent4j-loom`**

- `io.github.llm4j.loom.host.LoomTaskRunner` (with the record `ServedEvent`), `RemoteTasks`, `ExposedCard`.
- The AST classes `RemoteDef`, `AgentChannelDef`, `ExposeDef`, `NamedSettings` and `AskStmt`.
- `HarnessExecutor.setA2ATransport`, `setAgentChannel` and `setServedFromOutside`; `TraceEvent.REMOTE` and `EXCHANGE`.

**Changes to existing types**

- `RunSuspended.Reason` gains the constant `REMOTE`: a `switch` over it with no default needs a case.
- `WorkflowGraph` gains the component `exposedAs` and `GraphBuilder.A2AInfo` the component `exposed`; both keep their old constructors.
- The graph JSON gains `exposedAs` on a workflow and the node kinds `remote` and `ask` (the schema allows them).

**Behaviour change**

- `weave audit` gains LA24 to LA27, and the reliability rule LR02 now also fires on a remote call. A script that passed `weave audit --fail-on` may now fail it.
- Loom runs that call a remote need the host to supply an `A2ATransport`. Without one the step fails with a message that says so.

### A2A server half (phase 32 follow-up)

After a check against the A2A compatibility kit. Additive, with one behaviour change.

- `A2AJsonRpc10` gains `idOf(String)` and `requireSupportedVersion(String)`.
- New final class `A2AException.PushNotificationNotSupported` (code -32003).
- **Behaviour change:** `A2AJsonRpc10.parseRequest` now throws `UnsupportedOperation` for `SendStreamingMessage`, `SubscribeToTask` and `GetExtendedAgentCard`, and `PushNotificationNotSupported` for the four push notification configuration methods. Before, it threw `MethodNotFound`. A caller that mapped `MethodNotFound` to its own answer for those names now sees the specification's errors.
- A consumer that serves the handler should call `requireSupportedVersion` with the `A2A-Version` header, which it did not have to before.

---

## Supervision and child workflows (phases 33 and 34)

All additive unless marked. The core (`ai-agent4j`) is unchanged by phase 33.

### Starting and supervising workflows (phase 33)

**New words.** `start`, `wait`, `cancel`, `steer` and `supervise <handle>`, and the top-level settings `max_children` and `max_depth`. They are contextual: a word is a verb only where a statement starts and its shape follows, so no existing script changes meaning.

**New public types**

- In `io.github.llm4j.loom.execution`: `ChildRuns` (where a child keeps its run; a host built on `Persistence` provides one, and an executor given only a journal has none, so `start` fails closed), `ChildScheduler` and `ControlledChildScheduler`, `ChildLimits`, and `SuperviseEvaluator`.
- In `io.github.llm4j.loom.runtime`: `ChildHandle` and `ChildStatus`.
- New methods on `HarnessExecutor`: `setChildRuns`, `setChildScheduler`, `setChildLimits`, `setSuperviseTick` and `tickChildren`. On `LoomHost.Builder`: `childLimits`.

**Changed**

- `RunRecord` gains a component, `parentRunId`; its previous twelve-component constructor is kept.
- The JDBC table `llm4j_loom_runs` gains a nullable column `parent_run_id`, added by `LoomHost.createSchema` to an existing table.
- `weave audit` gains LA28 and LA29.

### Closing the phase 33 gaps (phase 34a)

**New**

- `io.github.llm4j.loom.runtime.Unavailable`: what `wait h -> r` binds for a child that did not complete; reading it raises the status error.
- `io.github.llm4j.loom.execution.ScriptedChild` (with `State` and `Step`) and `ChildScripts`: a test scripts a child instead of running it. `HarnessExecutor.setChildScripts` defaults to none, so every `start` runs its workflow.
- The `children:` key of a `weave test` case is new (`expect.children:` is unchanged).
- `cancel h rewind to <checkpoint> at most N times`, parsed since phase 33, now runs; `weave check` no longer refuses it.
- `ChildHandle.resume()` also clears `wakes`.

**Behaviour change**

- `wait h -> r` on an unfinished child bound nothing (so `{r}` read empty) and now binds a value that raises on read.
- A wait's timeout used a real wait with a frozen clock and now follows the injected clock.
- A `start` reserves its paired judge's budget.
- A handle named in `{...}` text is refused at run time.
- A child folder written by `weave run --journal` gains a `run.json` and is refused by `weave resume` (it is not a run of its own).

**Internal:** `ChildMonitor`, `ChildPairing`, `ChildLimitChecks`, `ChildLauncher.launch` (package-private).

### Declared progress (phase 34b)

**New words.** `progress` (only right after a workflow's `)` and before `{`), `report` (only as `report {` at the start of a statement), and in a `supervise` block `assert progress.<field> changes within <duration>`. Every other use of either word is an ordinary name, so no existing script changes.

**New and changed types**

- In `io.github.llm4j.loom.ast`: `ProgressDecl`, `ReportStmt`; `WorkflowDef.getProgress()`/`setProgress`; `SuperviseStmt.AssertionKind.CHANGES_WITHIN` (a `switch` over the enum needs the new constant).
- `ChildHandle` gains the fact `progress` (a map) and `progressFrom(Supplier)`.
- `SuperviseEvaluator.Facts` gains a component `unchangedMillis`, `ScriptedChild.Step` gains `progress` (old constructors kept in both).
- `graph.Kinds.CHILD_REPORT` (`child_report`) joins `Kinds.ALL` and the graph schema.

**Journal and trace.** A report is an entry `<step>#progress` (kind `progress`, or `progress-dropped`) in the **child's** journal; an older reader ignores keys it does not know. Trace and audit: `progress_dropped`.

### Signals (phase 34c)

**New words.** `notify` (only as `notify <handle|parent> "name"`), `signal` (only as `wait signal "name"`, `wait <handle> signal "name"` and `when signal "name"`) and `parent` (only as the first operand of `notify`). Every other use of each is an ordinary name, so no existing script changes.

**New and changed types**

- In `io.github.llm4j.loom.ast`: `NotifyStmt`, `SignalWaitStmt`; `SuperviseStmt.SignalRule` and `getSignals()`.
- `RunSuspended.Reason.SIGNAL` and `RunSuspended.signal(...)`: a `switch` over the enum needs the constant. Only a child run throws it, so a host that resumes top-level runs never sees one.
- `ChildHandle.pauseOnSignal`, `waitsFor()`, `waitsDeadlineMillis()` and the fact `waits`; `Unavailable.noSignal`.
- `ChildScheduler.idle(long)` (a default method) and `ControlledChildScheduler.onIdle`.
- `ScriptedChild.Signal` and a new `Step` component (the old constructors are kept).
- `HarnessExecutor.simulateSignalFromChild` and `clockMillis` (test seams).
- `graph.Kinds.CHILD_NOTIFY` (`child_notify`) and `CHILD_SIGNAL_WAIT` (`child_signal_wait`), added to the graph schema.

**Journal.** An older reader ignores keys it does not know.

| Where | Entry |
|---|---|
| The **child's** journal | `#sig.in.<name>.<n>` and `#sig.out.<name>.<n>` (kind `signal`), `#sig.take.<dir>.<name>.<n>` (kind `signal-take`), `<step>#deadline` |
| The reader's journal | Kind `signal-wait` at the step, and `notify` at the sender's step |

**`weave test`:** `signals:` and `expect.signals` are new, and a scripted child's step takes `signal:`.

**Audit.** LA30 (see `docs/security/weave-audit.md`): medium when the text reaches a prompt or a task argument (directly or through `set` copies), info otherwise. So `weave audit --fail-on medium` can now fail a script that reads a signal into a prompt. Audit events: `signal_sent`, `signal_received`, `signal_timeout`.

### `wait any` (phase 34d)

- New Loom word `any`, only directly after `wait`, with a handle after it and not a `timeout`. A handle called `any` is still waited for as any handle.
- `WaitStmt.isAny()` and `setAny`; `graph.Kinds.CHILD_WAIT_ANY` (`child_wait_any`), added to the graph schema.
- Journal, in the parent's journal: kind `wait-any` at the step (the event, the child it concerns, the messages it took, and an ordinal `n`).
- `weave test`: `expect.events`.

### Seeing signals and progress (phase 34e)

No public signature changed. A client that ignores unknown fields is unaffected.

- `TraceEvent.SIGNAL` and `TraceEvent.PROGRESS` are string constants (`"signal"`, `"progress"`), and the trace listener sees them.
- `signal` carries the operation (`sent` or `read`), the peer run id, the name and whether it is sticky, **never the text**. `progress` carries `f.<field>` values.
- In `loom-inspect-core`: `Semconv.EV_SIGNAL` (`loom.signal`) and `EV_PROGRESS` (`loom.progress`) are new log events. `RunState` counts signals each way and keeps a child's latest progress. The children listing of a run gains `signals` (`toChild`, `toParent`) and a `progress` entry in a child's summary.

---

## Evaluation and review

### Steps and evidence in eval4j (phase 35f)

Additive.

- `EvalScenario` gains a last component, `steps` (`List<EvalStep>`, null or empty for a case of one step), with `stepList()`. Its earlier canonical constructor (twenty-two components) is kept, as are the older ones, so existing code compiles unchanged.
- New: `EvalStep`, `criteria.Evidence`, `Criteria.evidencePassed` and `Criteria.evidenceAtMost`.
- The run bundle's `run.json` summary gains `costPerPass` (only when the run cost something) and keeps `schemaVersion: 1`, since every change to it is an added field.
- In Loom, `weave eval --json` gains `costPerPass` and `eval.CostPerPass` is new.
- A dataset check now accepts a case with `steps:` and no input of its own, and names a step that has none.

### Judges, datasets and the evaluation lens (phases 28a to 28e)

Newest first.

**Phase 28e: judge choice**

- `JudgeChoice`, `eval.properties` (`judge`), `judge:` in `dataset.yaml`, `--judge same`; review rules LR12 and LR14.
- **Behaviour change:** the default judge of `weave eval` is a different model than the one judged (it was the same model).
- `EvalCommand.judgeLine` changed shape (package-private).

**Phase 28d: only what changed**

- `weave eval --changed`, `--since`; `NodeFingerprints`, `NodeCaseRunner`; `EvaluatorDef.getFalseAlarmLimit`; the `false_alarm_limit` evaluator setting; `eval/last-run-fingerprints.json`, `eval/baseline-fingerprints.json`; the CI file `weave init` writes.
- `EvalSections` titles its section "By agent and node" (it was "By agent"). JSON consumers read the section by its id, `by-agent`, which is unchanged.
- `ScenarioResult.kind` may now be `extractor` or `evaluator`.
- Older runs without fingerprints are read as "no reference".

**Phase 28c: requirements.** `weave eval --requirements`, `--format`, `--approve`; `RequirementsPage`, `RequirementsApprovals`; `eval/requirements-approvals.yaml`. New options and a new file; nothing existing changes.

**Phase 28b: node cases**

- `NodeCasesGate`, `GoldenDatasets.nodeFiles`, `EvalDataset.load(dir, skip)` and `LoomLoader.setGateNoticeWanted`.
- `weave check` reports `NodeCasesGate.problems` as errors.
- `EvalDataset.load(Path)` is unchanged; the new overload leaves out files that hold another kind of case. Loading never reads `eval/golden`.

**Phase 28a: testability lens**

- **Behaviour change:** the testability lens is on by default (`Lens.TESTABILITY.optional()` is now `false`). LT06 no longer needs heuristics, is one finding per script, and its severity follows LT01.
- `DeployOptions` gains a component, `evaluationNotice` (the ten-argument constructor is kept).
- `HarnessExecutor.setEvaluated(boolean)` and the `evaluated` attribute of the run start event (`loom.run.evaluated`, present only when false) are new.
- `GoldenDatasets` (in `io.github.llm4j.loom.eval`) is the shared lookup of which agents and nodes have golden cases.

### Review setup findings and cost fields

Additive.

- `ReviewReport` gains a `setup` component (findings about the machine, such as a key that is not set). Its previous constructors are kept and give it no setup findings, and `withSetupAsFindings()` merges them back.
- The JSON report gains `setup` and `summary.setup`, and each cost workflow `tokenCap` and `capStopsBeforeWorstCase`.
- `weave review` gains `--include-setup`. **Behaviour change:** the default now excludes setup findings from `--fail-on`.
- `weave observe rules` gains `--init`.

---

## CLI and files

New commands, files and options, and one change to how text is substituted.

**Behaviour that changes**

- **Only `{name}` is replaced in a text.** A plain word that is also a variable's name is no longer replaced; a bare variable written where a text goes still means its value. A `substitution:` line is a load error. Review rule LP07 is retired.

**New**

| Item | Notes |
|---|---|
| `real:` | A new key of a workflow test case. Test files without it behave as before |
| `weave fabric up \| down \| status` | A new command. The deployer scripts keep working without it; with it, `fabric-up` generates a first-start admin password instead of refusing, and `run-observed` gains `--secrets-key-file` |
| eval4j `EvalScenario` | Gains six components (`correctConclusion`, `acceptedEquivalents`, `rejected`, `acceptableActions`, `unacceptableActions`, `requiredCitations`). The sixteen-component constructor and every older one are kept, and datasets without them read as before |
| `weave judge validate` | A new command; `eval/judge-validation.json`, a new file |
| `WeaveCLI.run(String...)` | A new public method: runs `weave` in the process and returns the exit code (`main` now calls it) |
| `weave security generate`, `--route-danger`, `--emit-test`, `weave gate --protocol`, `weave judge validate --system-model`, and the `expect.told` key of test cases | New |
