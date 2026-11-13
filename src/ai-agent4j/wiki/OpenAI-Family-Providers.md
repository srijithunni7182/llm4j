# OpenAI, xAI, Kimi, Qwen, Sarvam and any OpenAI-style server

> **Status (phase 8, sub-phases 8a to 8c-live done).** `OpenAiCompatibleProvider` and its dialects pass the shared provider contract
> (`ProviderContract` plus tools, reasoning text and odd-response checks). **Supported** means the contract also passes on responses *recorded
> from the real API* (each fixture states its date and model): **OpenAI and xAI are supported** (recorded 2026-10-22), and so is **Sarvam** (recorded 2026-11-12). **Kimi, Qwen, the
> Perplexity Orchestrator and the generic kind are preview**: their fixtures are written from documentation, or no third-party server was recorded.
> Web grounding (provider-side search with citations) needs the Responses API for OpenAI and xAI and is described in [Web-search grounding](Web-Search-Grounding.md); this page shows plain model access over chat completions.

One provider, `OpenAiCompatibleProvider`, speaks the whole family that uses `POST …/chat/completions`. What differs between vendors is **data** in a
`Dialect`; what differs between one vendor's models is in `ModelTraits`. Adding the next vendor is a constant, not a class.

| Provider | Factory | Key | Base URL (override) | Status |
|---|---|---|---|---|
| OpenAI | `OpenAiCompatibleProvider.openai(cfg)` | `OPENAI_API_KEY` | `https://api.openai.com/v1` (`OPENAI_BASE_URL`) | **supported** (recorded 2026-10-22, `gpt-6-luna`) |
| xAI (Grok) | `.xai(cfg)` | `XAI_API_KEY` | `https://api.x.ai/v1` (`XAI_BASE_URL`) | **supported** (recorded 2026-10-22, `grok-4.20-0309-non-reasoning`) |
| Moonshot Kimi | `.kimi(cfg)` | `MOONSHOT_API_KEY` | `https://api.moonshot.ai/v1` (`MOONSHOT_BASE_URL`) | **preview** (documentation-derived fixtures) |
| Sarvam AI (V2, with the Token Factory) | `.sarvam(cfg)` | `SARVAM_API_KEY` | `https://api.sarvam.ai` (`SARVAM_BASE_URL`); the path is `/v2/chat/completions` | **supported** (recorded 2026-11-12, `sarvam-105b` and `deepseekv4.1-flash-1017`) |
| Alibaba Qwen (DashScope) | `.qwen(cfg)` | `DASHSCOPE_API_KEY` | `https://dashscope-intl.aliyuncs.com/compatible-mode/v1` (`DASHSCOPE_BASE_URL`) | **preview** (documentation-derived fixtures) |
| Perplexity Orchestrator | `.of(Dialect.PERPLEXITY_ROUTER, cfg)` | `PERPLEXITY_API_KEY` | `https://api.perplexity.ai/router/v1` | **preview** (private preview at Perplexity; no live fixtures) |
| Any OpenAI-style server | `.of(Dialect.GENERIC, cfg)` | optional | **you give it** (`baseUrl`) | **preview** (the protocol is the one recorded for OpenAI and xAI; no third-party server recorded) |
| Perplexity Agent API | `OpenAiResponsesProvider.perplexity(cfg)` | `PERPLEXITY_API_KEY` | see [Responses schema and Perplexity](Responses-Schema-and-Perplexity.md) | **supported** (recorded 2026-10-22) |

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

> **Found against the real API (2026-10-22):** the gpt-6 models refuse *function tools* on chat completions unless `reasoning_effort` is `none`
> (`HTTP 400: Function tools with reasoning_effort are not supported for gpt-6-luna in /v1/chat/completions`). Either pass
> `.addParameter("reasoning_effort", "none")` on a request that offers tools, or use [OpenAI's Responses API](Responses-Schema-and-Perplexity.md#openais-own-responses-api).

### Structured output

A request can carry a `ResponseFormat` (a name and a JSON Schema whose root is an object). For OpenAI (gpt-4o and later, o1, o3 and o4) and xAI (the Grok 4 family) it is sent as `response_format: {type: "json_schema", json_schema: {name, strict, schema}}`, and the reply is held to the schema. The Responses API takes the same format flat under `text.format` (OpenAI, xAI), and Perplexity's Agent API as `response_format` with a nested `json_schema`. Anthropic and Ollama take it too, as described in [Providers and the uniform contract](Providers-and-the-Uniform-Contract.md). For any other model the field is left out, because support is recorded per model and an unknown model is never assumed to have it; ask `client.structuredOutput(model)` first. A strict schema needs every field in `required` and `additionalProperties: false`. The reply is still model output: a refusal or a reply cut at the token limit may not match, so check it. Sarvam's chat models take it too (`sarvam-105b`, GLM, Gemma, DeepSeek, Kimi and Qwen on Sarvam; checked live with `deepseekv4.1-flash-1017`). Kimi and Qwen are not switched on yet (preview vendors, no recorded fixtures), and the generic kind stays off unless its dialect says otherwise.

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

## Sarvam AI and its Token Factory

Sarvam's V2 chat completions serve its own `sarvam-105b` and the open-weight models of its [Token Factory](https://docs.sarvam.ai/api/token-factory/models) on the same key and credits: `glm5.3`, `glm5.3-flash`, `gemma4`, `deepseekv4.1-flash-1017` (and `-interactive`), and, for organisations with provisioned access, `deepseekv4-pro`, `kimi-k3.1-pro`, `qwen38-27b` and others. A provisioned model without access answers 404.

```java
LLMConfig cfg5 = LLMConfig.builder().apiKey(sarvamKey).defaultModel("sarvam-105b").build();
LLMClient sarvam = new DefaultLLMClient(OpenAiCompatibleProvider.sarvam(cfg5));
```

What the dialect knows (from Sarvam's reference, read 2026-11-12, and checked against the real API the same day):

- **Where and how**: the base URL is the host, `https://api.sarvam.ai`, and the version is in the path (`/v2/chat/completions`, `/v2/models`), so a `SARVAM_BASE_URL` set for the older `SarvamChatProvider` still works. The key goes in `api-subscription-key`; a wrong key is a 403, which reads as an `AuthenticationException`. The output limit is `max_tokens`, and reasoning counts against it.
- **Thinking**: `sarvam-105b`, the DeepSeek and the Qwen models think by default, so `reasoning_effort: "none"` is sent unless a streamed call asks to see reasoning. `gemma4` thinks only when asked, so it is sent `reasoning_effort: "low"` when a stream asks to see reasoning. GLM and Kimi always think and refuse `"none"`, so they are sent nothing. `sarvam-105b-conversations` is left alone. A different effort goes through `addParameter("reasoning_effort", "high")`, which wins over the switch. Reasoning text is `reasoning_content`.
- **Tools, pictures and schemas**: every chat model takes native tools and a JSON-Schema `response_format`. `gemma4`, `glm5.3-flash`, the DeepSeek flash models, `kimi-k3.1-pro` and `qwen38-27b` take pictures (as data, not URLs); `sarvam-105b`, `glm5.3` and `deepseekv4-pro` do not.
- **Not covered**: `clef` (the Decisions API), the embedding models, `x-session-affinity` and the Responses API. `SarvamChatProvider`, the V1 provider, is deprecated; speech, text-to-speech and translation stay on the other `Sarvam*` providers ([Sarvam guide](../docs/SARVAM.md)).

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

## Listing the models

```java
DescribableProvider provider = OpenAiCompatibleProvider.openai(cfg);
String[] ids = provider.listModels();               // GET /v1/models: ids, in the vendor's order
String first = provider.getFirstAvailableModel();   // the first id, or null when there are none
```

`OpenAiCompatibleProvider` and `OpenAiResponsesProvider` implement `DescribableProvider`, as `GoogleProvider`, `OllamaProvider` and `AnthropicProvider` do. The path is data in the dialect (`Dialect.modelsPath()`, `/models` for OpenAI, xAI, Kimi, Qwen and the generic dialect, so a vLLM or LM Studio server answers too, and `/v2/models` for Sarvam). **Perplexity has no such endpoint** (neither the Agent API nor the Orchestrator): `listModels()` throws an `InvalidRequestException` that says so, without a network call, rather than returning a list we made up. The older `SarvamChatProvider` is not describable; Sarvam through `OpenAiCompatibleProvider.sarvam` is. The result is what the vendor lists for your key, including models that are not for chat (embeddings, images, moderation): filter by the names you use. OpenAI and xAI listings were recorded from the real APIs on 2026-10-22 (`list-models.fixture`).

## Picking the vendor from a model name

```java
Vendor.ofModel("gpt-5-mini");                          // OPENAI, sends "gpt-5-mini"
Vendor.ofModel("perplexity/openai/gpt-5.6-sol");       // PERPLEXITY, sends "openai/gpt-5.6-sol"
Vendor.ofModel("sarvam/glm5.3");                       // SARVAM, sends "glm5.3"
Vendor.ofModel("qwen3");                               // empty: it usually means a local Ollama model
```

An explicit prefix always works (`openai/`, `xai/`, `kimi/`, `qwen/`, `sarvam/`, `perplexity/`). A bare name works only when no other vendor could own it:
`gpt-*`, `o1`/`o3`/`o4`, `chatgpt-*` → OpenAI; `grok-*` → xAI; `kimi-*`, `moonshot-*` → Kimi. `gpt-oss`, `qwen3` and a bare `sarvam-105b` or `glm5.3` are **not** guessed. Loom,
Spring and the other configuration layers use this one registry.

## Behaviour that is the same as every other provider

- Keys are never in an exception, a log line or an event; a stored secret is sent only to the hosts it is bound to, and the library refuses any other host before a request leaves.
- A rate limit carries when it resets (`Retry-After` and the `x-ratelimit-*` headers); the rate-limit report names the vendor (`openai`, `xai`, `kimi`, `qwen`, `sarvam`, `perplexity`).
- Context windows for the current models of each vendor are in the seed catalog (`ContextWindows.defaults()`), each checked against the vendor's documentation; an unknown model is not an error.
- Prices are not shipped: load your own `PriceTable`.

See also [Providers and the Uniform Contract](Providers-and-the-Uniform-Contract.md) for what every provider guarantees.
