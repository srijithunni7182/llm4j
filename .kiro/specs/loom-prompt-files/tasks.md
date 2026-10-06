# Implementation Plan: Prompt Files for Loom Agents

- [ ] 1. Registry: `MarkdownFolderPromptRegistry` in `ai-agent4j`, with tests for layout, front matter, ordering of versions, limits, traversal and symlinks, reload (R1, R5, R6.1)
- [ ] 2. Syntax: `prompt:` and `prompts:` in the parser and AST; `system_template:` as an alias; parser tests and golden AST (R2.1–R2.4)
- [ ] 3. Resolver and executor: folder choice, pins, base-plus-`system:` combination (R2.3–R2.5)
- [ ] 4. CLI: `--prompts`, `--prompt id@vN` on run, check, audit, graph and replay (R2.5, R3.4)
- [ ] 5. Validation messages with suggestions, unused-file warning, no silent fall-back (R3)
- [ ] 6. Identity and trace: resolved text in the agent identity, prompt and version in the run record and the trace, `attrs` for the report (R4.1, R4.2, R4.4)
- [ ] 7. Graph: chip and source for navigation in JSON, Mermaid, renderer and report; sync the shared renderer (R7.1)
- [ ] 8. Extension: open the prompt file, "Create prompt file" quick fix, tests, README (R7.1, R7.2)
- [ ] 9. Docs: `LOOM_GUIDE.md`, `llms.txt`, workflow-guide skill and `docs/guide/03`, `04`; both paths described (R7.3, R7.4)
- [ ] 10. A/B check: the same script run with two pins differs only in the prompt (R4.3); sample project `examples/…/prompts`
- [ ] 11. Verification: traceability, sabotage for the traversal and identity rules, full regression
