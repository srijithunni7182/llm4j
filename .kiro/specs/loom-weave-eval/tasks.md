# Implementation Plan: `weave eval`

Depends on nothing; pairs with `loom-prompt-files` (tasks 3 and 4 there make `--prompt` work here).

- [ ] 1. `EvalScenario`: `rubric`, `expect`, typed tags; legacy convention readers; `fromDirectory`; tests incl. unchanged existing datasets (R1.4, R2)
- [ ] 2. `DatasetFolder`: discovery, matching, `dataset.yaml`, validation with suggestions (R1)
- [ ] 3. `AgentEvalRunner` sharing agent construction with `HarnessExecutor` (R3.2)
- [ ] 4. `WorkflowEvalRunner` with trajectory checks (R3.3)
- [ ] 4a. Fixture tools from `eval/golden/fixtures.yaml` on top of `RecordedSearchTool` (R3.9)
- [ ] 5. `weave eval`, `--check`, `--init`, `--mock`, caps, confirmation, exit codes, summary (R3, R6.1)
- [ ] 6. Report: passed, failed and unjudged separated; graph overlay from the eval run; masking (R3.7, R6)
- [ ] 7. Guide: opt-out question, README record, order of work, go-live reminder, `docs/guide/02` and `06` (R4, R5)
- [ ] 8. Docs: `LOOM_GUIDE.md`, `llms.txt`, skill (R7.1)
- [ ] 9. Check that `run`, `check` and `audit` work with no dataset, no `eval/` and no prompts folder (R4.1, R4.4)
- [ ] 10. Extension command and summary (optional, R7.2)
- [ ] 11. Verification: traceability, sabotage (unjudged counted as passed; cap ignored; dataset required by `run`), full regression

See `loom-onboarding` for the templates (`weave init`), editor and `weave check` fixes, and the skill update that this feature depends on.
