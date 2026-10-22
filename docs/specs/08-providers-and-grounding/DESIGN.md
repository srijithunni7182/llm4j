# Phase 8 design: more providers, then native grounding (ai-agent4j, Loom, eval4j)

Status: **approved; decisions in §9, scope for 6.1.0 in §10**. Layers: ai-agent4j (providers, the grounding contract), Loom (provider kinds, a `grounding` option, audit), eval4j
(citation assertions). Companion documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md).
Builds on the provider SPI and `ProviderContract` tests, the model-window catalog ([Phase 1](../01-context-management/DESIGN.md)), call telemetry
([Phase 2](../02-call-telemetry/DESIGN.md)) and Loom's provider declarations ([Phase 3](../03-loom-integration/DESIGN.md)).
Independent of Phase 7 (classification, on branch `feature/classifier-jev`); the two can proceed in either order.

The work is in two parts, in this order: **Part A** adds the providers (OpenAI, xAI, Kimi, Qwen, Perplexity, and a generic OpenAI-compatible one; Perplexity and OpenAI's grounded path use a second, Responses-schema provider, §3.4);
**Part B** adds provider-neutral **grounding** (web search done by the provider, with citations) on top of them and the existing Google and
Anthropic providers. One spec, because grounding is specified across all providers and its design depends on what each one can do.

## 1. Problem

**Providers.** llm4j speaks to four vendors: Gemini, Anthropic, Sarvam and Ollama. Teams also run on OpenAI, xAI (Grok), Moonshot (Kimi),
Alibaba (Qwen via DashScope) and Perplexity, and on the many others that offer an OpenAI-style API (DeepSeek, Groq, Together, vLLM, LM Studio…).
Today they need a custom `LLMProvider`. Almost all of these use the same wire protocol (`POST …/chat/completions`, the dialect
`SarvamChatProvider` already speaks), with small, vendor-specific differences.

**Grounding.** Every one of these vendors now offers some form of **provider-side web search**: the model decides to search, the provider runs the
search and returns the answer with the sources it used. llm4j has none of it. Its only web search is client-side (`WebSearchTool`,
`DuckDuckGoSearchTool`, `SerpApiSearchTool`), which the agent calls as an ordinary tool. A developer who wants native grounding must pass raw
vendor JSON through `LLMRequest.additionalParameters`, and the sources come back, if at all, in unstructured metadata. So there is no typed request
option, no typed citations, no capability check, no cost accounting for searches (they are billed per request, on top of tokens), and no way for
`weave audit` to know that an agent is reading the open web.

## 2. Goals and non-goals

**Goals**

- G1. One `OpenAiCompatibleProvider` that covers the whole OpenAI-style family, with a small **dialect** per vendor for its differences, so adding
  the next vendor is a few lines and a fixture, not a new provider.
- G2. First-class presets for OpenAI, xAI, Kimi and Qwen (chat completions), OpenAI and Perplexity (Responses schema, §3.4), plus a generic `openai_compatible` kind with a `base_url`, wired through the
  Loom provider declarations, the model-name resolver, the secret store (with per-vendor host binding), the window catalog and the price table.
- G3. Behaviour **equal to the existing providers**: tool calling, streaming, reasoning text, finish-reason and usage normalisation, typed errors,
  retries and 429 handling, call telemetry. Each new provider passes `ProviderContract`.
- G4. **Grounding**: a typed request option (`Grounding`), typed response citations, a capability check, search accounting (count and cost) in
  telemetry and budgets, supported by every provider that can do it and refused clearly by those that cannot.
- G5. Grounding is visible to the layers above: Loom's `agent` can declare it; `weave check` and `weave audit` treat it as exposure to untrusted
  content; traces and Fabric show searches and citations; eval4j can assert on citations.
- G6. Opt-in and safe: nothing changes for existing providers or requests; no grounding unless a request asks for it; no vendor key is ever logged.

**Non-goals**

- Vendor-specific features beyond the shared contract: image/audio/embedding endpoints, batch APIs, fine-tuning, assistants/threads, hosted file
  search or code interpreters. (Escape hatch: `additionalParameters` stays.)
- Azure OpenAI and other gateways that need a different auth header or a version query parameter. The generic provider has a `header` and `query`
  option for simple cases; a dedicated Azure preset is out of scope.
- Replacing the client-side search tools. They remain: they work with any model and any provider, and `Grounding` can fall back to them (§8.3).
- Making a model's grounding **correct**. We pass sources through and measure whether answers are supported by them (eval4j, §9); we do not fact-check.
- Provider selection or routing logic. The existing `routing` package is unchanged; new providers just join it.

## 3. Part A: the providers

### 3.1 Public API (package `io.github.llm4j.provider.openai`)

```java
LLMConfig cfg = LLMConfig.builder().apiKey(System.getenv("OPENAI_API_KEY")).defaultModel("gpt-5-mini").build();

LLMClient openai     = new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
LLMClient grok       = new DefaultLLMClient(OpenAiCompatibleProvider.xai(cfg2));
LLMClient kimi       = new DefaultLLMClient(OpenAiCompatibleProvider.kimi(cfg3));
LLMClient qwen       = new DefaultLLMClient(OpenAiCompatibleProvider.qwen(cfg4));          // DashScope
LLMClient perplexity = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(cfg5));   // Agent API, §3.4
LLMClient any        = new DefaultLLMClient(OpenAiCompatibleProvider.of(Dialect.GENERIC, cfg6)); // cfg6 has a baseUrl (DeepSeek, Groq, vLLM, …)
```

The static factories are shorthands for `new OpenAiCompatibleProvider(Dialect.X, config)`. Every `LLMProvider` method works as it does on
`SarvamChatProvider`/`AnthropicProvider`: `chat`, `chatStream`, `supportsToolCalling`, `getProviderName`, `validate`.

### 3.2 The dialect

A `Dialect` is an immutable description of how a vendor differs from the plain protocol. It is data, not subclasses, so a new vendor is a constant
and a fixture:

| Field | Meaning | Examples |
|---|---|---|
| `name`, `defaultBaseUrl`, `chatPath` | provider name; where to call | `openai`, `https://api.openai.com/v1`, `/chat/completions`; Perplexity has no `/v1` |
| `maxTokensField` | which request field limits output | `max_tokens`; `max_completion_tokens` for OpenAI's newer and reasoning models |
| `temperaturePolicy` | whether a custom temperature is sent | OpenAI reasoning models reject it: the provider omits it for those models rather than failing |
| `reasoning` | where reasoning text comes back and how it is switched on | a `reasoning_content` field (xAI, Kimi, Qwen), `<think>` tags (Sarvam style), an effort parameter (OpenAI) |
| `tools` | whether native tool calling is offered | yes for OpenAI, xAI, Kimi, Qwen |
| `usage` | how token usage and any extra counts appear | cached-token and reasoning-token details are read when present |
| `extraRequest`, `extraResponse` | vendor fields added to the request or read from the response | Qwen's thinking switch; Perplexity's `citations`/`search_results` (Part B) |
| `keyEnv`, `baseUrlEnv`, `hosts` | environment names for key and URL; hosts a secret may be sent to | `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `api.openai.com` |

**Verified against each vendor's published API reference on 2026-10-22 (decision Q1); this corrects the draft table above:**

| Vendor | Facts used by the dialect (source: the vendor's chat-completions reference) |
|---|---|
| OpenAI | `POST /v1/chat/completions`; output limit `max_completion_tokens` (`max_tokens` is deprecated and rejected by the o-series); `reasoning_effort` for reasoning models (passed through `additionalParameters`); o-series and gpt-5+ (not the `-chat` variants) reject a custom `temperature`/`top_p`/`stop`; reasoning text is not returned on this endpoint; usage has `prompt_tokens_details.cached_tokens` and `completion_tokens_details.reasoning_tokens`; a refusal is `message.refusal`; `stream_options.include_usage` sends usage in a last chunk with no choices |
| xAI | `POST https://api.x.ai/v1/chat/completions`, now documented as **legacy** (the Responses API is the recommended path and where tools such as `web_search` live, §4.3); `max_completion_tokens`; `reasoning_effort`; reasoning models (grok-4+, grok-3-mini) reject `stop` and penalties; `message.reasoning_content`; usage adds `cost_in_usd_ticks` and `server_side_tool_usage_details.web_search_calls` |
| Kimi | `https://api.moonshot.ai/v1/chat/completions`, bearer; `max_completion_tokens` (`max_tokens` deprecated); K2.5+ and K3 fix `temperature`/`top_p`/`n` (any other value is an error); K2.5/K2.6 take `thinking: {type: enabled\|disabled}`, K2.7-code is always thinking, K3 takes top-level `reasoning_effort` (`low`/`high`/`max`); `reasoning_content` must be passed back with tool turns; `$web_search` is **deprecated** in favour of `/v1/tools/search` and the Kimi Responses API (§4.3) |
| Qwen | OpenAI-compatible mode; the documented base URL is now a **workspace domain** (`https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1`), the DashScope domain (`dashscope-intl.aliyuncs.com`) is for existing integrations and gets no new features after 2026-09-30; hybrid models take top-level `enable_thinking`, thinking text is `reasoning_content`; `max_tokens`. The preset defaults to the DashScope domain and takes the workspace URL from `base_url` / `DASHSCOPE_BASE_URL` |

Model-specific differences inside a vendor (an OpenAI reasoning model vs a classic one) are decided by a small, tested `ModelTraits` lookup beside
the dialect, driven by name patterns and overridable with `LLMConfig`/request parameters, so that an unknown new model degrades to the safe default
rather than failing.

### 3.3 Wiring

| Place | Change |
|---|---|
| **Loom `provider` kinds** | `ProviderSpec.KINDS` gains `openai`, `xai`, `kimi`, `qwen`, `perplexity` (the Agent API, §3.4), `perplexity_router` (preview, §3.4), `openai_compatible`. `provider Box { use: openai_compatible  base_url: "http://gpu:8000/v1"  api_key: env.KEY }` works like the existing kinds |
| **Model-name resolver** (`DefaultLLMClientFactory`) | Explicit prefixes always work: `openai/gpt-5-mini`, `xai/grok-4`, `kimi/kimi-k2`, `qwen/qwen3-max`, `perplexity/openai/gpt-5.6-sol` (the Agent API slug, §3.4). Bare names resolve only when unambiguous: `gpt-*`, `o1*/o3*/o4*`, `chatgpt-*` → openai; `grok-*` → xai; `kimi-*`, `moonshot-*` → kimi. A bare `qwen3` stays unresolved (it means a local Ollama model to most people); use `qwen/…` or `ollama/…`. The "unknown model" message lists the new forms |
| **Keys** | `OPENAI_API_KEY`, `XAI_API_KEY`, `MOONSHOT_API_KEY`, `DASHSCOPE_API_KEY`, `PERPLEXITY_API_KEY`, and `*_BASE_URL` for each; looked up in the secret store first, as for existing keys. A stored secret keeps the **host binding** it was stored with, and the presets' `hosts` are what `weave` offers when a secret is created for a vendor |
| **Catalogs** | `windows.properties` (context windows) and `PriceTable`/`Pricing` (prices) get entries for the common models of each vendor. An unknown model is not an error: windows fall back to a configured default and prices to "unknown", as today |
| **Rate-limit reports** | `HttpClientWrapper.providerOf` maps the new hosts to their names |
| **Provider tiers / routing** | The new providers can be used in `routing` exactly like the others; no new tier logic |
| **`weave audit`** | The new hosted vendors are listed as data flows, as Gemini and Anthropic are |

### 3.4 Perplexity, and the Responses-schema provider (revised from Perplexity's published documentation, read 2026-10-22)

The draft assumed Perplexity was a Sonar chat-completions vendor that always grounds. Its current documentation says otherwise
([docs index](https://docs.perplexity.ai/llms.txt)): the platform is one key over five APIs, and Sonar chat completions is the legacy path
(*Migrate from Sonar to the Agent API*). What matters here:

| Perplexity API | Endpoint | What llm4j does with it |
|---|---|---|
| **Agent API** | `POST https://api.perplexity.ai/v1/agent` (alias `/v1/responses`, OpenAI **Responses** schema). Models are `creator/model` slugs (for example `openai/gpt-5.6-sol`); web grounding is the `web_search` tool; presets bundle model, tools and limits; multi-turn by replaying input or `previous_response_id`; answer text is in `output[]` items of type `message` → `content[]` of type `output_text`; sources are `search_results` items and URL `annotations` | `OpenAiResponsesProvider.perplexity(cfg)`, kind `perplexity`. This is Perplexity's grounded path (Part B) and also its plain chat path |
| **Orchestrator API** (formerly "Router") | `https://api.perplexity.ai/router/v1` (chat completions, Responses, Anthropic Messages). Open-weight models. **Private preview**: access is by email request | `OpenAiCompatibleProvider` dialect `perplexity_router`, kind `perplexity_router`, **preview**, no live fixtures unless access is granted. No grounding |
| **Decisions API** | `POST https://api.perplexity.ai/v1/decisions`; `noul`, `choice` and `score` questions on a `state`, probabilities back; `pplx-decider-v1.2` (27B, $0.02 per million input tokens) and `pplx-decider-flash-v1.2` (4B, $0.01); output tokens free | Not a chat provider. It is a **second backend for the Phase 7 `Classifier`** (§3.5) |
| Search, Embeddings | `/search`, embeddings endpoints | Out of scope for this phase |

Consequences for this spec:
- A new class, **`OpenAiResponsesProvider`** (package `io.github.llm4j.provider.openai`), speaks the OpenAI Responses schema with the same dialect idea as
  §3.2: request mapping (`input`, `instructions`, `tools`, `previous_response_id`, `max_output_tokens`), response parsing (`output[]`, `output_text`,
  `annotations`, `search_results`), streaming events, usage, finish reasons, reasoning items. It serves **Perplexity's Agent API** and **OpenAI's own
  Responses API** (for its web search), so both share one implementation.
- A Perplexity request without `grounding(...)` runs the Agent API **without** `web_search`, so it is plain model access; grounding is opt-in like everywhere else
  (the "always on" idea is dropped, and `GroundingSupport.ALWAYS_ON` with it).
- Model names: `perplexity/<creator>/<model>` (for example `perplexity/openai/gpt-5.6-sol`) or a preset, `perplexity/preset:medium`; the resolver passes the slug through
  untouched. The catalog (windows, prices) is keyed by the slug and falls back safely for unknown ones (R3).
- Perplexity auth is a bearer key; an `x-api-key` header is not read. Rate-limit headers (`x-ratelimit-*`, `Retry-After`) feed the existing 429 handling.
- Because the Agent API reaches OpenAI, Anthropic, Google and xAI models with one key, a Perplexity key is also a cheap way to smoke-test the Responses
  provider and grounding across model families. Fixtures recorded that way are labelled as recorded **through Perplexity**, and do not count as proof for a
  vendor's own endpoint.

### 3.5 Perplexity Decisions as a classifier backend (depends on Phase 7)

The Decisions API's wire format is the one Phase 7 describes for Jev (`state`, named `questions`, types `noul`/`choice`/`score`, answers with `probabilities`,
`confidence`, and a legend for scores; bearer auth; `usage.input_tokens`/`output_tokens`). Phase 7's `ClassificationProvider` therefore gets a second
implementation, **`PerplexityDecisionsProvider`** (package `io.github.llm4j.provider.perplexity`, kind `perplexity_decisions`), with these points from its published reference:
`state` may be a string, an object or an array (images allowed); 1 to 128 questions per request; an unknown field in the body returns `400`; the model is required (`pplx-decider-latest`,
`pplx-decider-flash-latest`, or the versioned names); `x-api-key` is not read. Because both vendors share the shape, the shared parts live in one
`DecisionWire` class used by `JevWire` and `PerplexityDecisionsWire`, with each vendor's differences kept as data. It is **conditional on Phase 7 being merged**
(sub-phase 8k); its acceptance criteria are in VERIFICATION. It also makes the Phase 7 head-to-head study a three-way one (Jev, Perplexity Decisions, chat models).

## 4. Part B: grounding

### 4.1 What the developer writes

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("What changed in the WHO guidance on sodium this year?")
        .grounding(Grounding.webSearch()
                .maxSearches(3)                       // a cap on provider-side searches for this request
                .allowDomains("who.int", "nih.gov")   // or .blockDomains(...); mutually exclusive
                .recency(Recency.YEAR)                // optional
                .country("IN"))                       // optional: bias results toward a country
        .build();

LLMResponse r = client.chat(request);
r.getContent();                         // the grounded answer
r.getCitations();                       // List<Citation>
r.getSearches();                        // SearchUsage: how many searches ran, and the estimated cost
```

### 4.2 The types (package `io.github.llm4j.grounding`)

```java
public final class Grounding {                         // immutable; built with Grounding.webSearch()
    Integer maxSearches();  Set<String> allowDomains();  Set<String> blockDomains();  Recency recency();  String country();
    boolean lenient();                                  // §4.4: ignore options the provider cannot honour, instead of refusing
    Optional<Tool> fallback();                          // §8.3: a client-side search tool to use when the provider has no native grounding
}
public enum Recency { DAY, WEEK, MONTH, YEAR }

public record Citation(int index, String url, String title, String snippet, List<Span> spans) { }   // index counts from 1, as in "[1]"; spans may be empty
public record Span(int start, int end) { }             // character range in the answer this source supports

public record SearchUsage(Integer count, List<String> queries, Double estimatedCostUsd) { }   // count null = the vendor did not say; queries only at the detail level that allows text

public enum GroundingSupport { NONE, NATIVE }
public enum GroundingOption { MAX_SEARCHES, ALLOW_DOMAINS, BLOCK_DOMAINS, RECENCY, COUNTRY }   // what a provider can honour, for the central check
```

`LLMProvider` gains `default GroundingSupport groundingSupport() { return NONE; }` and `default Set<GroundingOption> groundingOptions()` (empty) and, to keep the SPI additive, grounding is read from
`LLMRequest.getGrounding()` (null when not asked for). `LLMResponse` gains `getCitations()` (never null; empty when none) and `getSearches()`
(null when no search ran). Streaming delivers citations and search usage in the **final chunk**, like usage and finish reason today.

### 4.3 How each vendor is mapped

Revised in sub-phase 8d from the vendors' **real grounded responses** ([`grounding-payloads/`](grounding-payloads/README.md), captured 2026-10-22) for Perplexity, OpenAI and xAI, and from
the vendors' reference pages (read 2026-10-22) for Anthropic and Google, which could not be called (no key) and so stay **preview**. The draft table written from memory is replaced.

| Provider | Mechanism | `maxSearches` | Domains / recency / country | Citations come from | Search count and cost come from |
|---|---|---|---|---|---|
| **Perplexity** Agent API (`OpenAiResponsesProvider`, Responses schema) | `tools: [{type: web_search}]`, opt-in; options in the tool: `filters.search_domain_filter` (up to 20; a `-` prefix excludes), `filters.search_recency_filter` (`hour` … `year`), `user_location.country` | **not offered** (`max_steps` is a loop bound; it did not limit searches): refused unless `lenient` | allow ✓, block ✓ (as `-domain`), recency ✓ (`DAY`…`YEAR`), country ✓ | the `search_results` output item (id, url, title, snippet): what it **consulted**; message `annotations` is empty | `usage.tool_calls_details.search_web.invocation` (one invocation can run several queries; they are in `search_results.queries`); real cost `…search_web.cost_usd` |
| **OpenAI** Responses (`api: responses`) | `tools: [{type: web_search, filters: {allowed_domains \| blocked_domains}, user_location: {type: approximate, country}}]` plus `include: [web_search_call.action.sources]` | `max_tool_calls` ✓ (2 ran, the third item stayed `searching`) | allow ✓ (up to 100), block ✓, country ✓, recency ✗ | `url_citation` annotations on the message (url, title, range): what it **cites** | **no usage field**: the number of `web_search_call` items with status `completed`; queries in `action.queries`; cost from the user's `PriceTable` |
| **xAI** Responses | `tools: [{type: web_search, filters: {allowed_domains \| excluded_domains}}]` | not tested: refused unless `lenient` | allow ✓ (up to 5), block ✓ (up to 5), recency ✗, country ✗ | `url_citation` annotations (title is only the citation number: dropped); the text has `[[N]](url)` markers | `usage.server_side_tool_usage_details.web_search_calls`; total cost `cost_in_usd_ticks` (10¹⁰ per dollar) |
| **Anthropic** (preview, from the reference) | server tool `{type: web_search_20250305, name: web_search, max_uses, allowed_domains \| blocked_domains, user_location}` | `max_uses` ✓ | allow ✓, block ✓, country ✓ (`user_location.country`), recency ✗ | `citations` on text blocks (`web_search_result_location`: url, title, `cited_text`) and the `web_search_tool_result` blocks; the blocks (with `encrypted_content`) must be sent back verbatim | `usage.server_tool_use.web_search_requests`; $10 per 1,000 searches |
| **Google** (preview, from the reference; `generateContent`) | `tools: [{googleSearch: {timeRangeFilter?}}]` | not offered: refused unless `lenient` | recency ✓ (`timeRangeFilter`: an interval ending now), allow ✗, block ✗, country ✗ | `groundingMetadata.groundingChunks[].web` (uri, title) and `groundingSupports` (segment byte offsets, chunk indices) | `groundingMetadata.webSearchQueries` (the count is the number of queries); $14 per 1,000 searches (Gemini 3 and newer) |
| Kimi | its `$web_search` function is **deprecated**; Moonshot now offers `/v1/tools/search` and server-side search in its Responses API | | | | **left out** of this release: no key, so nothing to record |
| Qwen | `enable_search` and `web_search` in the Responses API | | | | **left out** of this release: no key |
| Sarvam, Ollama | none | | | | `groundingSupport() == NONE` |

What the payloads settled (full list in the payloads README): citations mean *what the vendor attributes the answer to*, so each response says whether they were **cited** or **consulted**
(`LLMResponse.getMetadata()` key `grounding.citation_basis`); a search is what the vendor counts, not a query (queries are listed separately); spans mean the link the model wrote into the text
(code points, converted to Java offsets; out of range are dropped; Google's are UTF-8 byte offsets, converted); streaming and whole responses agree because both read the final response object;
the answer text keeps the markdown links the model wrote.

### 4.4 Capability and refusal

- A request with `grounding(...)` to a provider whose `groundingSupport()` is `NONE` is refused with `InvalidRequestException` ("provider X does not
  support grounding"), exactly as tools are today, **unless** the grounding carries a `fallback(tool)` (§8.3).
- An option the provider cannot honour (for example `maxSearches` on Google) is refused naming the option and provider, **unless** `lenient()`, in
  which case it is dropped and the response records that in `LLMResponse.getMetadata()` under `grounding.ignored`.
- `DefaultLLMClient` performs the check once, centrally, before the provider is called, so no provider re-implements it.

### 4.5 Cost, budgets and telemetry

- `ModelCallEvent` (Phase 2) gains `searches` (count) and `citations` (count); queries and URLs are text and appear only at the detail level that
  allows text, as for other events.
- `Budget` gains a `searches` dimension next to tokens and cost; a breach behaves as for the others. `PriceTable` gets per-search prices where the
  vendors publish them (some bill per request, some per 1,000), so the cost of a grounded call is tokens **plus** searches.
- `StatsCollector`/`RunStats` aggregate searches per model.

### 4.6 Agents

`ReActAgent` is unchanged: grounding is a property of the client's request, applied via `Grounding` on the agent's builder (`.grounding(...)`,
copied onto every model call) or on the request when the agent is not used. `AgentResult` exposes the union of citations across the run
(`AgentResult.citations()`), de-duplicated by URL, so an answer can be shown with its sources.

## 5. Loom

- **Providers**: the new kinds (§3.3). Nothing else is needed to use them.
- **Grounding**: `agent Researcher { model: "perplexity/openai/gpt-5.6-sol"  grounding: web }`, and with options:
  `grounding: web { allow: "who.int", "nih.gov"  recency: year  max_searches: 3 }`. `weave check` refuses `grounding:` on a model whose provider has no
  grounding support, and refuses an option the provider cannot honour (no `lenient` in scripts: an author sees every limit).
- **Audit**: a grounded agent counts as **exposed to untrusted content** for the lethal trifecta, exactly like an agent with a web-fetching tool. An
  `allow:` list narrows but does not remove that exposure, and the audit says so. `weave audit` lists the search provider as a data flow.
- **Trace and Fabric**: a model-call record shows `searches` and `citations`; the run page lists the sources (URLs only when the detail level allows).
- **Budgets**: `budget { searches: 20 }` is accepted.
- **Fan-out**: a grounded agent may be used by any workflow construct; its citations are bound in the step result (`step.citations`) so a later step
  can pass them on.

## 6. eval4j

- Assertions on `AgentResult`/`LLMResponse`: `hasCitations()`, `citesAtLeast(n)`, `citesDomain("who.int")`, `citesOnlyFrom("who.int", "nih.gov")`,
  `usedSearches(atMost)`. These are deterministic and cost nothing.
- Replay: `AgentReplay`/`ScriptedClient` can script a response with citations so a grounded workflow is testable offline.
- Judge: the existing RAG-style metrics can take a grounded answer plus its citation snippets as `retrievalContext`, so "is every claim supported
  by a cited source?" runs on the same judge machinery (and on a Phase 7 classifier, if present). A purpose-built faithfulness-to-citations metric
  is a follow-up (Q6).

## 7. Behaviour and invariants

- **R1**: Every new provider satisfies `ProviderContract` (tools, streaming, system messages, finish-reason mapping, usage, typed errors, retries).
- **R2**: A dialect difference is data. No vendor name appears in `OpenAiCompatibleProvider` outside its dialect constants and `ModelTraits`.
- **R3**: An unknown model name at a known vendor works with safe defaults (no custom temperature for names that look like reasoning models, the
  newer token-limit field where the vendor requires it), never fails because the catalog does not list it.
- **R4**: A key never appears in an exception message, a log line, an event or a trace, and a stored secret is sent only to its bound hosts.
- **R5**: `Grounding` is opt-in: a request without it produces byte-identical provider requests to the previous release for every existing provider
  (a test pins this).
- **R6**: A grounding request to an unsupported provider, or with an unsupported option, is refused before any network call, naming the provider
  and option, unless `lenient()` or a fallback applies.
- **R7**: Citations are typed: a response never carries citation data only in free-form metadata; URLs are as the vendor returned them; indices are
  stable within a response; spans, when present, lie within the answer text.
- **R8**: Search usage is counted from what the vendor reports; when the vendor does not report it, `getSearches()` says `count` unknown (null) rather
  than guessing; estimated cost is null unless the price is known.
- **R9**: Streaming and non-streaming grounded calls yield the same citations and search usage for the same answer.
- **R10**: A grounded agent is "exposed to untrusted content" in `weave audit`, whatever its `allow:` list says.
- **R11**: Search counts are charged to the `searches` budget dimension; a breach halts as for any other dimension.
- **R12**: Continuing a tool-using conversation across turns with a grounded provider keeps the vendor data it needs (Anthropic server-tool
  blocks, Kimi's round-tripped search call) in `providerData`, so a grounded turn followed by a tool turn is valid.

## 8. Further design points

### 8.1 Why one provider and dialects, not one class per vendor

The protocols are close enough that five near-copies of `SarvamChatProvider` would duplicate roughly 200 lines each and diverge on fixes. The cost
of the shared class is the dialect/traits layer; the benefit is that a bug fix in streaming or tool-call assembly lands once and `ProviderContract`
proves it for every vendor.

### 8.2 Why grounding is a request option, not a tool

A tool is something the **agent** calls and the **client** executes. Grounding is executed by the **provider** inside one model call and returns
sources the model has already used. Modelling it as a tool would force a round trip the provider does not need and would hide the citations in
tool text. The typed option keeps the call atomic and the sources structured.

### 8.3 Fallback to client-side search

`Grounding.webSearch().fallback(new WebSearchTool(...))` means: use native grounding where the provider has it; otherwise offer the tool to the
model and fold the tool's results into `Citation`s. This lets one agent definition run on Gemini (native) and on Ollama (client-side). It is
opt-in per request, never implicit, because the two differ in cost, latency and trust.

### 8.4 Privacy

A grounded query leaves the process for a search service in addition to the model vendor. `weave audit` and the docs say so; the PII masker that applies
to model requests applies to the text that can reach a search query (the prompt), and the docs warn that the model forms the query.

## 9. Decisions

The questions raised in the draft, answered. Nothing starts with a question open that changes the API.

| # | Decision |
|---|---|
| Q1 | The per-vendor facts (§3.2 dialect fields, §4.3 table) are a *precondition task* for each sub-phase, not a design question: the first step of 8a/8b is reading the vendor's current API reference and correcting this spec's table in the same commit; fixtures are recorded from the real API (8c-live). A vendor whose documentation cannot be read or whose API cannot be called waits; the others proceed |
| Q2 | **OpenAI**: `openai` stays on chat completions. A Responses-schema provider, `OpenAiResponsesProvider`, is built in Part A (sub-phase 8b2) because Perplexity's Agent API is a Responses-schema API (§3.4); OpenAI's own Responses API (and so its web search) reuses it, selected with `use: openai { api: responses }` |
| Q3 | **Kimi** `$web_search` is run as a bounded loop inside the provider (bound = `maxSearches`, default 3), returning one response; a test covers a model that never stops asking |
| Q4 | Citation `spans` stay **optional**; no assertion or Loom feature depends on them |
| Q5 | `lenient()` is **kept, default off** (it exists so one `Grounding` can be shared across providers in a router). Loom scripts have no `lenient` |
| Q6 | No dedicated "claims supported by cited sources" metric in this phase; reuse the RAG metrics with citation snippets as `retrievalContext` (§6) |
| Q7 | Part A builds **all five presets and the generic kind in one contract-tested sweep** (8a OpenAI first, 8b the rest; Perplexity on the Responses provider, 8b2) |
| Q8 | The generic kind supports a configurable **header name** and **static query parameters** only; no Azure-specific preset |
| Q9 | Part B begins (8d) with a throwaway script that prints each vendor's raw grounded response; the §4.3 table is revised from it before any Java for 8e/8f |

## 10. Scope for release 6.1.0

The release does not wait for everything in this phase. In priority order:

| Tier | Contents | Why |
|---|---|---|
| **Must** | Part A: 8a, 8b, 8b2, 8c (OpenAI, xAI, Kimi, Qwen, Perplexity via the Agent API, generic; Loom kinds; resolver; secrets; catalogs; docs) | The providers are independently useful and low risk: one shared class, proven by `ProviderContract` |
| **Should** | 8d (payload look), 8e (grounding contract: types, capability check, citations, telemetry, `searches` budget), 8f for Google, Anthropic and Perplexity | The grounding feature on the three vendors with the simplest, best-documented interfaces |
| **Stretch** | 8f for xAI, Kimi, Qwen and OpenAI's own Responses grounding; 8g (`ReActAgent.grounding`, Loom `grounding:`, eval4j assertions); 8c-live; 8h tooling | Can follow in 6.1.x |

Rules for the release:
- Grounding **does not ship without** its Java-API refusal checks (R6) and budget/telemetry (R11); a vendor that cannot meet R7–R9 is left out of the release, not released partially.
- Loom `grounding:` ships only **together with** its `weave check` rules and its audit rule (R10); if those are not ready the keyword is held back and grounding is documented as Java-API-only.
- A provider appears in the docs as supported only when its contract test passes on **recorded real-API fixtures** (hand-written fixtures mean "preview" in the docs).
