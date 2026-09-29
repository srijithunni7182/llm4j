# Providers and the Uniform Contract

ai-agent4j talks to **Google Gemini, Anthropic Claude, Sarvam and Ollama** behind one interface. Switch
between them by changing the provider or the model name; the rest of your code stays the same:

```java
LLMClient client = new DefaultLLMClient(new GoogleProvider(
        LLMConfig.builder().apiKey(System.getenv("GEMINI_API_KEY")).defaultModel("gemini-2.5-flash").build()));
// …or SarvamChatProvider / OllamaProvider / AnthropicProvider: nothing below changes

AgentResult result = ReActAgent.builder().llmClient(client).addTool(new CalculatorTool()).build()
        .run("What is 1234 * 5678?");
```

That works because every provider meets the same **contract**, and the build checks it: one shared
conformance suite runs against each provider.

## The contract

### Requests mean the same everywhere

- **System prompts** go to the provider's own system field: Gemini `systemInstruction`, Anthropic
  `system`, and `system` messages for Sarvam and Ollama. Several system messages are joined in order.
- **Turns** keep their order. Consecutive turns with the same role are merged where the API requires
  alternation (Gemini, Anthropic).
- **`maxTokens`, `temperature`, `topP` and `stopSequences`** are passed under each provider's names. A
  setting a model rejects is **left out** (logged at debug), never sent to fail. For example, current Claude
  models reject `temperature`, so a `ReActAgent`'s default temperature is simply not sent to them.
- **Required settings.** Anthropic requires `max_tokens`, so it defaults to 16,000 (64,000 when streaming).

### Responses mean the same everywhere

| Field | Meaning |
|---|---|
| `content` | The answer only. Gemini "thought" parts, Claude thinking blocks and Sarvam's leading `<think>…</think>` are removed. |
| `finishReason` | `STOP`, `LENGTH`, `CONTENT_FILTER`, `TOOL_CALLS` or `ERROR`, whatever the provider called it. The provider's own value is kept in `metadata.finish_reason_raw`. |
| `tokenUsage` | Prompt tokens include cached input; completion tokens include billed thinking (Gemini). |
| `model` | The model that actually answered. |

### Failures look the same everywhere

| Situation | Exception (all are `LLMException`s) |
|---|---|
| Bad or missing key | `AuthenticationException` (Gemini's 400 "API key not valid" included) |
| Bad request (400, 404, 413, 422) | `InvalidRequestException`, with the provider's own message |
| Rate limited, beyond the inline wait | `RateLimitException`, with `RateLimitInfo` (when it resets) |
| Temporarily unavailable (500, 502, 503, 504, Anthropic's 529), after retries | `ServiceUnavailableException` |
| Refused for safety | `ContentBlockedException`, naming the provider's category when it gives one |
| Anything else | `ProviderException` |

Messages carry the HTTP status, the provider's error text and its request id, and never the API key.

### Streaming works everywhere

```java
try (Stream<LLMResponse> chunks = client.chatStream(request)) {
    chunks.forEach(c -> {
        if (!c.getContent().isEmpty()) System.out.print(c.getContent());   // text as it arrives
        else System.out.println("\n" + c.getFinishReason() + " " + c.getTokenUsage());  // the final chunk
    });
}
```

- **Chunk order**: text chunks, then exactly one final chunk with empty text, the finish reason and the
  usage.
- **Budgets**: `BudgetedLLMClient` meters streams from that final chunk.
- **Native streaming**: every built-in provider streams with the provider's own mechanism. Gemini uses
  server-sent events via `streamGenerateContent?alt=sse`; Anthropic and Sarvam use server-sent events;
  Ollama uses newline-delimited JSON.
- **Your own providers**: an `LLMProvider` that implements only `chat` still streams through the default
  `chatStream`, as one text chunk plus the final chunk.
- **Errors**: before the stream starts, errors are thrown from `chatStream`. During the stream, they end it
  with the same exception types as above.

## Anthropic (Claude)

```java
new AnthropicProvider(LLMConfig.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .defaultModel("claude-opus-5-5")
        .build(),
        "high");   // optional effort: low | medium | high | xhigh | max
```

- **HTTP only.** It uses the Messages API over the library's own HTTP client. There is no SDK and no extra
  dependency.
- **Effort** can also be set per request with `LLMRequest.builder().addParameter("effort", "low")`.
- **Sampling settings.** `AnthropicModels.acceptsSampling(model)` decides whether `temperature`/`top_p`
  are sent. It's an allowlist of older models, so new models are safe by default.
- **ReAct agents.** Claude Opus 5.5 refuses (`reasoning_extraction`) prompts that ask it to fill in a
  `"thought"` field. So `ReActAgent` asks every model for a short `"plan"` note; `"thought"` replies are
  still accepted.
- **In Loom**: `model: "anthropic/claude-opus-5-5"` (or just `"claude-opus-5-5"`), with
  `ANTHROPIC_API_KEY`, or `provider Team { use: anthropic api_key: env.KEY }`.

## Verifying against the real APIs

Mocks prove the code matches what we believe an API does. The **live suite** proves the API agrees. It's
tagged `live`, is never part of a normal build, and runs for whichever credentials you set:

```bash
export ANTHROPIC_API_KEY=...        # and/or GEMINI_API_KEY, SARVAM_API_KEY, OLLAMA_BASE_URL
export ANTHROPIC_TEST_MODELS=claude-opus-5-5,claude-haiku-4-5   # optional; these are the defaults
mvn -pl ai-agent4j,loom/ai-agent4j-loom -Plive test
```

It covers:

- a plain answer, the system prompt and multi-turn memory;
- truncation, streaming, a ReAct agent with a tool, and JSON output;
- budget metering against real usage, and a bad key;
- **one agent task run unchanged across every available provider**;
- a Loom script end to end, with `--trace`.

Each provider runs under a 60,000-token budget. With both default Claude models, a run costs well under
US$0.50.

### Status per provider

| Provider | Conformance suite (every build) | Verified against the real service |
|---|---|---|
| Gemini | ✅ | ✅ Verified by the maintainer with a real API key (every showcase app runs on Gemini); bad-key check through the live suite (2026-09-29) |
| Sarvam | ✅ | ✅ Verified by the maintainer against the real Sarvam API |
| Ollama | ✅ | ✅ Verified by the maintainer against a local Ollama server |
| Anthropic | ✅ | ✅ Full live suite, L1–L11, on `claude-opus-5-5` and `claude-haiku-4-5` (2026-09-29) |
