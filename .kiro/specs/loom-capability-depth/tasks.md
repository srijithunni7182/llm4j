# Implementation Plan

- [x] 1. **ai-agent4j library additions and fixes**
  - [x] 1.1 `FileMemoryVectorStore` (agent/memory). `ReActAgent.Builder.semanticRecall`; `toBuilder`
        keeps the TTS and recall settings.
  - [x] 1.2 Sarvam:
        - `detectLanguage` calls `/text-lid`;
        - `TranscriptionRequest.model`;
        - chat uses the default model.
  - [x] 1.3 `FileGraphStore`.
  - [x] 1.4 `MaskingLLMClient`, `RuleBasedBiasMonitor`, `LLMBiasMonitor`.
  - [x] 1.5 Unit tests for each; `mvn install`.
- [x] 2. **Providers (R3)**
  - [x] 2.1 `ProviderDef` and parser.
  - [x] 2.2 `ProviderSpec`, the `LLMClientFactory` defaults, and `DefaultLLMClientFactory` (env lookup,
        `sarvam/`, `problem`).
  - [x] 2.3 Executor `clientFor` and `checkProviders`.
- [x] 3. **Agent memory (R1)**
  - [x] 3.1 AST and parser (new keys; old keys kept for migration errors).
  - [x] 3.2 Validation (replacing the "unsupported" error).
  - [x] 3.3 `AgentMemory`: per-session agents, the facts tool, audit and trace. No replay effects.
- [x] 4. **Voice and languages (R2)**
  - [x] 4.1 `SafePaths`, `SarvamServices`, `LanguageTools` kinds, built-ins.
  - [x] 4.2 Agent `voice`: parser, validation, listen and speak in the delegate, journaled audio.
- [x] 5. **Knowledge graph tool (R4)**
- [x] 6. **Guards (R5)**: parser, validation, mask client, block/warn checks, bias monitors, audit.
- [x] 7. **Remote skills and `skill_registry` (R6)**
- [x] 8. **Personas and templates (R7)**
- [x] 9. **Trace (R8)**: events, agent adapter, CLI `--trace[=json]`.
- [x] 10. **Docs (R9)**
  - LOOM_GUIDE sections and a parse-checked example file.
  - The READMEs, LOOM_PROMPT and VS Code.
  - The gap analysis status.
- [x] 11. **Full suites green and results recorded in `verification.md`**
- [ ] 12. *(optional, needs keys)* Live Sarvam and Gemini checks (L1–L3).
