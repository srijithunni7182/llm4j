# OpenAI, xAI, Kimi, Qwen and any OpenAI-style server

> **Status (phase 8, sub-phases 8a to 8c done).** `OpenAiCompatibleProvider` and its dialects pass the shared provider contract
> (`ProviderContract` plus tools, reasoning text and odd-response checks) on wire formats written from each vendor's published API
> reference. **Supported** means the contract also passes on fixtures *recorded from the real API*; until those recordings are committed
> (sub-phase 8c-live) every provider on this page is **preview**, and the release notes say which are which. **Kimi and Qwen stay preview**
> until their keys are used once. Web grounding (provider-side search with citations) is *coming*; it is not on this page.

One provider, `OpenAiCompatibleProvider`, speaks the whole family that uses `POST …/chat/completions`. What differs between vendors is **data** in a
`Dialect`; what differs between one vendor's models is in `ModelTraits`. Adding the next vendor is a constant, not a class.

| Provider | Factory | Key | Base URL (override) | Status |
|---|---|---|---|---|
| OpenAI | `OpenAiCompatibleProvider.openai(cfg)` | `OPENAI_API_KEY` | `https://api.openai.com/v1` (`OPENAI_BASE_URL`) | preview until recorded |
| xAI (Grok) | `.xai(cfg)` | `XAI_API_KEY` | `https://api.x.ai/v1` (`XAI_BASE_URL`) | preview until recorded |
| Moonshot Kimi | `.kimi(cfg)` | `MOONSHOT_API_KEY` | `https://api.moonshot.ai/v1` (`MOONSHOT_BASE_URL`) | **preview** (documentation-derived fixtures) |
| Alibaba Qwen (DashScope) | `.qwen(cfg)` | `DASHSCOPE_API_KEY` | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` (`DASHSCOPE_BASE_URL`) | **preview** (documentation-derived fixtures) |
| Perplexity Orchestrator | `.of(Dialect.PERPLEXITY_ROUTER, cfg)` | `PERPLEXITY_API_KEY` | `https://api.perplexity.ai/router/v1` | **preview** (private preview at Perplexity; no live fixtures) |
| Any OpenAI-style server | `.of(Dialect.GENERIC, cfg)` | optional | **you give it** (`baseUrl`) | preview until recorded |
| Perplexity Agent API | `OpenAiResponsesProvider.perplexity(cfg)` | `PERPLEXITY_API_KEY` | see [Responses schema and Perplexity](Responses-Schema-and-Perplexity.md) | |

The Orchestrator is a gateway in private preview; the Agent API above is Perplexity's grounded path.

## OpenAI

```java
LLMConfig cfg = LLMConfig.builder().apiKey(apiKey).defaultModel("gpt-5-mini").build();
LLMClient openai = new DefaultLLMClient(OpenAiCompatibleProvider.openai(cfg));
```

`apiKey` is your key, read from `OPENAI_API_KEY` or a [secret store](Secret-Store.md). Every `LLMProvider` method works as on the other providers: `chat`,
`chatStream`, `supportsToolCalling`, `getProviderName`, `validate`. Tools are offered as functions, a refusal throws `ContentBlockedException`,
reasoning text is never part of `getContent()`, and a stream ends with one final chunk carrying the finish reason and the token usage (cached and
reasoning tokens are in the response metadata as `usage.cached_tokens` and `usage.reasoning_tokens`).

### Models that refuse settings

```java
LLMRequest request = LLMRequest.builder()
        .addUserMessage("Plan the migration.")
        .temperature(0.2)                                 // left out for a reasoning model, which rejects it
        .addParameter("reasoning_effort", "low")          // anything else the vendor accepts goes through
        .build();
LLMResponse answer = client.chat(request);
```

OpenAI's o-series and gpt-5 and later models (not the `-chat` variants) reject a custom `temperature`, `top_p` and `stop`, so those are **left out**
(logged at debug) instead of failing the call; the output limit is sent as `max_completion_tokens`. A model name nobody has heard of gets the
safe defaults for its vendor and never fails because a catalog does not list it. `addParameter` is the escape hatch for anything vendor-specific.

## xAI, Kimi and Qwen

```java
LLMClient grok = new DefaultLLMClient(OpenAiCompatibleProvider.xai(cfg2));
LLMClient kimi = new DefaultLLMClient(OpenAiCompatibleProvider.kimi(cfg3));     // preview
LLMClient qwen = new DefaultLLMClient(OpenAiCompatibleProvider.qwen(cfg4));     // preview, DashScope
```

What the dialects know (from each vendor's reference, read 2026-10-22):

- **xAI**: output limit `max_completion_tokens`; reasoning models (grok-4 and later, grok-3-mini) reject `stop`; reasoning text is `reasoning_content`. xAI now documents chat completions as *legacy* next to its Responses API.
- **Kimi**: newer models (K2.5 and later, K3) fix `temperature` and `top_p`, so they are not sent; K2.5 and K2.6 have a `thinking` switch, which is sent explicitly (on when a streamed call asks to see reasoning, off otherwise); K3 takes `reasoning_effort` through `addParameter`. Reasoning text that came with a tool call is kept in `providerData` and sent back with the tool result, as Kimi requires.
- **Qwen**: hybrid models have an `enable_thinking` switch, sent the same way; the documented base URL is now a workspace domain (`https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1`), so set `baseUrl` (or `DASHSCOPE_BASE_URL`) to it; the DashScope domain is the default and gets no new features after 2026-09-30.

## Any OpenAI-style server

```java
Dialect gateway = Dialect.GENERIC
        .withAuthHeader("api-key", "")                              // the header name some gateways want
        .withQueryParams(Map.of("api-version", "2025-01-01"));      // fixed query parameters
LLMConfig cfg = LLMConfig.builder().baseUrl(gatewayUrl).apiKey(apiKey).defaultModel("llama-3.3-70b").build();
LLMClient any = new DefaultLLMClient(OpenAiCompatibleProvider.of(gateway, cfg));
```

DeepSeek, Groq, Together, vLLM and LM Studio need only a `baseUrl` (and a key where they want one). The generic dialect also removes a leading
`<think>…</think>` block that some servers write into the answer, and reads reasoning from `reasoning_content` or `reasoning`. It supports a
header name and fixed query parameters, and nothing more: there is no Azure-specific preset.

## A new vendor is a constant

```java
Dialect acme = Dialect.builder("acme")
        .defaultBaseUrl("https://llm.acme.example/api")
        .maxTokensField("max_new_tokens")               // how this vendor names the output limit
        .keyEnv("ACME_API_KEY").hosts(List.of("llm.acme.example"))
        .extraRequest(Map.of("safe_mode", true))         // sent with every request
        .build();
```

`hosts` is where a stored secret for the vendor may be sent (see [Secret Store](Secret-Store.md)); `keyEnv` and `baseUrlEnv` are the variable names
configuration layers read.

## Picking the vendor from a model name

```java
Vendor.ofModel("gpt-5-mini");                          // OPENAI, sends "gpt-5-mini"
Vendor.ofModel("perplexity/openai/gpt-5.6-sol");       // PERPLEXITY, sends "openai/gpt-5.6-sol"
Vendor.ofModel("qwen3");                               // empty: it usually means a local Ollama model
```

An explicit prefix always works (`openai/`, `xai/`, `kimi/`, `qwen/`, `perplexity/`). A bare name works only when no other vendor could own it:
`gpt-*`, `o1`/`o3`/`o4`, `chatgpt-*` → OpenAI; `grok-*` → xAI; `kimi-*`, `moonshot-*` → Kimi. `gpt-oss` and `qwen3` are **not** guessed. Loom,
Spring and the other configuration layers use this one registry.

## Behaviour that is the same as every other provider

- Keys are never in an exception, a log line or an event; a stored secret is sent only to the hosts it is bound to, and the library refuses any other host before a request leaves.
- A rate limit carries when it resets (`Retry-After` and the `x-ratelimit-*` headers); the rate-limit report names the vendor (`openai`, `xai`, `kimi`, `qwen`, `perplexity`).
- Context windows for the current models of each vendor are in the seed catalog (`ContextWindows.defaults()`), each checked against the vendor's documentation; an unknown model is not an error.
- Prices are not shipped: load your own `PriceTable`.

See also [Providers and the Uniform Contract](Providers-and-the-Uniform-Contract.md) for what every provider guarantees.
