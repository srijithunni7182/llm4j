# Implementation status

What is built, tested and shipped in this module and in the eval4j export, against [`spec/08-DELIVERY-PLAN.md`](spec/08-DELIVERY-PLAN.md). Written to be accurate rather than flattering: gaps are listed.

## Built and tested

| Area | State |
|---|---|
| Run bundle export from eval4j | `run.json`, `evaluations.jsonl`, `scenarios.jsonl`, `tests.jsonl`, `traces.jsonl`, `optimizations.jsonl`, `index.jsonl`; stable case and evaluation keys (spec test vectors pass); append-only, atomic `run.json`; contract-tested against the JSON Schemas |
| Deterministic assertions recorded | `AgentResultAssert`, `ConversationAssert`, `LlmResponseAssert` record pass and fail (and measured token/iteration/redundancy budgets); `EvalChecks.named(...)` scope |
| Judge telemetry | calls, tokens, latency, cache reuse per evaluation; per-judge statistics; pricing and cost |
| Profiles | `FAST`, `BUILD`, `SAMPLE`, `FULL`; budget stop; `NOT_EVALUATED` plus an aborted test (never a pass); carry-over of the latest known results, labelled `CARRIED` |
| Agent traces | step traces of agent results, linked to the case's evaluations |
| Prompt optimizer | rounds, diff, budget exported and drawn |
| Loom | neutral `WorkflowTrace` and trajectory assertions in eval4j; `LoomTrace` bridge with graph from the AST, inferred branches and loop counts, bounded buffer, redaction |
| Report core | lenient bundle reader (truncated lines, gzip, repeated keys), rollups, goals and gap, priority-weighted rate, dataset coverage states, evidence mix and saved-by-reuse, baseline selection (previous run on the same branch, else default branch), comparison with judge-noise band; reproduces `spec/examples/minimal/expected.json` |
| Interactive edition | overview, families, dimensions, metrics, tests, case drawer, compare (headline, setup difference, movement, changed cases with word diff, scatter), coverage, cost, models with an independence warning, traces, optimizer, data notes, priorities, dark mode, responsive; browser-tested for script errors, network requests and markup injection |
| Static edition | no script, no inline style; tested for escaping |
| Outputs and CLI | `summary.md`, `compare.md`, `junit.xml`, `evaluations.csv`; `render`, `compare`, `list`, `validate`; shaded CLI jar (`-P cli`) |

## Not built yet (honest gaps)

- **Pairwise A/B view**: A/B results are exported as `PAIRWISE` evaluations and appear as ordinary metrics; there is no dedicated side-by-side prompt A/B page.
- **Workflow timeline lanes and the filterable event log** in the Trajectory view: the path (expected versus taken) and the checks are drawn; the lane timeline and log are not.
- **Per-dimension trend sparklines**: the overview trend is the overall pass rate per run on the branch.
- **Multi-bundle merge** (`groupId`), `import-legacy`, `prune` and `merge` CLI commands, and the legacy v1 report import.
- **Reliability panel beyond the independence heuristic** (self-consistency and human agreement figures are shown only if present in the bundle).
- **Priority presets**, custom branding and logo from configuration.
- **A headless-browser test in the Maven build**: the smoke test is a Node script run by hand or in CI.
- Judge calls made on other threads (parallel per-chunk retrieval judging) count toward judge statistics but cannot be attributed to a single evaluation.
