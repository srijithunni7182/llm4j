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
