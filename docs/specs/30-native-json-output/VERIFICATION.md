# Phase 30 verification plan: native JSON output for extractors

Status: **not started** (design written 2026-11-04, awaiting the owner's decisions N1 to N9 and the Gemini question O1). Proposed sub-phases: **30a** the request field, the capability, the schema builder and the extractor wiring with a fake provider (R1, R2, R3, R5 to R8, R10); **30b** OpenAI-style providers: OpenAI and xAI chat and Responses, Perplexity Agent API (R4, R9); **30c** Anthropic; **30d** Google (blocked on O1); **30e** Ollama; **30f** Kimi and Qwen (after their fixtures); **30g** documentation and the skills.
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
