# Implementation Plan

Shared pieces come first, so the provider edits stay small.

- [x] 1. **Shared contract pieces** (all additive)
  - [x] 1.1 `FinishReason.fromValue` synonyms; C15.
  - [x] 1.2 `LLMException.responseBody`; status constructors on `AuthenticationException` and
        `InvalidRequestException`; new `ServiceUnavailableException`.
  - [x] 1.3 `HttpClientWrapper`: typed throws, provider-message extraction, request-id; add 502, 503, 504
        and 529 to `RetryPolicy.defaults()`.
  - [x] 1.4 `HttpClientWrapper.stream(...)`: `StreamingBody` with `events()` (server-sent events) and
        `lines()` (NDJSON); `StreamChunks` builder.
  - [x] 1.5 `Providers.typed(...)`; default `LLMProvider.chatStream`; C13.
- [x] 2. **Conformance suite skeleton**: `ProviderContract` with C1–C12 and C14, and the `Fixtures`
      interface.
- [x] 3. **Existing providers** (small edits only)
  - [x] 3.1 Google: catch lines, `systemInstruction`, same-role merge, `finish_reason_raw`, native
        `chatStream`; `GoogleContractTest`.
  - [x] 3.2 Ollama: catch lines, `finish_reason_raw`, NDJSON `chatStream`; `OllamaContractTest`.
  - [x] 3.3 Sarvam chat: catch line, `finish_reason_raw`, server-sent-events `chatStream`;
        `SarvamContractTest`. Sarvam TTS, STT and text: catch lines only.
- [x] 4. **Anthropic**: `AnthropicModels`, `AnthropicProvider` (chat, stream, refusal, effort);
      `AnthropicContractTest`.
- [x] 5. **Loom**: anthropic kind, factory, provider declaration, checks; tests.
- [x] 6. **Build**: exclude the `live` group by default; add a `live` profile to ai-agent4j and Loom.
- [x] 7. **Live suite**: `LiveProviderContract` (L1–L9), `LiveSwitchingTest` (L10), Loom live test (L11).
      They compile, and are skipped without keys.
- [x] 8. **Compatibility pass**: K1–K6; update and list any tests that asserted old behaviour.
- [x] 9. **Docs**:
  - a new ai-agent4j wiki page, "Providers and the Uniform Contract", covering the contract, how to
    switch, how to run the live suite, and a per-provider status table;
  - README;
  - LOOM_GUIDE and LOOM_PROMPT (anthropic models).
- [x] 10. **Full suites green**; results recorded; commit and push.
- [x] 11. **You run the live suite with your keys**; results are recorded, and whatever it reveals is
      fixed. Anthropic was run on 2026-09-29; the fixes were the ReAct `"plan"` field and Loom note
      interpolation. Gemini, Sarvam and Ollama are waiting on credentials.
