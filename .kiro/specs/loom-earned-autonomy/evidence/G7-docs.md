# G7: docs

- `AutonomyGuideTest` (passing in the G1 runs): every `loom` block in the "Earned Autonomy" section of `LOOM_GUIDE.md` parses and validates; the documented commands (`status`, `history`, `freeze`, `outcome`, `replay`) run against a seeded store; `LOOM_PROMPT.md`, the READMEs, the VS Code grammar and the language server's hover text mention `decision`, `decide` and `autonomy`.
- `weave --help` in the packaged JAR lists `autonomy` and `replay` (G6).
- Editor: `loom/vscode-loom/src/lsp/server.ts` carries hover text for `decision`, `decide` and `trust`; `syntaxes/loom.tmLanguage.json` has the keywords.
- Selling the feature: `loom/README.md` ("Earned autonomy: don't trust the agent, make it earn it"), the root `README.md` Loom list, and `WHY_LOOM.md` (section 10 and the comparison row).
- No sample workflow directory was added (V9.4; `ls loom/ai-agent4j-loom/samples` is unchanged from the baseline).
