# Implementation status

What is built, tested and shipped in this module and in the eval4j export, against [`spec/08-DELIVERY-PLAN.md`](spec/08-DELIVERY-PLAN.md). Written to be accurate rather than flattering. The full feature set with screenshots is in the [user guide](docs/USER-GUIDE.md).

## Built and tested

| Area | State |
|---|---|
| Run bundle export from eval4j | `run.json`, `evaluations.jsonl`, `scenarios.jsonl`, `tests.jsonl`, `traces.jsonl`, `optimizations.jsonl`, `index.jsonl`; stable case and evaluation keys (spec test vectors pass); append-only, atomic `run.json`; contract-tested against the JSON Schemas, including a freshly exported bundle and the documentation sample |
| Deterministic assertions recorded | `AgentResultAssert`, `ConversationAssert`, `LlmResponseAssert` record pass and fail (and measured token, iteration and redundancy budgets); `EvalChecks.named(...)` scope |
| Judge telemetry | calls, tokens, latency and cache reuse per evaluation, **including calls made on worker threads** (parallel retrieval judging); per-judge statistics; pricing and cost |
| Profiles | `FAST`, `BUILD`, `SAMPLE`, `FULL`; budget stop; `NOT_EVALUATED` plus an aborted test (never a pass); carry-over of the latest known results, labelled `CARRIED` |
| Pairwise A/B | `PairwiseCondition` exports `PAIRWISE` evaluations of the prompts family |
| Agent traces | step traces of agent results, linked to the case's evaluations |
| Prompt optimizer | rounds, diff, budget exported and drawn |
| Loom | neutral `WorkflowTrace` and trajectory assertions in eval4j; `LoomTrace` bridge in `eval4j-report` with graph from the AST, inferred branches and loop counts, bounded buffer, redaction |
| Report core | lenient bundle reader (truncated lines, gzip, repeated keys), **merging the bundles of one build (`groupId`)**, rollups, goals and gap, priority-weighted rate, dataset coverage states, evidence mix and saved-by-reuse, baseline selection (previous run on the same branch, else default branch), comparison with judge-noise band, **per-dimension trends**, **judge reliability** (self-consistency, spread, failure rate, same-family warning); reproduces `spec/examples/minimal/expected.json` |
| Interactive edition | overview with **priority presets**, families, dimensions with trend, metrics, tests, case drawer, compare, coverage, cost, models with reliability, **Prompt A/B page**, traces with agent reasoning summary, **workflow timeline lanes and filterable event log**, optimizer, data notes, **branding (title, logo, accent)**, dark mode, responsive |
| Static edition | no script, no inline style; tested for escaping; includes A/B counts and the configured title |
| Outputs and CLI | `summary.md`, `compare.md`, `junit.xml`, `evaluations.csv`; `render`, `compare`, `list`, `validate`, **`merge`, `import-legacy`, `prune`**; shaded CLI jar (`-P cli`) |
| Browser test | a Maven test (`BrowserSmokeTest`) drives every view in headless Chromium, with a hostile bundle; it is skipped when Node with Playwright is not installed (`PLAYWRIGHT_MODULE` locates it) |

## Not built (honest gaps)

- **Human-agreement figures** (for example Cohen's kappa against labelled cases) are not computed; the reliability panel uses the run's own samples, failures and the model-family heuristic.
- **Static edition does not draw** the dimension trend lines, the workflow timeline or the event log (it lists the comparison, coverage and the failing cases).
- **Pooled-variance judge noise** (`compare.pooledNoise`) is not implemented; the noise band is the judge's reported band or the configured default.
- **Branding accent** applies to the interactive edition only (the static edition cannot use inline styles).
- Loom's `alt` decisions are inferred, not observed, because Loom does not emit them.
- Building `eval4j-report` with `-am` also builds `ai-agent4j-loom` (an optional dependency); one existing Loom test reads other tests' surefire reports and fails when run in that order. It is unrelated to this module; build the report with `-pl eval4j-report` after installing its dependencies, or filter tests.
