# Implementation Plan: `weave eval`

Depends on nothing; pairs with `loom-prompt-files` (tasks 3 and 4 there make `--prompt` work here).

- [x] 1. `EvalScenario`: `rubric`, `expect`, typed tags; legacy convention readers; `fromDirectory`; tests incl. unchanged existing datasets (R1.4, R2); also snake_case names, tags as list or mapping, `EvalDataset` (dimensions, problems)
- [x] 2. `DatasetFolder`: discovery, matching, `dataset.yaml`, validation with suggestions (R1); `DatasetFolder` in the loom module (eval4j is now a loom dependency; Jackson aligned to one version)
- [x] 3. `AgentEvalRunner` sharing agent construction with `HarnessExecutor` (R3.2); the runner builds each executor fresh and gives it a hook to run before initialisation
- [x] 4. `WorkflowEvalRunner` with trajectory checks (R3.3); a workflow's output is what its last agent step produced; `expect` lines are judged against an account of the steps. No separate deterministic path check yet
- [x] 4a. Fixture tools from `eval/golden/fixtures.yaml` on top of `RecordedSearchTool` (R3.9); reads fixtures.yaml, see `Fixtures`
- [x] 5. `weave eval`, `--check`, `--init`, `--mock`, caps, confirmation, exit codes, summary (R3, R6.1); the default cap for a real run is 500,000 tokens, not dollars (a dollar cap needs a price table)
- [x] 6. Report: passed, failed and unjudged separated; graph overlay from the eval run; masking (R3.7, R6); deviation: a single self-contained HTML page and `--json`, written by the loom module. The eval4j report with the graph overlay lives in eval4j-report, which depends on loom, so loom cannot call it
- [x] 7. Guide: opt-out question, README record, order of work, go-live reminder, `docs/guide/02` and `06` (R4, R5); guide README, chapters 02, 06, 09 and the skill
- [x] 8. Docs: `LOOM_GUIDE.md`, `llms.txt`, skill (R7.1); Loom guide section 4, llms.txt, doc tests
- [x] 9. Check that `run`, `check` and `audit` work with no dataset, no `eval/` and no prompts folder (R4.1, R4.4); tested (`r4_1_checkAndRunNeverNeedADatasetOrAnEvalFolder`)
- [ ] 10. Extension command and summary (optional, R7.2); NOT DONE (optional in the spec): the extension command is left for a later release
- [x] 11. Verification: traceability, sabotage (unjudged counted as passed; cap ignored; dataset required by `run`), full regression; sabotage 12 of 12 caught (the sabotage script, since removed (it is in git history at f2730e8), evidence/sabotage.md)

See `loom-onboarding` for the templates (`weave init`), editor and `weave check` fixes, and the skill update that this feature depends on.
