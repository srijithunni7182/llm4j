# Phase 8 design: more providers, then native grounding (ai-agent4j, Loom, eval4j)

Status: **draft, awaiting review**. Layers: ai-agent4j (providers, the grounding contract), Loom (provider kinds, a `grounding` option, audit), eval4j
(citation assertions). Companion documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [roadmap](../README.md).
Builds on the provider SPI and `ProviderContract` tests, the model-window catalog ([Phase 1](../01-context-management/DESIGN.md)), call telemetry
([Phase 2](../02-call-telemetry/DESIGN.md)) and Loom's provider declarations ([Phase 3](../03-loom-integration/DESIGN.md)).
Independent of Phase 7 (classification, on branch `feature/classifier-jev`); the two can proceed in either order.

The work is in two parts, in this order: **Part A** adds the providers (OpenAI, xAI, Kimi, Qwen, Perplexity, and a generic OpenAI-compatible one);
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
- G2. First-class presets for OpenAI, xAI, Kimi, Qwen and Perplexity, plus a generic `openai_compatible` kind with a `base_url`, wired through the
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
LLMClient perplexity = new DefaultLLMClient(OpenAiCompatibleProvider.perplexity(cfg5));
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
| `tools` | whether native tool calling is offered | yes for OpenAI, xAI, Kimi, Qwen; **no** for Perplexity: `supportsToolCalling()` is false |
| `usage` | how token usage and any extra counts appear | cached-token and reasoning-token details are read when present |
| `extraRequest`, `extraResponse` | vendor fields added to the request or read from the response | Qwen's thinking switch; Perplexity's `citations`/`search_results` (Part B) |
| `keyEnv`, `baseUrlEnv`, `hosts` | environment names for key and URL; hosts a secret may be sent to | `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `api.openai.com` |

Model-specific differences inside a vendor (an OpenAI reasoning model vs a classic one) are decided by a small, tested `ModelTraits` lookup beside
the dialect, driven by name patterns and overridable with `LLMConfig`/request parameters, so that an unknown new model degrades to the safe default
rather than failing.

### 3.3 Wiring

| Place | Change |
|---|---|
| **Loom `provider` kinds** | `ProviderSpec.KINDS` gains `openai`, `xai`, `kimi`, `qwen`, `perplexity`, `openai_compatible`. `provider Box { use: openai_compatible  base_url: "http://gpu:8000/v1"  api_key: env.KEY }` works like the existing kinds |
| **Model-name resolver** (`DefaultLLMClientFactory`) | Explicit prefixes always work: `openai/gpt-5-mini`, `xai/grok-4`, `kimi/kimi-k2`, `qwen/qwen3-max`, `perplexity/sonar`. Bare names resolve only when unambiguous: `gpt-*`, `o1*/o3*/o4*`, `chatgpt-*` → openai; `grok-*` → xai; `kimi-*`, `moonshot-*` → kimi; `sonar*` → perplexity. A bare `qwen3` stays unresolved (it means a local Ollama model to most people); use `qwen/…` or `ollama/…`. The "unknown model" message lists the new forms |
| **Keys** | `OPENAI_API_KEY`, `XAI_API_KEY`, `MOONSHOT_API_KEY`, `DASHSCOPE_API_KEY`, `PERPLEXITY_API_KEY`, and `*_BASE_URL` for each; looked up in the secret store first, as for existing keys. A stored secret keeps the **host binding** it was stored with, and the presets' `hosts` are what `weave` offers when a secret is created for a vendor |
| **Catalogs** | `windows.properties` (context windows) and `PriceTable`/`Pricing` (prices) get entries for the common models of each vendor. An unknown model is not an error: windows fall back to a configured default and prices to "unknown", as today |
| **Rate-limit reports** | `HttpClientWrapper.providerOf` maps the new hosts to their names |
| **Provider tiers / routing** | The new providers can be used in `routing` exactly like the others; no new tier logic |
| **`weave audit`** | The new hosted vendors are listed as data flows, as Gemini and Anthropic are |

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

public record Citation(int index, String url, String title, String snippet, List<Span> spans) { }   // spans may be empty
public record Span(int start, int end) { }             // character range in the answer this source supports

public record SearchUsage(int count, List<String> queries, Double estimatedCostUsd) { }   // queries only at the detail level that allows text

public enum GroundingSupport { NONE, NATIVE, ALWAYS_ON }    // ALWAYS_ON: the provider grounds every answer (Perplexity)
```

`LLMProvider` gains `default GroundingSupport groundingSupport() { return NONE; }` and, to keep the SPI additive, grounding is read from
`LLMRequest.getGrounding()` (null when not asked for). `LLMResponse` gains `getCitations()` (never null; empty when none) and `getSearches()`
(null when no search ran). Streaming delivers citations and search usage in the **final chunk**, like usage and finish reason today.

### 4.3 How each vendor is mapped

This table is the plan, to be **re-verified against each vendor's current documentation at the start of the sub-phase that builds it** (Q1). The
vendors change these interfaces often, and this draft was written from memory.

| Provider | Mechanism | Maps `maxSearches` | Maps domains / recency / country | Citations come from | Notes |
|---|---|---|---|---|---|
| Google | the `google_search` tool on the request | not offered natively: refused unless `lenient` | no | `groundingMetadata` (sources and the spans they support) | Existing `GoogleProvider` gains it. Tool calling and grounding in one request may be limited per model: checked at build time |
| Anthropic | the server-side web search tool | `max_uses` | allowed/blocked domains; user location | citations on text blocks | Existing `AnthropicProvider` gains it; the tool-result blocks must be preserved in `providerData` so a continued turn works |
| Perplexity | always on | not offered: refused unless `lenient` | `search_domain_filter`, `search_recency_filter`, region | `citations` / `search_results` | `ALWAYS_ON`. `Grounding` on this provider only tunes it. Without `grounding(...)` the answer is still grounded and citations are still returned |
| OpenAI | the web search tool, which is in its Responses API, not in classic chat completions | per-tool limit if offered | domains and location if offered | annotations on the output text | Needs an `OpenAiResponsesProvider` (Q2) |
| xAI | provider-side search | per vendor | per vendor | per vendor | Interface has been changing: checked at build time |
| Kimi | the built-in `$web_search` function, which the client must round-trip as a tool call | via the loop's iteration cap | no | tool-call results | A client-driven loop inside the provider: a request is one logical call to the caller |
| Qwen | a request flag (`enable_search`) and search options | strategy option | no | `search_info` in the response | Behaviour differs between regions |
| Sarvam, Ollama | none | | | | `groundingSupport() == NONE` |

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
- **Grounding**: `agent Researcher { model: "perplexity/sonar"  grounding: web }`, and with options:
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

## 9. Open questions

| # | Question | Recommendation |
|---|---|---|
| Q1 | Vendor interfaces (every row of §4.3, the dialect fields in §3.2) are from memory and move often | The first task of every sub-phase is to read the vendor's current API reference and update this spec's table; fixtures are recorded from the real API once a key is available. If a vendor's docs are unreachable from the build environment, that vendor's row waits |
| Q2 | OpenAI's web search tool and newest features live in its Responses API, not in chat completions | Part A ships OpenAI on chat completions. Grounding for OpenAI is built as `OpenAiResponsesProvider` in Part B (it shares the dialect's auth/usage code), selectable with `use: openai { api: responses }`; `openai` without it stays on chat completions |
| Q3 | Kimi's `$web_search` needs the client to answer a tool call before the answer exists | The provider runs that loop internally, bounded by `maxSearches`, and returns one response; a test covers a model that never stops asking |
| Q4 | Citation `spans` are rich on Google and Anthropic, absent on some others | Keep `spans` optional; eval4j assertions must not depend on them |
| Q5 | Is `lenient()` needed, or is refusing always better? | Keep, default off. It exists so one `Grounding` can be shared across providers in a router |
| Q6 | eval4j: a dedicated "claims supported by cited sources" metric in this phase? | Not in this phase; reuse the RAG metrics (§6), design the metric once the citation shapes are real |
| Q7 | Part A scope: all five presets at once, or OpenAI first? | All five in one contract-tested sweep: the dialect is where the risk is, and the fixtures are cheap |
| Q8 | Generic `openai_compatible` auth variants (header name, query parameter) | Support a configurable header name and static query parameters only |
| Q9 | Should the Phase 7 spike-style quick look (a throwaway script against a real vendor) precede the Java work for grounding too? | Yes for Part B: a short script that calls each vendor's grounded API and prints the raw responses, so the citation mapping is built from real payloads |
