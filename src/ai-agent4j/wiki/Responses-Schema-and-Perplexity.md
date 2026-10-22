# The Responses schema, and Perplexity's Agent API

> **Status (phase 8, sub-phases 8b2 and 8c-live done).** `OpenAiResponsesProvider` passes the shared provider contract, and its responses
> recorded from the real APIs on 2026-10-22 (plain, streamed, tool call and answer, streamed tool call, reasoning) replay through it: the Perplexity
> Agent API (`openai/gpt-6-luna`) and OpenAI's Responses API (`gpt-6-luna`) are **supported**. Web grounding (typed citations and search counts) is
> built on the same classes and described in [Web-search grounding](Web-Search-Grounding.md); xAI's Responses API is the third dialect, supported for grounding.

Some APIs have moved from *messages in, choices out* to the OpenAI **Responses** schema: `instructions` and an `input` list in, `output[]` items out,
typed server-sent events when streaming. `OpenAiResponsesProvider` speaks it, with a `ResponsesDialect` for what differs:

| Provider | Factory | Endpoint | Key |
|---|---|---|---|
| Perplexity Agent API | `OpenAiResponsesProvider.perplexity(cfg)` | `POST https://api.perplexity.ai/v1/agent` (alias `/v1/responses`) | `PERPLEXITY_API_KEY` |
| OpenAI Responses API | `OpenAiResponsesProvider.openai(cfg)` | `POST https://api.openai.com/v1/responses` | `OPENAI_API_KEY` |
| xAI Responses API | `OpenAiResponsesProvider.xai(cfg)` | `POST https://api.x.ai/v1/responses` | `XAI_API_KEY` |

## Perplexity

```java
LLMConfig cfg = LLMConfig.builder().apiKey(apiKey).defaultModel("openai/gpt-5.6-sol").build();
LLMClient perplexity = new DefaultLLMClient(OpenAiResponsesProvider.perplexity(cfg));
```

One Perplexity key reaches OpenAI, Anthropic, Google and xAI models. Models are `creator/model` slugs (`openai/gpt-5.6-sol`,
`anthropic/claude-sonnet-5-5`), and a **preset** (a bundle of model, tools and limits) is named `preset:medium`: it is sent as `preset`, not as a model.
Without a tool in the request the Agent API is plain model access: nothing searches the web unless you ask for it (grounding is opt-in everywhere in llm4j: add `.grounding(Grounding.webSearch())` to the request, see [Web-search grounding](Web-Search-Grounding.md)).

What the dialect does for you:

- System messages become `instructions`; the turns become `input` items.
- Perplexity requires `max_output_tokens` for `anthropic/*` models; the provider sends 8,000 when you set none.
- The rules of the model behind the slug apply: an `openai/gpt-5…` slug is not sent a custom `temperature`.
- Stop sequences are not part of the Responses schema and are left out.
- A tool turn is replayed correctly: the response's `function_call` items are kept in `providerData` and sent back with the tool result (OpenAI's reasoning items too, which its reasoning models require).
- `previous_response_id` goes through `addParameter`; then only the messages after the last answer are sent, and the response id is in the metadata as `response_id`.
- Streaming reads `response.output_text.delta`, reasoning-summary and function-call-argument events, and ends on `response.completed`. A stream that ends before it, or a `response.failed`, is a `ProviderException`.

## OpenAI's own Responses API

`OpenAiResponsesProvider.openai(cfg)` is the same class with OpenAI's dialect: reasoning models take `reasoning` configuration through `addParameter`
(`Map.of("effort", "low")`), and a streamed call that asks to see reasoning requests summaries. In a Loom script it is `use: openai  api: responses` (and `use: xai  api: responses` for xAI's).

## Perplexity's other APIs

The **Orchestrator API** (open-weight models over chat completions, `https://api.perplexity.ai/router/v1`) is in private preview and is served by
[`Dialect.PERPLEXITY_ROUTER`](OpenAI-Family-Providers.md) as a preview. The **Decisions API** is a classifier backend, not a chat provider; see
[Classification](Classification.md).
