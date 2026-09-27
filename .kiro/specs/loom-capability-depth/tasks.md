# Implementation Plan

- [ ] 1. **ai-agent4j library additions and fixes**
  - [ ] 1.1 `FileMemoryVectorStore` (agent/memory). `ReActAgent.Builder.semanticRecall`; `toBuilder`
        keeps the TTS and recall settings.
  - [ ] 1.2 Sarvam:
        - `detectLanguage` calls `/text-lid`;
        - `TranscriptionRequest.model`;
        - chat uses the default model.
  - [ ] 1.3 `FileGraphStore`.
  - [ ] 1.4 `MaskingLLMClient`, `RuleBasedBiasMonitor`, `LLMBiasMonitor`.
  - [ ] 1.5 Unit tests for each; `mvn install`.
- [ ] 2. **Providers (R3)**
  - [ ] 2.1 `ProviderDef` and parser.
  - [ ] 2.2 `ProviderSpec`, the `LLMClientFactory` defaults, and `DefaultLLMClientFactory` (env lookup,
        `sarvam/`, `problem`).
  - [ ] 2.3 Executor `clientFor` and `checkProviders`.
- [ ] 3. **Agent memory (R1)**
  - [ ] 3.1 AST and parser (new keys; old keys kept for migration errors).
  - [ ] 3.2 Validation (replacing the "unsupported" error).
  - [ ] 3.3 `AgentMemory`: per-session agents, the facts tool, audit and trace. No replay effects.
- [ ] 4. **Voice and languages (R2)**
  - [ ] 4.1 `SafePaths`, `SarvamServices`, `LanguageTools` kinds, built-ins.
  - [ ] 4.2 Agent `voice`: parser, validation, listen and speak in the delegate, journaled audio.
- [ ] 5. **Knowledge graph tool (R4)**
- [ ] 6. **Guards (R5)**: parser, validation, mask client, block/warn checks, bias monitors, audit.
- [ ] 7. **Remote skills and `skill_registry` (R6)**
- [ ] 8. **Personas and templates (R7)**
- [ ] 9. **Trace (R8)**: events, agent adapter, CLI `--trace[=json]`.
- [ ] 10. **Docs (R9)**
  - LOOM_GUIDE sections and a parse-checked example file.
  - The READMEs, LOOM_PROMPT and VS Code.
  - The gap analysis status.
- [ ] 11. **Full suites green and results recorded in `verification.md`**
- [ ] 12. *(optional, needs keys)* Live Sarvam and Gemini checks (L1–L3).
