# Release notes: 6.1.0 (in preparation)

This page is written as the release is built. Each item says whether it **works now** on the release branch or is **coming**; nothing
that is not finished is described as working. The API policy is in [API_COMPATIBILITY.md](API_COMPATIBILITY.md).

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
| Google (Gemini) and Anthropic (Claude) native grounding | **preview**: written from the vendors' reference pages, tested on documentation-derived fixtures; no key was available to record real responses |
| Kimi and Qwen grounding | **left out**: no key, and Kimi's search function is deprecated |
| Grounding in `ReActAgent` and `AgentResult.citations`, Loom `grounding:` with its `weave check` and `weave audit` rules, eval4j citation assertions | **coming** |

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

### What Jev is, and what we found

Jev is a *decision model* from TypeSafe AI: it answers typed questions directly, in one call, with probabilities, and does not write text. We
measured it against Claude Opus 5.5 and Claude Haiku 5.5 on labelled data before building anything ([the study](specs/07-classifier/STUDY_REPORT.md)):

- **Intent routing and judging: recommended.** Same accuracy as the chat models on our sets, better calibrated, about 3 to 9 times lower p95 latency (about 170 ms
  median) and 2 to 4 times cheaper than the cheap chat model (about 100 times cheaper than the strong one), and the same answer on every repeat.
- **As the only safety guardrail for shell commands: not recommended.** It was below the cheap chat model on both sets and once called `> /etc/passwd` safe with confidence 0.95.
- The sets are small (30 to 65 cases per task) and ours; differences of one or two cases are noise. Rerun the comparison on your own cases before relying on it.
