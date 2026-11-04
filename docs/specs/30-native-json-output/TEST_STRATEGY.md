# Phase 30 test strategy: native JSON output for extractors

How each requirement of [DESIGN.md](DESIGN.md) is tested, under the rules of [TESTING_STRATEGY.md](../../TESTING_STRATEGY.md): hermetic, deterministic, no real model and no network in the default build. Tests are written first and run red before the code.

## 1. Principle: assert the bytes on the wire, and that nothing weaker happens

Two things must hold. The **request** each provider sends must be exactly what its documentation says, so the central device is a **recording HTTP server** (OkHttp `MockWebServer`) that captures the request body. And the **extractor's guarantees** must be as strong as before, so every outcome a provider can return (valid JSON, JSON in a code fence, a refusal, a cut-off reply, a 400 about the schema) is replayed from a fixed response and the same assertions as phase 24 are made.

## 2. Devices

| Device | Used for |
|---|---|
| **`MockWebServer` per provider** | the request body of each writer, byte for byte (R4); the 400 fallback (R6) |
| **Capability table test** | `structuredOutput(model)` for a table of provider and model names, including unknown ones and decorators (R2) |
| **Schema profile checker** | a test-only class that applies each provider's published strict rules to a schema (all `required`, `additionalProperties: false`, no unsupported keywords, size limits) and is run over a generated set of extractors (R3) |
| **Capturing client** (phase 24) | that a node still receives only its inputs, and now also which `ResponseFormat` it was sent (R5) |
| **Recorded fixtures** (`@Tag("live")`, run by hand with the owner's estimate first) | one real call per provider that returns a reply the reader parses, recorded before the provider is switched on (N6) |

## 3. Per requirement

| Requirement | Tests |
|---|---|
| **R1** request field | builder rejects a blank name and a non-object schema; `toBuilder` keeps it; old builder calls give the same request; a decorator passes it on (a test per decorator type) |
| **R2** capability | a table: each provider and model gives `JSON_SCHEMA` or `NONE`; an unknown model gives `NONE`; Ollama Cloud gives `NONE`; a decorator returns its delegate's answer |
| **R3** schema | every field type maps as designed; an optional field is `required` plus a nullable union; `additionalProperties` is `false` at every level; the schema passes **every** provider's profile checker; a schema over a limit is reported and the runner chooses the prompt route; the prompt text is identical in both modes |
| **R4** wire formats | one `MockWebServer` test per writer: OpenAI Chat, OpenAI Responses, xAI Chat, xAI Responses, Anthropic, Google, Ollama, Perplexity Agent, Kimi, Qwen. Each asserts the exact field path and values, and that a client that cannot honour the field **omits it** |
| **R5** use and trace | `structured: native` and `structured: prompt` (with each reason) appear in the trace; `weave check` prints the information line for a too-wide extractor and not otherwise; no behaviour changes without a trace entry |
| **R6** validation | the phase 24 suite (`MissingOptionalFieldReadsAsMissingTest`, `NullRequiredFieldFailsAtOnceTest`, …) runs unchanged against a native-mode client; a refusal fails the step with the provider's words and does not loop; a `max_tokens` cut takes the correction path and says why; a 400 about the schema falls back once, traces `structured_fallback`, and the next call in the run does not try native again |
| **R7** fingerprint | changing the model from a prompt-route one to a native one changes the extractor's fingerprint and `--changed` selects it; the eval table shows the mode; `--mock` and `weave test` are unchanged |
| **R8** cost | the estimate line carries the note for a native extractor; the budget check still runs before the call |
| **R9** provider care | Perplexity's first-call timeout is raised and the trace says why; Anthropic requests carry no citations and no prefill; a Kimi or Qwen reply with `reasoning_content` is parsed from `content` only; a Qwen non-thinking-only model is sent thinking off |
| **R10** security | the schema is built from declarations only (a test that puts hostile text in a field description and in an input and checks that neither changes the schema's structure); no request, log line or trace holds a key; a model's answer inside the schema still passes the provenance rules |

## 4. Guard checks (done by hand once, recorded in VERIFICATION)

- Remove the "unknown model means prompt route" default: the capability table test must fail.
- Let a writer send the field to a client that cannot honour it: the `MockWebServer` omission test must fail.
- Make the 400 fallback loop: the "next call does not retry native" test must fail.

## 5. Mutation testing

The decision logic (R2 capability, R3 limits, R5 choice, R6 fallback) is security-adjacent and decision-making, so its classes are added to a PIT profile in `ai-agent4j` and Loom (`mutation-structured`), with the 85% threshold of the credentials profile. Surviving mutants are killed with better assertions, never excluded.

## 6. Deliberately not tested in the default build

- That a real model obeys the schema (provider behaviour; checked by the recorded fixtures and the optional live run).
- Providers' latency, including Perplexity's first-schema delay, beyond that our timeout allows for it.
- Vendors' documentation staying the same. The date each provider's data was read is recorded (N9) and the live fixture is the check.
