# Phase 8 test strategy: providers and grounding

Companion to [DESIGN.md](DESIGN.md). Requirement ids (`R1`…) are the ones defined there.

## 1. Principles

- **The default build needs no network and no key.** Every provider is tested against recorded wire fixtures replayed by a local HTTP server, the way
  the existing provider tests work. Credits are needed only for the opt-in live smoke tests (§5), and only a few cents of them.
- **One behavioural contract, many providers.** All new providers extend `ProviderContract`, the abstract suite the existing providers already pass.
  A bug found in one is a test added to the contract, so it is checked for all.
- **Fixtures come from the real API.** A fixture is recorded once from a live call (by the smoke runner, §5) and committed with its date and model.
  Until a vendor's fixture is recorded from the real API, the sub-phase is not done, and hand-written fixtures are marked as such in the file header.
- **A requirement without a failing-first test is not done.**

## 2. Levels

| Level | Where | What |
|---|---|---|
| Unit | ai-agent4j | `Dialect` and `ModelTraits` lookups (token field, temperature, reasoning, tools) including unknown model names (R3); request JSON built for each dialect; response parsing for each dialect incl. reasoning text, usage details, finish reasons |
| Contract | ai-agent4j | `OpenAiContractTest`, `XaiContractTest`, `KimiContractTest`, `QwenContractTest`, `OpenAiCompatibleContractTest` (including the preview `perplexity_router` dialect) and, for the Responses schema, `PerplexityAgentContractTest` and `OpenAiResponsesContractTest` extend `ProviderContract` (R1). The Responses tests cover `output[]` parsing, `previous_response_id`, streaming events, usage and the `web_search` tool's `search_results`/`annotations` |
| Streaming | ai-agent4j | chunked text, reasoning chunks, tool-call deltas, final chunk with usage and finish reason, stream cut mid-way, `[DONE]` missing, a vendor that sends usage in an extra chunk |
| Errors | ai-agent4j | 401 → `AuthenticationException`, 400 → `InvalidRequestException` (with the vendor's message, key redacted, R4), 429 with and without `Retry-After`, 5xx retry, timeout, malformed JSON, empty `choices`, content filtered |
| Security | ai-agent4j | canary-key test: the key is absent from exceptions, logs, events and traces for every dialect (R4); stored secret refused for a host not in its binding |
| Wiring | Loom | every new `use:` kind builds a client; the resolver maps explicit and bare model names as specified and leaves the ambiguous ones unresolved; the "unknown model" message lists the new forms; env and secret-store key lookup order; `*_BASE_URL` override |
| Catalog | ai-agent4j | windows and prices exist for the listed models; unknown model falls back without error |
| Grounding unit | ai-agent4j | `Grounding` builder rules (domains mutually exclusive, `maxSearches ≥ 1`); `Citation`/`Span` invariants (R7); capability check and refusal messages (R6); `lenient()` and `fallback()` behaviour; `SearchUsage` with unknown counts (R8) |
| Grounding provider | ai-agent4j | per vendor, a recorded grounded response → expected `Citation`s and `SearchUsage`; the request each vendor receives for each `Grounding` option; refusals for options a vendor cannot honour; Perplexity's Agent API with and without the `web_search` tool (plain model access without it); Kimi's search loop (a model that asks once, a model that never stops, `maxSearches` bound, R3-of-Q3); Anthropic/Kimi `providerData` round trip across a following tool turn (R12) |
| Streaming parity | ai-agent4j | for each grounded vendor, the streamed and the whole response yield equal citations and search usage (R9) |
| Regression | all | the entire existing suite is the R5 test, plus a request-bytes test: for Gemini, Anthropic, Sarvam and Ollama a request without `grounding` is byte-identical to the previous release's recorded request |
| Telemetry/budget | ai-agent4j | `ModelCallEvent` carries `searches`/`citations`; `Budget` `searches` dimension charges and halts (R11); `RunStats` aggregates; text (queries, URLs) absent at the default detail level |
| Agent | ai-agent4j | `ReActAgent.grounding(...)` applies to every call; `AgentResult.citations()` de-duplicates by URL; a grounded agent over a scripted client |
| Loom | Loom | `grounding:` parses with and without options; `weave check` refusals (unsupported provider, unsupported option); audit marks a grounded agent as untrusted-content exposure, including with an `allow:` list (R10); `budget { searches }`; `step.citations` binds; trace and graph show it; the VS Code grammar and diagnostics |
| eval4j | eval4j | each citation assertion passes and fails correctly, with readable messages; scripted citations in `ScriptedClient`; a grounded answer as `retrievalContext` to a RAG metric |
| Docs | all | all documentation samples are executed in tests (the `ContextDocsExamplesTest` pattern) |

## 3. Fault injection

| Fault | Expected |
|---|---|
| Vendor returns an unknown finish reason, a missing `usage`, `null` content with tool calls | normalised value or null per the contract; never an exception from parsing alone |
| Reasoning field present when not requested, or absent when requested | the answer text never contains reasoning; reasoning is null when the vendor sent none |
| Tool call with malformed or partial arguments in a stream | assembled when complete; a typed error when the stream ends mid-call |
| Grounded response with citations but zero reported searches, or searches but no citations | `getSearches()` count as reported/null (R8); citations as returned; no exception |
| A citation without a title or with a relative or empty URL | kept with null/"" fields as given, never dropped silently; a test pins the choice |
| Spans outside the answer text | dropped with a recorded warning in metadata (R7) |
| Vendor changes a field name | the dialect unit test fails on the next recorded fixture, naming the field (this is what the live smoke run is for) |
| `maxSearches` ignored by a vendor and exceeded | the call completes; the overrun is counted against the `searches` budget and visible in telemetry |

## 4. Deliberately not tested in the default build

- A vendor's search quality or the correctness of its answers.
- Live pricing: prices in the catalog are checked for shape and presence, not against the vendor's live pages.
- Regional DashScope endpoints beyond the two documented base URLs.

## 5. Live smoke tests (opt-in, a few cents each)

A runner, `ProviderSmokeIntegrationTest`, skipped unless the vendor's key variable is set (`OPENAI_API_KEY`, `XAI_API_KEY`, `MOONSHOT_API_KEY`,
`DASHSCOPE_API_KEY`, `PERPLEXITY_API_KEY`, plus the existing `GEMINI_API_KEY` and `ANTHROPIC_API_KEY`). For each vendor it runs: one plain chat, one
streamed chat, one tool call (where supported), one reasoning call (where supported), and in Part B one grounded call with a question that needs
current information. It writes the raw responses to `target/smoke/<vendor>/` and a one-page Markdown summary of what worked.

Its two jobs: (1) record the fixtures the contract tests replay (§1), and (2) detect when a vendor has changed an interface. A `SpendGuard` caps the
run (default: 25 cents per vendor). For Part B, a throwaway script first prints each vendor's raw grounded response (Q9), so the citation mapping
is built from real payloads; its output is kept in the spec folder as `grounding-payloads/`.

Expected live spend for one full pass over all seven vendors: **well under US$3** (grounded calls are the largest part; some vendors bill searches
per request).
