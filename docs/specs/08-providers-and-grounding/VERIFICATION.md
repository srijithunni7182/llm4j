# Phase 8 verification: providers and grounding

Companion to [DESIGN.md](DESIGN.md) and [TEST_STRATEGY.md](TEST_STRATEGY.md). The phase is done when every box below is ticked and the result is
recorded in §7. Part A is shippable on its own; Part B starts only when Part A is verified.

## 1. Sub-phases

| # | Sub-phase | Delivers |
|---|---|---|
| **Part A: providers** | | |
| 8a | Core | `OpenAiCompatibleProvider`, `Dialect`, `ModelTraits`; the OpenAI preset; `OpenAiContractTest`; fixtures recorded from the real API |
| 8b | Presets | xAI, Kimi, Qwen, the generic `openai_compatible` and the preview `perplexity_router`, each with its contract test and fixtures |
| 8b2 | Responses provider | `OpenAiResponsesProvider` (DESIGN §3.4): the OpenAI Responses schema, the Perplexity Agent API preset (`perplexity`) and OpenAI's `api: responses`; contract test; fixtures |
| 8c | Wiring | Loom kinds, resolver, key lookup and secret host binding, window and price catalogs, rate-limit names, audit data flows, `windows.properties`/`PriceTable`, docs |
| 8c-live | Smoke | `ProviderSmokeIntegrationTest` run for each vendor with real keys; fixtures replaced by recordings; results in §7 |
| **Part B: grounding** | | |
| 8d | Payload look | The throwaway script that prints each vendor's raw grounded response (design Q9); the spec's §4.3 table is revised from it **before** any Java |
| 8e | Contract | `Grounding`, `Citation`, `SearchUsage`, `GroundingSupport`, `LLMRequest.grounding`, `LLMResponse.getCitations/getSearches`, the central check in `DefaultLLMClient`, telemetry and the `searches` budget dimension |
| 8f | Vendors | Grounding in Google, Anthropic, Perplexity (Agent API `web_search`) first; then OpenAI Responses, xAI, Kimi, Qwen; each with fixtures and parity tests |
| 8g | Agent, Loom, eval4j | `ReActAgent.grounding`, `AgentResult.citations`; Loom `grounding:` with checks, audit, trace, budget; eval4j citation assertions and scripted citations |
| 8h | Docs and tooling | Wiki pages, Loom guide chapter, `llms.txt`, examples, VS Code grammar/diagnostics, skills |

| 8k | Perplexity Decisions (**conditional on Phase 7 merged**) | `PerplexityDecisionsProvider` and the shared `DecisionWire` (DESIGN §3.5); contract tests (`ClassifierContract`); fixtures; Phase 7's study re-run with it as a third contender |

## 2. Acceptance criteria

| # | Criterion | Requirement | Test | Result |
|---|---|---|---|---|
| A1 | Each new provider passes the full `ProviderContract` | R1 | six `*ContractTest` classes | ✅ 8 provider contract classes (`OpenAiContractTest`, `XaiContractTest`, `KimiContractTest`, `QwenContractTest`, `OpenAiCompatibleContractTest`, `PerplexityRouterContractTest`, `OpenAiResponsesContractTest`, `PerplexityAgentContractTest`) extend `ProviderContract`: 35–37 tests each, green |
| A2 | Adding a dialect needs no change to `OpenAiCompatibleProvider` outside its constants and `ModelTraits` | R2 | code review checklist + a test that registers a made-up dialect | ✅ `DialectTest.aMadeUpDialectNeedsNoProviderChange`; `NoVendorLogicTest` (no string literal in the shared code names a vendor) |
| A3 | Unknown model names at a known vendor work with safe defaults | R3 | `ModelTraits` tests | ✅ `DialectTest` (`openAiReasoningModelsRejectSamplingSettings`, `anUnrecognisedModelAtAnyVendorGetsTheSafeDefaults`), `OpenAiContractTest.anUnknownFutureModelDegradesToTheSafeDefaults` |
| A4 | Keys never appear in exceptions, logs, events or traces; secrets go only to bound hosts | R4 | canary-key and host-binding tests | ✅ `ProviderContract` (C7, C11, C12 for every new provider), `OpenAiFamilySecretTest` (per-request fetch, header only, host binding, no key in errors, Chat and Responses), `FixtureProvenanceTest` (no key or id in any fixture), `ScrubTest` |
| A5 | Loom `use:` kinds, resolver prefixes, bare-name inference and the ambiguous-name behaviour are as specified | §3.3 | wiring tests | ✅ Loom `OpenAiFamilyProvidersTest` (prefixes, bare names, ambiguous `qwen3`/`gpt-oss`, kinds, options, checks, host-bound stored key), `ProvidersTest`; Spring `ClientsTest`; `VendorTest` |
| A6 | Windows and prices exist for the listed models; unknown models fall back | §3.3 | catalog tests | ✅ `ContextWindowsTest` (19 entries for OpenAI, xAI, Kimi and Qwen models, each checked against the vendor's page on 2026-10-22; unknown models and nested slugs resolve or fall back). Prices: not shipped by design (`PriceTable` is user-supplied); per-search prices come with 8e |
| A7 | Requests without `grounding` are byte-identical to the previous release for Gemini, Anthropic, Sarvam, Ollama | R5 | request-bytes regression test | ✅ `RequestBytesRegressionTest`: 48 recorded requests (Google, Anthropic, Sarvam, Ollama; plain, system, multi-turn, tools, reasoning; whole and streamed), recorded **before** any grounding change, compared as JSON with sorted keys because a tool schema built from a hash map lists its keys differently from one JVM run to the next |
| A8 | Grounding on an unsupported provider or option is refused before any network call, naming both | R6 | grounding unit tests | ✅ `GroundingCheckTest` (refused before any call for `chat` and `chatStream`, naming provider and option; `lenient()` and `grounding.ignored`; lenient does not rescue an unsupported provider), `GroundingTypesTest` |
| A9 | Citations are typed, indices stable, spans in range | R7 | grounding provider tests | ⏳ 8f |
| A10 | Search usage is as the vendor reports; unknown stays unknown | R8 | grounding provider tests | ⏳ 8f |
| A11 | Streaming and non-streaming agree on citations and search usage | R9 | parity tests | ⏳ 8f |
| A12 | A grounded agent is untrusted-content exposure in `weave audit`, with or without `allow:` | R10 | Loom audit tests | ⏳ 8g |
| A13 | Searches are charged to the `searches` budget; a breach halts | R11 | budget tests | ✅ `SearchBudgetTest` (count, refusal before the call while a plain call still runs, unknown count, ignored cap, streams, windows, restore, per-1000 prices, old shapes) |
| A14 | A grounded turn followed by a tool turn is valid for Anthropic and Kimi | R12 | provider tests | ⏳ 8f |
| A15 | Fallback to a client-side tool yields citations in the same shape | §8.3 | grounding unit tests | ✅ `GroundingCheckTest` (fallback runs the tool, citations built from the URLs it returned, bounded by `maxSearches`, same shape on a stream, never used when the provider has native grounding) |
| A16 | eval4j citation assertions pass and fail correctly; scripted citations work offline | §6 | eval4j tests | ⏳ 8g |
| A17 | The live smoke run passes for every vendor with a key, within its spend cap, and recorded fixtures replace the hand-written ones | §5 of the test strategy | `ProviderSmokeIntegrationTest` (live) | ◐ run for OpenAI (chat and Responses), xAI and Perplexity on 2026-10-22, within the cap; Anthropic and Gemini have no key here, Kimi and Qwen no key: owed (see §7 follow-up) |
| A18 | All documentation samples execute in tests | docs | docs examples test | ◐ 8a to 8c samples execute (`ProvidersDocsExamplesTest`); the 8e grounding samples execute (`GroundingDocsExamplesTest`, which also checks the page shows them verbatim); vendor samples with 8f |
| A19 | `OpenAiResponsesProvider` passes `ProviderContract` for the Perplexity Agent API and OpenAI Responses; `web_search` results map to typed citations | §3.4, R1, R7 | contract and grounding provider tests | ◐ contract: ✅ both Responses dialects pass `ProviderContract` and replay recorded real payloads; `web_search` citations: ⏳ 8f |
| A20 | (If Phase 7 is merged) `PerplexityDecisionsProvider` passes `ClassifierContract` and shares `DecisionWire` with `JevWire` | §3.5 | classifier contract tests | ⏳ 8k |

## 3. Traceability

Filled in as tests are written: one row per requirement `R1`…`R12`, naming the test classes that cover it. A requirement with no row blocks the phase.

| Req | Tests |
|---|---|
| R1 | `OpenAiContractTest`, `XaiContractTest`, `KimiContractTest`, `QwenContractTest`, `OpenAiCompatibleContractTest`, `PerplexityRouterContractTest`, `OpenAiResponsesContractTest`, `PerplexityAgentContractTest` (via `ChatCompletionsContract` / `ResponsesContract`, which extend `ProviderContract`); `RecordedFixturesTest` on real payloads |
| R2 | `NoVendorLogicTest`, `DialectTest.aMadeUpDialectNeedsNoProviderChange` |
| R3 | `DialectTest`, `OpenAiContractTest.anUnknownFutureModelDegradesToTheSafeDefaults` |
| R4 | `ProviderContract` C12 and the `noSecret` checks, `OpenAiFamilySecretTest`, Loom `OpenAiFamilyProvidersTest.aStoredKeyKeepsItsHostBindingForTheVendor`, `SecretCommandsTest` (vendor host binding), `FixtureProvenanceTest`, `ScrubTest` |
| R5 | `RequestBytesRegressionTest` |
| R6 | `GroundingCheckTest` |
| R11 | `SearchBudgetTest`; `SearchTelemetryTest` (counts per model, never text) |
| R7–R10, R12 | _listed as 8f and 8g are built_ |

## 4. Quality gates

- The repo's own build, formatting and static checks pass on every changed module; coverage and PIT mutation thresholds as for the other core packages.
- No new runtime dependency in ai-agent4j.
- `weave check` and `weave audit` run clean on all new example scripts.
- Public types have Javadoc; wiki samples are executed (A18).
- `docs/VERSION_MATRIX.md` and `docs/API_COMPATIBILITY.md` updated: the SPI gains default methods only, so the change is source- and binary-compatible.

## 5. Manual and live checks (owed, opt-in)

- [ ] Smoke run per vendor (8c-live) and a second one for grounding (8f), each capped, with the spend recorded in §7.
- [ ] A Loom research workflow with a grounded agent run with `--observe` to see searches and citations in Fabric.
- [ ] The VS Code extension on the new `grounding:` syntax.
- [ ] One end-to-end run per vendor through `routing` (fall back from one vendor to another).

## 6. Documentation owed

At the end of each sub-phase: the wiki pages (a providers page covering all five presets and the generic one; a grounding page), executed samples,
Javadoc, module READMEs and hub links, `llms.txt`, and the Loom guide chapter. Once, at the end of the whole phase: stack-level pages (root
`README.md`, `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`), the provider list in the root README badge row, and the `llm4j-workflow-guide` and
`llm4j-loom-deployer` skills (new provider kinds; grounding and the audit change).

## 6a. Release 6.1.0 gate

For this work to be part of 6.1.0: every **Must** and **Should** item of DESIGN §10 that is included has its acceptance criteria ticked in §2; the
release notes (`RELEASE_NOTES_6_1_0.md`) list each provider as *supported* (contract test on recorded real-API fixtures) or *preview*
(hand-written fixtures only), and each grounding vendor likewise; `docs/VERSION_MATRIX.md`, `docs/API_COMPATIBILITY.md` and the provider list in
the root README are updated; the publish steps in `docs/PUBLISHING.md` are followed. Items not ready are listed as "coming", never described as working.

## 7. Result

Progress log, newest sub-phase last. Resume from the last entry.

### 7.0 Step 0: environment (2026-10-22)

- Keys present (names only): `OPENAI_API_KEY`, `PERPLEXITY_API_KEY`, `XAI_API_KEY`. Absent: `MOONSHOT_API_KEY`, `DASHSCOPE_API_KEY`, `GEMINI_API_KEY`, `SMOKE_GEMINI_API_KEY`, `SPIKE_ANTHROPIC_KEY`, `ANTHROPIC_API_KEY`. The smoke runner accepts `SPIKE_ANTHROPIC_KEY` for `ANTHROPIC_API_KEY` and `SMOKE_GEMINI_API_KEY` for `GEMINI_API_KEY`.
- Hosts reachable (an HTTP status came back): api.openai.com, api.x.ai, api.perplexity.ai, docs.perplexity.ai, api.anthropic.com, generativelanguage.googleapis.com, api.moonshot.ai, dashscope-intl.aliyuncs.com. None blocked.
- Consequence: OpenAI, xAI and Perplexity can be recorded and become "supported"; Kimi and Qwen are **preview** (documentation-derived fixtures); Anthropic and Gemini have no key here, so their existing providers keep their current status and their grounding stays "preview" unless a key appears.
- Phase 7 is merged on `main` and was merged into this branch (4908290), so sub-phase 8k applies.

### Follow-up when keys are available

- **Kimi** (`MOONSHOT_API_KEY`): run `ProviderSmokeIntegrationTest`; replace `src/test/resources/providers/kimi/*` with scrubbed recordings; confirm `max_completion_tokens`, fixed sampling per model, the `thinking` switch on K2.5/K2.6 and `reasoning_effort` on K3, and `reasoning_content` round trip; decide the grounding route (Responses API server-side search, §4.3); drop the "preview" label.
- **Qwen** (`DASHSCOPE_API_KEY`): the same; confirm the `enable_thinking` switch for hybrid models (and whether `false` is accepted by thinking-only models), the workspace-domain base URL, the `max_tokens` field and `search_info` for grounding; drop "preview".
- **Anthropic / Gemini** (`ANTHROPIC_API_KEY` or `SPIKE_ANTHROPIC_KEY`, `GEMINI_API_KEY` or `SMOKE_GEMINI_API_KEY`): run the smoke and the grounding payload script; record the payloads before the grounding mapping is called supported.

### 7.1 8a: core (OpenAI)

Built: `Dialect`, `ModelTraits`, `OpenAiCompatibleProvider` (+ package-private `ChatRequestWriter`, `ChatResponseReader`), `ChatCompletionsContract` (shared contract), `OpenAiContractTest`, `DialectTest`. ai-agent4j suite: 2939 tests, 0 failures. Fixtures recorded from the real API: **owed to 8c-live**.

### 7.2 8b: presets

Built: xAI, Kimi (preview), Qwen (preview), the generic `openai_compatible` dialect (header name, fixed query parameters, `<think>` removal) and the preview `perplexity_router` dialect, each with a contract test extending `ChatCompletionsContract` (`XaiContractTest`, `KimiContractTest`, `QwenContractTest`, `OpenAiCompatibleContractTest`, `PerplexityRouterContractTest`); `Fixtures` and `FixtureProvenanceTest` (every fixture states its source; recorded ones their date and model; none holds a key); `PresetFixturesTest` replays the Kimi, Qwen and Orchestrator fixtures (all `documentation-derived`, header says so); `OpenAiFamilySecretTest` (R4: per-request key fetch, header only, host binding, no key in errors); `ProviderNamesTest` (`HttpClientWrapper.providerOf` knows xai, kimi, qwen, perplexity).
Test that caught a gap: `<think>` removal for the generic dialect was specified but not built until its test failed. Fixtures for OpenAI and xAI: recorded in 8c-live.

### 7.3 8b2: Responses provider

Built: `OpenAiResponsesProvider`, `ResponsesDialect` (`OPENAI`, `PERPLEXITY`; model field by prefix so `preset:medium` is sent as `preset`; an output limit Perplexity requires for `anthropic/*`; which output items are replayed with a tool result), package-private `ResponsesRequestWriter` and `ResponsesReader`, `ModelTraits.perplexity` (the rules of the model behind a `creator/model` slug). Tests: `ResponsesContract` (shared; extends `ProviderContract`, with a new `supportsStopSequences()` hook because the schema has no stop sequences), `OpenAiResponsesContractTest`, `PerplexityAgentContractTest`, and the Responses cases of `OpenAiFamilySecretTest`. Covered: `instructions`/`input` mapping, `previous_response_id` (sends only what follows the last answer, reports `response_id`), `output[]` parsing, refusal, incomplete/content-filter, function-call round trip with the turn's output items kept in `providerData`, streaming events (text, reasoning summary, function-call arguments, completed/incomplete/failed/error, a stream cut before completion), usage with cached and reasoning tokens.
Findings from the docs that shaped it: Perplexity's `output_text.annotations` was an empty array in its own streaming example while the text carried `[web:N]` markers and a `search_results` item held the sources (to be checked in 8d); `web_search` has no search-count cap (only `max_results` per call and a loop bound `max_steps`); the search count is `usage.tool_calls_details.search_web.invocation`.
Fixtures: recorded from the real APIs in 8c-live.

### 7.4 8c: wiring (done 2026-10-22)

Built and tested: `Vendor` (kinds, prefixes, key and base-URL variables, hosts, bare-name inference, `create`) shared by every layer; Loom: `ProviderSpec.KINDS` and the new `options` component (old constructors kept), `DefaultLLMClientFactory` (explicit prefixes before the `contains(...)` rules, so `perplexity/google/gemini-…` is Perplexity's; keys from the store first, then the environment; per-vendor `*_BASE_URL`; the "unknown model" message lists the new forms), `ExecutorValidation` (option checks: `api`, `header`, `query`, `base_url` required for `openai_compatible`, key optional there), `HarnessExecutor.clientFor`, `weave audit` data flows (`SecurityAudit.hostedService`), `weave secret set` binds a vendor's key to the vendor's hosts unless `--allow-host` says otherwise, `weave eval` key hint; Spring `ClientFactory`; `HttpClientWrapper.providerOf`; `windows.properties` (19 entries) and `ContextWindows` (a second provider prefix is dropped: `perplexity/openai/gpt-5.6-sol`); CTK `provider_declarations.loom` and `ProviderDeclarationsCtkTest`; example `src/examples/provider-switching`; VS Code hover and CHANGELOG; Loom guide, prompt, README, `docs/guide` chapters 6, 9, 10; release notes.
Deviations from the draft, recorded here: (1) PriceTable ships no prices by design, so there are no price entries to add (prices for the smoke run are in `eval4j/src/test/resources/smoke-prices.properties`; per-search prices arrive with 8e); (2) loom-inspect and Fabric hold no provider lists (their price book and windows come from the user's files and `ContextWindows`), so 8c changes nothing there; their model-call fields come with 8e; (3) two existing assertions encoded the old behaviour (`gpt-5` and `use: openai` were "unknown") and now use genuinely unknown names (`ProvidersTest`, spring `ClientsTest`).
Full suites after 8c: ai-agent4j 3,248 tests, Loom 2,234 (before the last additions, rerun below), Spring 136, eval4j 541, all green. A mistake caught on the way: several early runs filtered Maven output with `grep` and a compile error looked like a pass; a helper that counts the surefire reports replaced it.

### 7.5 8c-live: smoke run (done 2026-10-22)

`ProviderSmokeIntegrationTest` (eval4j, tag `live`, opt-in): a recording reverse proxy keeps the vendor's real bytes; per vendor one plain chat, one stream, one tool call plus its answer, one streamed tool call and one streamed call asking for reasoning; `SpendGuard` cap US$0.25 per vendor (default); fixtures written scrubbed (`Scrub`: keys, ids, fingerprints) under `ai-agent4j/src/test/resources/providers/<vendor>/` with date and model. It accepts `SPIKE_ANTHROPIC_KEY` for `ANTHROPIC_API_KEY` and `SMOKE_GEMINI_API_KEY` for `GEMINI_API_KEY`, skips a vendor with no key, and refuses to run a vendor with no known price (the cap would protect nothing).

| Vendor | Model | Result | Calls | Tokens in / out | Spend (token prices) |
|---|---|---|---|---|---|
| OpenAI chat completions | `gpt-6-luna` | 5/5 steps (after passing `reasoning_effort: none` for tool steps, see below) | 6 | 517 / 110 | US$0.0001 |
| OpenAI Responses | `gpt-6-luna` | 5/5 | 6 | 272 / 168 | US$0.0001 |
| xAI | `grok-4.20-0309-non-reasoning` | 5/5 | 6 | 1,460 / 129 | US$0.0021 |
| Perplexity Agent API | `openai/gpt-6-luna` | 5/5 | 6 | 388 / 93 | US$0.0001 (Perplexity's own `usage.cost` agrees: a few thousandths of a cent per call) |
| **Total** | | | 24 (+3 in the first failed OpenAI pass) | | **about US$0.003** |

Findings: the first OpenAI pass failed on the tool steps with `HTTP 400 Function tools with reasoning_effort are not supported for gpt-6-luna in /v1/chat/completions` (use `reasoning_effort: none` or the Responses API); documented in the wiki and release notes. `RecordedFixturesTest` replays every recorded fixture through its provider (22 tests); the OpenAI, xAI and Perplexity providers are therefore **supported**. Kimi and Qwen: no key (skipped by the runner); Anthropic and Gemini: no key here (skipped). Account balances were not read; the spend above is computed from the reported token counts and the published prices.

### 7.6 8d: payload look (done 2026-10-22)

`grounding-payloads/capture.py` (a throwaway script, kept) printed and saved the raw grounded responses of Perplexity's Agent API, OpenAI's Responses API and xAI's Responses API, whole and streamed, with and without the search options we plan to map (10 calls, all HTTP 200; the script's own conservative estimate US$0.089, Perplexity's reported cost about US$0.003 a call). Anthropic and Gemini: not captured (no key). DESIGN §4.3 was rewritten from the payloads **before** any grounding Java (see `grounding-payloads/README.md` for the nine findings: sources are *cited* for OpenAI and xAI but only *consulted* for Perplexity, whose `annotations` is empty; a search is not a query; OpenAI reports no search count in `usage`; Perplexity and xAI have no usable search cap while OpenAI's `max_tool_calls` works; spans cover the link the model wrote; the final `response.completed` event carries everything, so streaming parity follows).
Spend so far, all live calls: smoke runs about US$0.003, payload capture about US$0.03 to 0.09 (upper bound), total well under the few dollars planned.

### 7.7 8e: the contract (done 2026-10-22)

Built in `ai-agent4j`: `Grounding` (builder, `lenient()`, `fallback(Tool)`), `Citation`, `Span`, `SearchUsage`, `GroundingSupport`, `GroundingOption`, `Recency`; `LLMRequest.grounding`; `LLMResponse.getCitations/getSearches`; `LLMProvider`/`LLMClient` defaults (`NONE`); the central check and the fallback loop in `DefaultLLMClient`; the `searches` budget dimension (`Limits`, `Spent`, `Remaining`, `Charge`, `BudgetSet.reserve(..., searches)`, `Dimension.SEARCHES`); `PriceTable` per-1000 search price (optional third number); `ModelCallEvent`/`ModelStats`/`RunStats` search and citation counts. Every old constructor and shape is kept (additive).

Decisions and deviations:
- The request-bytes baselines are compared as JSON with sorted keys. The first attempt compared raw bytes and failed intermittently on `google/tools.txt`: the tool-schema map prints its keys in a different order from one JVM run to the next, in the code as released, so a raw-byte baseline cannot be stable. All 48 re-recorded files were checked equal to the originals once keys are sorted.
- A grounded call reserves `max(1, maxSearches)` searches and refuses only when fewer than one is left (DESIGN §4.5 updated).
- No provider declares grounding yet: that is 8f. The wiki page lists the vendors as "coming".

Docs: new wiki page `Web-Search-Grounding.md` (six samples, executed and checked verbatim); budgets, telemetry, providers pages, Home, README and `llms.txt` link it. Full `ai-agent4j` suite: 3,310 tests, 0 failures; Loom, eval4j and Spring compile against the changes.

