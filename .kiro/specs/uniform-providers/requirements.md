# Requirements Document

## Introduction

A consumer of ai-agent4j should be able to switch between Gemini, Claude, Ollama and Sarvam, and later
OpenAI, by changing a model name, and nothing else in their code. Today that isn't true:

| Provider | Streaming | System prompt | Finish reasons | Errors |
|---|---|---|---|---|
| Google | throws | glued onto the first user message | raw values, some of which read as `UNKNOWN` | a generic `ProviderException` wrapping everything |
| Ollama | throws | own format | raw values | same |
| Sarvam | throws | own format | raw values | same |
| Anthropic | doesn't exist yet | — | — | — |

This spec:

- defines one **provider contract**;
- enforces it with a **shared conformance suite**, run against mocks in every build and against the real
  APIs with the user's keys;
- brings the three existing providers up to it;
- adds **Anthropic** as the first provider built to it from the start.

**Constraint from the user: existing code must not have to change drastically.** So:

- the public types stay as they are: `LLMClient`, `LLMProvider`, `LLMRequest`, `LLMResponse`,
  `FinishReason` and the exception classes;
- every change to them is additive: new enum synonyms, a new exception subclass, a default method, new
  metadata keys;
- existing providers get small, local edits, not rewrites;
- code that compiles today still compiles, and `catch (LLMException e)` still catches everything it caught
  before.

Out of scope: an OpenAI provider (a later spec that only has to pass the same suite), native tool-use
APIs, images and PDFs, batches, prompt caching, and cloud-platform variants (Bedrock, Vertex, Foundry).

---

## Glossary

| Term | Meaning |
|---|---|
| **Contract** | The behaviour every `LLMProvider` must have (R1–R5). |
| **Conformance suite** | Tests written once against `LLMProvider`. Each provider runs them with its own canned wire responses (mock) or its real API (live). |
| **Chunk** | One `LLMResponse` from `chatStream`. |
| **Final chunk** | The last chunk: empty text, plus the finish reason and token usage. |
| **Raw finish reason** | The provider's own value (`end_turn`, `MAX_TOKENS`, `done_reason`…), kept in metadata as `finish_reason_raw`. |
| **Live suite** | Conformance and scenario tests against real APIs. Tagged `live`, skipped unless the provider's key is set, and never part of a default build. |

---

## Requirements

### R1: Requests mean the same everywhere

**User Story:** As a consumer, I want the same `LLMRequest` to have the same meaning for every provider.

#### Acceptance Criteria

1. **System prompt.** System messages SHALL reach the model as the provider's native system instruction
   where it has one:
   - Gemini: `systemInstruction`;
   - Anthropic: `system`;
   - Sarvam and Ollama: `system` role messages.

   Several system messages SHALL be joined, in order.
2. **Turn order.** User and assistant turns SHALL keep their order. Consecutive turns with the same role
   SHALL be merged where the provider requires alternation (Gemini, Anthropic).
3. **Generation settings.** `maxTokens`, `temperature`, `topP` and `stopSequences` SHALL be passed as the
   provider's equivalents.
   - A setting a provider or model doesn't accept SHALL be **left out and logged at debug**, never sent
     so as to cause a 400. For example, Claude models that reject `temperature`.
   - A provider that requires `max_tokens` (Anthropic) SHALL default it: 16,000 for chat, 64,000 for
     streaming.
4. **Model.** `LLMRequest.model` SHALL override the provider's default model.

### R2: Responses mean the same everywhere

#### Acceptance Criteria

1. **Content.** `content` SHALL be the model's answer text only, with no reasoning or thinking text.
2. **Finish reason.** `finishReason` SHALL be one of the existing `FinishReason` values, never `UNKNOWN`
   for a documented provider value:
   - `STOP`: natural end, or a stop sequence;
   - `LENGTH`: hit `maxTokens`;
   - `CONTENT_FILTER`: safety or refusal;
   - `TOOL_CALLS`;
   - `ERROR`.

   The raw value SHALL be kept in `metadata.finish_reason_raw`.
3. **Token usage.** `tokenUsage` SHALL be set whenever the provider reports it. Prompt tokens SHALL include
   cached input tokens, where the provider reports them.
4. **Model.** `model` SHALL be the model that actually answered.

### R3: Failures look the same everywhere

#### Acceptance Criteria

1. **One set of exceptions.** Every provider SHALL throw the same exception types, all subclasses of
   `LLMException` as today:

   | Situation | Exception |
   |---|---|
   | Bad or missing key (401, 403) | `AuthenticationException` |
   | Bad request (400, 404, 413, 422) | `InvalidRequestException`, with the provider's own error message |
   | Rate limited (429, after inline retries) | `RateLimitException`, with `RateLimitInfo` (unchanged) |
   | Temporarily unavailable (500, 502, 503, 504, 529, after retries) | **new** `ServiceUnavailableException extends ProviderException` |
   | Refused by safety | `ContentBlockedException` (exists), naming the provider's category when it gives one |
   | Anything else | `ProviderException` (as today) |

2. **Not re-wrapped.** A provider SHALL NOT wrap one of these typed exceptions in a generic
   `ProviderException`. Today every provider does, which hides the type.
3. **Retries.** Retryable statuses (429, 500, 502, 503, 504, 529) SHALL be retried with backoff, and
   `retry-after` SHALL be honoured. This is the existing `HttpClientWrapper` behaviour, plus 529.
4. **No secrets.** Exception messages SHALL include the HTTP status and the provider's request id when
   present, and SHALL never include an API key.

### R4: Streaming works everywhere

**User Story:** As a consumer, I want `chatStream` to work with every provider, and to behave the same.

#### Acceptance Criteria

1. **Every provider streams.** Google, Anthropic, Ollama and Sarvam SHALL stream with the provider's
   native streaming:
   - Gemini: `streamGenerateContent?alt=sse`;
   - Anthropic and Sarvam: server-sent events;
   - Ollama: newline-delimited JSON.
2. **Chunk semantics.**
   - Zero or more text chunks, each carrying only new text.
   - Then exactly one final chunk, with empty text, the finish reason (R2.2) and the token usage (R2.3).
   - Joined, the text SHALL equal what `chat` would return for the same completion.
3. **Laziness.** The stream SHALL be lazy: no network read until it is consumed. Closing it SHALL release
   the connection.
4. **Errors.**
   - Before the first chunk, an error SHALL throw R3 exceptions from `chatStream` itself or from the
     first read.
   - Mid-stream, a provider error event SHALL end the stream with the matching R3 exception.
5. **Default for providers without streaming.** `LLMProvider.chatStream` SHALL gain a **default
   implementation**: `chat()` returned as one text chunk plus a final chunk. A future provider without
   native streaming still conforms. Existing custom `LLMProvider` implementations keep compiling, and any
   that currently throw keep their own behaviour until they opt in.
6. **Budgets.** `BudgetedLLMClient` SHALL meter streams from the final chunk's usage. This is today's
   behaviour; the conformance suite checks it.

### R5: Anthropic

#### Acceptance Criteria

1. **Provider class.** `AnthropicProvider` SHALL talk to `POST /v1/messages` over the existing
   `HttpClientWrapper`, with headers `x-api-key`, `anthropic-version: 2023-06-01` and a JSON content type.
   There is no SDK and no new dependency.
2. **Contract.** It SHALL meet R1–R4, including:
   - `temperature` and `top_p` only for models that accept them. An allowlist is used, so unknown future
     models are treated as not accepting them;
   - thinking blocks skipped;
   - `stop_reason: refusal` → `ContentBlockedException` with the category;
   - `overloaded_error` (529) → `ServiceUnavailableException` after retries.
3. **Effort.** An optional `effort` (provider-level, or per request via `additionalParameters.effort`)
   SHALL be sent as `output_config.effort`.
4. **Loom.**
   - `model: "anthropic/<model>"` and bare `claude-…` names SHALL resolve with `ANTHROPIC_API_KEY`
     (`ANTHROPIC_BASE_URL` optional).
   - `provider X { use: anthropic … }` SHALL work.
   - A missing key SHALL be a load error.

### R6: Proof

#### Acceptance Criteria

1. **Mock conformance.** One abstract conformance suite SHALL run for each of the four providers against
   MockWebServer, using that provider's recorded wire formats, in every build.
2. **Live conformance.** The same contract SHALL run live for each provider whose credentials are present:
   - Anthropic: `ANTHROPIC_API_KEY`;
   - Gemini: `GEMINI_API_KEY`;
   - Sarvam: `SARVAM_API_KEY`;
   - Ollama: `OLLAMA_BASE_URL` reachable.

   Each provider has a model list (`ANTHROPIC_TEST_MODELS`, `GEMINI_TEST_MODELS`, …). The live suite:
   - is tagged `live`;
   - never runs in a default build;
   - runs with `-Plive`;
   - runs under a token budget per provider.
3. **Switching test.** One test SHALL run the same `ReActAgent` task (a calculator question) across every
   available provider. It SHALL pass with no provider-specific code.
4. **Honest status.** Results SHALL be recorded per provider, with models and date. A provider not yet
   run live is marked *mock-verified only*.

### R7: Compatibility

#### Acceptance Criteria

1. **Public API.** No public signature SHALL change or be removed. Additions only:
   - `FinishReason.fromValue` synonyms;
   - `ServiceUnavailableException`;
   - a default `LLMProvider.chatStream`;
   - `LLMException.getResponseBody()`;
   - new `HttpClientWrapper` streaming helpers.
2. **Tests and callers.** Every existing test SHALL pass. Where an existing test asserted the old
   behaviour, the test is updated and the change is listed in the results:
   - `UnsupportedOperationException` from `chatStream`;
   - a generic `ProviderException` where a typed one is now thrown;
   - a raw finish reason.

   The Loom, eval4j and GetViral callers SHALL need no changes.
3. **Change size.** Per existing provider, the diff SHALL be confined to:
   - the error `catch` lines;
   - finish-reason metadata;
   - Google's system instruction;
   - a new `chatStream` using the shared helpers.
