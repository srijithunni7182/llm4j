# Onboarding findings: dogfooding sessions

Source: a cold-start session that built "Laptop Doctor" (4 agents, a human clarification step, a bounded
security-review loop) with the workflow guide, plus follow-up review of the script the agent wrote. Each finding names
the spec that covers it. Status is **specified, not built**.

| # | Finding | Evidence | Covered by |
|---|---|---|---|
| F1 | Editor reports `Unexpected character '%'` for `warn_at: 80%` while `weave check` accepts it | `vscode-loom/src/lsp/server.ts` has its own scanner; `VALID_CHAR_RE` has no `%`; `Lexer.java` accepts it | `loom-onboarding` R1 |
| F2 | Guide stages 3 and 4 assume a Java `PromptRegistry`; `weave` cannot supply one; the guide does not say the paths differ | `HarnessExecutor.setPromptRegistry` is reachable only from Java; no CLI flag | `loom-prompt-files` |
| F3 | A script-only user must write Java to load a golden dataset and to run it; rubrics and expectations are smuggled into `context` as `RUBRIC:` / `EXPECT:` lines; tags carry `key:value` data | `GoldenDataset.java` written by the agent; `EvalScenario` has no rubric field; no `weave eval` | `loom-weave-eval` |
| F4 | The agent leaned on `examples/hexamind-hub` for everything; the guide tells it to. The example is a Java-runtime hybrid, is absent for installed users, and makes every workflow look like it | every chapter and the skill link `examples/hexamind-hub` | `loom-onboarding` R2, R3 |
| F5 | About 250 lines of test scaffolding were re-derived (loader, consistency test, fixture search tool, scripted runner); `RecordedSearchTool` already existed but the guide did not point at it | session report; `eval4j/.../RecordedSearchTool.java` | `loom-onboarding` R2.3, `loom-weave-eval` R3.9 |
| F6 | A pom copied without Hexamind's parent gets an old surefire that finds zero JUnit 5 tests and reports success | session report; Hexamind pins surefire in its own pom | `loom-onboarding` R4 |
| F7 | A stale installed `ai-agent4j-loom` jar (older than its source) broke dependent modules with unrelated-looking compile errors | session report | `loom-onboarding` R5 |
| F8 | In the workflow the agent wrote, the answer to the `on_exhausted` human prompt is never read, so a declined unapproved script is still shown, labelled "Approved script". `weave check` and `weave audit` say nothing; the graph shows the problem | graph of the script: the exhausted branch merges into the success path | `loom-onboarding` R6 (check), `loom-weave-eval` (scenario in the guide) |
| F9 | The audit counts "can send or act" per agent, so a script that a person will copy and run, authored from untyped web text, shows no effect | audit of the same script | `loom-onboarding` R7 |
| F10 | `weave check` fails on a fresh checkout only because API keys are not set | 5 problems, all "not set" | `loom-onboarding` R6.3 |
| F12 | The session had the repository open, so the agent could read the guide, the example, the source and the tests; a first-time user has only the jar, the extension and the skill | the skill names `docs/guide/` and `examples/` and says to check API names against the code | `loom-onboarding` R10 |
| F11 | The reviewer shares the author's model; only one clarification round | the script | `loom-onboarding` R8 (guide advice) |

## What worked and must not regress

- Loom syntax learned from the docs alone was accurate: `expecting`, `loop … max … on_exhausted`, `alt`, `human_prompt`, `guard`.
- `weave check` and `weave audit` passed on first run, and the audit's per-agent table backs the script's own safety claim.
- `ScriptedClient`, `FakeJudge` and `HarnessExecutor.runAgentTask` made agents declared only in the script testable for free.
- The agent-or-task chapter resolved a real design question (should the workflow run the remediation script?) correctly.
