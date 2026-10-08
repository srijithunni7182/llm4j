# Implementation Plan: Prompt Files for Loom Agents

- [x] 1. Registry: `MarkdownFolderPromptRegistry` in `ai-agent4j`, with tests for layout, front matter, ordering of versions, limits, traversal and symlinks, reload (R1, R5, R6.1); reads front matter by hand (the YAML library's version does not match on the weave classpath)
- [x] 2. Syntax: `prompt:` and `prompts:` in the parser and AST; `system_template:` as an alias; parser tests and golden AST (R2.1–R2.4)
- [x] 3. Resolver and executor: folder choice, pins, base-plus-`system:` combination (R2.3–R2.5)
- [x] 4. CLI: `--prompts`, `--prompt id@vN` on run, check, audit, graph and replay (R2.5, R3.4)
- [x] 5. Validation messages with suggestions, unused-file warning, no silent fall-back (R3)
- [x] 6. Identity and trace: resolved text in the agent identity, prompt and version in the run record and the trace, `attrs` for the report (R4.1, R4.2, R4.4)
- [x] 7. Graph: chip and source for navigation in JSON, Mermaid, renderer and report; sync the shared renderer (R7.1); Mermaid does not show per-step settings, so the chip is in JSON, the panel and the report
- [x] 8. Extension: open the prompt file, "Create prompt file" quick fix, tests, README (R7.1, R7.2); "Create prompt file" is a command (Loom: Create Prompt File), not an automatic quick fix
- [x] 9. Docs: `LOOM_GUIDE.md`, `llms.txt`, workflow-guide skill and `docs/guide/03`, `04`; both paths described (R7.3, R7.4)
- [x] 10. A/B check: the same script run with two pins differs only in the prompt (R4.3); sample project `src/examples/…/prompts`; sample project `src/examples/newsletter`
- [x] 11. Verification: traceability, sabotage for the traversal and identity rules, full regression; sabotage 11 of 11 caught (the sabotage script, since removed (it is in git history at f2730e8), evidence/sabotage.md)

See `loom-onboarding` for the starter templates that create the `prompts/` layout and the skill update that describes both paths.
