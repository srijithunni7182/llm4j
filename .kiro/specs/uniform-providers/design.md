# Design Document

## Principle: put uniformity in shared code, not in each provider

Most of the contract is met in **three shared places**, so each provider changes only a few lines:

```
                 ┌──────────── shared (new or extended) ─────────────┐
LLMProvider ───► │ FinishReason.fromValue      synonyms → R2.2        │
 (Google,        │ HttpClientWrapper           typed errors → R3.1/3  │
  Ollama,        │   .stream(...) → SseEvents / JsonLines   → R4      │
  Sarvam,        │ Providers.typed(name, msg, e)  no re-wrap → R3.2   │
  Anthropic)     │ LLMProvider.chatStream default       → R4.5        │
                 └────────────────────────────────────────────────────┘
provider-specific: request JSON, response JSON, event → chunk mapping
```

## 1. Finish reasons: extend `FinishReason.fromValue`

Only the lookup changes; the enum values stay the same.

| Provider value(s) | `FinishReason` |
|---|---|
| `stop`, `end_turn`, `stop_sequence`, `STOP`, `FINISH_REASON_STOP` | `STOP` |
| `length`, `max_tokens`, `MAX_TOKENS` | `LENGTH` |
| `content_filter`, `refusal`, `SAFETY`, `RECITATION`, `BLOCKLIST`, `PROHIBITED_CONTENT`, `SPII` | `CONTENT_FILTER` |
| `tool_calls`, `tool_use`, `function_call` | `TOOL_CALLS` |
| `error`, `MALFORMED_FUNCTION_CALL` | `ERROR` |

Matching is case-insensitive and exact; anything else is `UNKNOWN`, as today. Anthropic's `pause_turn`
maps to `STOP`; `model_context_window_exceeded` maps to `LENGTH`.

Providers also put the raw value in `metadata.finish_reason_raw`: one line each, in the builder call they
already make.

## 2. Typed errors in `HttpClientWrapper`

Where the wrapper throws today, it throws the typed subclass instead. Every type is still an
`LLMException` with the same status code.

| Status | Today | New |
|---|---|---|
| 401, 403 | `LLMException(msg, status)` | `AuthenticationException` |
| 400, 404, 413, 422 | same | `InvalidRequestException` |
| 429 | `RateLimitException` | unchanged |
| 500, 502, 503, 504, 529 (after retries) | `LLMException` | `ServiceUnavailableException(provider, msg, status)` |
| other | `LLMException` | unchanged |

- **Constructors.** `AuthenticationException` and `InvalidRequestException` gain `(String, Integer
  status)` constructors. They are additive.
- **Response body.** `LLMException` gains a nullable `responseBody` (additive constructor and getter), so
  providers can read the error JSON without scraping message text.
- **Messages.** The wrapper extracts a provider message from common error shapes: `{"error":{"message"}}`
  (Google, Anthropic, OpenAI-style), `{"error":"…"}` (Ollama) and `{"message"}`. The message format is
  `"<provider> <status> <type>: <message> (request-id …)"`, using `request-id`, `x-request-id` or
  `x-goog-request-id` when present. Header values are never echoed, so no keys.
- **Retries.** `RetryPolicy.defaults()` adds 502, 503, 504 and 529 if they're missing. It's a builder
  default, so explicit policies are untouched.

**No re-wrapping.** A new static helper, `io.github.llm4j.provider.Providers`:

```java
/** Keeps typed failures as they are; wraps anything else as today. */
static RuntimeException typed(String provider, String message, Exception e)
```

It returns `e` itself when `e` is `AuthenticationException`, `InvalidRequestException`,
`RateLimitException` or a `ProviderException`, and `new ProviderException(provider, message, e)`
otherwise. Each provider's existing

```java
} catch (IOException | LLMException e) {
    throw new ProviderException(getProviderName(), "Failed to process chat request", e);
```

becomes `throw Providers.typed(getProviderName(), "Failed to process chat request", e);`. That's one line
per catch: 8 catch sites in 5 files.

## 3. Streaming: shared readers in the HTTP layer

`HttpClientWrapper` gains:

```java
/** Opens a streaming POST. Non-2xx responses throw the typed errors of §2 (after retries: the
 *  request isn't consumed until a 2xx arrives). The returned body is closed when the stream closes. */
public StreamingBody stream(String url, String jsonBody, Headers headers)
```

`StreamingBody` has two views:

- `events()` → `Stream<SseEvent(name, data)>`: server-sent events, supporting multi-line `data:` and
  ignoring comments and keep-alives;
- `lines()` → `Stream<String>`: newline-delimited JSON.

Both are lazy and close the OkHttp response on close or exhaustion. `createStreamingCall` stays as it is.

**Chunk building.** Each provider maps its events to chunks with the shared `StreamChunks` builder:
`text(String)` for a text chunk, `finish(raw, usage, model)` for the final chunk, which also sets
`finish_reason_raw`. That keeps R4.2's final-chunk rule identical across providers.

| Provider | Endpoint | Events → chunks |
|---|---|---|
| Google | `…/models/{m}:streamGenerateContent?alt=sse` | Each `data:` is a `GenerateContentResponse`. Emit the text of its parts, skipping `thought: true` parts. `finishReason` and `usageMetadata` arrive on the last event, which becomes the final chunk. |
| Anthropic | `/v1/messages` with `"stream": true` | `message_start` gives the input usage and model. `content_block_delta` with `text_delta` becomes a text chunk. `message_delta` gives `stop_reason` and output usage. `message_stop` becomes the final chunk. `error` throws (as in §2). `ping` and thinking deltas are ignored. |
| Sarvam | `/v1/chat/completions` with `"stream": true` | OpenAI-style: `choices[0].delta.content` becomes a text chunk; `finish_reason` plus a `usage` event (or the last event) becomes the final chunk; `[DONE]` ends the stream. |
| Ollama | `/api/chat` with `"stream": true` | NDJSON lines: `message.content` becomes a text chunk; the line with `done: true` becomes the final chunk (`done_reason`, `prompt_eval_count`, `eval_count`). |

**Default `chatStream`.** `LLMProvider.chatStream` becomes a `default` method: `chat()` split into one
text chunk and one final chunk. The four providers here override it with native streaming. The interface
change is source- and binary-compatible: implementers that already override it are unaffected.

## 4. Google: native system instruction

In `GoogleProvider.buildRequestJson`, the joined system text goes into
`"systemInstruction": {"parts": [{"text": …}]}` instead of being prepended to the first user message.
That's about 10 lines. Consecutive same-role turns are merged, since Gemini requires alternation.

## 5. Anthropic provider

This is the design from the earlier draft, now expressed through the shared pieces:

- **Classes.** `provider/anthropic/AnthropicProvider` plus `AnthropicModels.acceptsSampling(model)`, an
  allowlist:
  - `claude-haiku-4-5`;
  - `claude-sonnet-4-6`, `claude-opus-4-6`;
  - `claude-*-4-5`, `claude-*-4-1`, `claude-opus-4`, `claude-sonnet-4`;
  - `claude-3*`.

  Anything else is treated as not accepting sampling parameters.
- **Request**:
  - `model`;
  - `max_tokens` (16,000 by default; 64,000 when streaming);
  - `system` (joined);
  - `messages` (merged by role);
  - `temperature` and `top_p` when allowed;
  - `stop_sequences`;
  - `output_config.effort` when set;
  - `stream`.

  `thinking` is never sent.
- **Response**:
  - text blocks joined; thinking blocks skipped;
  - usage: prompt = `input_tokens` + `cache_creation_input_tokens` + `cache_read_input_tokens`;
  - `refusal` → `ContentBlockedException("anthropic", "refused (<category>): <explanation>")`.
- **Retry policy**: the §2 defaults (they include 529).

## 6. Loom

- `DefaultLLMClientFactory`: `Kind.ANTHROPIC` (`anthropic/…`, `claude-…`), `ANTHROPIC_API_KEY`,
  `ANTHROPIC_BASE_URL`, and `problem()` reporting a missing key.
- `ProviderSpec.KINDS` gains `anthropic`, and `forProvider` handles it.
- Docs.

## 7. Conformance suite

`src/test/java/io/github/llm4j/provider/contract/ProviderContract.java`, an abstract JUnit 5 class:

```java
abstract class ProviderContract {
    protected abstract LLMProvider provider(String baseUrl);   // pointed at MockWebServer
    protected abstract Fixtures fixtures();                    // this provider's wire formats
    // shared @Test methods: C1…C12 (see verification.md)
}
```

`Fixtures` holds this provider's canned responses:

- `answer(text, inTokens, outTokens)`;
- `truncated()`;
- `refusal(category)`;
- `error(status, type, message)`;
- `rateLimited(resetHeaders)`;
- `stream(textPieces…)`;
- `streamError()`;
- the provider's expected request JSON, to check system and sampling handling.

Four subclasses (`GoogleContractTest`, `AnthropicContractTest`, `OllamaContractTest`,
`SarvamContractTest`) supply fixtures only; they contain no assertions of their own.

**Live.** `provider/contract/LiveProviderContract` is parameterised over `(provider, model)` pairs built
from whichever credentials are present, running the live scenarios (L1–L10) with the same assertions.
`LiveSwitchingTest` runs one ReAct task across every available pair.

- **Build.** Surefire gets `<excludedGroups>live</excludedGroups>` in ai-agent4j and Loom. A `live`
  profile swaps it for `<groups>live</groups>`.
- **Budget.** Each provider's live run is wrapped in `BudgetedLLMClient` with a 60,000-token `Budget`.

## Change summary per existing file

| File | Change | Size |
|---|---|---|
| `LLMResponse.FinishReason` | synonyms in `fromValue` | ~20 lines |
| `LLMException` | `responseBody` field and constructor | ~10 lines |
| `AuthenticationException`, `InvalidRequestException` | status constructors | ~6 lines |
| `ServiceUnavailableException` | new | ~10 lines |
| `HttpClientWrapper` | typed throws, message extraction, `stream(...)` | ~120 lines |
| `RetryPolicy.defaults()` | add 502, 503, 504, 529 | ~4 lines |
| `LLMProvider` | `chatStream` becomes a default method | ~15 lines |
| `GoogleProvider` | catch lines, system instruction, raw finish reason, `chatStream` | ~70 lines |
| `OllamaProvider` | catch lines, raw finish reason, `chatStream` | ~45 lines |
| `SarvamChatProvider` | catch line, raw finish reason, `chatStream` | ~45 lines |
| Sarvam TTS, STT, text providers | catch lines only | 3 lines |
| Loom `DefaultLLMClientFactory`, `ProviderSpec` | anthropic kind | ~25 lines |

No changes are needed in `ReActAgent`, `BudgetedLLMClient`, `RoutingLLMClient`, Loom's executor,
eval4j or GetViral.
