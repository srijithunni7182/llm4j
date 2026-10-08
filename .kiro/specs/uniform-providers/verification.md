# Verification Plan

There are three layers:

| Layer | What it proves | When it runs |
|---|---|---|
| **C**: conformance, against mocks | Every provider meets the contract as *we* understand each API. | Every build |
| **L**: live | The real APIs agree. | Only with your keys, `-Plive` |
| **K**: compatibility | Existing code didn't have to change. | Every build |

Results record, per provider, how it has been verified against the real service: through this live
suite, or by the maintainer directly.

## C: Conformance suite

The same test methods run for Google, Anthropic, Ollama and Sarvam, each with its own recorded wire
formats.

| # | Check |
|---|---|
| C1 | **System prompt**: two system messages reach the provider's native system field (Gemini `systemInstruction`, Anthropic `system`, Sarvam and Ollama `system` messages), joined in order. For Gemini and Anthropic, none is left inside a user turn. |
| C2 | **Turns**: user/assistant/user order is kept. Consecutive same-role turns are merged for Gemini and Anthropic. |
| C3 | **Settings**: `maxTokens`, `temperature`, `topP` and `stopSequences` appear under the provider's names. For a model that rejects sampling (Anthropic `claude-opus-5-5`, and an unknown `claude-future-9`), `temperature` and `topP` are absent. `claude-haiku-4-5` gets them. Anthropic always sends `max_tokens` (16,000 by default). |
| C4 | **Answer**: `content` is only the answer text: Gemini `thought` parts and Anthropic thinking blocks are excluded. `tokenUsage` matches the fixture, `model` is set, and `finishReason` is `STOP` with `finish_reason_raw` holding the provider's value. |
| C5 | **Truncation**: a max-tokens stop gives `LENGTH` for all four (Gemini `MAX_TOKENS`, Anthropic `max_tokens`, Ollama and Sarvam `length`). |
| C6 | **Refusal**: a safety stop gives `ContentBlockedException` naming the provider's category (Gemini `SAFETY`, Anthropic `refusal`). For providers with no such signal, the test is marked not applicable. |
| C7 | **Errors**: 401 gives `AuthenticationException`, and 400 gives `InvalidRequestException` carrying the provider's message and status. Neither is wrapped in a generic `ProviderException`. |
| C8 | **Unavailable**: 503, then 200, succeeds after one retry. A persistent 503 (and 529 for Anthropic) gives `ServiceUnavailableException`. |
| C9 | **Rate limit**: 429 with the provider's rate-limit signal and a long reset gives `RateLimitException`, with `RateLimitInfo` reset time and scope. |
| C10 | **Streaming**: the recorded stream gives text chunks in order, then exactly one final chunk (empty text, `finishReason`, `tokenUsage`). Joined text equals the `chat` answer for the same fixture. Nothing is read before the stream is consumed, and closing it early releases the connection (MockWebServer sees the socket closed). |
| C11 | **Stream errors**: a 401 before streaming gives `AuthenticationException`. A mid-stream error event gives the matching exception, after the chunks already received. |
| C12 | **No secrets**: the API key appears in no exception message and in no captured log line, across C1–C11. |

Also covered by the conformance tests:

| # | Check |
|---|---|
| C13 | **Default `chatStream`**: a minimal custom `LLMProvider` that overrides only `chat` streams one text chunk plus a final chunk. |
| C14 | **Budgets**: `BudgetedLLMClient` over each provider's stream charges exactly the final chunk's usage. |
| C15 | **Finish reasons**: every value in the design §1 table maps as listed. Unknown values are still `UNKNOWN`. |

## L: Live suite

Each provider runs if its credentials are present, over its model list, under a 60,000-token budget.

| Provider | Credentials | Models (default) |
|---|---|---|
| Anthropic | `ANTHROPIC_API_KEY` | `ANTHROPIC_TEST_MODELS` = `claude-opus-5-5,claude-haiku-4-5` |
| Gemini | `GEMINI_API_KEY` | `GEMINI_TEST_MODELS` = `gemini-2.5-flash` |
| Sarvam | `SARVAM_API_KEY` | `SARVAM_TEST_MODELS` = `sarvam-m` |
| Ollama | `OLLAMA_BASE_URL` reachable | `OLLAMA_TEST_MODELS` = `llama3.2` |

| # | Check | Proves |
|---|---|---|
| L1 | A one-line question gets a non-empty answer, usage above 0, `STOP`, and the model echoed. | Basic request and response |
| L2 | The system prompt "End every reply with the word PINEAPPLE." is obeyed. | Native system field |
| L3 | Multi-turn ("My name is Asha" … "What is my name?") answers with Asha. | Turn mapping |
| L4 | `maxTokens: 5` on a long ask gives `LENGTH` and no exception. | Finish-reason normalising |
| L5 | Streaming "Count from 1 to 5" gives more than one text chunk, joined text containing 1–5, and a final chunk with usage and `STOP`. | Real streaming formats |
| L6 | A `ReActAgent` with `CalculatorTool` and the **default temperature** answers "1234 × 5678" with 7006652, with at least one tool step and `protocolFollowed` true. | Agents work; sampling handled |
| L7 | `ReActAgent` with a JSON output schema: the answer parses as the schema. | Structured output |
| L8 | `BudgetedLLMClient`: tokens spent equal the reported usage, for both chat and a stream. | Metering on real usage |
| L9 | A bad key gives `AuthenticationException` with a clear message and no key in it. | Real error bodies |
| L10 | **Switching**: one `ReActAgent` task, unchanged code, run across every available provider and model, gets the same correct answer. | The user's actual goal |
| L11 | **Loom end to end**: `weave run --trace` of a script with `model: "anthropic/<model>"` (and the Gemini equivalent if its key is set), using calculator and `output_schema`. The variable holds correct JSON, and the trace shows the tool call. | The whole stack |

**Not triggered live**: 429, 529, 503 and refusals can't be produced on demand; C6, C8 and C9 cover them.
If any occur during a live run, the results will record what the provider actually sent.

## K: Compatibility

| # | Check |
|---|---|
| K1 | Every public signature is unchanged, and only additions appear in the diff. `japicmp` isn't available offline, so this is done by a review checklist in the results listing each changed public type. |
| K2 | Every existing test in all modules passes. Any test changed because it asserted an old behaviour (`UnsupportedOperationException` from `chatStream`, a generic `ProviderException`, a raw finish reason) is listed with the reason. |
| K3 | Loom, eval4j and GetViral compile and pass with no source changes, apart from Loom's additive Anthropic support. |
| K4 | No new runtime dependencies (`mvn dependency:tree` diff). |
| K5 | A default build runs zero live tests, even with every key set. |
| K6 | Coverage of the new and changed provider code (`AnthropicProvider`, the streaming helpers, typed errors, the `chatStream` methods) is at least 90% of lines from C tests. |

## Done when

C1–C15 and K1–K6 pass in the build, **and** you have run the live suite for Anthropic at least. Gemini,
Sarvam and Ollama are run as you have keys. The results are recorded per provider, with models and date.

## Running the live suite

```bash
export ANTHROPIC_API_KEY=sk-ant-...        # plus GEMINI_API_KEY / SARVAM_API_KEY / OLLAMA_BASE_URL if you have them
mvn -pl ai-agent4j,src/loom/ai-agent4j-loom -Plive verify
```

**Expected cost**:

- Anthropic with both default models: well under US$0.50.
- Gemini Flash: a few cents.
- Ollama: free, since it runs locally.

Every provider is capped by its 60,000-token budget.

---

## Results (2026-09-29)

### Status

| Provider | Conformance (C) | Real service |
|---|---|---|
| Gemini | ✅ | Verified by the maintainer with a real API key (every showcase app runs on Gemini), plus L9 (bad key) through the live suite |
| Sarvam | ✅ | Verified by the maintainer against the real API, outside this suite |
| Ollama | ✅ | Verified by the maintainer against a local server, outside this suite |
| Anthropic | ✅ | Full live suite, L1–L11 |

**L: live, Anthropic, run 2026-09-29** with `claude-opus-5-5` and `claude-haiku-4-5`
(`mvn -pl ai-agent4j,src/loom/ai-agent4j-loom -Plive test`):

| # | `claude-opus-5-5` | `claude-haiku-4-5` |
|---|---|---|
| L1 answer | ✅ | ✅ |
| L2 system prompt | ✅ | ✅ |
| L3 multi-turn | ✅ | ✅ |
| L4 truncation → `LENGTH` | ✅ | ✅ |
| L5 streaming | ✅ | ✅ |
| L6 ReAct + calculator, default temperature | ✅ (7006652) | ✅ (7006652) |
| L7 JSON output schema | ✅ | ✅ |
| L8 budget = reported usage (chat and stream) | ✅ | ✅ |
| L9 bad key → `AuthenticationException` | ✅ | ✅ |
| L10 switching, same unchanged task | ✅ → 1776 | ✅ → 1776 |
| L11 Loom `weave run --trace` | ✅ | not run (one model is enough) |

- **ai-agent4j live:** 18 of 18 passed.
- **Loom L11:** the trace shows the plan, then `calculator {"expression":"48 * 37"}` → `1776`, then the
  variable `{"boxes":48,"items_per_box":37,"total":1776}`.
- **Not seen live:** 429, 529, 503 and refusals of normal requests (except the one below). C6, C8 and C9
  still cover them.

**What the live suite revealed** (all fixed and re-run):

1. **Opus 5.5 refused the ReAct prompt.** It returned `stop_reason: refusal`, category
   `reasoning_extraction`, whenever the prompt asked it to fill in a JSON field named `"thought"`.
   - `ReActAgent` now asks for a short `"plan"` note instead, and replays it as `Plan:`.
   - Replies that use `"thought"` or `Thought:` are still read, so other models and existing tests are
     unaffected.
   - A JSON answer that happens to contain a `"plan"` key (with no `action` or `final_answer`) is still
     treated as a structured answer, not as the protocol. `ReActAgentPlanFieldTest` covers all three
     cases.
   - This is the one change outside the design's "no changes to `ReActAgent`" list. It is a prompt
     wording change; the public API is unchanged.
2. **Loom `note` statements didn't interpolate variables.** They printed `${total}` literally. This was
   found reading the L11 trace; notes now resolve variables like `say`.
3. **A zero-credit account** returns 400 `invalid_request_error` ("credit balance is too low"). It is
   correctly an `InvalidRequestException` carrying Anthropic's message, so no change was needed.

**C: conformance.** The same checks ran for all four providers. The one refusal check is marked not
applicable for Ollama and Sarvam, which have no refusal signal.

| Test class | Result |
|---|---|
| `AnthropicContractTest` | 13 passed |
| `GoogleContractTest` | 14 passed (includes the bad-key quirk below) |
| `OllamaContractTest` | 12 passed |
| `SarvamContractTest` | 12 passed |
| `ContractPiecesTest` (C13, C15, helpers) | 29 passed |
| `StreamingBodyTest` | 4 passed |
| `AnthropicProviderTest` | 21 passed |
| `ProviderEdgesTest` | 11 passed |

**L: live, earlier, without any key.**

- **L9, bad key: passed against both real APIs.**
  - Anthropic returned 401 `authentication_error: API key is invalid.`, reported as
    `AuthenticationException`.
  - Gemini returned **400** `INVALID_ARGUMENT: API key not valid`. This is a real quirk: the contract
    says a bad key is an `AuthenticationException` for every provider, so `GoogleProvider` now maps it.
    It was found by accident, because an old test called the real Gemini API with a dummy key.

**K: compatibility.**

| # | Result |
|---|---|
| K1 | No public signature changed or was removed. See the list of additions below. |
| K2 | All suites green after the live fixes: ai-agent4j 651, addons 17, eval4j 125, Loom 355, Engram 9, Tantrik 22, GetViral 77. Two existing tests were changed; see below. |
| K3 | Loom, eval4j and GetViral needed no source changes. Loom's only change is the additive `anthropic` support in its factory and provider kinds, plus error-message wording. |
| K4 | No new dependencies. The pom changes are surefire's live-group properties and a `live` profile. |
| K5 | A default build runs **0** live tests. `-Plive` runs them, skipping those without credentials. |
| K6 | New and changed provider code has **96.0%** line coverage (649 lines, per method, measured by JaCoCo). |

**Public API additions** (K1):

- `FinishReason.fromValue` synonyms;
- `LLMException(String, Integer, String)`, `getResponseBody()` and a protected `setResponseBody`;
- `AuthenticationException` and `InvalidRequestException` status-and-body constructors;
- `ServiceUnavailableException`;
- a default `LLMProvider.chatStream`;
- `HttpClientWrapper.stream`, `StreamingBody`, `Providers`, `ThinkTags`;
- `provider.anthropic.*`;
- `RetryPolicy.defaultPolicy()` now includes 529.

### Existing tests changed because they asserted old behaviour (K2)

- **`GoogleProviderTest.testChat_handlesSystemMessageCorrectly`** asserted that the system prompt was
  glued onto the user text. It now asserts `systemInstruction` (R1.1).
- **`SarvamChatProviderTest.chatStream_shouldThrowUnsupportedOperationException`** is now
  `chatStream_streamsThroughTheHttpClientAndKeepsTypedErrors`: Sarvam streams now (R4.1).

### Found along the way

- **Google.** Errors it created itself (auth, invalid request) were caught by its own `catch
  (LLMException)` and re-wrapped as a generic `ProviderException`. A long answer split across several
  `parts` was cut to the first part. Thinking tokens (billed as output) weren't counted.
- **Sarvam** reported `model: null` whenever the model came from config (caught by C4).
  `sarvam-m`'s leading `<think>` block is now removed, both in chat and when streaming.
- **The streaming reader.** Asking `hasNext()` again after a stream ended re-read a closed reader. This
  was caught by C14, because `BudgetedLLMClient` does exactly that.
- **Anthropic** puts the request id in the error body (`request_id`), not in a header, and it is `null`
  for an invalid key. Messages now use the id from either place.

### Deviations from the design

1. **`chatStream` sends the request immediately.** Only the body is read lazily. This is what lets
   failures before the stream (auth, rate limit) throw from `chatStream` itself (R4.4).
2. **The `StreamChunks` builder became static helpers**, `Providers.textChunk` and
   `Providers.finalChunk`.
3. **Live tests are named `*LiveTest` / `Live*Test`**, which keeps them away from the old
   `*IntegrationTest` exclusion. Those older tests remain as they were.

### Still open

- **The new live suite for the other providers.** Run the same command with `GEMINI_API_KEY`,
  `SARVAM_API_KEY` or `OLLAMA_BASE_URL` set to put them through L1–L11 as well. L11 covers all four.
