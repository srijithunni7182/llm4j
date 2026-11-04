# Phase 30 verification plan: native JSON output for extractors

Status: **30a built (2026-11-04) with the owner's go-ahead to use the recommendations for N1 to N9; 30b to 30g not started** (the Gemini question O1 is still open). Proposed sub-phases: **30a** the request field, the capability, the schema builder and the extractor wiring with a fake provider (R1, R2, R3, R5 to R8, R10); **30b** OpenAI-style providers: OpenAI and xAI chat and Responses, Perplexity Agent API (R4, R9); **30c** Anthropic; **30d** Google (blocked on O1); **30e** Ollama; **30f** Kimi and Qwen (after their fixtures); **30g** documentation and the skills.
Strategy: [TEST_STRATEGY.md](TEST_STRATEGY.md).

## 0. Before writing

| # | Check | Result |
|---|---|---|
| P1 | Read each vendor's official documentation for the field, the strict-schema rules, the refusal and truncation behaviour, and the supported models; record the date | ◐ 2026-11-04: done for OpenAI (Responses; Chat Completions reference refused the fetch), xAI, Anthropic, Ollama, Perplexity, Kimi, Qwen, Mistral and Groq; Gemini's schema field for `generateContent` and DeepSeek not confirmed (DESIGN §3, O1, O4) |
| P2 | Settle O1: which Gemini request the provider uses and its fields | open |
| P3 | Confirm the OpenAI Chat Completions request shape from a recorded call | open |
| P4 | Owner's decisions on N1 to N9, especially N4 (schema mode only) and N5 (no script switch) | open |
| P5 | Estimate for the live fixtures (one small call per provider, a model each) and ask before running | open; will be put to the owner first |

## 1. Acceptance criteria

| # | Criterion | Evidence |
|---|---|---|
| A1 | `LLMRequest` carries an optional `ResponseFormat`; old calls are unchanged; every decorator passes it on | the R1 tests |
| A2 | `structuredOutput(model)` answers per provider and model, unknown means `NONE`, decorators delegate | the capability table test |
| A3 | One extractor schema passes every provider's strict-rule checker; a schema over a limit goes to the prompt route and `weave check` says so | the R3 tests |
| A4 | Each switched-on provider sends exactly its documented field; a client that cannot honour it omits it | one `MockWebServer` test each |
| A5 | The extractor's checks are unchanged; a refusal, a cut-off reply and a schema 400 are handled as designed | the R6 tests and the phase 24 suite |
| A6 | The mode is in the trace, the fingerprint and the eval table | the R5 and R7 tests |
| A7 | A recorded real reply exists for each switched-on provider, and the date of the documentation reading is in its data | the fixtures; the data files |
| A8 | The docs, the skills and the release notes say which providers use it and which do not, and why | §3 |
| A9 | The mutation profile passes its threshold on the decision classes | `mutation-structured` |

## 2. Manual checks

| # | Check |
|---|---|
| M1 | A real extractor on each switched-on provider returns the same fields as on the prompt route for the same input (the owner's estimate first) |
| M2 | A very wide extractor shows the `weave check` information line and still works |
| M3 | Reading the provider table in the docs, a developer can tell whether their provider and model will use the native route |

## 3. Documentation to update with the code (CLAUDE.md principle 7)

`src/ai-agent4j/wiki` (providers, a new page on structured output), `LOOM_GUIDE.md` (the extractor section: how it asks the provider), `LOOM_PROMPT.md`, `docs/API_COMPATIBILITY.md` (the new request field and capability), `docs/RELEASE_NOTES_<version>.md`, the provider READMEs, `llms.txt`, the `llm4j-workflow-guide` skill's reference on extractors, the phase 24 design (replace "deferred" with a link to this phase), and the specs README rows.

## 4. Risks

- **Vendors change quickly.** Gemini's page moved to a new API within the time of this design; N9 and the dated data are the answer.
- **A schema accepted by one provider and refused by another.** The universal schema and the profile checker are the answer; the 400 fallback is the net.
- **Silent behaviour change.** The trace, the fingerprint and the `weave check` line make the mode visible.

## 7. What was built and what was not (2026-11-04)

**30a built, green, pushed on the branch `native-json-extractors`.**

| Item | Where | Result |
|---|---|---|
| R1 the request field | `ResponseFormat`, `StructuredOutput`, `LLMRequest.getResponseFormat`/`responseFormat` | `ResponseFormatTest` (6) |
| R2 the capability | `LLMClient`/`LLMProvider.structuredOutput`, `Dialect.structuredOutput`; delegated by `DefaultLLMClient`, `MaskingLLMClient`, `BudgetedLLMClient`, `RoutingLLMClient` (all clients must say yes), `ServedModelRecorder`, `LlmCallCounter`, `SpendGuard` | `StructuredOutputCapabilityTest` (17), `WrappersKeepStructuredOutputTest`, `ServedModelRecorderStructuredOutputTest` |
| R3 the schema | `ExtractorSchema` (all fields required, optional = nullable, closed objects, the strictest limits) | `ExtractorSchemaTest` (10) |
| R4 the wire format, OpenAI and xAI chat completions only | `ChatRequestWriter`, `Dialect.OPENAI`/`XAI` patterns | `StructuredOutputWireTest` (5), exact body on a MockWebServer |
| R5 use and trace | `ExtractRunner.modeFor`; `structured` and `structured_reason` on `extract_end` | `NativeJsonExtractTest` (10) |
| R6 handling | a cut-off reply says "token limit"; a provider's 400 retries once without the schema and is remembered for the model (`structured_fallback`) | `NativeJsonExtractTest` |
| R10 | the schema comes from the declarations only; a hostile input leaves it unchanged | `NativeJsonExtractTest` |

Verified: `mvn verify` on `ai-agent4j` (3461 tests, 2 skipped), `mvn test` on `eval4j` (576), `mvn verify` on `ai-agent4j-loom` with its coverage gates (4706, 3 skipped).

**Deviations from the design, and what covers each:**

1. **Closed 2026-11-04:** the live fixture was recorded afterwards ([LIVE_FIXTURES.md](LIVE_FIXTURES.md): six calls on gpt-4o-mini and grok-4-fast, all native on the first attempt). It was first listed as: no live fixture was recorded before switching OpenAI and xAI on (N6). The xAI page gives the request shape verbatim, and OpenAI-compatible chat completions use the same nested form, but the OpenAI chat reference refused the fetch (O2). What was lost until the fixture: proof that the real API accepts our exact body. What covered it: the exact-body test, the fallback (a refused request is made again without the schema), and the unchanged validation of every answer. 
2. **A refusal reported in a provider's own field** (OpenAI `refusal`) is not read yet. It arrives as an empty or non-matching reply and is treated as a failed attempt by the existing checks. Covered by the unchanged validation; the provider-specific reading belongs to 30b with the Responses writers.
3. **R5's `weave check` information line** for a too-wide extractor is not written. `weave check` has only errors and warnings, and a review rule would drag in the corpus and the noise gate. The reason is in the trace (`structured_reason: schema_limits`).
4. **R7:** the node fingerprint does not name the mode. The mode is a function of the model and the library version, and the model is already in the fingerprint; a library upgrade that newly turns a model native would not mark the extractor changed. The eval table does not show the mode yet.
5. **R9** (Perplexity's first-call timeout, Kimi and Qwen thinking, Anthropic) belongs to the providers that are not built.
6. **The mutation profile `mutation-structured`** (TEST_STRATEGY §5) is not added yet.
7. **Guard checks by hand** (TEST_STRATEGY §4) are not done yet.

**Not started:** 30b (OpenAI and xAI Responses, Perplexity Agent API), 30c Anthropic, 30d Google (blocked on O1), 30e Ollama, 30f Kimi and Qwen, 30g the wiki and skills pages beyond those updated here.

**Docs updated with 30a:** `LOOM_GUIDE.md` (extractor section), `LOOM_PROMPT.md`, `docs/API_COMPATIBILITY.md`, `docs/RELEASE_NOTES_6_2_0.md`, `src/ai-agent4j/wiki/OpenAI-Family-Providers.md`.
