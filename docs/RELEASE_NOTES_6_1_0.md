# Release notes: 6.1.0

All eleven artifacts are at `6.1.0`, aligned ([VERSION_MATRIX.md](VERSION_MATRIX.md)). Each item says whether it **works now** or is **preview** (built, but not yet checked
against a real service or by hand); what is not in this release is listed at the end. Nothing that is not finished is described as working. The API policy is in [API_COMPATIBILITY.md](API_COMPATIBILITY.md).

## Loom: from a sentence to a running, watched workflow

6.0.0 had the Loom language and its runtime. 6.1.0 adds the tools around it, so one person can describe a workflow, see it, check it, test it, debug it, run it and ship it. All of it is in the
`weave` command line (16 new commands; `weave man` lists every command and `weave man <command>` prints one page, [docs/COMMAND_REFERENCE.md](COMMAND_REFERENCE.md) is the same text) and
the VS Code extension, which carries the same engine.

| Piece | Status |
|---|---|
| **First run.** `weave try` runs a bundled workflow on demo models (no key, no network) and explains each step; `weave doctor` checks Java, keys, models and the run store and says the one thing to do for each problem | **works now** |
| **Generate.** `weave generate` turns a sentence into a plan you can read and edit, then a project that passed `check`, `review` and tests the tool wrote for it (`--plan-model` lets a stronger model write the plan). `--gate-only`, `--edit` (a change as a diff with the review and cost difference), `--max-cost` | **works now**; measured by replaying recorded model replies for 30 workflow descriptions, 27 produced a project that passed every gate; it is not a promise about your description |
| **Review.** `weave review` reads a script without running it: 44 rules in ten lenses (cost, reliability, prompts, durability, observability, privacy, and the optional model, maintainability, human and blast-radius lenses), each finding with why it matters and what to do; a cost estimate (typical and worst case); `--fix` for safe fixes, baselines and accepted findings, `weave rules` to list, test, add, diff and document rule packs | **works now** |
| **Test without a model.** `weave test` with scripted models, injected failures and a virtual clock; step coverage | **works now** |
| **Editor engine.** `weave serve` answers check, graph and review for an editor; `weave symbols` lists definitions and uses; `weave dap` and `weave step` debug a workflow one statement at a time with breakpoints, variables and a way back | **works now** |
| **Time travel.** `weave why` explains a run: each choice, over which values, and the way it did not go; `weave whatif` changes one thing in a past run and shows how it would have gone | **works now** |
| **Production loop.** `weave capture` turns a real run into a test case, `weave regress` tries a change on past runs (unsafe changes first), `weave gate` prints one verdict for CI | **works now** |
| **Embed and deploy.** `weave deploy` writes a container recipe, a compose file or Kubernetes manifests from the script; `weave bindgen` writes typed Java bindings (an `Outcome` of `Done`, `Waiting` or `Stopped`) | **works now** |
| **Coding agents.** `weave guide --install-skill --for <agent>` writes what Claude Code, Codex, Copilot, Cursor and others read, so they build with Loom and look commands up in `weave man` | **works now** |
| **A question on a closed console.** `weave run` with no terminal to answer (a pipe, a scheduler) now stops with exit code 1, says what the question was and how to let the run pause and be answered later (`--store`, `--ask-via`), and no longer ends in a stack trace | **works now** |

**The VS Code extension** is published as `srijithunni7182.vscode-llm4j-loom` on the Visual Studio Marketplace (it is not a Maven artifact). It carries `weave.jar`, needs only Java 17, and adds the
live workflow graph, the Review panel, generate and change, tests, evaluation, a step debugger, a run with a live page, deployment files and agent set-up, and (from extension 1.1.0) makes
`weave` available in VS Code's integrated terminals. The graph and the review panel were tried by hand in VS Code before this release. Extension versions are separate from the library version; see its
[changelog](../src/loom/vscode-loom/CHANGELOG.md).

## More providers: OpenAI, xAI, Perplexity, Kimi, Qwen and any OpenAI-style server

6.1.0 adds one provider for the whole OpenAI family (`OpenAiCompatibleProvider`, vendors as data in a `Dialect` and `ModelTraits`) and one for the OpenAI **Responses**
schema (`OpenAiResponsesProvider`: Perplexity's Agent API and OpenAI's own Responses API). Every one passes the shared provider contract. A provider is called
**supported** only when it also passes on responses *recorded from the real API* (a fixture states its date and model); **preview** means documentation-derived fixtures,
no key or no access. Design: [specs/08-providers-and-grounding](specs/08-providers-and-grounding/DESIGN.md). Wiki: [OpenAI, xAI, Kimi, Qwen and any OpenAI-style server](../src/ai-agent4j/wiki/OpenAI-Family-Providers.md),
[the Responses schema and Perplexity](../src/ai-agent4j/wiki/Responses-Schema-and-Perplexity.md).

| Provider | Status |
|---|---|
| OpenAI, chat completions (`gpt-…`, `o3`, …) | **supported**: recorded 2026-10-22 with `gpt-6-luna` (plain, stream, tool call and answer, streamed tool call, reasoning) |
| OpenAI, Responses API (`use: openai  api: responses`) | **supported**: recorded 2026-10-22 with `gpt-6-luna` |
| xAI (Grok) | **supported**: recorded 2026-10-22 with `grok-4.20-0309-non-reasoning` (chat completions, which xAI now calls legacy) |
| Perplexity Agent API (`perplexity/openai/gpt-5.6-sol`, `perplexity/preset:medium`) | **supported**: recorded 2026-10-22 (plain model access and web grounding, see below) |
| xAI, Responses API (`use: xai  api: responses`) | **supported for web grounding**: recorded 2026-10-22 with `grok-4.3` |
| Kimi (Moonshot) | **preview**: fixtures are written from Moonshot's documentation; no key was available |
| Qwen (Alibaba DashScope) | **preview**: fixtures are written from Alibaba's documentation; no key was available |
| Perplexity Orchestrator (`perplexity_router`) | **preview**: Perplexity's API is itself in private preview; no access, no live fixtures |
| `openai_compatible` (DeepSeek, Groq, Together, vLLM, LM Studio, a gateway with its own header or `api-version`) | **preview**: contract-tested, and the protocol is the one recorded for OpenAI and xAI, but no third-party server has been recorded |
| Loom: `provider … { use: openai | xai | kimi | qwen | perplexity | perplexity_router | openai_compatible }`, model names (`gpt-5-mini`, `xai/grok-4`, `perplexity/openai/gpt-5.6-sol`), keys by vendor and bound to the vendor's host, `weave audit` data flows | **works now** |
| Spring Boot starter: `llm4j.clients.<name>.provider` accepts the new providers | **works now** |
| Spring Boot starter: `llm4j.classifiers.<name>` (`jev`, `perplexity_decisions`) as a `Classifiers` bean; `llm4j.budget.searches` and `llm4j.pricing.models.<m>.search-per-thousand` for grounded calls | **works now** (tests only; no live Spring run) |
| Loom: `provider … { use: perplexity_decisions }` names a Decisions classifier backend, beside `jev` | **works now** |
| `listModels()` / `getFirstAvailableModel()` (`DescribableProvider`) on `OpenAiCompatibleProvider` and `OpenAiResponsesProvider` (path in `Dialect.modelsPath()` / `ResponsesDialect.modelsPath()`) and on `AnthropicProvider`; Perplexity (no such endpoint) throws `InvalidRequestException` | **supported** for OpenAI, xAI and Anthropic: listings recorded 2026-10-22; Kimi, Qwen and generic servers use the same `/models` path (preview) |
| Anthropic `pause_turn`: `chat` continues a paused server-tool turn (up to five times) and returns one response; streaming does not continue | **preview**: fixtures written from the reference |
| Context-window catalog entries for the current OpenAI, xAI, Kimi and Qwen models (each checked against the vendor's documentation) | **works now** |
| `SpendGuard` (eval4j) now meters streamed calls from their final chunk, and the opt-in `ProviderSmokeIntegrationTest` records fixtures from the real APIs under a spend cap | **works now** |

What we found against the real APIs: OpenAI's `gpt-6` models refuse function tools on chat completions unless `reasoning_effort` is `none` (pass
`addParameter("reasoning_effort", "none")`, or use `api: responses`); xAI returns its reasoning text in `reasoning_content`; Perplexity's Agent API returns its price in
`usage.cost`. A bare `qwen3` or `gpt-oss` is not resolved to a vendor (it usually means a local Ollama model): write `qwen/qwen3-max` or `ollama/qwen3`.

## Web-search grounding: one option, typed citations

A request can now carry `Grounding.webSearch()`: the model searches the web inside the call, and the answer comes back with typed `Citation`s (url, title, snippet,
character spans), the number of searches the vendor ran, and a `grounding.citation_basis` that says whether the sources were *cited* or only *consulted*. A provider that
cannot do it, or cannot honour one setting, is refused before any network call, naming both (or, with `lenient()`, drops the setting and says so). Design:
[specs/08-providers-and-grounding](specs/08-providers-and-grounding/DESIGN.md). Wiki: [Web-search grounding](../src/ai-agent4j/wiki/Web-Search-Grounding.md).

| Piece | Status |
|---|---|
| `Grounding`, `Citation`, `Span`, `SearchUsage`, `LLMRequest.grounding`, `LLMResponse.getCitations/getSearches`, the central capability check, `fallback(tool)` (a client-side search tool when the provider cannot search), streaming parity | **works now** |
| A `searches` budget dimension (`Budget.builder().searches(n)`), a per-1000 search price in the price table, search and citation counts in model-call events and run statistics | **works now** |
| Perplexity Agent API, OpenAI Responses, xAI Responses: native grounding | **supported**: tested on responses recorded 2026-10-22 (allow and block lists, recency and country where the vendor has them, OpenAI's search cap) |
| Google (Gemini) and Anthropic (Claude) native grounding | **supported**: a grounded answer and stream recorded from each real API on 2026-10-22, and an allowed-domain call for Claude. Not yet checked against the real API: Claude's `blockDomains` and `country`, a Claude tool turn sent back with its search blocks, and Gemini's recency beyond one accepted request |
| Kimi and Qwen grounding | **left out**: no key, and Kimi's search function is deprecated |
| Grounding in `ReActAgent` and `AgentResult.citations`, Loom `grounding:` with its `weave check` and `weave audit` rules (LA20), eval4j citation assertions | **works now** |

Found against the real APIs: Perplexity lists what it *consulted* and leaves the message's `annotations` empty, while OpenAI and xAI *cite* with spans; OpenAI reports
no search count, so completed search items are counted; only Perplexity reports what the searches cost; Perplexity has no cap on searches and xAI's was not tested, so
`maxSearches` is refused there unless `lenient()`; xAI gives only the citation number as a title (dropped).

## Classification: typed questions, typed answers

Two jobs in an agent system need an answer from a fixed set: routing a message (what does the customer want?) and judging an output
(did it meet the criterion?). Both used to mean asking a chat model for text and parsing it. 6.1.0 adds a `Classifier`: you ask typed
questions (a choice from your labels, a yes/no, a point on a scale) and get typed answers, from any chat model, with a result that can
never contain a label you did not offer. Design: [specs/07-classifier](specs/07-classifier/DESIGN.md).

| Piece | Status |
|---|---|
| `Classifier`, `ClassifyRequest`, `LlmClassifier` (any chat model), `ClassificationProvider` SPI, `ScriptedClassifier` test double (eval4j). Wiki: [Classification](../src/ai-agent4j/wiki/Classification.md) | **works now** |
| `llmJudged(...).classifier(...)` and `ClassifierComparison` (compare backends on your own labelled cases) in eval4j. Guide: [LLM-as-judge](../src/eval4j/docs/LLM-AS-JUDGE.md#judge-with-a-classifier) | **works now** |
| `JevProvider` (TypeSafe AI's Jev), `ClassifierResolver` (`Jev/jev-latest` or any chat model name: one string to switch backends) | **works now** |
| Loom `intentclassifier` / `intentclassify`: typed questions in a script, a confidence rule (`else escalate` / `ask person` / `fail`), `pii:`, budget, trace step, `weave check`, `weave audit` rule LA19, the workflow graph and the conformance kit. Guide: [Loom guide, "Classifying"](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md); example: [ticket-triage](../src/examples/ticket-triage/README.md) | **works now** |
| VS Code support for the new Loom syntax (highlighting, hover, snippets, graph node) | **works now** |
| `PerplexityDecisionsProvider` (Perplexity's Decisions API, `pplx-decider-latest` and `pplx-decider-flash-latest`) as a second calibrated classifier backend, sharing Jev's wire format; `ClassifierResolver` and Loom `intentclassifier { model: "pplx-decider-flash-latest" }` (key `PERPLEXITY_API_KEY`, audit LA19, `weave check`) | **supported**: a real answer and a real error recorded 2026-10-22. Not yet measured against Jev and chat models: no accuracy claim |

### What Jev is, and what we found

Jev is a *decision model* from TypeSafe AI: it answers typed questions directly, in one call, with probabilities, and does not write text. We
measured it against Claude Opus 5.5 and Claude Haiku 5.5 on labelled data before building anything ([the study](specs/07-classifier/STUDY_REPORT.md)):

- **Intent routing and judging: recommended.** Same accuracy as the chat models on our sets, better calibrated, about 3 to 9 times lower p95 latency (about 170 ms
  median) and 2 to 4 times cheaper than the cheap chat model (about 100 times cheaper than the strong one), and the same answer on every repeat.
- **As the only safety guardrail for shell commands: not recommended.** It was below the cheap chat model on both sets and once called `> /etc/passwd` safe with confidence 0.95.
- The sets are small (30 to 65 cases per task) and ours; differences of one or two cases are noise. Rerun the comparison on your own cases before relying on it.

## Not in this release, and known issues

- **Typed workflow parameters** (a language change) are deferred; embedding passes parameters as text.
- **`weave why --verify`** (re-running a past run to confirm an explanation) is not built.
- **Kimi, Qwen, `openai_compatible` and the Perplexity Orchestrator** are **preview**, as listed above: their fixtures come from documentation, not from the real services.
- **`weave check` can warn that a shared prompt file is "not used by any agent"** although `shared_prompt: prompt "house-rules"` names it. The prompt is applied at run time (a test shows its text reaching the
  model); only the warning is wrong. Tracked for a later release.
- **A build made from the repository between two releases reports the version of the last release.** A jar built from the repository before this release said `6.0.0` while differing from the `6.0.0` on
  Maven Central, so a project could end up with two different `6.0.0` jars. `weave --version` also prints the build time, which tells them apart.
