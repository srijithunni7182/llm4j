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

| # | Criterion | Requirement | Test |
|---|---|---|---|
| A1 | Each new provider passes the full `ProviderContract` | R1 | six `*ContractTest` classes |
| A2 | Adding a dialect needs no change to `OpenAiCompatibleProvider` outside its constants and `ModelTraits` | R2 | code review checklist + a test that registers a made-up dialect |
| A3 | Unknown model names at a known vendor work with safe defaults | R3 | `ModelTraits` tests |
| A4 | Keys never appear in exceptions, logs, events or traces; secrets go only to bound hosts | R4 | canary-key and host-binding tests |
| A5 | Loom `use:` kinds, resolver prefixes, bare-name inference and the ambiguous-name behaviour are as specified | §3.3 | wiring tests |
| A6 | Windows and prices exist for the listed models; unknown models fall back | §3.3 | catalog tests |
| A7 | Requests without `grounding` are byte-identical to the previous release for Gemini, Anthropic, Sarvam, Ollama | R5 | request-bytes regression test |
| A8 | Grounding on an unsupported provider or option is refused before any network call, naming both | R6 | grounding unit tests |
| A9 | Citations are typed, indices stable, spans in range | R7 | grounding provider tests |
| A10 | Search usage is as the vendor reports; unknown stays unknown | R8 | grounding provider tests |
| A11 | Streaming and non-streaming agree on citations and search usage | R9 | parity tests |
| A12 | A grounded agent is untrusted-content exposure in `weave audit`, with or without `allow:` | R10 | Loom audit tests |
| A13 | Searches are charged to the `searches` budget; a breach halts | R11 | budget tests |
| A14 | A grounded turn followed by a tool turn is valid for Anthropic and Kimi | R12 | provider tests |
| A15 | Fallback to a client-side tool yields citations in the same shape | §8.3 | grounding unit tests |
| A16 | eval4j citation assertions pass and fail correctly; scripted citations work offline | §6 | eval4j tests |
| A17 | The live smoke run passes for every vendor with a key, within its spend cap, and recorded fixtures replace the hand-written ones | §5 of the test strategy | `ProviderSmokeIntegrationTest` (live) |
| A18 | All documentation samples execute in tests | docs | docs examples test |
| A19 | `OpenAiResponsesProvider` passes `ProviderContract` for the Perplexity Agent API and OpenAI Responses; `web_search` results map to typed citations | §3.4, R1, R7 | contract and grounding provider tests |
| A20 | (If Phase 7 is merged) `PerplexityDecisionsProvider` passes `ClassifierContract` and shares `DecisionWire` with `JevWire` | §3.5 | classifier contract tests |

## 3. Traceability

Filled in as tests are written: one row per requirement `R1`…`R12`, naming the test classes that cover it. A requirement with no row blocks the phase.

| Req | Tests |
|---|---|
| R1–R12 | _to be listed when the tests are written_ |

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

_Not started._ Smoke-run results, fixture dates and models, and live spend per vendor go here.
