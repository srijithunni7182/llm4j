# Phase 30 design: native JSON output for extractors

Status: **draft for review** (written 2026-11-04; the decisions N1 to N9 are proposals for the owner). Layer: core (`ai-agent4j`: the request model, the providers), Loom (the extractor runner and its fingerprint), docs. No change to the Loom language.
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Depends on phase 24 (the `extractor`, built). It is the item phase 24 deferred ("native JSON mode, deferred by the owner").

## 1. Problem

An `extractor` is a stateless step that pulls typed fields out of text ([phase 24](../24-stateless-nodes/DESIGN.md)). Today it asks for JSON **in the prompt only** ("answer with ONE JSON object … no prose, no markdown, no code fences") and then parses and checks what comes back. When the answer is not JSON, the runner corrects the model once and retries.

Most hosted providers can now **constrain the answer to a JSON Schema** as part of the request. The reply is then structured JSON by construction, not by good manners. For an extractor that means fewer corrections and retries (each one is a paid call), no code fences or chatter around the JSON, and a schema the provider enforces while it decodes.

What it does **not** mean: the answer is still model output. A provider's own docs say the output can fail the schema on a refusal or when the reply is cut at the token limit. The extractor's own checks (`ExtractAnswer`: a required field must be present, a value must have its type, null for an optional field reads as missing) stay exactly as they are.

## 2. Goals and non-goals

Goals:

- An extractor asks the provider for schema-constrained JSON **where the provider and the model support it**, and falls back to today's prompt route everywhere else (R1 to R5).
- One schema, built once from the extractor's fields, that every provider's strict rules accept (R3).
- Nothing the extractor guarantees today gets weaker: validation, retries, budgets, the journal, the trace, `weave test`, `--mock` and the eval (R6 to R8).
- Every provider the repository serves is accounted for, with the reason when it is left out (§4).

Non-goals:

- Agents' `output_schema`, classifiers, evaluators and the other internal nodes. They can adopt the same request field later; this phase builds it for the extractor only (N8).
- Tool-call `strict` modes (a separate provider feature for tool arguments).
- New Loom syntax. There is no switch in the script (N5).
- Reading a provider's documentation at run time. Support is **data** in the repository, recorded from the docs on a date.

## 3. What the providers' own documentation says (read 2026-11-04)

Facts below come from the vendors' official pages fetched on the date above. Items marked ▲ were read in part from a summary of a long page, and items marked ? could not be confirmed; both must be settled by a recorded fixture before the provider is switched on (N6).

| Provider | Field in the request | Strict-schema rules that matter to us | Notes |
|---|---|---|---|
| **OpenAI**, Chat Completions | `response_format: {type: "json_schema", json_schema: {name, schema, strict: true}}` ? (the API reference refused the fetch) | every field `required`; `additionalProperties: false`; optional = a union with `null` ▲; 5000 properties, 10 levels deep ▲ | a refusal is reported in its own field ▲; older models support only JSON mode |
| **OpenAI**, Responses | `text: {format: {type: "json_schema", name, schema, strict: true}}` (flat, verbatim) | same | a refusal is a content part of type `refusal` ▲ |
| **xAI**, Chat Completions | `response_format: {type: "json_schema", json_schema: {name, schema, strict: true}}` (verbatim) | `additionalProperties` is `false` unless set; not-`required` fields are optional; nullable by type array; `$ref` only if not circular | combining with tools only on Grok 4 family models |
| **xAI**, Responses | `text: {format: {type: "json_schema", name, schema, strict: true}}` (verbatim) | same | works together with server-side `web_search` |
| **Anthropic** | `output_config: {format: {type: "json_schema", schema}}` (verbatim; the old `output_format` plus a beta header is deprecated) | `additionalProperties` must be `false`; no `minimum`, `maximum`, `minLength`, `maxLength`, recursion; 400 on an unsupported keyword; at most 24 optional parameters and 16 parameters with a union type across strict schemas; 180 s to compile | `stop_reason: "refusal"` (HTTP 200) or `"max_tokens"` can leave output that does not match; citations plus a format is a 400; generally available on the current models |
| **Google Gemini** | `generateContent` (what the provider uses): `generationConfig.responseMimeType` with `application/json` is still documented (verbatim); the schema field is **not settled**: the reference lists `responseJsonSchema` with a self-referring description and no `responseSchema` entry, and the newer *Interactions* API page uses `response_format: {type: "text", mime_type: "application/json", schema}` | the Schema object is "a select subset of an OpenAPI 3.0 schema object" (verbatim), with `nullable` and `required`; on the Interactions page: types, `enum`, `required`, `additionalProperties`, `items`, `minItems/maxItems`, nullable as a type array ▲ | `finishReason` includes `MAX_TOKENS` and `SAFETY`; the page does not say whether the JSON is then valid; no deprecation of `generateContent` is stated; **open question O1** |
| **Ollama** | `/api/chat` `format`: a schema object (or the string `"json"`) (verbatim) | keyword restrictions not documented ? | **Ollama Cloud does not support it** (verbatim); the docs advise also putting the schema in the prompt; the OpenAI-compatible `response_format` shape is not documented ? |
| **Perplexity**, Agent API | `response_format: {type: "json_schema", json_schema: {name, schema}}` (not `text.format`) | `name` 1 to 64 letters or digits; fields not in `required` may be `null`; unsupported keywords not stated ? | the **first request with a new schema waits 10 to 30 seconds** and may time out (verbatim); Sonar Chat Completions support ended 2026-09-27 |
| **Perplexity**, Orchestrator (Chat Completions, private preview) | ? not documented in what was read | ? | stays on the prompt route |
| **Kimi** (Moonshot) | `response_format: {type: "json_object"}` or `{type: "json_schema", json_schema: {name, strict, schema}}` | the vendor's own "MFJS" rules; `kimi-k3` and `kimi-k3.1-pro` reliable; `kimi-k2.6` unstable on complex schemas | thinking models return `reasoning_content`; only `content` is the JSON; combining with `tools` not confirmed |
| **Qwen** (DashScope, Model Studio) | `response_format: {type: "json_object"}` (the prompt must contain the word "JSON") or `{type: "json_schema", json_schema: {…, strict: true}}`; Responses API uses `text.format` | schema mode only for the Qwen3.7 and Qwen3.8 Plus, Flash and Max series; non-thinking only for models labelled so | a media input falls back to `json_object` ▲ |
| **Mistral** (generic kind) | `response_format: {type: "json_object"}`; a custom schema is passed to the model **in the system prompt**, not constrained | not constrained decoding | |
| **Groq** (generic kind) | `response_format: {type: "json_schema", json_schema: {name, strict, schema}}` | strict needs all fields `required` and `additionalProperties: false`; strict only on listed models; "streaming and tool use are not currently supported" with it | |
| **DeepSeek** (generic kind) | ? not found in the pages that were served | ? | prompt route |

## 4. Every provider the repository serves

| Provider kind in the repo | Decision |
|---|---|
| `openai` (chat), `openai` with `api: responses` | native schema on supported models (R2) |
| `xai` (chat and responses) | native schema on Grok 4 family models |
| `anthropic` | native schema on the models the docs list |
| `google` | native schema, subject to O1 |
| `ollama` | native schema for a local server; off for Ollama Cloud |
| `perplexity` (Agent API) | native schema, with the first-call delay handled (R9) |
| `perplexity_router` (private preview) | prompt route (not documented) |
| `kimi`, `qwen` (preview vendors) | native schema only for the models the docs name; `json_object` is **not** used for them (see N4) |
| `openai_compatible` (generic: Mistral, Groq, DeepSeek, Together, vLLM, LM Studio, …) | prompt route by default. A user who knows their server supports it can say so when building the dialect (N7) |
| Sarvam (translation, speech), the typed-decision provider (Jev, Decisions API) | not applicable: they are not chat models that write free JSON |

## 5. Requirements

**R1 A typed request field.** `LLMRequest` gains an optional `ResponseFormat` (a name, a JSON-Schema object as a map, and `strict`). It is a field of the request, **not** a key hidden in `additionalParameters`: a decorator (`MaskingLLMClient`, a retrying or budget client) must see it and pass it on, and a provider that does not understand it must be able to tell. The builder rejects a blank name and a schema that is not an object. `toBuilder` keeps it. Old constructors and builder calls are unchanged (compatibility, `docs/API_COMPATIBILITY.md`).

**R2 A capability the provider declares, per model.** `LLMClient` gains `StructuredOutput structuredOutput(String model)` returning `NONE` or `JSON_SCHEMA`, default `NONE`. A provider answers from data it holds: the dialect and the model traits for the OpenAI-style vendors (a list of models or a name pattern per vendor, as `ModelTraits` already does for reasoning), the model list for Anthropic, a switch for Ollama (off for Ollama Cloud), and so on. **Unknown means `NONE`**: a model name nobody recorded stays on the prompt route (the safe, older behaviour). Every decorator delegates the answer (Liskov: a wrapper behaves like what it wraps).

**R3 One schema for every provider.** The extractor's fields become one JSON Schema built by one class (`ExtractorSchema`, in Loom), using only what **all** the strict rules above accept:

- `type: object`, `additionalProperties: false`, **every field listed in `required`**;
- an optional field is written as a union of its type and `null` (the extractor already reads `null` as missing, so the meaning is the same);
- types limited to `string`, `number`, `integer`, `boolean`, `enum` (strings), `array` and nested `object`;
- no `minimum`, `maximum`, `minLength`, `maxLength`, `format`, `$ref`, recursion or `anyOf` beyond the nullable union;
- a size check against the strictest limits (properties, depth, the Anthropic count of optional fields). **A schema over any limit sends the extractor to the prompt route** and `weave check` says so as information (R5).

The same schema also goes into the prompt as it does today (Ollama's docs advise it, Mistral's custom mode relies on it, and Qwen's `json_object` mode needs the word JSON). So the prompt does not change when the mode does.

**R4 The wire formats.** Each provider turns `ResponseFormat` into its field from §3: `ChatRequestWriter` (the `response_format` form, with the nested `json_schema`), `ResponsesRequestWriter` (the flat `text.format` form), and for Perplexity's Agent API its own key. Anthropic writes `output_config.format`; Ollama writes `format`; Google writes whichever form O1 settles on. A provider that gets a `ResponseFormat` it cannot honour **ignores it** and the extractor still works through the prompt. Each writer is pinned by a MockWebServer test on the exact request body, and a vendor is switched on only after a fixture recorded from the real API exists (N6, as the preview vendors already require).

**R5 When the extractor uses it, and when it says so.** `ExtractRunner` sends a `ResponseFormat` only when `client.structuredOutput(model)` is `JSON_SCHEMA` and the schema is within the limits. The trace event for the call carries `structured: native` or `structured: prompt` (and, for the prompt route, the reason: `provider`, `model`, `schema_limits`). `weave check` prints one information line per extractor on the prompt route because of its schema, so a developer can split a very wide extractor. No script ever changes behaviour silently.

**R6 Validation does not change.** `ExtractAnswer` reads the reply as it does now. Three provider outcomes are handled on purpose:

- a **refusal** (Anthropic `stop_reason: "refusal"`, OpenAI's refusal field or content part) is a failed extraction, not a retry loop: it is reported with the provider's words and counted as a failure of the step, and the script's `on_failure` applies;
- a reply **cut at the token limit** (`max_tokens`, `finish_reason: length`) is incomplete JSON: it takes the existing correction-and-retry path, and the failure message says the limit was hit;
- a provider's **400 that names the schema** (an unsupported keyword) falls back to the prompt route once, is traced as `structured_fallback`, and is remembered for the rest of the run for that provider and model so it does not repeat on every call.

**R7 Fingerprints, journal and eval.** The mode changes what a node does, so `NodeFingerprints` (phase 28) include `structured: native|prompt`: switching a model onto the native route marks the extractor as changed for `--changed`. The journal and a resumed run are unchanged (the recorded answer is reused). `weave eval`'s per-node table shows the mode. `--mock` and `weave test` give the same fields either way (a mock has no provider).

**R8 Cost and budgets.** A schema in the request adds input tokens on some providers; the cost estimate does not try to guess it and says "native JSON adds a small unknown amount" on the extractor's line (honest, as phase 22 asks). Budgets are checked before the call exactly as now.

**R9 Provider-specific care.**

- **Perplexity**: a new schema costs 10 to 30 seconds on its first request. The extractor's timeout must allow it, or the first call fails and the retry then succeeds. The runner raises the first-call timeout for that provider to the documented figure rather than telling every script author to know it; the trace says why.
- **Anthropic**: citations cannot be on with a format (a 400); message prefill is incompatible. The extractor uses neither.
- **Kimi / Qwen thinking models**: only `content` is the JSON; `reasoning_content` is ignored. A Qwen model labelled non-thinking-only is sent the format only with thinking off.
- **Gemini**: see O1.

**R10 Security.** The schema is built from the script's own field declarations, never from model or tool text. The output is untrusted exactly as before and goes through the same checks, then (once a policy exists) the same provenance rules. A schema never contains a secret. The new request field is not logged with its schema at a level that could hold user text (the schema is the script's, but the instructions beside it are not logged either, as now).

## 6. Decisions proposed for the owner

| # | Decision |
|---|---|
| N1 | Build the request field (R1) and capability (R2) in the core, and use them from the extractor only. |
| N2 | One universal strict schema (R3), not one per provider. A wider extractor stays on the prompt route and is told so, rather than building provider-specific variants. |
| N3 | **Unknown model means prompt route.** A recorded list per vendor is data; adding a model to it is a one-line change with a test. |
| N4 | Use **only** the schema mode. `json_object` ("valid JSON, any shape") is not used: it guarantees no fields, the prompt already asks for JSON, and Qwen's mode needs special wording. This leaves Kimi and Qwen models without schema support on the prompt route. |
| N5 | No Loom syntax and no per-extractor switch in this phase. If a developer needs one (a provider that misbehaves), a later `structured: off` on the extractor is cheap. **Open for the owner.** |
| N6 | A provider is switched on only after (a) a MockWebServer test pins its request, and (b) a fixture recorded from the real API shows the reply. Preview vendors (Kimi, Qwen) stay off until their fixtures exist, as they do today. |
| N7 | The generic `openai_compatible` kind stays on the prompt route; a user can declare support in the dialect builder (`structuredOutput(JSON_SCHEMA)`). The repository does not guess for Mistral, Groq or DeepSeek. |
| N8 | Agents' `output_schema`, classifiers and evaluators are out of scope; the request field is built so they can adopt it later. |
| N9 | The vendors' page contents are read again before each provider is turned on (they change quickly; for example, Gemini's page has moved to a new API). The date of the reading is recorded beside each provider in the data. |

## 7. Open questions

- **O1 Gemini.** Two things are known: the reference still documents `responseMimeType` for `generateContent` and says nothing of retiring the method; and the docs now describe a newer Interactions API. Not known: the exact schema field for `generateContent` (`responseSchema` as an OpenAPI-subset schema, or `responseJsonSchema` for full JSON Schema), whether either combines with function calling, and whether the provider should move to Interactions. **Settled 2026-11-04:** a recorded call showed `generateContent` takes `generationConfig.responseMimeType: "application/json"` with `responseJsonSchema` (full JSON Schema, nullable as a type list), so the provider stays on `generateContent`; moving to Interactions remains a separate decision.
- **O2 OpenAI Chat Completions** shape could not be confirmed from the official reference (it refused the fetch). The Responses form is verbatim. A fixture settles it (N6).
- **O3 Ollama's OpenAI-compatible endpoint**: the request shape is undocumented in what was read; the native `format` field is used instead.
- **O4 Perplexity's Orchestrator** and **DeepSeek**: nothing found; they stay on the prompt route.
