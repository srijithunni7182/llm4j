# Phase 30 live fixtures

The real-API check of N6 for the two vendors switched on in 30a. Run on 2026-11-04 with `mvn -pl src/loom/ai-agent4j-loom -Plive -Dtest=NativeJsonLiveTest test` (`NativeJsonLiveTest`), through the real extractor path (`DefaultLLMClientFactory`, `ExtractRunner`). Each row is one `extract` call with the extractor from the test: `vendor: string`, `total: number` (optional), `currency: enum[USD, EUR, INR, OTHER]`. Keys come from the environment and are never written; the file holds outcomes, not requests or replies.

| Model | Input | Route | Fallbacks | Attempts | Fields found | Missing | Tokens in / out |
|---|---|---|---|---|---|---|---|
| openai/gpt-4o-mini | a normal invoice | native | 0 | 1 | vendor, total, currency | none | 251 / 18 |
| openai/gpt-4o-mini | no total stated | native | 0 | 1 | vendor, currency | total | 254 / 16 |
| openai/gpt-4o-mini | text telling the model to say HELLO | native | 0 | 1 | vendor, total, currency | none | 261 / 16 |
| xai/grok-4-fast | a normal invoice | native | 0 | 1 | vendor, total, currency | none | 454 / 18 |
| xai/grok-4-fast | no total stated | native | 0 | 1 | vendor, currency | total | 457 / 16 |
| xai/grok-4-fast | text telling the model to say HELLO | native | 0 | 1 | vendor, total, currency | none | 464 / 16 |

What it shows: both vendors accept the nested `response_format.json_schema` request we send, return JSON that matches the schema on the first attempt, read a `null` optional field as missing, and were not steered by an instruction inside the input. About 2,150 input and 100 output tokens in all, far below a cent.

What it does not show: the exact request body (the mock-server test pins that), a reply cut at the token limit, a refusal, or any model other than these two. The xAI page's use of `grok-4.7` in its examples and this run's `grok-4-fast` both match the `grok-4` family pattern; other Grok 4 names are not individually checked.

## Second run: the rest of the providers (2026-11-04)

Run with `NativeJsonLiveTest` again, three inputs per route, with the keys read from the environment (Anthropic's from a variable the owner named, passed to the test process only). The xAI Responses route was rerun once after a mistake in the test itself (a provider block named `Xai`, which Loom reserves; renamed `Grok`), so its first three attempts never reached the API.

| Route | Model | Result on all three inputs | Tokens in / out (per call) |
|---|---|---|---|
| OpenAI, Responses API | gpt-4o-mini | native, 1 attempt, 0 fallbacks; the absent total read as missing | 248 to 258 / 17 to 19 |
| xAI, Responses API | grok-4-fast | native, 1 attempt, 0 fallbacks; the absent total read as missing | 454 to 464 / 311 to 382 |
| Anthropic, Messages API | claude-haiku-4-5-20251001 | native, 1 attempt, 0 fallbacks; the absent total read as missing | 436 to 447 / 21 to 25 |
| Perplexity, Agent API | openai/gpt-6-luna | native, 1 attempt, 0 fallbacks; the absent total read as missing | 283 to 293 / 52 to 55 |

About 8,000 input and 3,000 output tokens in all (most of the output is grok-4-fast's reasoning), a cent or two. The text telling the model to say HELLO changed nothing on any route. Perplexity's first-schema delay (10 to 30 seconds in its docs) was not observed: the whole nine-call run of the other routes plus Perplexity took 18 seconds, so the call did not time out. That is one observation, not a guarantee, and no longer timeout was set for it.

**Not run live, and why:** Kimi and Qwen (no keys; preview vendors, not switched on). Google and Ollama were run afterwards, below.

## Third run: Google Gemini (2026-11-04)

One raw `generateContent` call first, to settle open question O1, then the same three inputs through the extractor. The key was supplied by the owner in the chat, held in a private file outside the repository, read into the test process as an environment variable, and shredded afterwards; it appears in no file of the repository.

- **The raw call:** `generationConfig.responseMimeType: "application/json"` with `generationConfig.responseJsonSchema` (full JSON Schema, a nullable number written as `["number", "null"]`) returned HTTP 200, `finishReason: STOP`, and `{"vendor":"ACME GmbH","total":null,"currency":"EUR"}` for an invoice that states no total. So `generateContent` takes `responseJsonSchema`, and the Interactions API is not needed.
- **Through the extractor:**

| Model | Result on all three inputs | Tokens in / out (per call) |
|---|---|---|
| gemini-2.5-flash | native, 1 attempt, 0 fallbacks; the absent total read as missing; the HELLO injection changed nothing | 196 to 203 / 88 to 147 |

What this does not show: other Gemini models (the pattern is Gemini 2.5 and later, from Google's announcement, and only 2.5-flash was run), combining the schema with function calling (an extractor has no tools), or a truncated or blocked reply.

## Fourth run: a local Ollama server (2026-11-04)

Ollama 0.40.14 (the official `ollama-linux-amd64.tar.zst` release, CPU only, listening on loopback) was installed in the sandbox for this check, with the 135-million-parameter model `smollm2:135m`, and **removed afterwards** (server stopped, binary, archive, model, logs and the keypair it generates all deleted; no process or port left). Constrained decoding is the server's, so a tiny model is enough to test the request; it is not a test of extraction quality.

- **Raw call, same prompt and schema, with and without `format`:** without it the model answered in a code fence with the wrong shape and ran to the length limit; with `format` it answered exactly `{"vendor": "ACME GmbH", "total": 100000, "currency": "USD"}`: valid, the three fields, an allowed currency.
- **Through the extractor** (`ollama/smollm2:135m`, three inputs): native, 1 attempt, 0 fallbacks each time; 223 to 232 tokens in, 28 to 35 out.

What this shows: Ollama accepts the `format` schema we send and enforces it. What it does not show: quality. The 135M model invented a total of 100000 for an invoice that states none, so on "no total stated" the field was present, not missing. That is the model's judgement, which a schema cannot supply; the extractor's checks only guarantee shape and type. It also says nothing about Ollama Cloud, which is left on the prompt route.
