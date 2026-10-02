# G7: docs

Base commit fa50f17.

- `RewindGuideTest` (5 tests, passing in the G1 runs): every `loom` block in the "Checkpoints, Rewind and Fork" section of `LOOM_GUIDE.md` parses and validates; the documented commands and options (`timeline`, `rewind`, `reset`, `fork`, `--stop-at`, `--max-rewinds`, `--effects`, `--reason`) exist on the real command line; `--max-rewinds` caps a run.
- `weave --help` in the packaged JAR lists `timeline`, `rewind`, `reset`, `fork` (G6).
- Editor: `loom/vscode-loom/src/lsp/server.ts` carries hover text for `checkpoint`, `rewind` and the side-effects words.
- Also updated: `LOOM_PROMPT.md`, `loom/README.md`, `loom/ai-agent4j-loom/README.md`.
